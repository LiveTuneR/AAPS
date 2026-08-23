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
    val deviceReportedStartTimeAvailable: Boolean,
    val deviceReportedPatchAge: Long,
    val deviceReportedPatchAgeAvailable: Boolean,
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
        "deviceReportedStartTimeAvailable" to deviceReportedStartTimeAvailable,
        "deviceReportedPatchAge" to deviceReportedPatchAge,
        "deviceReportedPatchAgeAvailable" to deviceReportedPatchAgeAvailable,
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

    fun traceFields(): Map<String, Any> = mapOf(
        "alarmSetting" to alarmSetting,
        "hourlyMaxInsulin" to hourlyMaxInsulin,
        "dailyMaxInsulin" to dailyMaxInsulin,
        "expirationEnabled" to expirationEnabled,
        "autoSuspendEnabled" to autoSuspendEnabled,
        "autoSuspendTime" to autoSuspendTime,
        "lowSuspend" to lowSuspend,
        "predictiveLowSuspend" to predictiveLowSuspend,
        "predictiveLowSuspendRange" to predictiveLowSuspendRange
    )
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
    val responseCode: Int? = null,
    val transmitted: Boolean = true,
    val transport: String = "TEST_DOUBLE",
    val latencyMs: Long = 0,
    val rawRequest: ByteArray? = null,
    val rawResponse: ByteArray? = null,
    val profileHash: String? = null,
    val failureReason: String? = null
)

enum class KnownHardwareProbeStatus { NOT_RUN, COMPLETED, PARTIAL, FAILED }
enum class BenchProbeOutcome { NOT_RUN, ACCEPTED, REJECTED, TIMEOUT, UNKNOWN }
enum class BenchTimerEffect { NO_RESET_OBSERVED, RESET_OBSERVED, UNKNOWN }
enum class HiddenTransitionStatus { CONFIRMED, NOT_FOUND, BLOCKED }
enum class OverallRestartVerdict { CONFIRMED, NOT_CONFIRMED, BLOCKED, FAILED }

data class BenchRestartRequest(
    val engineeringMode: Boolean,
    val experimentalEnabled: Boolean,
    val queueSafe: Boolean,
    val bolusSafe: Boolean
)

data class BenchRestartResult(
    val state: BenchRestartState,
    val message: String,
    val restartProven: Boolean = false,
    val knownHardwareProbes: KnownHardwareProbeStatus = KnownHardwareProbeStatus.NOT_RUN,
    val setPatchWhileActive: BenchProbeOutcome = BenchProbeOutcome.NOT_RUN,
    val setPatchTimerEffect: BenchTimerEffect = BenchTimerEffect.UNKNOWN,
    val activateWhileActive: BenchProbeOutcome = BenchProbeOutcome.NOT_RUN,
    val activateResponseCode: Int? = null,
    val activateTimerEffect: BenchTimerEffect = BenchTimerEffect.UNKNOWN,
    val hiddenTransition: HiddenTransitionStatus = HiddenTransitionStatus.BLOCKED,
    val overallVerdict: OverallRestartVerdict = OverallRestartVerdict.BLOCKED,
    val realBleWritesAttempted: Int = 0,
    val setPatchWrites: Int = 0,
    val activateWrites: Int = 0
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
    fun transmitActivate(): BenchWriteResult
    fun readOnlyRecovery(): Boolean
    fun waitForTimerObservation()
}

object BenchRestartCandidateRegistry {
    // No path currently satisfies the two-source CONFIRMED evidence gate.
    fun confirmedFor(firmware: String, deviceType: Int): ConfirmedRestartCandidate? = null
}

object BenchRestartVisibility {
    fun isVisible(engineeringMode: Boolean, experimentalEnabled: Boolean): Boolean =
        engineeringMode && experimentalEnabled
}
