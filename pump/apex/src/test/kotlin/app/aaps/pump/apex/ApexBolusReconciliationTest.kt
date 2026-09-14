package app.aaps.pump.apex

import android.content.Context
import app.aaps.pump.apex.bolus.ApexBolusCoordinator
import app.aaps.pump.apex.bolus.ApexBolusState
import app.aaps.pump.apex.bolus.ApexHistoryCandidate
import app.aaps.pump.apex.bolus.ApexReconciliationResult
import app.aaps.pump.apex.diagnostics.ApexTrace
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path

class ApexBolusReconciliationTest {
    @TempDir lateinit var directory: Path
    private lateinit var coordinator: ApexBolusCoordinator

    @BeforeEach fun setup() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory.toFile())
        coordinator = ApexBolusCoordinator(context, mock<ApexTrace>())
    }

    @Test fun `incident 9_25 live complete without history remains uncertain`() {
        val operation = prepared(9.25, 370, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 370, 4)
        coordinator.markAccepted(operation.operationUuid, 4)
        listOf(360, 362, 364, 366, 368).forEach { coordinator.markProgress(operation.operationUuid, it, 4) }
        coordinator.markLiveCompleted(operation.operationUuid, 370, 4)

        assertThat(coordinator.reconcile(pump, "LatestBoluses", incidentRows(), 4)).isEqualTo(ApexReconciliationResult.NeedLatestRetry)
        assertThat(coordinator.reconcile(pump, "LatestBoluses", incidentRows(), 4)).isEqualTo(ApexReconciliationResult.NeedFullHistory)
        val result = coordinator.reconcile(pump, "BolusHistory", incidentRows(), 4)

        assertThat(result).isInstanceOf(ApexReconciliationResult.StillUncertain::class.java)
        assertThat(coordinator.current()!!.state).isEqualTo(ApexBolusState.DELIVERY_UNCERTAIN)
        assertThat(coordinator.current()!!.highestProgressSteps).isEqualTo(370)
        assertThat(coordinator.safetyGateActive).isTrue()
    }

    @Test fun `normal 7_60 bolus matches persistent 304 steps exactly once`() {
        val operation = prepared(7.60, 304, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 304, 5)
        coordinator.markAccepted(operation.operationUuid, 5)
        coordinator.markLiveCompleted(operation.operationUuid, 304, 5)
        val row = candidate(1, incidentTime, 304, 304)

        val first = coordinator.reconcile(pump, "LatestBoluses", listOf(row), 5)
        val second = coordinator.reconcile(pump, "BolusHistory", listOf(row), 5)

        assertThat((first as ApexReconciliationResult.Matched).operation.state).isEqualTo(ApexBolusState.CONFIRMED_DELIVERED)
        assertThat(second).isEqualTo(ApexReconciliationResult.NoUnresolved)
        assertThat(coordinator.safetyGateActive).isFalse()
    }

    @Test fun `requested 129 performed 130 preserves mismatch and delivered amount`() {
        val operation = prepared(3.225, 129, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 129, 6)
        val match = coordinator.reconcile(pump, "LatestBoluses", listOf(candidate(2, incidentTime, 129, 130)), 6) as ApexReconciliationResult.Matched
        assertThat(match.operation.matchedRequestedSteps).isEqualTo(129)
        assertThat(match.operation.matchedPerformedSteps).isEqualTo(130)
        assertThat(match.candidate.performedSteps - match.operation.encodedSteps).isEqualTo(1)
    }

    @Test fun `partial persistent result is confirmed partial`() {
        val operation = prepared(1.0, 40, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 40, 1)
        val result = coordinator.reconcile(pump, "LatestBoluses", listOf(candidate(1, incidentTime, 40, 17)), 1) as ApexReconciliationResult.Matched
        assertThat(result.operation.state).isEqualTo(ApexBolusState.PARTIALLY_DELIVERED_CONFIRMED)
        assertThat(result.operation.matchedPerformedSteps).isEqualTo(17)
    }

    @Test fun `cancelled zero persistent result is confirmed zero`() {
        val operation = prepared(1.0, 40, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 40, 1)
        val result = coordinator.reconcile(pump, "LatestBoluses", listOf(candidate(1, incidentTime, 40, 0)), 1) as ApexReconciliationResult.Matched
        assertThat(result.operation.state).isEqualTo(ApexBolusState.CANCELLED_CONFIRMED)
    }

    @Test fun `stale August result zero never matches current September operation`() {
        val operation = prepared(9.25, 370, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 370, 3)
        val stale = candidate(0, 1_786_537_499_000L, 370, 370)
        assertThat(coordinator.reconcile(pump, "LatestBoluses", listOf(stale), 3)).isEqualTo(ApexReconciliationResult.NeedLatestRetry)
        assertThat(coordinator.safetyGateActive).isTrue()
    }

    @Test fun `timestamp alone cannot match unrelated dose`() {
        prepared(9.25, 370, incidentTime)
        val sameTimeWrongDose = candidate(0, incidentTime, 16, 16)
        assertThat(coordinator.reconcile(pump, "LatestBoluses", listOf(sameTimeWrongDose), 1)).isEqualTo(ApexReconciliationResult.NeedLatestRetry)
    }

    @Test fun `same dose outside explicit tolerance cannot match`() {
        prepared(9.25, 370, incidentTime)
        val tooOld = candidate(0, incidentTime - ApexBolusCoordinator.HISTORY_TIME_TOLERANCE_MS - 1, 370, 370)
        assertThat(coordinator.reconcile(pump, "LatestBoluses", listOf(tooOld), 1)).isEqualTo(ApexReconciliationResult.NeedLatestRetry)
    }

    @Test fun `different pump cannot reconcile operation`() {
        prepared(1.0, 40, incidentTime)
        assertThat(coordinator.reconcile("other-pump", "BolusHistory", listOf(candidate(1, incidentTime, 40, 40)), 2))
            .isEqualTo(ApexReconciliationResult.DifferentPump)
        assertThat(coordinator.safetyGateActive).isTrue()
    }

    @Test fun `full history no match enables audited operator resolution`() {
        val operation = prepared(0.1, 4, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 4, 7)
        coordinator.markTransportWriteIssued(operation.operationUuid, 7)
        coordinator.markTransportOutcomeUnknown(operation.operationUuid, 7, "disconnect_after_write")

        coordinator.reconcile(pump, "BolusHistory", emptyList(), 8)
        assertThat(coordinator.canOperatorResolve(operation.operationUuid)).isTrue()
        assertThat(coordinator.operatorConfirmNotDelivered(operation.operationUuid, pump, "deadbeef")).isTrue()
        assertThat(coordinator.safetyGateActive).isFalse()

        val audit = coordinator.operation(operation.operationUuid)!!
        assertThat(audit.state).isEqualTo(ApexBolusState.OPERATOR_CONFIRMED_NOT_DELIVERED)
        assertThat(audit.operatorConfirmation).isTrue()
        assertThat(audit.operatorConfirmationBuildSha).isEqualTo("deadbeef")
        assertThat(audit.fullHistoryResult).isEqualTo("NOT_FOUND")

        val restored = restoredCoordinator()
        assertThat(restored.safetyGateActive).isFalse()
        assertThat(restored.operation(operation.operationUuid)!!.state).isEqualTo(ApexBolusState.OPERATOR_CONFIRMED_NOT_DELIVERED)
    }

    @Test fun `manual resolution is unavailable before successful full history`() {
        val operation = prepared(0.1, 4, incidentTime)
        coordinator.beginTransportWrite(operation.operationUuid, 4, 7)
        coordinator.markTransportWriteIssued(operation.operationUuid, 7)
        coordinator.markTransportOutcomeUnknown(operation.operationUuid, 7, "disconnect_after_write")

        assertThat(coordinator.operatorConfirmNotDelivered(operation.operationUuid, pump, "deadbeef")).isFalse()
        assertThat(coordinator.safetyGateActive).isTrue()
    }

    @Test fun `automatic reconciliation uses bounded backoff while manual remains available`() {
        prepared(0.1, 4, incidentTime)
        assertThat(coordinator.beginReconciliationAttempt(manual = false, onConnect = false, now = 1_000L)).isTrue()
        assertThat(coordinator.beginReconciliationAttempt(manual = false, onConnect = false, now = 1_001L)).isFalse()
        assertThat(coordinator.beginReconciliationAttempt(manual = true, onConnect = false, now = 1_002L)).isTrue()
        assertThat(coordinator.current()!!.automaticAttempts).isEqualTo(1)
        assertThat(coordinator.current()!!.manualAttempts).isEqualTo(1)
    }

    private fun prepared(units: Double, steps: Int, timestamp: Long) = requireNotNull(coordinator.prepare(
        pump, "1.1", "4.12", timestamp, timestamp, "NORMAL", units, steps, "test", "queue-1", 1,
    ))

    private fun restoredCoordinator(): ApexBolusCoordinator {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory.toFile())
        return ApexBolusCoordinator(context, mock<ApexTrace>())
    }

    private fun candidate(index: Int, timestamp: Long, requested: Int, performed: Int) = ApexHistoryCandidate(
        index, timestamp, requested, performed, "260913113059", 16, "LatestBoluses", index,
    )

    private fun incidentRows() = listOf(
        candidate(0, 1_786_537_499_000L, 16, 16),
        candidate(1, incidentTime + 7 * 60_000L, 17, 17),
        candidate(2, incidentTime, 6, 6),
    )

    companion object {
        private const val pump = "pump-hash"
        private const val incidentTime = 1_789_298_639_000L
    }
}
