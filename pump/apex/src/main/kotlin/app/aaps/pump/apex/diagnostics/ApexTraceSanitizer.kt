package app.aaps.pump.apex.diagnostics

import org.json.JSONObject
import java.security.MessageDigest

internal object ApexTraceSanitizer {
    private val sensitiveKeyParts = listOf("serial", "address", "mac", "deviceid", "device_id")
    private val secretKey = Regex("secret|password|token|credential|pairing|encrypt|private.?key",RegexOption.IGNORE_CASE)

    fun sanitize(key: String, value: Any?, maxLength: Int): Any {
        if (secretKey.containsMatchIn(key)) return "[REDACTED]"
        if (value != null && sensitiveKeyParts.any { key.lowercase().contains(it) }) return anonymize(value.toString())
        return when (value) {
        null -> JSONObject.NULL
        is ByteArray -> JSONObject.NULL
        is Number, is Boolean -> value
        is Enum<*> -> value.name
        else -> {
            val text = value.toString()
            if (sensitiveKeyParts.any { key.lowercase().contains(it) }) anonymize(text) else text.take(maxLength)
        }
    }
    }

    fun anonymize(value: String): String {
        if (value.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
