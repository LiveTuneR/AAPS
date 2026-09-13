package app.aaps.implementation.telemetry

import app.aaps.core.interfaces.telemetry.TelemetryException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One bounded crash marker, independent of the async writer. Never intercepts Android termination. */
internal class TherapyCrashMarker(private val directory: File) {
    private val file get()=File(directory,"pending-crash.json")
    fun write(error: Throwable,now: Long) {
        check(directory.isDirectory || directory.mkdirs())
        val temp=File(directory,"pending-crash.part")
        val data=TelemetryException.fields(error).put("stage","UNCAUGHT_EXCEPTION").put("eventTimestamp",now)
        FileOutputStream(temp).use { it.write(data.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
    fun recover(accept: (JSONObject) -> Boolean) {
        if (!file.exists()) return
        val record=try {
            require(file.length()<=64*1024)
            JSONObject(file.readText())
        } catch (_: Exception) { JSONObject().put("stage","CRASH_MARKER_CORRUPT").put("boundsKnown",false) }
        if (accept(record)) check(file.delete())
    }
}
