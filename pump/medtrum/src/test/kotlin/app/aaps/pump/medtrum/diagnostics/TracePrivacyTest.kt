package app.aaps.pump.medtrum.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.json.JSONObject

class TracePrivacyTest {
    @Test fun `credentials numeric serial and packet bytes never escape trace`() {
        for (key in listOf("pairingKey","encryptionKey","password","accessToken","serial","sn","address")) {
            assertEquals("[REDACTED]",MedtrumTraceRedaction.clean(key,12345678))
        }
        assertEquals(JSONObject.NULL,MedtrumTraceRedaction.clean("data",byteArrayOf(1,2,3)))
        assertEquals(4,MedtrumTraceRedaction.clean("generation",4))
    }
}
