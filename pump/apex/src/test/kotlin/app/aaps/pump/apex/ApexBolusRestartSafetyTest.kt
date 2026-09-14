package app.aaps.pump.apex

import android.content.Context
import app.aaps.pump.apex.bolus.ApexBolusCoordinator
import app.aaps.pump.apex.bolus.ApexBolusState
import app.aaps.pump.apex.bolus.ApexReconciliationResult
import app.aaps.pump.apex.diagnostics.ApexTrace
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.json.JSONArray
import org.json.JSONObject
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
        first.markTransportWriteIssued(operation.operationUuid, 1)
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
        value.markTransportWriteIssued(operation.operationUuid, 1)
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

    @Test fun `prepared record before physical write clears safely after restart`() {
        val first = coordinator()
        val operation = prepare(first)
        val restored = coordinator()
        assertThat(restored.safetyGateActive).isFalse()
        assertThat(restored.current()).isNull()
        assertThat(restored.operation(operation.operationUuid)!!.state).isEqualTo(ApexBolusState.DEFINITELY_NOT_DELIVERED)
        assertThat(restored.operation(operation.operationUuid)!!.transportWriteIssued).isFalse()
    }

    @Test fun `attempt started but issue unknown remains fail closed after restart`() {
        val first = coordinator()
        val operation = prepare(first)
        first.beginTransportWrite(operation.operationUuid, 198, 3)

        val restored = coordinator()
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.current()!!.transportWriteIssued).isNull()
    }

    @Test fun `corrupt durable journal fails closed`() {
        directory.resolve("apex").toFile().mkdirs()
        directory.resolve("apex/bolus-operations.json").toFile().writeText("{truncated")
        val restored = coordinator()
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.current()!!.state).isEqualTo(ApexBolusState.RECONCILIATION_REQUIRED)
    }

    @Test fun `legacy 4fa14e29 uncertain operation migrates without deletion`() {
        val apex = directory.resolve("apex").toFile().apply { mkdirs() }
        val operation = JSONObject()
            .put("schemaVersion", 1)
            .put("operationUuid", "4fa14e29-0000-0000-0000-000000000000")
            .put("pumpIdentityHash", pump)
            .put("createdUtc", now)
            .put("requestedTimestamp", now)
            .put("temporaryId", now)
            .put("bolusType", "SMB")
            .put("requestedUnits", 0.1)
            .put("encodedSteps", 4)
            .put("caller", "legacy")
            .put("state", "DELIVERY_UNCERTAIN")
        apex.resolve("bolus-operations.json").writeText(
            JSONObject().put("schemaVersion", 1).put("operations", JSONArray().put(operation)).toString(),
        )

        val restored = coordinator()
        val migrated = restored.current()!!
        assertThat(migrated.operationUuid).startsWith("4fa14e29")
        assertThat(migrated.schemaVersion).isEqualTo(2)
        assertThat(migrated.legacyMigrated).isTrue()
        assertThat(migrated.transportWriteIssued).isNull()
        assertThat(migrated.state).isEqualTo(ApexBolusState.RECONCILIATION_REQUIRED)
        assertThat(restored.safetyGateActive).isTrue()
        assertThat(restored.reconcile(pump, "BolusHistory", emptyList(), 9)).isInstanceOf(ApexReconciliationResult.StillUncertain::class.java)
        assertThat(restored.canOperatorResolve(migrated.operationUuid)).isTrue()
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
