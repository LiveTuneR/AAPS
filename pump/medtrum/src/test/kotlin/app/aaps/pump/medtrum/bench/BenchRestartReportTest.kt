package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

class BenchRestartReportTest {

    @Test fun `blocked report contains device baseline and zero dangerous writes`() {
        val report = BenchRestartReport("campaign-test")
        report.record(
            BenchRestartEvent(
                BenchRestartState.BASELINE_SYNC,
                "bench_restart_baseline_snapshot",
                snapshot().traceFields()
            )
        )
        report.record(
            BenchRestartEvent(
                BenchRestartState.BLOCKED,
                "bench_restart_blocked",
                mapOf("reason" to "No restart candidate reached CONFIRMED evidence")
            )
        )

        val json = JSONObject(report.toJson(BenchRestartResult(BenchRestartState.BLOCKED, "blocked")))
        assertThat(json.getString("finalVerdict")).isEqualTo("BLOCKED")
        assertThat(json.getJSONObject("baseline").getLong("deviceReportedPatchAge")).isEqualTo(500L)
        assertThat(json.getJSONObject("commandSummary").getInt("primeTx")).isEqualTo(0)
        assertThat(json.getJSONObject("commandSummary").getInt("stopPatchTx")).isEqualTo(0)
        assertThat(report.toMarkdown(BenchRestartResult(BenchRestartState.BLOCKED, "blocked"))).contains("Final verdict: **BLOCKED**")
    }

    private fun snapshot() = BenchRestartSnapshot(
        firmware = "1.80.89",
        deviceType = 80,
        supportedModel = true,
        pumpState = MedtrumPumpState.ACTIVE,
        connectionState = "CONNECTED",
        patchId = 1,
        localPatchStartTime = 100,
        deviceReportedStartTime = 100,
        deviceReportedPatchAge = 500,
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
