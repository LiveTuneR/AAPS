package app.aaps.pump.apex

import android.content.Context
import app.aaps.pump.apex.bolus.ApexBolusCoordinator
import app.aaps.pump.apex.bolus.ApexBolusState
import app.aaps.pump.apex.diagnostics.ApexTrace
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path

class ApexBolusRestartSafetyTest {
    @TempDir lateinit var directory: Path

    @Test fun `restart after accepted restores gate`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 8)
        first.markAccepted(operation.operationUuid, 8)

        val restored = coordinator()
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.ACCEPTED)
    }

    @Test fun `restart after completed before history restores gate`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 8)
        first.markAccepted(operation.operationUuid, 8)
        first.markLiveCompleted(operation.operationUuid, 198, 8)

        val restored = coordinator()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.HISTORY_CONFIRMING)
        assertThat(restored.safetyGateActive).isTrue()
    }

    @Test fun `disconnect after progress stays uncertain across restart`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 9)
        first.markProgress(operation.operationUuid, 140, 9)
        first.markTimeoutOrDisconnect(operation.operationUuid, 9, "gatt_status_22")

        assertThat(coordinator().current()!!.state).isEqualTo(ApexBolusState.DELIVERY_UNCERTAIN)
        assertThat(coordinator().current()!!.highestProgressSteps).isEqualTo(140)
    }

    @Test fun `one operation cannot authorize a second physical write`() {
        val value = coordinator()
        val operation = prepare(value)
        assertThat(value.beginTransportWrite(operation.operationUuid, 198, 1)).isTrue()
        assertThat(value.beginTransportWrite(operation.operationUuid, 198, 1)).isFalse()
    }

    @Test fun `new bolus is blocked while unresolved`() {
        val value = coordinator()
        prepare(value)
        assertThat(value.prepare(pump, "1.1", "4.12", now + 60_000, now + 60_000, "SMB", 0.1, 4, "loop", "queue-2", 2)).isNull()
    }

    @Test fun `provable write failure clears gate`() {
        val value = coordinator()
        val operation = prepare(value)
        value.beginTransportWrite(operation.operationUuid, 198, 1)
        value.markDefinitelyNotIssued(operation.operationUuid)
        assertThat(value.safetyGateActive).isFalse()
        assertThat(value.current()).isNull()
    }

    @Test fun `issued write timeout stays uncertain across restart`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 1)
        first.markTransportOutcomeUnknown(operation.operationUuid, 1, "write_callback_timeout")

        val restored = coordinator()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.DELIVERY_UNCERTAIN)
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.beginTransportWrite(operation.operationUuid, 198, 2)).isFalse()
    }

    @Test fun `gatt 133 after issue stays uncertain`() {
        val value = coordinator()
        val operation = prepare(value)
        value.beginTransportWrite(operation.operationUuid, 198, 1)
        value.markTransportOutcomeUnknown(operation.operationUuid, 1, "write_status_133")

        assertThat(value.current()!!.state).isEqualTo(ApexBolusState.DELIVERY_UNCERTAIN)
        assertThat(value.safetyGateActive).isTrue()
    }

    @Test fun `accepted cancel without progress requires history`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 1)
        first.markAccepted(operation.operationUuid, 1)
        first.markCancelAcceptedRequiresHistory(operation.operationUuid, 1)

        val restored = coordinator()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.DELIVERY_UNCERTAIN)
        assertThat(restored.current()!!.cancelled).isTrue()
        assertThat(restored.safetyGateActive).isTrue()
    }

    @Test fun `invalid before dosing clears gate`() {
        val value = coordinator()
        val operation = prepare(value)
        value.rejectBeforeDelivery(operation.operationUuid)
        assertThat(value.safetyGateActive).isFalse()
    }

    @Test fun `prepared record is durable before send`() {
        val first = coordinator()
        prepare(first)
        val restored = coordinator()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.PREPARED)
    }

    @Test fun `corrupt durable journal fails closed`() {
        directory.resolve("apex").toFile().mkdirs()
        directory.resolve("apex/bolus-operations.json").toFile().writeText("{truncated")
        val restored = coordinator()
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.RECONCILIATION_REQUIRED)
    }

    private fun coordinator(): ApexBolusCoordinator {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory.toFile())
        return ApexBolusCoordinator(context, mock<ApexTrace>())
    }

    private fun prepare(value: ApexBolusCoordinator) = requireNotNull(value.prepare(
        pump, "1.1", "4.12", now, now, "NORMAL", 4.95, 198, "test", "queue", 1,
    ))

    companion object {
        private const val pump = "pump-hash"
        private const val now = 1_789_248_659_000L
    }
}
