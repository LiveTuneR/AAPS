package app.aaps.core.interfaces.telemetry

import app.aaps.core.interfaces.aps.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Only typed numerical algorithm inputs, never plugin object graphs or network payloads. */
object ApsTelemetryFields {
    private val json=Json { encodeDefaults=true; allowSpecialFloatingPointValues=true }
    private fun <T> encoded(serializer: KSerializer<T>,value: T?): Any = value?.let { JSONObject(json.encodeToString(serializer,it)) } ?: JSONObject.NULL

    fun inputs(result: APSResult): JSONObject {
        val rt=result.rawData() as? RT
        val decision=rt?.decision
        return JSONObject().put("algorithm",result.algorithm.name).put("rawBgTimestamp",result.glucoseStatus?.date ?: JSONObject.NULL)
            .put("decisionTimestamp",result.date).put("glucoseMgdl",result.glucoseStatus?.glucose ?: JSONObject.NULL)
            .put("delta",result.glucoseStatus?.delta ?: JSONObject.NULL).put("shortAvgDelta",result.glucoseStatus?.shortAvgDelta ?: JSONObject.NULL)
            .put("longAvgDelta",result.glucoseStatus?.longAvgDelta ?: JSONObject.NULL).put("noise",result.glucoseStatus?.noise ?: JSONObject.NULL)
            .put("iob",result.iob?.iob ?: JSONObject.NULL).put("basalIob",result.iob?.basaliob ?: JSONObject.NULL)
            .put("bolusIob",JSONObject.NULL).put("insulinActivity",result.iob?.activity ?: JSONObject.NULL)
            .put("iobForecast",result.iobData?.let { values -> JSONArray().apply { values.forEach { put(encoded(IobTotal.serializer(),it)) } } } ?: JSONObject.NULL)
            .put("meal",encoded(MealData.serializer(),result.mealData)).put("cob",result.mealData?.mealCOB ?: JSONObject.NULL)
            .put("carbs",result.mealData?.carbs ?: JSONObject.NULL).put("lastCarbTimestamp",result.mealData?.lastCarbTime ?: JSONObject.NULL)
            .put("currentTemp",encoded(CurrentTemp.serializer(),result.currentTemp)).put("tbrRate",result.currentTemp?.rate ?: JSONObject.NULL)
            .put("tbrRemaining",result.currentTemp?.duration ?: JSONObject.NULL)
            .put("oapsProfile",encoded(OapsProfile.serializer(),result.oapsProfile))
            .put("autoIsfProfile",encoded(OapsProfileAutoIsf.serializer(),result.oapsProfileAutoIsf))
            .put("scheduledBasal",result.oapsProfile?.current_basal ?: result.oapsProfileAutoIsf?.current_basal ?: JSONObject.NULL)
            .put("target",result.targetBG).put("tempTarget",result.oapsProfile?.temptargetSet ?: result.oapsProfileAutoIsf?.temptargetSet ?: JSONObject.NULL)
            .put("cr",decision?.carbRatio ?: result.oapsProfile?.carb_ratio ?: result.oapsProfileAutoIsf?.carb_ratio ?: JSONObject.NULL)
            .put("profileIsf",decision?.profileIsfMgdl ?: JSONObject.NULL).put("currentDynamicIsf",decision?.currentDynamicIsfMgdl ?: JSONObject.NULL)
            .put("dosingIsf",decision?.insulinReqIsfMgdl ?: JSONObject.NULL).put("futureIsf",decision?.futureIsfMgdl ?: JSONObject.NULL)
            .put("weightedTdd",decision?.tddU ?: JSONObject.NULL).put("dynIsfAdjustmentFactor",decision?.dynIsfAdjustmentFactor ?: JSONObject.NULL)
            .put("autoIsfFactor",decision?.autoIsfFactor ?: JSONObject.NULL)
            .put("autosensRatio",result.autosensResult?.ratio ?: JSONObject.NULL)
    }

    fun decision(result: APSResult): JSONObject {
        val rt=result.rawData() as? RT
        val snapshot=rt?.decision
        return JSONObject().put("algorithm",result.algorithm.name).put("decisionTimestamp",result.date)
            .put("rawBgTimestamp",result.glucoseStatus?.date ?: JSONObject.NULL).put("smbRequested",result.smb)
            .put("tbrRequested",result.rate).put("durationMinutes",result.duration).put("insulinReq",rt?.insulinReq ?: JSONObject.NULL)
            .put("smbEligible",snapshot?.conditionEligible ?: JSONObject.NULL).put("smbWaiting",snapshot?.intervalWaiting ?: JSONObject.NULL)
            .put("zeroTempEquivalentMinutes",rt?.smbZeroTempEquivalentMinutes ?: JSONObject.NULL)
            .put("decisionSnapshot",snapshot?.let { JSONObject(it.toJson()) } ?: JSONObject.NULL).put("reason",result.reason)
            .put("predictions",encoded(Predictions.serializer(),rt?.predBGs))
    }
}
