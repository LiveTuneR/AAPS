package app.aaps.pump.medtrum.bench

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

enum class BenchRestartState {
    IDLE,
    PREFLIGHT,
    ACQUIRE_EXCLUSIVE_ACCESS,
    CONNECT,
    BASELINE_SYNC,
    BASELINE_HISTORY,
    REVERSIBLE_SETTINGS_TEST,
    RESTORE_SETTINGS,
    PRE_RESTART_SNAPSHOT,
    REACTIVATION_TRANSITION,
    VERIFY_TRANSITION,
    ACTIVATE,
    VERIFY_ACTIVATION,
    POST_RESTART_SETTINGS_TEST,
    FINAL_SNAPSHOT,
    EXPORT,
    COMPLETE,
    FAILED,
    BLOCKED,
    INTERRUPTED
}

data class BenchRestartStatus(
    val campaignId: String? = null,
    val state: BenchRestartState = BenchRestartState.IDLE,
    val message: String = ""
)

data class BenchRestartSnapshot(
    val firmware: String,
    val deviceType: Int,
    val supportedModel: Boolean,
    val pumpState: MedtrumPumpState,
    val connectionState: String,
    val patchId: Long,
    val localPatchStartTime: Long,
    val deviceReportedStartTime: Long,
    val deviceReportedPatchAge: Long,
    val reservoir: Double,
    val batteryA: Double,
    val batteryB: Double,
    val currentSequence: Int,
    val syncedSequence: Int,
    val sessionTokenFingerprint: String,
    val basalType: String,
    val basalRate: Double,
    val activeAlarms: List<String>,
    val desiredPatchExpiration: Boolean
) {
    fun traceFields(): Map<String, Any?> = mapOf(
        "firmware" to firmware,
        "deviceType" to deviceType,
        "supportedModel" to supportedModel,
        "realPumpState" to pumpState.name,
        "connectionState" to connectionState,
        "patchId" to patchId,
        "localPatchStartTime" to localPatchStartTime,
        "deviceReportedStartTime" to deviceReportedStartTime,
        "deviceReportedPatchAge" to deviceReportedPatchAge,
        "reservoir" to reservoir,
        "batteryA" to batteryA,
        "batteryB" to batteryB,
        "currentSequence" to currentSequence,
        "syncedSequence" to syncedSequence,
        "sessionTokenFingerprint" to sessionTokenFingerprint,
        "basalType" to basalType,
        "basalRate" to basalRate,
        "activeAlarms" to activeAlarms.joinToString(","),
        "desiredPatchExpiration" to desiredPatchExpiration
    )
}

data class BenchPatchSettings(
    val alarmSetting: Int,
    val hourlyMaxInsulin: Int,
    val dailyMaxInsulin: Int,
    val expirationEnabled: Boolean,
    val autoSuspendEnabled: Int,
    val autoSuspendTime: Int,
    val lowSuspend: Int,
    val predictiveLowSuspend: Int,
    val predictiveLowSuspendRange: Int
) {
    fun expirationProbe(): BenchPatchSettings = copy(expirationEnabled = !expirationEnabled)
}

enum class CandidateConfidence { UNKNOWN, INFERRED, CONFIRMED, DISPROVED }

data class ConfirmedRestartCandidate(
    val id: String,
    val opcode: Int,
    val request: ByteArray,
    val confidence: CandidateConfidence
) {
    init {
        require(confidence == CandidateConfidence.CONFIRMED)
    }
}

data class BenchWriteResult(
    val success: Boolean,
    val timedOut: Boolean = false,
    val responseCode: Int? = null
)

data class BenchRestartRequest(
    val engineeringMode: Boolean,
    val experimentalEnabled: Boolean,
    val queueSafe: Boolean,
    val bolusSafe: Boolean
)

data class BenchRestartResult(
    val state: BenchRestartState,
    val message: String,
    val restartProven: Boolean = false
)

data class BenchRestartEvent(
    val state: BenchRestartState,
    val name: String,
    val fields: Map<String, Any?> = emptyMap()
)

interface BenchRestartIo {
    fun synchronize(): Boolean
    fun readHistory(): Boolean
    fun snapshot(): BenchRestartSnapshot
    fun readPatchSettings(): BenchPatchSettings?
    fun transmitSettings(settings: BenchPatchSettings): BenchWriteResult
    fun transmitCandidate(candidate: ConfirmedRestartCandidate): BenchWriteResult
    fun transmitActivate(): BenchWriteResult
    fun readOnlyRecovery(): Boolean
}

object BenchRestartCandidateRegistry {
    // No path currently satisfies the two-source CONFIRMED evidence gate.
    fun confirmedFor(firmware: String, deviceType: Int): ConfirmedRestartCandidate? = null
}

object BenchRestartVisibility {
    fun isVisible(engineeringMode: Boolean, experimentalEnabled: Boolean): Boolean =
        engineeringMode && experimentalEnabled
}
