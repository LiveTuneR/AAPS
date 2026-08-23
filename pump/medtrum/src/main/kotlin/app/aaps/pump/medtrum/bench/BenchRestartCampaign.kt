package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

class BenchRestartCampaign(
    private val io: BenchRestartIo,
    private val candidateProvider: (BenchRestartSnapshot) -> ConfirmedRestartCandidate? = {
        BenchRestartCandidateRegistry.confirmedFor(it.firmware, it.deviceType)
    },
    private val emit: (BenchRestartEvent) -> Unit = {}
) {
    private var knownHardwareProbes = KnownHardwareProbeStatus.NOT_RUN
    private var setPatchWhileActive = BenchProbeOutcome.NOT_RUN
    private var setPatchTimerEffect = BenchTimerEffect.UNKNOWN
    private var activateWhileActive = BenchProbeOutcome.NOT_RUN
    private var activateResponseCode: Int? = null
    private var activateTimerEffect = BenchTimerEffect.UNKNOWN
    private var setPatchWrites = 0
    private var activateWrites = 0

    fun run(request: BenchRestartRequest): BenchRestartResult {
        resetResultState()
        event(BenchRestartState.PREFLIGHT, "bench_restart_preflight")
        if (!request.engineeringMode || !request.experimentalEnabled) return blocked("Experimental option is disabled")
        if (!request.queueSafe || !request.bolusSafe) return blocked("Command queue or bolus state is unsafe")

        event(BenchRestartState.ACQUIRE_EXCLUSIVE_ACCESS, "bench_restart_lock_acquired")
        event(BenchRestartState.CONNECT, "bench_restart_connect")
        event(BenchRestartState.CONNECT, "bench_restart_auth", mapOf("source" to "normal_connection_fsm"))
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_baseline_sync")
        if (!io.synchronize()) return failed("Read-only SYNCHRONIZE failed")
        event(BenchRestartState.BASELINE_HISTORY, "bench_restart_baseline_history")
        if (!io.readHistory()) return failed("Read-only history failed")
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_baseline_second_sync")
        if (!io.synchronize()) return failed("Second read-only SYNCHRONIZE failed")

        val baseline = io.snapshot()
        event(BenchRestartState.BASELINE_SYNC, "bench_restart_snapshot_a0", baseline.traceFields())
        if (baseline.firmware != SUPPORTED_FIRMWARE) return blocked("Unsupported firmware: ${baseline.firmware}")
        if (!baseline.supportedModel) return blocked("Unsupported Medtrum model: ${baseline.deviceType}")
        if (baseline.pumpState !in SUPPORTED_ENTRY_STATES) return blocked("Device state is ${baseline.pumpState}, ACTIVE required")
        if (!hasDeviceTimer(baseline)) return blocked("Device START_TIME or AGE was not observed in baseline RX")

        val originalSettings = io.readPatchSettings()
            ?: return blocked("Exact outbound SET_PATCH settings are unavailable")
        event(
            BenchRestartState.REVERSIBLE_SETTINGS_TEST,
            "bench_restart_set_patch_source",
            originalSettings.traceFields() + mapOf("independentDeviceReadbackAvailable" to false)
        )

        knownHardwareProbes = KnownHardwareProbeStatus.PARTIAL
        val b1Result = sendSettings("bench_real_set_patch_idempotent", originalSettings)
        setPatchWhileActive = outcome(b1Result)
        if (!b1Result.success) return stopAfterSetPatchFailure("idempotent", b1Result)
        val b1 = synchronizeSnapshot("bench_real_set_patch_idempotent_snapshot")
            ?: return ambiguousFailure("Unable to verify idempotent SET_PATCH")
        updateSetPatchTimer(baseline, b1)
        validatePreservedActiveState(baseline, b1)?.let { return failed(it) }

        val toggledSettings = originalSettings.expirationProbe()
        val b2Result = sendSettings("bench_real_set_patch_toggle", toggledSettings)
        setPatchWhileActive = outcome(b2Result)
        if (!b2Result.success) return stopAfterSetPatchFailure("expiration toggle", b2Result)
        val b2 = synchronizeSnapshot("bench_real_set_patch_toggle_snapshot")
            ?: return ambiguousFailure("Unable to verify SET_PATCH expiration toggle")
        event(
            BenchRestartState.REVERSIBLE_SETTINGS_TEST,
            "bench_real_set_patch_toggle_readback",
            mapOf(
                "commandAccepted" to true,
                "independentExpirationReadbackAvailable" to false,
                "restorationRequired" to true
            )
        )
        updateSetPatchTimer(b1, b2)
        validatePreservedActiveState(b1, b2)?.let { return failed(it) }

        val b3Result = sendSettings("bench_real_set_patch_restore", originalSettings, BenchRestartState.RESTORE_SETTINGS)
        setPatchWhileActive = outcome(b3Result)
        if (!b3Result.success) return stopAfterSetPatchFailure("restoration", b3Result)
        val b3 = synchronizeSnapshot("bench_real_set_patch_restore_snapshot", BenchRestartState.RESTORE_SETTINGS)
            ?: return ambiguousFailure("Unable to verify SET_PATCH restoration")
        event(
            BenchRestartState.RESTORE_SETTINGS,
            "bench_real_set_patch_restore_readback",
            mapOf(
                "commandAccepted" to true,
                "independentExpirationReadbackAvailable" to false,
                "originalSettingBelievedRestored" to true
            )
        )
        updateSetPatchTimer(b2, b3)
        validatePreservedActiveState(b2, b3)?.let { return failed(it) }

        event(BenchRestartState.PRE_RESTART_SNAPSHOT, "bench_restart_second_baseline_sync")
        if (!io.synchronize()) return ambiguousFailure("Second pre-ACTIVATE baseline failed")
        val beforeActivate = io.snapshot()
        event(BenchRestartState.PRE_RESTART_SNAPSHOT, "bench_restart_pre_activate_snapshot", beforeActivate.traceFields())
        validatePreservedActiveState(b3, beforeActivate)?.let { return failed(it) }

        val activate = io.transmitActivate()
        if (activate.transmitted) activateWrites++
        activateWhileActive = outcome(activate)
        activateResponseCode = activate.responseCode
        event(
            BenchRestartState.ACTIVATE,
            "bench_real_activate_active_state_tx",
            writeTxFields(activate) + mapOf(
                "opcode" to ACTIVATE_OPCODE,
                "preState" to beforeActivate.pumpState.name,
                "preDeviceStartTime" to beforeActivate.deviceReportedStartTime,
                "preDeviceAge" to beforeActivate.deviceReportedPatchAge,
                "prePatchId" to beforeActivate.patchId,
                "preReservoir" to beforeActivate.reservoir,
                "preSequence" to beforeActivate.currentSequence,
                "expirationEnabled" to originalSettings.expirationEnabled,
                "profileHash" to activate.profileHash
            )
        )
        event(BenchRestartState.ACTIVATE, "bench_real_activate_active_state_rx", writeRxFields(activate))
        if (!activate.transmitted) return failed("ACTIVATE was not transmitted: ${activate.failureReason ?: "service unavailable"}")

        return when {
            activate.timedOut -> handleActivateTimeout(beforeActivate)
            activate.success -> handleActivateSuccess(beforeActivate)
            activate.responseCode != null -> handleActivateRejection(beforeActivate)
            else -> ambiguousFailure("ACTIVATE failed without an explicit protocol result")
        }
    }

    private fun sendSettings(
        eventPrefix: String,
        settings: BenchPatchSettings,
        state: BenchRestartState = BenchRestartState.REVERSIBLE_SETTINGS_TEST
    ): BenchWriteResult {
        val result = io.transmitSettings(settings)
        if (result.transmitted) setPatchWrites++
        event(state, "${eventPrefix}_tx", writeTxFields(result) + settings.traceFields())
        event(state, "${eventPrefix}_rx", writeRxFields(result))
        return result
    }

    private fun synchronizeSnapshot(
        name: String,
        state: BenchRestartState = BenchRestartState.REVERSIBLE_SETTINGS_TEST
    ): BenchRestartSnapshot? {
        if (!io.synchronize()) return null
        return io.snapshot().also { event(state, name, it.traceFields()) }
    }

    private fun stopAfterSetPatchFailure(stage: String, result: BenchWriteResult): BenchRestartResult {
        if (result.timedOut || result.responseCode == null) {
            if (io.readOnlyRecovery()) {
                event(
                    BenchRestartState.VERIFY_TRANSITION,
                    "bench_restart_set_patch_recovery_snapshot",
                    io.snapshot().traceFields()
                )
            }
            return failed("SET_PATCH $stage was ambiguous; no retransmission or further write was performed")
        }
        val recovered = synchronizeSnapshot("bench_restart_set_patch_rejection_snapshot")
        if (recovered == null) io.readOnlyRecovery()
        return blocked("SET_PATCH $stage was explicitly rejected; known write sequence stopped")
    }

    private fun handleActivateRejection(before: BenchRestartSnapshot): BenchRestartResult {
        val after = recoverAndSnapshot("bench_real_activate_snapshot_c1")
            ?: return failed("ACTIVATE was rejected and read-only verification failed")
        activateTimerEffect = timerEffect(before, after)
        if (after.pumpState !in SUPPORTED_ENTRY_STATES) {
            return failed("ACTIVATE rejection was followed by unexpected device state ${after.pumpState}")
        }
        knownHardwareProbes = KnownHardwareProbeStatus.COMPLETED
        return finishAtHiddenCandidate(after)
    }

    private fun handleActivateTimeout(before: BenchRestartSnapshot): BenchRestartResult {
        val first = recoverAndSnapshot("bench_real_activate_snapshot_c1")
            ?: return failed("ACTIVATE timed out and read-only recovery failed")
        if (first.pumpState !in SUPPORTED_ENTRY_STATES) {
            return failed("ACTIVATE timeout was followed by unexpected device state ${first.pumpState}")
        }
        io.waitForTimerObservation()
        val second = synchronizeSnapshot("bench_real_activate_snapshot_c2", BenchRestartState.VERIFY_ACTIVATION)
            ?: return failed("Unable to obtain second post-timeout AGE observation")
        if (second.pumpState !in SUPPORTED_ENTRY_STATES) {
            return failed("Second post-timeout snapshot has unexpected state ${second.pumpState}")
        }
        activateTimerEffect = timerEffect(before, first)
        if (deviceTimerProvesRestart(before, first, second)) {
            knownHardwareProbes = KnownHardwareProbeStatus.COMPLETED
            return complete("Device evidence proves restart after ACTIVATE timeout", restartProven = true)
        }
        knownHardwareProbes = KnownHardwareProbeStatus.COMPLETED
        return finishAtHiddenCandidate(second)
    }

    private fun handleActivateSuccess(before: BenchRestartSnapshot): BenchRestartResult {
        val first = synchronizeSnapshot("bench_real_activate_snapshot_c1", BenchRestartState.VERIFY_ACTIVATION)
            ?: return ambiguousFailure("ACTIVATE succeeded but the first device verification failed")
        if (first.pumpState !in SUPPORTED_ENTRY_STATES) {
            io.readOnlyRecovery()
            return failed("ACTIVATE success caused unexpected state ${first.pumpState}; all writes stopped")
        }
        io.waitForTimerObservation()
        val second = synchronizeSnapshot("bench_real_activate_snapshot_c2", BenchRestartState.VERIFY_ACTIVATION)
            ?: return ambiguousFailure("ACTIVATE succeeded but the second AGE observation failed")
        if (second.pumpState !in SUPPORTED_ENTRY_STATES) {
            io.readOnlyRecovery()
            return failed("ACTIVATE success was followed by unexpected state ${second.pumpState}; all writes stopped")
        }
        activateTimerEffect = timerEffect(before, first)
        knownHardwareProbes = KnownHardwareProbeStatus.COMPLETED
        return if (deviceTimerProvesRestart(before, first, second)) {
            complete("Device AGE/START_TIME proves a new session", restartProven = true)
        } else {
            complete("ACTIVATE was accepted, but device AGE/START_TIME does not prove restart", restartProven = false)
        }
    }

    private fun finishAtHiddenCandidate(snapshot: BenchRestartSnapshot): BenchRestartResult {
        val candidate = candidateProvider(snapshot)
        event(
            BenchRestartState.REACTIVATION_TRANSITION,
            "bench_restart_hidden_transition_blocked",
            mapOf(
                "confirmedCandidateAvailable" to (candidate != null),
                "candidateTransmitted" to false,
                "reason" to "No undocumented command is whitelisted in this build"
            )
        )
        return blocked(
            "Known real hardware probes completed; missing ACTIVE -> activation-ready transition remains unproven",
            knownStatus = KnownHardwareProbeStatus.COMPLETED
        )
    }

    private fun recoverAndSnapshot(name: String): BenchRestartSnapshot? {
        if (!io.readOnlyRecovery()) return null
        return io.snapshot().also { event(BenchRestartState.VERIFY_ACTIVATION, name, it.traceFields()) }
    }

    private fun validatePreservedActiveState(before: BenchRestartSnapshot, after: BenchRestartSnapshot): String? = when {
        after.pumpState !in SUPPORTED_ENTRY_STATES -> "Known SET_PATCH probe caused unexpected state ${after.pumpState}"
        !hasDeviceTimer(after) -> "Device timer telemetry disappeared after known SET_PATCH probe"
        before.patchId != after.patchId -> "Patch ID changed during known SET_PATCH probe"
        before.deviceReportedStartTime != after.deviceReportedStartTime -> "Device START_TIME changed during known SET_PATCH probe"
        after.deviceReportedPatchAge < before.deviceReportedPatchAge -> "Device AGE reset during known SET_PATCH probe"
        else -> null
    }

    private fun updateSetPatchTimer(before: BenchRestartSnapshot, after: BenchRestartSnapshot) {
        setPatchTimerEffect = mergeTimerEffects(setPatchTimerEffect, timerEffect(before, after))
    }

    private fun timerEffect(before: BenchRestartSnapshot, after: BenchRestartSnapshot): BenchTimerEffect = when {
        !hasDeviceTimer(before) || !hasDeviceTimer(after) -> BenchTimerEffect.UNKNOWN
        before.deviceReportedStartTime != after.deviceReportedStartTime -> BenchTimerEffect.RESET_OBSERVED
        after.deviceReportedPatchAge < before.deviceReportedPatchAge -> BenchTimerEffect.RESET_OBSERVED
        else -> BenchTimerEffect.NO_RESET_OBSERVED
    }

    private fun mergeTimerEffects(current: BenchTimerEffect, next: BenchTimerEffect): BenchTimerEffect = when {
        current == BenchTimerEffect.RESET_OBSERVED || next == BenchTimerEffect.RESET_OBSERVED -> BenchTimerEffect.RESET_OBSERVED
        current == BenchTimerEffect.UNKNOWN -> next
        next == BenchTimerEffect.UNKNOWN -> BenchTimerEffect.UNKNOWN
        else -> BenchTimerEffect.NO_RESET_OBSERVED
    }

    private fun hasDeviceTimer(snapshot: BenchRestartSnapshot): Boolean =
        snapshot.deviceReportedStartTimeAvailable && snapshot.deviceReportedPatchAgeAvailable && snapshot.deviceReportedStartTime > 0

    private fun outcome(result: BenchWriteResult): BenchProbeOutcome = when {
        result.timedOut -> BenchProbeOutcome.TIMEOUT
        result.success -> BenchProbeOutcome.ACCEPTED
        result.responseCode != null -> BenchProbeOutcome.REJECTED
        else -> BenchProbeOutcome.UNKNOWN
    }

    private fun writeTxFields(result: BenchWriteResult): Map<String, Any?> = mapOf(
        "transport" to result.transport,
        "transmitted" to result.transmitted,
        "rawRequest" to result.rawRequest,
        "retryCount" to 0
    )

    private fun writeRxFields(result: BenchWriteResult): Map<String, Any?> = mapOf(
        "transport" to result.transport,
        "success" to result.success,
        "timeout" to result.timedOut,
        "responseCode" to result.responseCode,
        "latencyMs" to result.latencyMs,
        "rawResponse" to result.rawResponse,
        "failureReason" to result.failureReason,
        "retryCount" to 0
    )

    private fun ambiguousFailure(message: String): BenchRestartResult {
        if (io.readOnlyRecovery()) {
            event(BenchRestartState.VERIFY_TRANSITION, "bench_restart_ambiguous_recovery_snapshot", io.snapshot().traceFields())
        }
        return failed(message)
    }

    private fun blocked(
        message: String,
        knownStatus: KnownHardwareProbeStatus = knownHardwareProbes
    ): BenchRestartResult {
        knownHardwareProbes = knownStatus
        event(BenchRestartState.BLOCKED, "bench_restart_blocked", mapOf("reason" to message))
        return result(BenchRestartState.BLOCKED, message, OverallRestartVerdict.BLOCKED)
    }

    private fun failed(message: String): BenchRestartResult {
        if (knownHardwareProbes == KnownHardwareProbeStatus.NOT_RUN && realBleWritesAttempted() > 0) {
            knownHardwareProbes = KnownHardwareProbeStatus.PARTIAL
        }
        event(BenchRestartState.FAILED, "bench_restart_failed", mapOf("reason" to message))
        return result(BenchRestartState.FAILED, message, OverallRestartVerdict.FAILED)
    }

    private fun complete(message: String, restartProven: Boolean): BenchRestartResult {
        event(
            BenchRestartState.COMPLETE,
            "bench_restart_complete",
            mapOf("restartProven" to restartProven, "reason" to message)
        )
        return result(
            BenchRestartState.COMPLETE,
            message,
            if (restartProven) OverallRestartVerdict.CONFIRMED else OverallRestartVerdict.NOT_CONFIRMED,
            restartProven
        )
    }

    private fun result(
        state: BenchRestartState,
        message: String,
        overallVerdict: OverallRestartVerdict,
        restartProven: Boolean = false
    ) = BenchRestartResult(
        state = state,
        message = message,
        restartProven = restartProven,
        knownHardwareProbes = knownHardwareProbes,
        setPatchWhileActive = setPatchWhileActive,
        setPatchTimerEffect = setPatchTimerEffect,
        activateWhileActive = activateWhileActive,
        activateResponseCode = activateResponseCode,
        activateTimerEffect = activateTimerEffect,
        hiddenTransition = HiddenTransitionStatus.BLOCKED,
        overallVerdict = overallVerdict,
        realBleWritesAttempted = realBleWritesAttempted(),
        setPatchWrites = setPatchWrites,
        activateWrites = activateWrites
    )

    private fun realBleWritesAttempted() = setPatchWrites + activateWrites

    private fun resetResultState() {
        knownHardwareProbes = KnownHardwareProbeStatus.NOT_RUN
        setPatchWhileActive = BenchProbeOutcome.NOT_RUN
        setPatchTimerEffect = BenchTimerEffect.UNKNOWN
        activateWhileActive = BenchProbeOutcome.NOT_RUN
        activateResponseCode = null
        activateTimerEffect = BenchTimerEffect.UNKNOWN
        setPatchWrites = 0
        activateWrites = 0
    }

    private fun event(state: BenchRestartState, name: String, fields: Map<String, Any?> = emptyMap()) =
        emit(BenchRestartEvent(state, name, fields))

    companion object {
        const val SUPPORTED_FIRMWARE = "1.80.89"
        const val ACTIVATE_OPCODE = 18
        val SUPPORTED_ENTRY_STATES = setOf(MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT)

        fun deviceTimerProvesRestart(
            before: BenchRestartSnapshot,
            after: BenchRestartSnapshot,
            subsequent: BenchRestartSnapshot
        ): Boolean {
            if (!before.deviceReportedStartTimeAvailable || !before.deviceReportedPatchAgeAvailable ||
                !after.deviceReportedStartTimeAvailable || !after.deviceReportedPatchAgeAvailable ||
                !subsequent.deviceReportedPatchAgeAvailable
            ) return false
            val ageReset = before.deviceReportedPatchAge > after.deviceReportedPatchAge
            val ageAdvances = subsequent.deviceReportedPatchAge > after.deviceReportedPatchAge
            val startChanged = after.deviceReportedStartTime > 0 && after.deviceReportedStartTime != before.deviceReportedStartTime
            return ageReset && ageAdvances && startChanged
        }
    }
}
