package app.aaps.implementation.telemetry

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class TherapyTelemetryEdgeTest {
    @TempDir lateinit var directory: File

    @Test fun `period carries last known settings and subsequent changes without importing old BG`() {
        var now=1_800_000_000_000L
        val store=TherapyTelemetryStore(File(directory,"store"),"head",{ now })
        store.append("SETTINGS_SNAPSHOT",JSONObject().put("profilePercentage",80))
        now+=60_000
        store.append("SETTINGS_SNAPSHOT",JSONObject().put("profilePercentage",90))
        store.append("SETTINGS_CHANGE",JSONObject().put("setting","ApsMaxIob").put("oldValue",3).put("newValue",4))
        store.append("CGM",JSONObject().put("rawBgTimestamp",now).put("glucoseMgdl",110))
        now+=60_000
        val from=now
        store.append("CGM",JSONObject().put("rawBgTimestamp",now).put("glucoseMgdl",120))
        val zipFile=File(directory,"result.zip")
        val manifest=store.export(zipFile,from,now,60_000)
        assertEquals(1,manifest.getInt("actualCgmCount"))
        ZipFile(zipFile).use { zip ->
            val json=JSONObject(zip.getInputStream(zip.getEntry("settings-snapshots.json")).reader().readText())
            val context=json.getJSONArray("contextBeforePeriod")
            assertEquals(2,context.length())
            assertEquals(90,context.getJSONObject(0).getJSONObject("data").getInt("profilePercentage"))
            assertEquals(4,context.getJSONObject(1).getJSONObject("data").getInt("newValue"))
            val csv=zip.getInputStream(zip.getEntry("therapy-minute.csv")).reader().readText()
            assertTrue(csv.contains("\"90\""))
            assertFalse(csv.contains("\"110\""))
        }
        store.close()
    }

    @Test fun `queue reported delivery joins explicit SMB decision and never guesses from failed default zero`() {
        val summary=TherapyMinuteSummary()
        fun record(type: String, data: JSONObject, id: String="d1")=JSONObject().put("type",type).put("data",data).put("correlationId",id)
        summary.accept(record("CGM",JSONObject().put("rawBgTimestamp",1000).put("glucoseMgdl",140)))
        summary.accept(record("APS_INPUT",JSONObject().put("rawBgTimestamp",1000).put("iob",2)))
        summary.accept(record("PUMP_RESULT",JSONObject().put("commandType","SMB_BOLUS").put("reportedDeliveredU",0.15)))
        summary.accept(record("PUMP_RESULT",JSONObject().put("commandType","SMB_BOLUS").put("reportedDeliveredU",2),"unrelated"))
        val file=File(directory,"summary.csv")
        summary.write(file,1000,1000)
        val rows=file.readLines()
        val column=rows[0].split(',').indexOf("smb_delivered")
        assertEquals("\"0.15\"",rows[1].split(',')[column])
    }
}
