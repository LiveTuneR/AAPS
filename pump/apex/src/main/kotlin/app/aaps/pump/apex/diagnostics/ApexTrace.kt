package app.aaps.pump.apex.diagnostics

import android.app.ActivityManager
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
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApexTrace @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ApexTrace").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operationSequence = AtomicLong(0L)
    private val traceDirectory = File(context.filesDir, "apex-diagnostics")
    private val exportDirectory = File(context.cacheDir, "apex-diagnostics")
    private var activeFile: File? = null

    fun nextOperationId(): Long = operationSequence.incrementAndGet()

    fun record(
        event: String,
        generation: Long? = null,
        operationId: Long? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            val data = JSONObject()
                .put("wallMs", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime())
                .put("event", event)
            generation?.let { data.put("generation", it) }
            operationId?.let { data.put("operationId", it) }
            fields.forEach { (key, value) -> data.put(key, ApexTraceSanitizer.sanitize(key, value, MAX_FIELD_LENGTH)) }
            append(data.toString())
        }
    }

    fun capturePreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        scope.launch {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return@launch
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return@launch
            val marker = File(traceDirectory, "last-exit.timestamp")
            val previousTimestamp = marker.takeIf(File::exists)?.readText()?.toLongOrNull() ?: 0L
            if (exit.timestamp <= previousTimestamp) return@launch
            marker.parentFile?.mkdirs()
            marker.writeText(exit.timestamp.toString())
            record(
                event = "previous_process_exit",
                fields = mapOf(
                    "reason" to exit.reason,
                    "status" to exit.status,
                    "importance" to exit.importance,
                    "pssKb" to exit.pss,
                    "rssKb" to exit.rss,
                    "timestamp" to exit.timestamp,
                ),
            )
        }
    }

    suspend fun export(): File = withContext(dispatcher) {
        traceDirectory.mkdirs()
        exportDirectory.mkdirs()
        val output = File(exportDirectory, "apex-diagnostics-${System.currentTimeMillis()}.zip")
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
            traceFiles().forEach { file ->
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

    suspend fun captureThreadDump(reason: String) = withContext(dispatcher) {
        val dump = JSONObject()
            .put("wallMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("event", "thread_dump")
            .put("reason", reason)
        val threads = JSONObject()
        Thread.getAllStackTraces()
            .toList()
            .sortedBy { (thread, _) -> thread.name }
            .forEach { (thread, stack) ->
                threads.put(
                    thread.name.take(MAX_FIELD_LENGTH),
                    JSONObject()
                        .put("state", thread.state.name)
                        .put("daemon", thread.isDaemon)
                        .put("priority", thread.priority)
                        .put("stack", stack.take(MAX_STACK_DEPTH).joinToString("\n") { frame ->
                            "${frame.className}.${frame.methodName}(${frame.fileName ?: "?"}:${frame.lineNumber})"
                        }),
                )
            }
        dump.put("threads", threads)
        append(dump.toString())
    }

    suspend fun share(context: Context) {
        val archive = export()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun anonymize(value: String): String = ApexTraceSanitizer.anonymize(value)

    private fun append(line: String) {
        traceDirectory.mkdirs()
        var file = activeFile
        if (file == null || !file.exists() || file.length() >= MAX_TRACE_BYTES) {
            file = File(traceDirectory, "apex-trace-${System.currentTimeMillis()}.jsonl")
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
        private const val MAX_TRACE_BYTES = 1_048_576L
        private const val MAX_TRACE_FILES = 6
        private const val MAX_EXPORTS = 2
        private const val MAX_FIELD_LENGTH = 160
        private const val MAX_STACK_DEPTH = 80
    }
}
