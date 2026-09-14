package app.aaps.pump.apex.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.json.JSONObject

class TracePrivacyTest {
    @Test fun `credentials bytes and numeric pump serial never escape trace`() {
        for (key in listOf("pairingKey","encryptionKey","password","accessToken")) {
            assertEquals("[REDACTED]",ApexTraceSanitizer.sanitize(key,12345678,100))
        }
        assertNotEquals("12345678",ApexTraceSanitizer.sanitize("serial",12345678,100).toString())
        assertEquals(JSONObject.NULL,ApexTraceSanitizer.sanitize("data",byteArrayOf(1,2,3),100))
        assertEquals(4,ApexTraceSanitizer.sanitize("generation",4,100))
    }
}
