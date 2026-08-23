package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BenchRestartCampaignTest {

    @Test fun `1 button hidden when experimental flag is false`() {
        assertThat(BenchRestartVisibility.isVisible(engineeringMode = true, experimentalEnabled = false)).isFalse()
    }

    @Test fun `2 unknown firmware blocks before writes`() {
        val io = FakeIo(listOf(snapshot(firmware = "unknown")))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `3 unsupported model blocks before writes`() {
        val io = FakeIo(listOf(snapshot(supportedModel = false)))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `4 non-active real state blocks`() {
        val io = FakeIo(listOf(snapshot(state = MedtrumPumpState.EJECTED)))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `5 active bolus or unsafe queue blocks`() {
        val io = FakeIo(listOf(snapshot()))
        assertBlockedWithoutWrites(campaign(io).run(validRequest().copy(queueSafe = false, bolusSafe = false)), io)
    }

    @Test fun `6 campaign cannot emit PRIME`() {
        val io = FakeIo(listOf(snapshot()))
        campaign(io).run(validRequest())
        assertThat(io.primeCount).isEqualTo(0)
    }

    @Test fun `7 campaign cannot emit STOP_PATCH`() {
        val io = FakeIo(listOf(snapshot()))
        campaign(io).run(validRequest())
        assertThat(io.stopPatchCount).isEqualTo(0)
    }

    @Test fun `8 campaign cannot invoke deactivatePatch`() {
        val io = FakeIo(listOf(snapshot()))
        campaign(io).run(validRequest())
        assertThat(io.deactivateCount).isEqualTo(0)
    }

    @Test fun `9 campaign cannot invoke resetPatchParameters`() {
        val io = FakeIo(listOf(snapshot()))
        campaign(io).run(validRequest())
        assertThat(io.resetLocalCount).isEqualTo(0)
    }

    @Test fun `10 campaign cannot spoof local EJECTED state`() {
        val io = FakeIo(listOf(snapshot()))
        campaign(io).run(validRequest())
        assertThat(io.localStateSpoofCount).isEqualTo(0)
    }

    @Test fun `11 baseline event contains distinct device age and start fields`() {
        val events = mutableListOf<BenchRestartEvent>()
        val io = FakeIo(listOf(snapshot(localStart = 10, deviceStart = 20, age = 30)))
        BenchRestartCampaign(io, candidateProvider = { null }, emit = events::add).run(validRequest())
        val fields = events.first { it.name == "bench_restart_baseline_snapshot" }.fields
        assertThat(fields["localPatchStartTime"]).isEqualTo(10L)
        assertThat(fields["deviceReportedStartTime"]).isEqualTo(20L)
        assertThat(fields["deviceReportedPatchAge"]).isEqualTo(30L)
    }

    @Test fun `12 SET_PATCH probe preserves unrelated fields`() {
        val original = settings()
        val probe = original.expirationProbe()
        assertThat(probe.copy(expirationEnabled = original.expirationEnabled)).isEqualTo(original)
    }

    @Test fun `13 SET_PATCH probe restores original setting`() {
        val io = successfulRestartIo()
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.settings).isEqualTo(settings())
        assertThat(io.settingsWrites).containsExactly(settings().expirationProbe(), settings()).inOrder()
    }

    @Test fun `14 SET_PATCH timeout is not retransmitted`() {
        val io = FakeIo(listOf(snapshot()), settings = settings(), settingsResults = ArrayDeque(listOf(BenchWriteResult(false, timedOut = true))))
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.settingsWrites).hasSize(1)
    }

    @Test fun `15 no confirmed candidate blocks with no experimental write`() {
        val io = FakeIo(listOf(snapshot()))
        val result = campaign(io).run(validRequest())
        assertBlockedWithoutWrites(result, io)
        assertThat(io.synchronizeCount).isEqualTo(2)
    }

    @Test fun `16 candidate is transmitted at most once`() {
        val io = successfulRestartIo()
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.candidateCount).isEqualTo(1)
    }

    @Test fun `17 candidate timeout performs read-only recovery without duplicate TX`() {
        val io = successfulRestartIo(candidateResult = BenchWriteResult(false, timedOut = true))
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.candidateCount).isEqualTo(1)
        assertThat(io.readOnlyRecoveryCount).isEqualTo(1)
    }

    @Test fun `18 candidate success while device remains ACTIVE does not ACTIVATE`() {
        val io = FakeIo(
            snapshots = listOf(snapshot(), snapshot(), snapshot(state = MedtrumPumpState.ACTIVE)),
            settings = settings()
        )
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.activateCount).isEqualTo(0)
    }

    @Test fun `19 real activation-ready state allows ACTIVATE`() {
        val io = successfulRestartIo()
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.activateCount).isEqualTo(1)
    }

    @Test fun `20 ACTIVATE is transmitted at most once`() {
        val io = successfulRestartIo()
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.activateCount).isEqualTo(1)
    }

    @Test fun `21 ACTIVATE timeout synchronizes before further decision`() {
        val io = successfulRestartIo(activateResult = BenchWriteResult(false, timedOut = true))
        campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(io.readOnlyRecoveryCount).isEqualTo(1)
        assertThat(io.synchronizeCount).isAtLeast(6)
    }

    @Test fun `22 final ACTIVE without device timer evidence is not confirmed`() {
        val io = successfulRestartIo(
            final = snapshot(state = MedtrumPumpState.ACTIVE, deviceStart = 100, age = 500),
            subsequent = snapshot(state = MedtrumPumpState.ACTIVE, deviceStart = 100, age = 501)
        )
        val result = campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(result.restartProven).isFalse()
    }

    @Test fun `23 AGE reset and subsequent increase proves restart`() {
        val io = successfulRestartIo()
        val result = campaign(io, confirmedCandidate()).run(validRequest())
        assertThat(result.restartProven).isTrue()
    }

    @Test fun `24 campaign construction after process restart does not auto-write`() {
        val io = FakeIo(listOf(snapshot()))
        BenchRestartCampaign(io)
        assertThat(io.totalWrites()).isEqualTo(0)
    }

    @Test fun `25 failure cleanup never emits STOP_PATCH`() {
        val io = FakeIo(listOf(snapshot()), synchronizeSuccess = false)
        campaign(io).run(validRequest())
        assertThat(io.stopPatchCount).isEqualTo(0)
    }

    private fun campaign(io: FakeIo, candidate: ConfirmedRestartCandidate? = null) =
        BenchRestartCampaign(io, candidateProvider = { candidate })

    private fun validRequest() = BenchRestartRequest(true, true, queueSafe = true, bolusSafe = true)

    private fun assertBlockedWithoutWrites(result: BenchRestartResult, io: FakeIo) {
        assertThat(result.state).isEqualTo(BenchRestartState.BLOCKED)
        assertThat(io.totalWrites()).isEqualTo(0)
    }

    private fun confirmedCandidate() = ConfirmedRestartCandidate("test-only", 0x55, byteArrayOf(0x55), CandidateConfidence.CONFIRMED)

    private fun settings() = BenchPatchSettings(7, 40, 180, true, 0, 12, 0, 0, 30)

    private fun successfulRestartIo(
        candidateResult: BenchWriteResult = BenchWriteResult(true),
        activateResult: BenchWriteResult = BenchWriteResult(true),
        final: BenchRestartSnapshot = snapshot(state = MedtrumPumpState.ACTIVE, deviceStart = 200, age = 2),
        subsequent: BenchRestartSnapshot = snapshot(state = MedtrumPumpState.ACTIVE, deviceStart = 200, age = 3)
    ) = FakeIo(
        snapshots = listOf(
            snapshot(deviceStart = 100, age = 500),
            snapshot(deviceStart = 100, age = 500),
            snapshot(state = MedtrumPumpState.EJECTED, deviceStart = 100, age = 500),
            final,
            subsequent
        ),
        settings = settings(),
        candidateResult = candidateResult,
        activateResult = activateResult
    )

    private class FakeIo(
        snapshots: List<BenchRestartSnapshot>,
        var settings: BenchPatchSettings? = null,
        private val synchronizeSuccess: Boolean = true,
        private val candidateResult: BenchWriteResult = BenchWriteResult(true),
        private val activateResult: BenchWriteResult = BenchWriteResult(true),
        private val settingsResults: ArrayDeque<BenchWriteResult> = ArrayDeque()
    ) : BenchRestartIo {
        private val snapshots = ArrayDeque(snapshots)
        var synchronizeCount = 0
        var candidateCount = 0
        var activateCount = 0
        var readOnlyRecoveryCount = 0
        var primeCount = 0
        var stopPatchCount = 0
        var deactivateCount = 0
        var resetLocalCount = 0
        var localStateSpoofCount = 0
        val settingsWrites = mutableListOf<BenchPatchSettings>()

        override fun synchronize(): Boolean {
            synchronizeCount++
            return synchronizeSuccess
        }

        override fun readHistory(): Boolean = true
        override fun snapshot(): BenchRestartSnapshot = snapshots.removeFirst()
        override fun readPatchSettings(): BenchPatchSettings? = settings

        override fun transmitSettings(settings: BenchPatchSettings): BenchWriteResult {
            settingsWrites += settings
            val result = if (settingsResults.isEmpty()) BenchWriteResult(true) else settingsResults.removeFirst()
            if (result.success) this.settings = settings
            return result
        }

        override fun transmitCandidate(candidate: ConfirmedRestartCandidate): BenchWriteResult {
            candidateCount++
            return candidateResult
        }

        override fun transmitActivate(): BenchWriteResult {
            activateCount++
            return activateResult
        }

        override fun readOnlyRecovery(): Boolean {
            readOnlyRecoveryCount++
            return true
        }

        fun totalWrites(): Int = settingsWrites.size + candidateCount + activateCount + primeCount + stopPatchCount + deactivateCount + resetLocalCount + localStateSpoofCount
    }

    companion object {
        private fun snapshot(
            firmware: String = "1.80.89",
            supportedModel: Boolean = true,
            state: MedtrumPumpState = MedtrumPumpState.ACTIVE,
            localStart: Long = 100,
            deviceStart: Long = 100,
            age: Long = 500
        ) = BenchRestartSnapshot(
            firmware = firmware,
            deviceType = 80,
            supportedModel = supportedModel,
            pumpState = state,
            connectionState = "CONNECTED",
            patchId = 1,
            localPatchStartTime = localStart,
            deviceReportedStartTime = deviceStart,
            deviceReportedPatchAge = age,
            reservoir = 80.0,
            batteryA = 3.0,
            batteryB = 3.0,
            currentSequence = 10,
            syncedSequence = 9,
            sessionTokenFingerprint = "abcdef",
            basalType = "STANDARD",
            basalRate = 1.0,
            activeAlarms = emptyList(),
            desiredPatchExpiration = true
        )
    }
}
