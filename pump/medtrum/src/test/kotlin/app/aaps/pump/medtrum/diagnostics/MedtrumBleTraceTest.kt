package app.aaps.pump.medtrum.diagnostics

import app.aaps.pump.medtrum.diagnostics.MedtrumBleTrace.Companion.toHex
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MedtrumBleTraceTest {

    @Test
    fun `raw BLE bytes use unsigned fixed-width hex`() {
        assertThat(byteArrayOf(0x00, 0x0F, 0x10, 0x7F, 0x80.toByte(), 0xFF.toByte()).toHex())
            .isEqualTo("000F107F80FF")
    }
}
