package app.aaps.implementation.telemetry

import org.json.JSONObject
import java.io.File

/** Joins explicit decision correlations; never guesses an insulin result from the nearest glucose. */
internal class TherapyMinuteSummary {
    private val rows = sortedMapOf<Long,JSONObject>()
    private val decisions = hashMapOf<String,Long>()
    private var pump: JSONObject? = null
    private var settings: JSONObject? = null
    private var activity: JSONObject? = null

    fun accept(record: JSONObject) {
        val type=record.getString("type")
        val data=record.getJSONObject("data")
        if (type=="PUMP_STATE") { pump=data; return }
        if (type=="SETTINGS_SNAPSHOT") { settings=data; return }
        if (type=="ACTIVITY") { activity=data; return }
        val timestamp=when (type) {
            "CGM" -> if (data.optBoolean("metadataOnly") || !data.optBoolean("isValid",true)) return else data.optLong("rawBgTimestamp",-1)
            "APS_INPUT", "APS_DECISION", "CALCULATION" -> data.optLong("rawBgTimestamp",data.optLong("inputTimestamp",-1))
            else -> decisions[record.optString("correlationId")] ?: return
        }
        if (timestamp < 0) return
        val row=rows.getOrPut(timestamp) { JSONObject().put("timestamp",timestamp) }
        if (type=="CGM") {
            copy(data,row,listOf("glucoseMgdl","displayGlucose","delta","trend"))
            row.put("sample",true)
            if (data.optString("displayUnits")=="mmol" && !data.isNull("glucoseMgdl")) row.put("displayGlucose",data.getDouble("glucoseMgdl")/18.0)
            else if (data.optString("displayUnits")=="mg/dl") row.put("displayGlucose",data.opt("glucoseMgdl"))
            pump?.let { row.put("pumpConnected",it.opt("connected")).put("pumpReservoirPumpUnits",it.opt("reservoirPumpUnits"))
                .put("pumpBattery",it.opt("battery")) }
            settings?.let { copy(it,row,listOf("profileName","profilePercentage")) }
            activity?.let { row.put("activityState",it.opt("activityState")) }
        }
        if (type in setOf("APS_INPUT","APS_DECISION","CALCULATION")) {
            val id=record.optString("correlationId")
            if (id.isNotEmpty() && id!="null") decisions[id]=timestamp
            copy(data,row,columns.values.toList())
            data.optJSONObject("decisionSnapshot")?.let { copy(it,row,columns.values.toList()) }
            if (!record.isNull("generation")) row.put("loopGeneration",record.opt("generation"))
        }
        if (type=="SMB_REQUEST") row.put("smbRequested",data.opt("smbRequested"))
        if (type=="PUMP_RESULT" && data.optString("commandType")=="SMB") row.put("smbDelivered",data.opt("smbDelivered"))
        if (type=="PUMP_RESULT" && data.optString("commandType")=="SMB_BOLUS") row.put("smbDelivered",data.opt("reportedDeliveredU"))
        if (type=="CONSTRAINT") {
            row.put("smbConstrained",data.opt("smbConstrained")).put("tbrConstrained",data.opt("tbrConstrained"))
        }
    }

    fun write(file: File, from: Long, to: Long) = file.bufferedWriter().use { writer ->
        writer.appendLine(columns.keys.joinToString(","))
        rows.filter { it.key in from..to && it.value.optBoolean("sample") }.forEach { (_,row) ->
            writer.appendLine(columns.values.joinToString(",") { csv(row.opt(it)) })
        }
    }

    private fun copy(source: JSONObject, target: JSONObject, keys: List<String>) {
        keys.filter { source.has(it) }.forEach { target.put(it,source.opt(it)) }
    }

    companion object {
        val columns = linkedMapOf("timestamp" to "timestamp", "glucose_mgdl" to "glucoseMgdl", "display_glucose" to "displayGlucose",
            "delta" to "delta", "trend" to "trend", "target" to "target", "temp_target" to "tempTarget", "basal_profile" to "scheduledBasal",
            "tbr_rate" to "tbrRate", "tbr_remaining" to "tbrRemaining", "iob" to "iob", "basal_iob" to "basalIob", "bolus_iob" to "bolusIob",
            "cob" to "cob", "carbs_recent" to "carbs", "profile_isf" to "profileIsf", "dynamic_isf" to "currentDynamicIsf",
            "dosing_isf" to "dosingIsf", "future_isf" to "futureIsf", "cr" to "cr", "tdd" to "weightedTdd",
            "dynisf_factor" to "dynIsfAdjustmentFactor", "autoisf_factor" to "autoIsfFactor", "insulin_req" to "insulinReq", "smb_eligible" to "smbEligible", "smb_wait" to "smbWaiting",
            "smb_requested" to "smbRequested", "smb_constrained" to "smbConstrained", "smb_delivered" to "smbDelivered",
            "tbr_constrained" to "tbrConstrained", "loop_generation" to "loopGeneration", "calculation_ms" to "calculationMs",
            "pump_connected" to "pumpConnected", "pump_reservoir_pump_units" to "pumpReservoirPumpUnits", "pump_battery" to "pumpBattery",
            "activity_state" to "activityState", "profile_name" to "profileName", "profile_percentage" to "profilePercentage")
        private fun csv(value: Any?): String {
            if (value==null || value==JSONObject.NULL) return ""
            val text=value.toString()
            val safe=if (text.firstOrNull() in listOf('=','+','@','\t','\r') || text.startsWith('-') && text.toDoubleOrNull()==null) "'$text" else text
            return "\"${safe.replace("\"","\"\"")}\""
        }
    }
}
