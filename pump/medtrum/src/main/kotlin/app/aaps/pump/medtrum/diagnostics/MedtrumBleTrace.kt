package app.aaps.pump.medtrum.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedtrumBleTrace @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MedtrumBleTrace").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val traceDirectory = File(context.filesDir, "medtrum-ble-diagnostics")
    private val exportDirectory = File(context.cacheDir, "medtrum-ble-diagnostics")
    private var activeFile: File? = null

    fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        scope.launch {
            val data = JSONObject()
                .put("wallMs", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime())
                .put("event", event)
            fields.forEach { (key, value) -> data.put(key, encode(value)) }
            append(data.toString())
        }
    }

    suspend fun export(): File = withContext(dispatcher) {
        traceDirectory.mkdirs()
        exportDirectory.mkdirs()
        val output = File(exportDirectory, "medtrum-ble-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val metadata = JSONObject()
                .put("schema", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL)
                .put("packageName", context.packageName)
                .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .toString(2)
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadata.toByteArray())
            zip.closeEntry()
            traceFiles().reversed().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        exportDirectory.listFiles { file -> file.extension == "zip" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_EXPORTS)
            ?.forEach(File::delete)
        output
    }

    suspend fun share() {
        val archive = export()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun encode(value: Any?): Any = when (value) {
        null         -> JSONObject.NULL
        is ByteArray -> value.toHex()
        is Number,
        is Boolean   -> value
        else         -> value.toString().take(MAX_FIELD_LENGTH)
    }

    private fun append(line: String) {
        traceDirectory.mkdirs()
        var file = activeFile
        if (file == null || !file.exists() || file.length() >= MAX_TRACE_BYTES) {
            file = File(traceDirectory, "medtrum-ble-${System.currentTimeMillis()}.jsonl")
            activeFile = file
            traceFiles().drop(MAX_TRACE_FILES - 1).forEach(File::delete)
        }
        file.appendText(line + "\n")
    }

    private fun traceFiles(): List<File> =
        traceDirectory.listFiles { file -> file.extension == "jsonl" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()

    companion object {
        private const val MAX_TRACE_BYTES = 2_097_152L
        private const val MAX_TRACE_FILES = 10
        private const val MAX_EXPORTS = 2
        private const val MAX_FIELD_LENGTH = 240

        internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
}
