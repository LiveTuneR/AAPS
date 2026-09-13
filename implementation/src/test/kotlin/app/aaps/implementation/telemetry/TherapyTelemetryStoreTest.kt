package app.aaps.implementation.telemetry

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipFile
import java.security.MessageDigest

class TherapyTelemetryStoreTest {
    @TempDir lateinit var directory: File
    private val start = 1_800_000_000_000L
    private fun bg(time: Long) = JSONObject().put("rawBgTimestamp",time).put("glucoseMgdl",120.0).put("isValid",true)
        .put("metadataOnly",false).put("displayUnits","mmol").put("trend","FLAT")
    private fun records(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.map(::JSONObject).toList()
    }

    @Test fun `seven days preserve 10080 samples actions transitions and ordered hashed export below budget`() {
        var now=start
        var zone=ZoneId.of("UTC")
        val root=File(directory,"store")
        fun open() = TherapyTelemetryStore(root,"test-head",{ now },{ (now-start)*1_000_000L },{ zone })
        var store=open()
        var actions=0
        var snapshots=0
        repeat(10080) { minute ->
            now=start+minute*60_000L
            if (minute==5040) { store.close(); store=open() }
            if (minute==5500) zone=ZoneId.of("Europe/Moscow")
            if (minute%1440==0 || minute==5040) {
                store.append("SETTINGS_SNAPSHOT",JSONObject().put("profilePercentage",100+minute/1440).put("source","TEST")); snapshots++
            }
            assertTrue(store.append("CGM",bg(now)))
            assertTrue(store.append("APS_INPUT",JSONObject().put("rawBgTimestamp",now).put("iob",1.5).put("profileIsf",40.0),minute.toLong(),"decision:$minute"))
            assertTrue(store.append("APS_DECISION",JSONObject().put("rawBgTimestamp",now).put("smbEligible",true).put("smbWaiting",false)
                .put("smbRequested",0.1),minute.toLong(),"decision:$minute"))
            if (minute%180==0) {
                listOf("CARBS","BOLUS","SMB","TBR","TEMP_TARGET","PROFILE_SWITCH").forEach { type ->
                    store.append("THERAPY_EVENT",JSONObject().put("type",type).put("amountU",0.1).put("eventTimestamp",now)); actions++
                }
                store.append("PUMP_STATE",JSONObject().put("connected",false).put("battery",80))
                store.append("ERROR",JSONObject().put("errorType","TIMEOUT"))
                store.append("PUMP_STATE",JSONObject().put("connected",true).put("battery",80))
            }
            if (minute%10==0) store.append("CGM",bg(now).put("metadataOnly",true))
        }
        val output=File(directory,"seven-day.zip")
        val manifest=store.export(output,start,now,60_000)
        assertEquals(10080,manifest.getInt("actualCgmCount"))
        assertEquals(10080,manifest.getInt("expectedCgmCount"))
        assertEquals(10080,manifest.getInt("decisionCount"))
        assertEquals(0,manifest.getInt("duplicateDecisions"))
        assertEquals(0,manifest.getJSONArray("missingIntervals").length())
        assertTrue(manifest.getBoolean("coverageVerified"))
        assertTrue(output.length()<100L*1024*1024)
        ZipFile(output).use { zip ->
            val all=records(zip,"telemetry.jsonl")
            assertEquals(all.map { it.getLong("timestampUtc") }.sorted(),all.map { it.getLong("timestampUtc") })
            assertEquals(actions,all.count { it.getString("type")=="THERAPY_EVENT" })
            assertEquals(1,all.count { it.getString("type")=="CLOCK_CHANGE" })
            val settings=JSONObject(zip.getInputStream(zip.getEntry("settings-snapshots.json")).reader().readText()).getJSONArray("records")
            assertEquals(snapshots,settings.length())
            val csv=zip.getInputStream(zip.getEntry("therapy-minute.csv")).bufferedReader().readLines()
            assertEquals(10081,csv.size)
            assertTrue(csv.first().contains("dosing_isf"))
            assertTrue(csv[1].contains("\"1.5\""))
            manifest.getJSONObject("fileSha256").keys().forEach { name ->
                val digest=MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(zip.getEntry(name)).readBytes())
                assertEquals(manifest.getJSONObject("fileSha256").getString(name),digest.joinToString("") { "%02x".format(it) })
            }
        }
        println("TELEMETRY_7D samples=10080 actions=$actions settings=$snapshots diskBytes=${store.bytesOnDisk()} exportBytes=${output.length()}")
        store.close()
    }

    @Test fun `truncated tail restart and bad middle line retain every readable record and flag gaps`() {
        var now=start
        val root=File(directory,"recovery")
        val before=TherapyTelemetryStore(root,"head",{ now })
        before.append("CGM",bg(now))
        File(root,"active.jsonl").appendText("{bad middle}\n")
        now+=60_000
        before.append("CGM",bg(now))
        File(root,"active.jsonl").appendText("{\"partial\":")
        // Deliberately no close(): models abrupt process loss, not a normal shutdown.
        val after=TherapyTelemetryStore(root,"head",{ now })
        now+=60_000
        after.append("CGM",bg(now))
        val manifest=after.export(File(directory,"recover.zip"),start,now,60_000)
        assertEquals(3,manifest.getInt("actualCgmCount"))
        assertTrue(manifest.getLong("corruptedRecords")>=2)
        assertEquals(1,manifest.getInt("uncleanSessions"))
        assertFalse(manifest.getBoolean("coverageVerified"))
        after.close()
    }

    @Test fun `pressure keeps young data and persists aggregated loss ledger across restart`() {
        var now=start
        val root=File(directory,"pressure")
        var store=TherapyTelemetryStore(root,"head",{ now },diskLimit=4096,segmentLimit=Long.MAX_VALUE)
        repeat(100) { store.append("CGM",bg(now)); now+=60_000 }
        assertTrue(store.writerDrops>0)
        val drops=store.writerDrops
        val manifest=store.export(File(directory,"pressure.zip"),start,now,60_000)
        assertFalse(manifest.getBoolean("coverageVerified"))
        assertTrue(manifest.getInt("actualCgmCount")>0)
        store.close()
        store=TherapyTelemetryStore(root,"head",{ now },diskLimit=4096)
        assertEquals(drops,store.writerDrops)
        store.dropped(count=10_000)
        assertEquals(drops+10_000,store.writerDrops)
        ZipFile(File(directory,"pressure.zip")).use { zip ->
            assertTrue(records(zip,"errors.jsonl").any { it.getString("type")=="TELEMETRY_STORAGE_PRESSURE" })
        }
        store.close()
    }

    @Test fun `clock jump never deletes young segments and exports sorted by actual timestamp across 9 10 99 100`() {
        var now=start
        var mono=0L
        val root=File(directory,"clock")
        val store=TherapyTelemetryStore(root,"head",{ now },{ mono },segmentLimit=1)
        repeat(130) { index ->
            now=start+(129-index)*60_000L; mono+=60_000_000_000L
            store.append("CGM",bg(now))
        }
        now+=50*86_400_000L; mono+=60_000_000_000L
        store.append("ERROR",JSONObject().put("errorType","CLOCK_TEST"))
        val output=File(directory,"clock.zip")
        val manifest=store.export(output,start,now)
        assertEquals(130,manifest.getInt("actualCgmCount"))
        assertTrue(manifest.getBoolean("retentionClockUncertain"))
        ZipFile(output).use { zip ->
            val times=records(zip,"telemetry.jsonl").map { it.getLong("timestampUtc") }
            assertEquals(times.sorted(),times)
        }
        store.close()
    }

    @Test fun `missing sample and duplicate decision cannot pass integrity gate`() {
        var now=start
        val store=TherapyTelemetryStore(File(directory,"negative"),"head",{ now })
        repeat(31) { minute -> now=start+minute*60_000; if (minute!=15) store.append("CGM",bg(now)) }
        repeat(2) { store.append("APS_DECISION",JSONObject().put("rawBgTimestamp",now),1,"same-decision") }
        val manifest=store.export(File(directory,"negative.zip"),start,now,60_000)
        assertEquals(30,manifest.getInt("actualCgmCount"))
        assertEquals(1,manifest.getInt("duplicateDecisions"))
        assertFalse(manifest.getBoolean("coverageVerified"))
        assertTrue(manifest.getJSONArray("missingIntervals").length()>0)
        store.close()
    }

    @Test fun `secret keys nested auth URLs bearer and private object dumps are removed`() {
        val raw=JSONObject().put("glucoseMgdl",120).put("apiSecret","SHOULD_NOT_EXIST").put("pumpSerial","PRIVATE_SERIAL")
            .put("nested",JSONObject().put("accessToken","TOKEN_SECRET").put("pairingKey","PAIRING_SECRET"))
            .put("reason","Bearer TOKEN_SECRET https://private.example/?api-secret=SHOULD_NOT_EXIST password=PW_SECRET")
            .put("wearGraph",List(1440) { JSONObject().put("value",it) }.toString()).put("tidepoolBody","PRIVATE_BODY")
        val cleaned=TelemetrySanitizer.clean(raw)
        assertEquals(120,cleaned.getInt("glucoseMgdl"))
        listOf("SHOULD_NOT_EXIST","PRIVATE_SERIAL","TOKEN_SECRET","PAIRING_SECRET","PW_SECRET","private.example","PRIVATE_BODY")
            .forEach { assertFalse(cleaned.toString().contains(it),it) }
        assertTrue(cleaned.toString().length<1024)
        assertTrue(cleaned.isNull("wearGraph"))
    }
}
