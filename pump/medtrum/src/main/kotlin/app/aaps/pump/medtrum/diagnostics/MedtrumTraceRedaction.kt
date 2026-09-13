package app.aaps.pump.medtrum.diagnostics

import org.json.JSONObject

internal object MedtrumTraceRedaction {
    private val secret=Regex("secret|token|password|pairing|encrypt|serial|address|device.?id|mac|^sn$",RegexOption.IGNORE_CASE)
    fun clean(key: String,value: Any?): Any = when {
        secret.containsMatchIn(key) -> "[REDACTED]"
        value is ByteArray -> JSONObject.NULL
        value==null -> JSONObject.NULL
        value is Number || value is Boolean -> value
        else -> value.toString().take(240)
    }
}
