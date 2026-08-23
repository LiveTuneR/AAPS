package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class BenchRestartCampaignTest {

    @Test fun `1 experimental option is available in normal and simple modes`() {
        val key = MedtrumBooleanKey.MedtrumBenchRestartExperimental
        assertThat(key.engineeringModeOnly).isFalse()
        assertThat(key.defaultedBySM).isFalse()
        assertThat(BenchRestartVisibility.isVisible(experimentalEnabled = false)).isFalse()
        assertThat(BenchRestartVisibility.isVisible(experimentalEnabled = true)).isTrue()
    }

    @Test fun `2 unknown firmware blocks before writes`() {
        val io = FakeIo(listOf(snapshot(firmware = "unknown")))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `3 unsupported model blocks before writes`() {
        val io = FakeIo(listOf(snapshot(supportedModel = false)))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `4 non-active real state blocks before writes`() {
        val io = FakeIo(listOf(snapshot(state = MedtrumPumpState.EJECTED)))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `5 missing device timer blocks before writes`() {
        val io = FakeIo(listOf(snapshot(timerAvailable = false)))
        assertBlockedWithoutWrites(campaign(io).run(validRequest()), io)
    }

    @Test fun `6 active bolus or unsafe queue blocks before IO`() {
        val io = FakeIo(defaultSnapshots())
        assertBlockedWithoutWrites(campaign(io).run(validRequest().copy(queueSafe = false, bolusSafe = false)), io)
        assertThat(io.synchronizeCount).isEqualTo(0)
    }

    @Test fun `7 null hidden candidate does not block known protocol phases`() {
        val io = successfulIo()
        val result = campaign(io, candidate = null).run(validRequest())
        assertThat(io.settingsWrites).hasSize(3)
        assertThat(io.activateCount).isEqualTo(1)
        assertThat(result.state).isEqualTo(BenchRestartState.BLOCKED)
        assertThat(result.knownHardwareProbes).isEqualTo(KnownHardwareProbeStatus.COMPLETED)
        assertThat(result.realBleWritesAttempted).isEqualTo(4)
    }

    @Test fun `8 idempotent SET_PATCH sends current settings first`() {
        val io = successfulIo()
        campaign(io).run(validRequest())
        assertThat(io.settingsWrites.first()).isEqualTo(settings())
    }

    @Test fun `9 expiration toggle is followed by exact restoration`() {
        val io = successfulIo()
        campaign(io).run(validRequest())
        assertThat(io.settingsWrites).containsExactly(settings(), settings().expirationProbe(), settings()).inOrder()
    }

    @Test fun `10 expiration probe preserves every unrelated SET_PATCH field`() {
        val original = settings()
        val toggled = original.expirationProbe()
        assertThat(toggled.copy(expirationEnabled = original.expirationEnabled)).isEqualTo(original)
    }

    @Test fun `11 SET_PATCH timeout is never retransmitted`() {
        val io = FakeIo(
            defaultSnapshots(),
            settingsResults = ArrayDeque(listOf(writeResult(success = false, timedOut = true)))
        )
        val result = campaign(io).run(validRequest())
        assertThat(io.settingsWrites).hasSize(1)
        assertThat(io.activateCount).isEqualTo(0)
        assertThat(io.readOnlyRecoveryCount).isEqualTo(1)
        assertThat(result.setPatchWhileActive).isEqualTo(BenchProbeOutcome.TIMEOUT)
    }

    @Test fun `12 explicit SET_PATCH rejection stops later writes`() {
        val io = FakeIo(
            defaultSnapshots(),
            settingsResults = ArrayDeque(listOf(writeResult(success = false, responseCode = 7)))
        )
        val result = campaign(io).run(validRequest())
        assertThat(io.settingsWrites).hasSize(1)
        assertThat(io.activateCount).isEqualTo(0)
        assertThat(result.setPatchWhileActive).isEqualTo(BenchProbeOutcome.REJECTED)
    }

    @Test fun `13 ACTIVATE phase is reached only after three accepted SET_PATCH writes`() {
        val io = successfulIo()
        campaign(io).run(validRequest())
        assertThat(io.writeOrder).containsExactly("SET_PATCH", "SET_PATCH", "SET_PATCH", "ACTIVATE").inOrder()
    }

    @Test fun `14 non-active second baseline prevents ACTIVATE`() {
        val snapshots = defaultSnapshots().toMutableList().apply {
            this[4] = snapshot(state = MedtrumPumpState.EJECTED, age = 504)
        }
        val io = FakeIo(snapshots)
        campaign(io).run(validRequest())
        assertThat(io.activateCount).isEqualTo(0)
    }

    @Test fun `15 standard ActivatePacket encoder is reused`() {
        val source = source("services/MedtrumService.kt")
        assertThat(source).contains("ActivatePacket(injector, profileBytes)")
        assertThat(source).doesNotContain("RawActivate")
    }

    @Test fun `16 experimental controller has no raw packet API`() {
        val combined = source("bench/BenchRestartCampaign.kt") + source("bench/MedtrumBenchRestartController.kt")
        assertThat(combined).doesNotContain("sendRaw")
        assertThat(combined).doesNotContain("transmitCandidate")
        assertThat(combined).doesNotContain("0x94")
        assertThat(combined).doesNotContain("0x11")
    }

    @Test fun `17 ACTIVATE is transmitted maximum once`() {
        val io = successfulIo(activateResult = writeResult(success = true))
        campaign(io).run(validRequest())
        assertThat(io.activateCount).isEqualTo(1)
    }

    @Test fun `18 ACTIVATE timeout performs read-only recovery without retry`() {
        val io = successfulIo(activateResult = writeResult(success = false, timedOut = true))
        val result = campaign(io).run(validRequest())
        assertThat(io.activateCount).isEqualTo(1)
        assertThat(io.readOnlyRecoveryCount).isEqualTo(1)
        assertThat(io.synchronizeCount).isAtLeast(8)
        assertThat(result.activateWhileActive).isEqualTo(BenchProbeOutcome.TIMEOUT)
    }

    @Test fun `19 ACTIVATE rejection records raw result and ends write sequence`() {
        val events = mutableListOf<BenchRestartEvent>()
        val io = successfulIo(activateResult = writeResult(success = false, responseCode = 22, rawResponse = byteArrayOf(1, 2, 3)))
        val result = campaign(io, events = events).run(validRequest())
        val rx = events.single { it.name == "bench_real_activate_active_state_rx" }
        assertThat(rx.fields["responseCode"]).isEqualTo(22)
        assertThat(rx.fields["rawResponse"]).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(io.totalWrites()).isEqualTo(4)
        assertThat(result.state).isEqualTo(BenchRestartState.BLOCKED)
    }

    @Test fun `20 unexpected ACTIVATE success collects two device observations`() {
        val io = successfulIo(activateResult = writeResult(success = true))
        val result = campaign(io).run(validRequest())
        assertThat(io.waitForTimerObservationCount).isEqualTo(1)
        assertThat(io.snapshotCount).isEqualTo(7)
        assertThat(result.activateWhileActive).isEqualTo(BenchProbeOutcome.ACCEPTED)
        assertThat(result.overallVerdict).isEqualTo(OverallRestartVerdict.NOT_CONFIRMED)
    }

    @Test fun `21 unexpected state after ACTIVATE stops all further writes`() {
        val snapshots = defaultSnapshots().toMutableList().apply {
            this[5] = snapshot(state = MedtrumPumpState.EJECTED, age = 505)
        }
        val io = FakeIo(snapshots, activateResult = writeResult(success = true))
        val result = campaign(io).run(validRequest())
        assertThat(io.activateCount).isEqualTo(1)
        assertThat(io.waitForTimerObservationCount).isEqualTo(0)
        assertThat(result.state).isEqualTo(BenchRestartState.FAILED)
    }

    @Test fun `22 AGE reset and changed start proves restart`() {
        val snapshots = defaultSnapshots().toMutableList().apply {
            this[5] = snapshot(deviceStart = 200, age = 2)
            this[6] = snapshot(deviceStart = 200, age = 3)
        }
        val io = FakeIo(snapshots, activateResult = writeResult(success = true))
        val result = campaign(io).run(validRequest())
        assertThat(result.restartProven).isTrue()
        assertThat(result.overallVerdict).isEqualTo(OverallRestartVerdict.CONFIRMED)
    }

    @Test fun `23 ACTIVE without device timer reset is not confirmed`() {
        val io = successfulIo(activateResult = writeResult(success = true))
        val result = campaign(io).run(validRequest())
        assertThat(result.restartProven).isFalse()
        assertThat(result.activateTimerEffect).isEqualTo(BenchTimerEffect.NO_RESET_OBSERVED)
    }

    @Test fun `24 campaign source cannot emit PRIME or STOP_PATCH`() {
        val combined = source("bench/BenchRestartCampaign.kt") + source("bench/MedtrumBenchRestartController.kt")
        assertThat(combined).doesNotContain("PrimePacket")
        assertThat(combined).doesNotContain("StopPatchPacket")
        assertThat(combined).doesNotContain("startPrime")
        assertThat(combined).doesNotContain("deactivatePatch")
    }

    @Test fun `25 construction after process restart does not auto-write`() {
        val io = FakeIo(defaultSnapshots())
        BenchRestartCampaign(io)
        assertThat(io.totalWrites()).isEqualTo(0)
    }

    @Test fun `26 failure cleanup never adds another command`() {
        val io = FakeIo(defaultSnapshots(), synchronizeSuccesses = ArrayDeque(listOf(false)))
        campaign(io).run(validRequest())
        assertThat(io.totalWrites()).isEqualTo(0)
    }

    @Test fun `27 write events identify physical transport and zero retries`() {
        val events = mutableListOf<BenchRestartEvent>()
        val io = successfulIo()
        campaign(io, events = events).run(validRequest())
        events.filter { it.name.endsWith("_tx") || it.name.endsWith("_rx") }.forEach {
            assertThat(it.fields["transport"]).isEqualTo("REAL_BLE")
            assertThat(it.fields["retryCount"]).isEqualTo(0)
        }
    }

    private fun campaign(
        io: FakeIo,
        candidate: ConfirmedRestartCandidate? = null,
        events: MutableList<BenchRestartEvent>? = null
    ) = BenchRestartCampaign(io, candidateProvider = { candidate }, emit = { event -> events?.add(event) })

    private fun validRequest() = BenchRestartRequest(experimentalEnabled = true, queueSafe = true, bolusSafe = true)

    private fun assertBlockedWithoutWrites(result: BenchRestartResult, io: FakeIo) {
        assertThat(result.state).isEqualTo(BenchRestartState.BLOCKED)
        assertThat(io.totalWrites()).isEqualTo(0)
    }

    private fun settings() = BenchPatchSettings(7, 40, 180, true, 0, 12, 0, 0, 30)

    private fun writeResult(
        success: Boolean,
        timedOut: Boolean = false,
        responseCode: Int? = if (success) 0 else null,
        rawResponse: ByteArray? = byteArrayOf(0, 0, 0, 0, 0, 0)
    ) = BenchWriteResult(
        success = success,
        timedOut = timedOut,
        responseCode = responseCode,
        transport = "REAL_BLE",
        rawRequest = byteArrayOf(1),
        rawResponse = rawResponse,
        profileHash = "profile-hash"
    )

    private fun successfulIo(activateResult: BenchWriteResult = writeResult(success = false, responseCode = 22)) =
        FakeIo(defaultSnapshots(), activateResult = activateResult)

    private class FakeIo(
        snapshots: List<BenchRestartSnapshot>,
        private var settings: BenchPatchSettings = BenchRestartCampaignTest().settings(),
        private val activateResult: BenchWriteResult = BenchRestartCampaignTest().writeResult(success = false, responseCode = 22),
        private val settingsResults: ArrayDeque<BenchWriteResult> = ArrayDeque(),
        private val synchronizeSuccesses: ArrayDeque<Boolean> = ArrayDeque(),
        private val recoverySuccess: Boolean = true
    ) : BenchRestartIo {
        private val snapshots = ArrayDeque(snapshots)
        var synchronizeCount = 0
        var snapshotCount = 0
        var activateCount = 0
        var readOnlyRecoveryCount = 0
        var waitForTimerObservationCount = 0
        val settingsWrites = mutableListOf<BenchPatchSettings>()
        val writeOrder = mutableListOf<String>()

        override fun synchronize(): Boolean {
            synchronizeCount++
            return if (synchronizeSuccesses.isEmpty()) true else synchronizeSuccesses.removeFirst()
        }

        override fun readHistory(): Boolean = true

        override fun snapshot(): BenchRestartSnapshot {
            snapshotCount++
            return snapshots.removeFirst()
        }

        override fun readPatchSettings(): BenchPatchSettings = settings

        override fun transmitSettings(settings: BenchPatchSettings): BenchWriteResult {
            settingsWrites += settings
            writeOrder += "SET_PATCH"
            val result = if (settingsResults.isEmpty()) BenchRestartCampaignTest().writeResult(success = true) else settingsResults.removeFirst()
            if (result.success) this.settings = settings
            return result
        }

        override fun transmitActivate(): BenchWriteResult {
            activateCount++
            writeOrder += "ACTIVATE"
            return activateResult
        }

        override fun readOnlyRecovery(): Boolean {
            readOnlyRecoveryCount++
            synchronizeCount++
            return recoverySuccess
        }

        override fun waitForTimerObservation() {
            waitForTimerObservationCount++
        }

        fun totalWrites(): Int = settingsWrites.size + activateCount
    }

    companion object {
        private fun defaultSnapshots() = listOf(
            snapshot(age = 500),
            snapshot(age = 501),
            snapshot(age = 502),
            snapshot(age = 503),
            snapshot(age = 504),
            snapshot(age = 505),
            snapshot(age = 506)
        )

        private fun source(relative: String): String {
            val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
            val moduleDirectory = if (workingDirectory.path.replace('\\', '/').endsWith("pump/medtrum")) {
                workingDirectory
            } else {
                File(workingDirectory, "pump/medtrum")
            }
            return File(moduleDirectory, "src/main/kotlin/app/aaps/pump/medtrum/$relative").readText()
        }

        private fun snapshot(
            firmware: String = "1.80.89",
            supportedModel: Boolean = true,
            state: MedtrumPumpState = MedtrumPumpState.ACTIVE,
            localStart: Long = 100,
            deviceStart: Long = 100,
            age: Long = 500,
            timerAvailable: Boolean = true
        ) = BenchRestartSnapshot(
            firmware = firmware,
            deviceType = 80,
            supportedModel = supportedModel,
            pumpState = state,
            connectionState = "CONNECTED",
            patchId = 1,
            localPatchStartTime = localStart,
            deviceReportedStartTime = deviceStart,
            deviceReportedStartTimeAvailable = timerAvailable,
            deviceReportedPatchAge = age,
            deviceReportedPatchAgeAvailable = timerAvailable,
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
