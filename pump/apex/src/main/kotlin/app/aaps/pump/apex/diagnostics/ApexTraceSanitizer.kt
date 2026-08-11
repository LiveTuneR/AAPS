package app.aaps.pump.apex.diagnostics

import org.json.JSONObject
import java.security.MessageDigest

internal object ApexTraceSanitizer {
    private val sensitiveKeyParts = listOf("serial", "address", "mac", "deviceid", "device_id")

    fun sanitize(key: String, value: Any?, maxLength: Int): Any = when (value) {
        null -> JSONObject.NULL
        is Number, is Boolean -> value
        is Enum<*> -> value.name
        else -> {
            val text = value.toString()
            if (sensitiveKeyParts.any { key.lowercase().contains(it) }) anonymize(text) else text.take(maxLength)
        }
    }

    fun anonymize(value: String): String {
        if (value.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
