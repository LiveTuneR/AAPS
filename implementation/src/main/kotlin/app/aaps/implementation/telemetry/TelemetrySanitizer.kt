package app.aaps.implementation.telemetry

import org.json.JSONArray
import org.json.JSONObject

/** Numeric evidence is allowlisted by callers; credentials and arbitrary object text never pass through. */
internal object TelemetrySanitizer {
    private val forbidden = Regex("secret|token|password|passwd|credential|authorization|oauth|pairing|encryption|serial|wifi|ssid|api.?key|private.?key", RegexOption.IGNORE_CASE)
    private val texts = setOf("algorithm", "sourcesensor", "source", "trend", "displayunits", "reason", "conditionreason", "blockreason",
        "isfbasis", "futureisfbasis", "type", "eventtype", "state", "model", "firmware", "protocol", "profilename", "profileid", "insulintype",
        "commandtype", "errortype", "stage", "status", "timezone", "buildsha", "appversion", "class", "method", "file", "setting", "unit",
        "activitystate", "decisionid", "requestid", "queuerequestid", "correlationid", "runningmode", "schema", "outcome", "availability", "name", "oldvalue", "newvalue", "changeclassification",
        "command", "commandname", "safety", "priority", "gapreason", "linkstate", "pendingcommand", "expected")
    private val arrays = setOf("iobforecast", "basalschedule", "isfschedule", "crschedule", "targetschedule", "predictions", "iob", "zt", "cob", "acob", "uam", "constraints", "stack", "changes", "components")
    private val bearer = Regex("(?i)(bearer\\s+|(?:access[_-]?token|api[_-]?secret|password)\\s*[:=]\\s*)[^\\s,;]+")
    private val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val jwt = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    fun clean(source: JSONObject): JSONObject = objectValue(source,0)

    private fun objectValue(source: JSONObject, depth: Int): JSONObject {
        require(depth <= 12) { "Telemetry nesting limit" }
        val result = JSONObject()
        source.keys().asSequence().forEach { key ->
            if (forbidden.containsMatchIn(key)) return@forEach
            val value = source.opt(key)
            result.put(key, when (value) {
                null, JSONObject.NULL -> JSONObject.NULL
                is Double -> if (value.isFinite()) value else JSONObject.NULL
                is Float -> if (value.isFinite()) value else JSONObject.NULL
                is Number, is Boolean -> value
                is JSONObject -> objectValue(value,depth+1)
                is JSONArray -> if (key.lowercase() in arrays) arrayValue(value,depth+1) else JSONObject.NULL
                is String -> if (key.lowercase() in texts) safeText(value) else JSONObject.NULL
                else -> JSONObject.NULL
            })
        }
        return result
    }

    private fun arrayValue(source: JSONArray, depth: Int): JSONArray {
        require(depth <= 12 && source.length() <= 1000) { "Telemetry array limit" }
        return JSONArray().apply {
            for (i in 0 until source.length()) {
                val value = source.opt(i)
                put(when (value) {
                    is JSONObject -> objectValue(value,depth+1)
                    is Double -> if (value.isFinite()) value else JSONObject.NULL
                    is Number, is Boolean -> value
                    is String -> safeText(value)
                    else -> JSONObject.NULL
                })
            }
        }
    }

    private fun safeText(value: String): String {
        require(value.length <= 8192) { "Telemetry text limit" }
        return jwt.replace(url.replace(bearer.replace(value,"[REDACTED]"),"[URL_REDACTED]"),"[JWT_REDACTED]")
    }
}
