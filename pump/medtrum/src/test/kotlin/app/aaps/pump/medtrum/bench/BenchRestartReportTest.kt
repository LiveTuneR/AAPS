package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

class BenchRestartReportTest {

    @Test fun `report preserves completed probes behind blocked verdict`() {
        val report = BenchRestartReport("campaign-test")
        report.record(BenchRestartEvent(BenchRestartState.BASELINE_SYNC, "bench_restart_snapshot_a0", snapshot(age = 500).traceFields()))
        report.record(
            BenchRestartEvent(
                BenchRestartState.REVERSIBLE_SETTINGS_TEST,
                "bench_real_set_patch_idempotent_tx",
                mapOf("transport" to "REAL_BLE", "rawRequest" to byteArrayOf(35, 7), "retryCount" to 0)
            )
        )
        report.record(BenchRestartEvent(BenchRestartState.REVERSIBLE_SETTINGS_TEST, "bench_real_set_patch_idempotent_snapshot", snapshot(age = 501).traceFields()))
        report.record(BenchRestartEvent(BenchRestartState.ACTIVATE, "bench_real_activate_active_state_rx", mapOf("responseCode" to 22, "success" to false)))
        report.record(BenchRestartEvent(BenchRestartState.VERIFY_ACTIVATION, "bench_real_activate_snapshot_c1", snapshot(age = 502).traceFields()))

        val result = BenchRestartResult(
            state = BenchRestartState.BLOCKED,
            message = "hidden transition blocked",
            knownHardwareProbes = KnownHardwareProbeStatus.COMPLETED,
            setPatchWhileActive = BenchProbeOutcome.ACCEPTED,
            setPatchTimerEffect = BenchTimerEffect.NO_RESET_OBSERVED,
            activateWhileActive = BenchProbeOutcome.REJECTED,
            activateResponseCode = 22,
            activateTimerEffect = BenchTimerEffect.NO_RESET_OBSERVED,
            overallVerdict = OverallRestartVerdict.BLOCKED,
            realBleWritesAttempted = 4,
            setPatchWrites = 3,
            activateWrites = 1
        )
        val json = JSONObject(report.toJson(result))
        val dimensions = json.getJSONObject("resultDimensions")
        val summary = json.getJSONObject("commandSummary")

        assertThat(json.getString("finalVerdict")).isEqualTo("BLOCKED")
        assertThat(dimensions.getString("knownHardwareProbes")).isEqualTo("COMPLETED")
        assertThat(dimensions.getString("activateWhileActive")).isEqualTo("REJECTED")
        assertThat(dimensions.getInt("activateResponseCode")).isEqualTo(22)
        assertThat(json.getJSONArray("snapshots").getJSONObject(0).getLong("deviceAge")).isEqualTo(500L)
        assertThat(summary.getInt("realBleWritesAttempted")).isEqualTo(4)
        assertThat(summary.getInt("prime")).isEqualTo(0)
        assertThat(summary.getInt("stopPatch")).isEqualTo(0)
        assertThat(summary.getInt("unknownRaw")).isEqualTo(0)
        assertThat(report.toMarkdown(result)).contains("Known hardware probes: **COMPLETED**")
    }

    private fun snapshot(age: Long) = BenchRestartSnapshot(
        firmware = "1.80.89",
        deviceType = 80,
        supportedModel = true,
        pumpState = MedtrumPumpState.ACTIVE,
        connectionState = "CONNECTED",
        patchId = 1,
        localPatchStartTime = 100,
        deviceReportedStartTime = 100,
        deviceReportedStartTimeAvailable = true,
        deviceReportedPatchAge = age,
        deviceReportedPatchAgeAvailable = true,
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
