package app.aaps.implementation.telemetry

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TherapyCrashMarkerTest {
    @TempDir lateinit var directory: File
    @Test fun `crash marker survives failed acceptance and contains no exception credential message`() {
        val marker=TherapyCrashMarker(directory)
        marker.write(IllegalStateException("Bearer PRIVATE_TOKEN password=PRIVATE_PASSWORD"),1234)
        marker.recover { false }
        var recovered: JSONObject?=null
        marker.recover { recovered=it; true }
        assertEquals(1234,recovered!!.getLong("eventTimestamp"))
        assertTrue(recovered!!.getJSONArray("stack").length()>0)
        assertFalse(recovered.toString().contains("PRIVATE_"))
        marker.recover { _: JSONObject -> fail<Unit>("Already committed marker must not duplicate"); true }
    }
    @Test fun `truncated crash marker exports explicit corruption and cannot silently disappear`() {
        File(directory,"pending-crash.json").writeText("{unfinished")
        TherapyCrashMarker(directory).recover { assertEquals("CRASH_MARKER_CORRUPT",it.getString("stage")); true }
        assertFalse(File(directory,"pending-crash.json").exists())
    }
}
