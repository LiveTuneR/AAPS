package app.aaps.implementation.telemetry

import app.aaps.core.interfaces.aps.*
import app.aaps.core.interfaces.telemetry.ApsTelemetryFields
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.*
import java.io.File
import java.util.zip.GZIPOutputStream

class TherapyTelemetrySizeTest {
    @TempDir lateinit var directory: File

    @Test fun `seven day forecast heavy APS payload stays bounded after production serialization and redaction`() {
        val result=mock<APSResult>()
        val rt=RT(runningDynamicIsf=true)
        whenever(result.algorithm).thenReturn(APSResult.Algorithm.SMB)
        whenever(result.rawData()).thenReturn(rt)
        whenever(result.reason).thenReturn("SYNTHETIC_SIZE_FIXTURE")
        val output=File(directory,"forecast-seven-day.jsonl.gz")
        var rawBytes=0L
        var maxRecord=0
        val baseline=1_800_000_000_000L
        val forecast=Array(288) { index -> IobTotal(time=baseline+index*300_000L,
            iob=1.5*(288-index)/288,basaliob=0.2,activity=0.004,lastBolusTime=baseline-60_000,
            iobWithZeroTemp=IobTotal(time=baseline+index*300_000L,iob=1.5*(288-index)/300,activity=0.003)) }
        whenever(result.iobData).thenReturn(forecast)
        whenever(result.iob).thenReturn(forecast[0])
        whenever(result.date).thenReturn(baseline)
        whenever(result.glucoseStatus).thenReturn(GlucoseStatusSMB(140.0,0.0,2.0,1.5,1.0,baseline))
        whenever(result.currentTemp).thenReturn(CurrentTemp(30,1.1,null))
        whenever(result.mealData).thenReturn(MealData(carbs=40.0,mealCOB=20.0,lastCarbTime=baseline-60_000))
        rt.decision=AlgorithmDecisionSnapshot("SMB",true,baseline,baseline,40.0,36.0,8.0,true,
            conditionEligible=true,intervalWaiting=false,insulinReqIsfMgdl=38.0,minPredBgMgdl=100.0,
            minGuardBgMgdl=90.0,tddU=42.0,dynIsfAdjustmentFactor=0.9)
        rt.predBGs=Predictions(IOB=List(48) { 140-it/2 },ZT=List(48) { 140-it/3 },
            COB=List(48) { 140+it/2 },UAM=List(48) { 140+it/3 })
        val input=TelemetrySanitizer.clean(ApsTelemetryFields.inputs(result))
        val decision=TelemetrySanitizer.clean(ApsTelemetryFields.decision(result))
        assertEquals(288,input.getJSONArray("iobForecast").length())
        assertEquals(48,decision.getJSONObject("predictions").getJSONArray("IOB").length())
        GZIPOutputStream(output.outputStream()).bufferedWriter().use { writer ->
            repeat(10080) { minute ->
                val now=baseline+minute*60_000L
                listOf("APS_INPUT" to input,"APS_DECISION" to decision).forEach { (type,data) ->
                    val line=JSONObject().put("schemaVersion",1).put("timestampUtc",now).put("generation",minute)
                        .put("correlationId","size-decision-$minute").put("type",type).put("data",data).toString()
                    val bytes=line.toByteArray(Charsets.UTF_8).size
                    maxRecord=maxOf(maxRecord,bytes); rawBytes+=bytes+1
                    writer.appendLine(line)
                }
            }
        }
        assertTrue(maxRecord<256*1024,"A full forecast must fit the production record limit")
        assertTrue(output.length()<80L*1024*1024,"Reserve 20 MB of preferred 100 MB budget for settings, commands and runtime")
        println("TELEMETRY_SIZE_7D samples=10080 forecastPoints=288 rawBytes=$rawBytes gzipBytes=${output.length()} maxRecordBytes=$maxRecord")
    }
}
