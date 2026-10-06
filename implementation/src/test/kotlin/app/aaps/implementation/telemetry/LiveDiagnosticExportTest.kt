package app.aaps.implementation.telemetry

import app.aaps.core.data.diagnostics.BoundedDiagnosticTrace
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LiveDiagnosticExportTest {
    @TempDir lateinit var directory: File
    @Test fun `normal support files contain both live rings without consuming forensic evidence`() {
        val ring = BoundedDiagnosticTrace(2, 1024)
        ring.offer("{}",10); ring.offer("{}",20); ring.offer("{}",30)
        val snapshots = mapOf("apex" to ring.snapshot(), "medtrum" to ring.snapshot())
        val files = LiveDiagnosticExport.write(directory, snapshots)
        assertEquals(2, files.size)
        files.forEach { file ->
            val lines = file.readLines(); assertEquals(3, lines.size)
            val marker = JSONObject(lines.first())
            assertEquals(1L, marker.getLong("dropped")); assertEquals(20L, marker.getLong("oldestUtc"))
        }
        assertEquals(snapshots["apex"], ring.snapshot())
    }
}
