package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

class BenchRestartCampaign(
    private val io: BenchRestartIo,
    private val candidateProvider: (BenchRestartSnapshot) -> ConfirmedRestartCandidate? = {
        BenchRestartCandidateRegistry.confirmedFor(it.firmware, it.deviceType)
    },
    private val emit: (BenchRestartEvent) -> Unit = {}
) {
    private var candidateTxCount = 0
    private var activateTxCount = 0

    fun run(request: BenchRestartRequest): BenchRestartResult {
        event(BenchRestartState.PREFLIGHT, "bench_restart_preflight")
        if (!request.engineeringMode || !request.experimentalEnabled) return blocked("Experimental option is disabled")
        if (!request.queueSafe || !request.bolusSafe) return blocked("Command queue or bolus state is unsafe")

        event(BenchRestartState.ACQUIRE_EXCLUSIVE_ACCESS, "bench_restart_lock_acquired")
        event(BenchRestartState.CONNECT, "bench_restart_connect")
        event(BenchRestartState.CONNECT, "bench_restart_auth", mapOf("source" to "normal_connection_fsm"))
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_baseline_sync")
        if (!io.synchronize()) return failed("Read-only SYNCHRONIZE failed")

        val baseline = io.snapshot()
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_baseline_snapshot", baseline.traceFields())
        event(BenchRestartState.BASELINE_HISTORY, "bench_restart_baseline_history")
        if (!io.readHistory()) return failed("Read-only history failed")
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_baseline_second_sync")
        if (!io.synchronize()) return failed("Second read-only SYNCHRONIZE failed")

        if (baseline.firmware != SUPPORTED_FIRMWARE) return blocked("Unsupported firmware: ${baseline.firmware}")
        if (!baseline.supportedModel) return blocked("Unsupported Medtrum model: ${baseline.deviceType}")
        if (baseline.pumpState !in SUPPORTED_ENTRY_STATES) return blocked("Device state is ${baseline.pumpState}, ACTIVE required")

        val candidate = candidateProvider(baseline)
            ?: return blocked("No restart candidate reached CONFIRMED evidence")

        val originalSettings = io.readPatchSettings()
            ?: return blocked("Exact SET_PATCH read-back is unavailable")
        val probeSettings = originalSettings.expirationProbe()
        event(BenchRestartState.REVERSIBLE_SETTINGS_TEST, "bench_restart_settings_probe_start")
        event(BenchRestartState.REVERSIBLE_SETTINGS_TEST, "bench_restart_settings_probe_tx")
        val probe = io.transmitSettings(probeSettings)
        event(BenchRestartState.REVERSIBLE_SETTINGS_TEST, "bench_restart_settings_probe_rx", resultFields(probe))
        if (!probe.success) {
            io.readOnlyRecovery()
            return failed(if (probe.timedOut) "SET_PATCH probe timed out; not retransmitted" else "SET_PATCH probe failed")
        }
        if (!io.synchronize() || io.readPatchSettings() != probeSettings) return failed("SET_PATCH probe could not be verified")
        event(BenchRestartState.REVERSIBLE_SETTINGS_TEST, "bench_restart_settings_probe_snapshot")

        event(BenchRestartState.RESTORE_SETTINGS, "bench_restart_settings_restore_tx")
        val restore = io.transmitSettings(originalSettings)
        event(BenchRestartState.RESTORE_SETTINGS, "bench_restart_settings_restore_rx", resultFields(restore))
        if (!restore.success || !io.synchronize() || io.readPatchSettings() != originalSettings) {
            io.readOnlyRecovery()
            return failed("SET_PATCH restoration was not verified")
        }

        val beforeTransition = io.snapshot()
        event(BenchRestartState.PRE_RESTART_SNAPSHOT, "bench_restart_pre_transition_snapshot", beforeTransition.traceFields())
        event(
            BenchRestartState.REACTIVATION_TRANSITION,
            "bench_restart_transition_selected",
            mapOf("candidate" to candidate.id, "opcode" to candidate.opcode, "confidence" to candidate.confidence.name)
        )
        if (candidateTxCount != 0) return failed("Candidate retransmission prevented")
        candidateTxCount++
        event(BenchRestartState.REACTIVATION_TRANSITION, "bench_restart_transition_tx", mapOf("opcode" to candidate.opcode, "rawRequest" to candidate.request))
        val transition = io.transmitCandidate(candidate)
        event(BenchRestartState.REACTIVATION_TRANSITION, "bench_restart_transition_rx", resultFields(transition))
        if (transition.timedOut) {
            event(BenchRestartState.REACTIVATION_TRANSITION, "bench_restart_transition_timeout")
            io.readOnlyRecovery()
        }
        if (!io.synchronize()) return failed("Unable to verify transition")
        val transitioned = io.snapshot()
        event(BenchRestartState.VERIFY_TRANSITION, "bench_restart_transition_verify", transitioned.traceFields())
        if (transitioned.pumpState !in ACTIVATION_READY_STATES) {
            return failed("Device did not enter an activation-ready state")
        }

        if (activateTxCount != 0) return failed("ACTIVATE retransmission prevented")
        activateTxCount++
        event(BenchRestartState.ACTIVATE, "bench_restart_activate_tx", mapOf("opcode" to ACTIVATE_OPCODE))
        val activate = io.transmitActivate()
        event(BenchRestartState.ACTIVATE, "bench_restart_activate_rx", resultFields(activate))
        if (activate.timedOut) {
            event(BenchRestartState.ACTIVATE, "bench_restart_activate_timeout")
            io.readOnlyRecovery()
        }
        if (!io.synchronize()) return failed("Unable to verify ACTIVATE")
        val final = io.snapshot()
        event(BenchRestartState.VERIFY_ACTIVATION, "bench_restart_activate_verify", final.traceFields())
        if (final.pumpState !in SUPPORTED_ENTRY_STATES) return failed("Device is not ACTIVE after ACTIVATE")

        if (!io.synchronize()) return failed("Unable to obtain subsequent device age")
        val subsequent = io.snapshot()
        event(BenchRestartState.FINAL_SNAPSHOT, "bench_restart_final_snapshot", subsequent.traceFields())
        val proven = deviceTimerProvesRestart(beforeTransition, final, subsequent)
        return if (proven) {
            event(BenchRestartState.COMPLETE, "bench_restart_complete")
            BenchRestartResult(BenchRestartState.COMPLETE, "Device AGE/START_TIME proves a new session", restartProven = true)
        } else {
            failed("ACTIVE observed, but device AGE/START_TIME does not prove restart")
        }
    }

    private fun blocked(message: String): BenchRestartResult {
        event(BenchRestartState.BLOCKED, "bench_restart_blocked", mapOf("reason" to message))
        return BenchRestartResult(BenchRestartState.BLOCKED, message)
    }

    private fun failed(message: String): BenchRestartResult {
        event(BenchRestartState.FAILED, "bench_restart_failed", mapOf("reason" to message))
        return BenchRestartResult(BenchRestartState.FAILED, message)
    }

    private fun event(state: BenchRestartState, name: String, fields: Map<String, Any?> = emptyMap()) =
        emit(BenchRestartEvent(state, name, fields))

    private fun resultFields(result: BenchWriteResult): Map<String, Any?> = mapOf(
        "success" to result.success,
        "timeout" to result.timedOut,
        "responseCode" to result.responseCode,
        "retryCount" to 0
    )

    companion object {
        const val SUPPORTED_FIRMWARE = "1.80.89"
        const val ACTIVATE_OPCODE = 18
        val SUPPORTED_ENTRY_STATES = setOf(MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT)
        val ACTIVATION_READY_STATES = setOf(MedtrumPumpState.PRIMED, MedtrumPumpState.EJECTED)

        fun deviceTimerProvesRestart(
            before: BenchRestartSnapshot,
            after: BenchRestartSnapshot,
            subsequent: BenchRestartSnapshot
        ): Boolean {
            val ageReset = before.deviceReportedPatchAge > after.deviceReportedPatchAge
            val ageAdvances = subsequent.deviceReportedPatchAge > after.deviceReportedPatchAge
            val startChanged = after.deviceReportedStartTime > 0 && after.deviceReportedStartTime != before.deviceReportedStartTime
            return ageReset && ageAdvances && startChanged
        }
    }
}
