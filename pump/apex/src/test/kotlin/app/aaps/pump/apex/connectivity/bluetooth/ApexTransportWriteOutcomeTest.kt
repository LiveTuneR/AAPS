package app.aaps.pump.apex.connectivity.bluetooth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ApexTransportWriteOutcomeTest {

    @Test
    fun `first rejected chunk is definitely not issued`() {
        assertThat(rejectedWriteOutcome(anyChunkIssued = false)).isEqualTo(ApexTransportWriteOutcome.NOT_ISSUED)
    }

    @Test
    fun `later rejected chunk leaves command outcome unknown`() {
        assertThat(rejectedWriteOutcome(anyChunkIssued = true)).isEqualTo(ApexTransportWriteOutcome.ISSUED_OUTCOME_UNKNOWN)
    }
}
