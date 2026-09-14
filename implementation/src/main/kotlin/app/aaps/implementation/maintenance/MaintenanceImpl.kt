package app.aaps.implementation.maintenance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.ExportResult
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaintenanceImpl @Inject constructor(
    private val context: Context,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val nsSettingsStatus: NSSettingsStatus,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val fileListProvider: FileListProvider,
    private val loggerUtils: LoggerUtils,
    private val cloudStorageManager: CloudStorageManager
) : Maintenance {

    private val archiveLock = Any()

    override suspend fun executeSendLogs(): ExportResult {
        val amount = preferences.get(IntKey.MaintenanceLogsAmount)
        val zip = prepareLogExport(amount) ?: return ExportResult(localSuccess = false)

        val logEmail = preferences.get(BooleanNonKey.ExportLogEmailEnabled)
        val logCloud = preferences.get(BooleanNonKey.ExportLogCloudEnabled)
        val isCloudActive = cloudStorageManager.isCloudStorageActive()
        val cloudEnabled = logCloud && isCloudActive

        var emailSuccess: Boolean? = null
        var cloudSuccess: Boolean? = null

        if (logEmail || !cloudEnabled) {
            emailSuccess = try {
                val recipient = preferences.get(StringKey.MaintenanceEmail)
                val emailIntent = sendMail(zip.uri, recipient, "Log Export")
                context.startActivity(emailIntent)
                true
            } catch (e: Exception) {
                aapsLogger.error("Failed to launch email intent", e)
                false
            }
        }

        if (cloudEnabled) {
            cloudSuccess = performCloudLogUpload(zip)
        }

        return ExportResult(localSuccess = emailSuccess, cloudSuccess = cloudSuccess)
    }

    override fun deleteLogs(keep: Int) {
        synchronized(archiveLock) {
        val logDir = File(loggerUtils.logDirectory)
        val files = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("AndroidAPS") && name.endsWith(".zip"))
        }
        val autotuneFiles = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("autotune") && name.endsWith(".zip"))
        }
        // keep counts archived files exactly; the active .log is never a retention target.
        listOf(files, autotuneFiles).forEach { group ->
            LogFileOrder.newest(group?.toList().orEmpty()).drop(keep.coerceAtLeast(0)).forEach { file ->
                if (!file.delete()) aapsLogger.warn(LTag.CORE, "Could not delete archived log")
            }
        }
        }
    }

    private suspend fun performCloudLogUpload(zipFile: DocumentFile): Boolean {
        return try {
            val provider = cloudStorageManager.getActiveProvider() ?: return false
            val bytes = context.contentResolver.openInputStream(zipFile.uri)?.use { it.readBytes() } ?: return false
            provider.getOrCreateFolderPath(CloudConstants.CLOUD_PATH_LOGS)?.let { provider.setSelectedFolderId(it) }
            var uploadedFileId = provider.uploadFileToPath(zipFile.name ?: "logs.zip", bytes, "application/zip", CloudConstants.CLOUD_PATH_LOGS)
            if (uploadedFileId == null) {
                uploadedFileId = provider.uploadFile(zipFile.name ?: "logs.zip", bytes, "application/zip")
            }
            uploadedFileId != null
        } catch (e: Exception) {
            aapsLogger.error("Cloud log upload failed", e)
            false
        }
    }

    /**
     * Returns a list of log files. The number of returned logs is given via the amount parameter.
     * Active log first, then filename date and numeric rotation. amount <= 0 selects none.
     */
    internal fun getLogFiles(amount: Int): List<File> {
        aapsLogger.debug("getting $amount logs from directory ${loggerUtils.logDirectory}")
        val logDir = File(loggerUtils.logDirectory)
        val files = logDir.listFiles { _: File?, name: String ->
            (name.startsWith("AndroidAPS")
                && (name.endsWith(".log")
                || name.endsWith(".zip") && !name.endsWith(loggerUtils.suffix)))
        } ?: emptyArray()
        return LogFileOrder.newest(files.filter { it.isFile }).take(amount.coerceAtLeast(0))
    }

    internal fun prepareLogExport(amount: Int): DocumentFile? = synchronized(archiveLock) {
        var local: File? = null
        var document: DocumentFile? = null
        try {
            local = File.createTempFile("aaps-log-export-", ".zip", context.cacheDir)
            LogArchive.create(getLogFiles(amount), local, config.HEAD, amount)
            document = fileListProvider.ensureTempDirExists()?.createFile("application/zip", constructName())
                ?: throw IOException("Export document unavailable")
            context.contentResolver.openOutputStream(document.uri, "w")?.use { out ->
                local.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Export output unavailable")
            fun sha(input: java.io.InputStream): ByteArray = input.use {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = it.read(buffer)
                while (count != -1) { digest.update(buffer, 0, count); count = it.read(buffer) }
                digest.digest()
            }
            val actual = context.contentResolver.openInputStream(document.uri)?.let(::sha)
                ?: throw IOException("Export verification unavailable")
            if (!actual.contentEquals(sha(local.inputStream()))) throw IOException("Export checksum mismatch")
            aapsLogger.info(LTag.CORE, "Log export verified: selectedLimit=${amount.coerceAtLeast(0)} bytes=${local.length()}")
            document
        } catch (error: Exception) {
            document?.delete()
            aapsLogger.error("Log export failed: ${error.javaClass.simpleName}")
            null
        } finally {
            local?.delete()
        }
    }

    /** AndroidAPS_LOG_ + Long Time + .log.zip */
    private fun constructName(): String =
        "AndroidAPS_LOG_" + System.currentTimeMillis() + loggerUtils.suffix

    @Suppress("SameParameterValue")
    private fun sendMail(attachmentUri: Uri, recipient: String, subject: String): Intent {
        val builder = StringBuilder()
        builder.append("ADD TIME OF EVENT HERE: " + System.lineSeparator())
        builder.append("ADD ISSUE DESCRIPTION OR GITHUB ISSUE REFERENCE NUMBER: " + System.lineSeparator())
        builder.append("-------------------------------------------------------" + System.lineSeparator())
        builder.append("(Please remember this will send only very recent logs." + System.lineSeparator())
        builder.append("If you want to provide logs for event older than a few hours," + System.lineSeparator())
        builder.append("you have to do it manually)" + System.lineSeparator())
        builder.append("-------------------------------------------------------" + System.lineSeparator())
        builder.append(rh.gs(config.appName) + " " + config.VERSION + System.lineSeparator())
        if (config.AAPSCLIENT) builder.append("NSCLIENT" + System.lineSeparator())
        builder.append("Build: " + config.BUILD_VERSION + System.lineSeparator())
        builder.append("Remote: " + config.REMOTE + System.lineSeparator())
        builder.append("Flavor: " + config.FLAVOR + config.BUILD_TYPE + System.lineSeparator())
        builder.append(rh.gs(app.aaps.core.ui.R.string.configbuilder_nightscoutversion_label) + " " + nsSettingsStatus.getVersion() + System.lineSeparator())
        if (config.isEngineeringMode()) builder.append(rh.gs(app.aaps.core.ui.R.string.engineering_mode_enabled))
        val body = builder.toString()
        aapsLogger.debug("Opening verified log export share intent")
        val emailIntent = Intent(Intent.ACTION_SEND)
        emailIntent.type = "text/plain"
        emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject)
        emailIntent.putExtra(Intent.EXTRA_TEXT, body)
        emailIntent.putExtra(Intent.EXTRA_STREAM, attachmentUri)
        emailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return emailIntent
    }
}
