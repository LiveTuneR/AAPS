package app.aaps.pump.apex.bolus

import org.json.JSONObject

enum class ApexBolusState {
    PREPARED,
    COMMAND_SENT,
    ACCEPTED,
    DELIVERING,
    LIVE_COMPLETED,
    HISTORY_CONFIRMING,
    CONFIRMED_DELIVERED,
    REJECTED_BEFORE_DELIVERY,
    DEFINITELY_NOT_DELIVERED,
    CANCELLED_CONFIRMED,
    PARTIALLY_DELIVERED_CONFIRMED,
    OPERATOR_CONFIRMED_NOT_DELIVERED,
    DELIVERY_UNCERTAIN,
    RECONCILIATION_REQUIRED;

    val terminal: Boolean
        get() = this in setOf(
            CONFIRMED_DELIVERED,
            REJECTED_BEFORE_DELIVERY,
            DEFINITELY_NOT_DELIVERED,
            CANCELLED_CONFIRMED,
            PARTIALLY_DELIVERED_CONFIRMED,
            OPERATOR_CONFIRMED_NOT_DELIVERED,
        )
}

data class ApexBolusOperation(
    var schemaVersion: Int = 2,
    val operationUuid: String,
    val pumpIdentityHash: String,
    val firmware: String?,
    val protocol: String?,
    val createdUtc: Long,
    val requestedTimestamp: Long,
    val temporaryId: Long,
    val bolusType: String,
    val requestedUnits: Double,
    val encodedSteps: Int,
    val caller: String,
    val queueCommandIdentity: String?,
    val commandGeneration: Long?,
    var transportGeneration: Long? = null,
    var state: ApexBolusState = ApexBolusState.PREPARED,
    var commandSentUtc: Long? = null,
    var commandSentElapsed: Long? = null,
    var transportWriteAttempted: Boolean? = false,
    var transportWriteIssued: Boolean? = false,
    var transportOutcome: String? = "NOT_ATTEMPTED",
    var writeStartedUtc: Long? = null,
    var writeCallbackUtc: Long? = null,
    var acceptedUtc: Long? = null,
    var acceptedObserved: Boolean = false,
    var highestProgressSteps: Int = 0,
    var completedObserved: Boolean = false,
    var completedSteps: Int? = null,
    var cancelled: Boolean = false,
    var timedOut: Boolean = false,
    var lastReconciliationUtc: Long? = null,
    var latestHistoryCheckedUtc: Long? = null,
    var fullHistoryCheckedUtc: Long? = null,
    var latestHistoryResult: String? = null,
    var fullHistoryResult: String? = null,
    var reconciliationReason: String? = null,
    var reconciliationAttempts: Int = 0,
    var automaticAttempts: Int = 0,
    var manualAttempts: Int = 0,
    var lastAttemptUtc: Long? = null,
    var nextAutomaticAttemptUtc: Long? = null,
    var latestHistoryAttempts: Int = 0,
    var fullHistoryAttempts: Int = 0,
    var matchedPumpHistoryId: Long? = null,
    var matchedPumpHistoryTime: Long? = null,
    var matchedRequestedSteps: Int? = null,
    var matchedPerformedSteps: Int? = null,
    var legacyMigrated: Boolean = false,
    var operatorConfirmedNotDeliveredUtc: Long? = null,
    var operatorConfirmation: Boolean = false,
    var operatorConfirmationBuildSha: String? = null,
) {
    val unresolved: Boolean get() = !state.terminal

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("operationUuid", operationUuid)
        .put("pumpIdentityHash", pumpIdentityHash)
        .put("firmware", firmware)
        .put("protocol", protocol)
        .put("createdUtc", createdUtc)
        .put("requestedTimestamp", requestedTimestamp)
        .put("temporaryId", temporaryId)
        .put("bolusType", bolusType)
        .put("requestedUnits", requestedUnits)
        .put("encodedSteps", encodedSteps)
        .put("caller", caller)
        .put("queueCommandIdentity", queueCommandIdentity)
        .put("commandGeneration", commandGeneration)
        .put("transportGeneration", transportGeneration)
        .put("state", state.name)
        .put("commandSentUtc", commandSentUtc)
        .put("commandSentElapsed", commandSentElapsed)
        .put("transportWriteAttempted", transportWriteAttempted)
        .put("transportWriteIssued", transportWriteIssued)
        .put("transportOutcome", transportOutcome)
        .put("writeStartedUtc", writeStartedUtc)
        .put("writeCallbackUtc", writeCallbackUtc)
        .put("acceptedUtc", acceptedUtc)
        .put("acceptedObserved", acceptedObserved)
        .put("highestProgressSteps", highestProgressSteps)
        .put("completedObserved", completedObserved)
        .put("completedSteps", completedSteps)
        .put("cancelled", cancelled)
        .put("timedOut", timedOut)
        .put("lastReconciliationUtc", lastReconciliationUtc)
        .put("latestHistoryCheckedUtc", latestHistoryCheckedUtc)
        .put("fullHistoryCheckedUtc", fullHistoryCheckedUtc)
        .put("latestHistoryResult", latestHistoryResult)
        .put("fullHistoryResult", fullHistoryResult)
        .put("reconciliationReason", reconciliationReason)
        .put("reconciliationAttempts", reconciliationAttempts)
        .put("automaticAttempts", automaticAttempts)
        .put("manualAttempts", manualAttempts)
        .put("lastAttemptUtc", lastAttemptUtc)
        .put("nextAutomaticAttemptUtc", nextAutomaticAttemptUtc)
        .put("latestHistoryAttempts", latestHistoryAttempts)
        .put("fullHistoryAttempts", fullHistoryAttempts)
        .put("matchedPumpHistoryId", matchedPumpHistoryId)
        .put("matchedPumpHistoryTime", matchedPumpHistoryTime)
        .put("matchedRequestedSteps", matchedRequestedSteps)
        .put("matchedPerformedSteps", matchedPerformedSteps)
        .put("legacyMigrated", legacyMigrated)
        .put("operatorConfirmedNotDeliveredUtc", operatorConfirmedNotDeliveredUtc)
        .put("operatorConfirmation", operatorConfirmation)
        .put("operatorConfirmationBuildSha", operatorConfirmationBuildSha)

    companion object {
        fun fromJson(json: JSONObject) = ApexBolusOperation(
            schemaVersion = json.optInt("schemaVersion", 1),
            operationUuid = json.getString("operationUuid"),
            pumpIdentityHash = json.getString("pumpIdentityHash"),
            firmware = json.optString("firmware").takeIf(String::isNotBlank),
            protocol = json.optString("protocol").takeIf(String::isNotBlank),
            createdUtc = json.getLong("createdUtc"),
            requestedTimestamp = json.getLong("requestedTimestamp"),
            temporaryId = json.getLong("temporaryId"),
            bolusType = json.getString("bolusType"),
            requestedUnits = json.getDouble("requestedUnits"),
            encodedSteps = json.getInt("encodedSteps"),
            caller = json.getString("caller"),
            queueCommandIdentity = json.optString("queueCommandIdentity").takeIf(String::isNotBlank),
            commandGeneration = json.optLongOrNull("commandGeneration"),
            transportGeneration = json.optLongOrNull("transportGeneration"),
            state = runCatching { ApexBolusState.valueOf(json.getString("state")) }.getOrDefault(ApexBolusState.RECONCILIATION_REQUIRED),
            commandSentUtc = json.optLongOrNull("commandSentUtc"),
            commandSentElapsed = json.optLongOrNull("commandSentElapsed"),
            transportWriteAttempted = json.optBooleanOrNull("transportWriteAttempted"),
            transportWriteIssued = json.optBooleanOrNull("transportWriteIssued"),
            transportOutcome = json.optString("transportOutcome").takeIf(String::isNotBlank),
            writeStartedUtc = json.optLongOrNull("writeStartedUtc"),
            writeCallbackUtc = json.optLongOrNull("writeCallbackUtc"),
            acceptedUtc = json.optLongOrNull("acceptedUtc"),
            acceptedObserved = json.optBoolean("acceptedObserved"),
            highestProgressSteps = json.optInt("highestProgressSteps"),
            completedObserved = json.optBoolean("completedObserved"),
            completedSteps = json.optIntOrNull("completedSteps"),
            cancelled = json.optBoolean("cancelled"),
            timedOut = json.optBoolean("timedOut"),
            lastReconciliationUtc = json.optLongOrNull("lastReconciliationUtc"),
            latestHistoryCheckedUtc = json.optLongOrNull("latestHistoryCheckedUtc"),
            fullHistoryCheckedUtc = json.optLongOrNull("fullHistoryCheckedUtc"),
            latestHistoryResult = json.optString("latestHistoryResult").takeIf(String::isNotBlank),
            fullHistoryResult = json.optString("fullHistoryResult").takeIf(String::isNotBlank),
            reconciliationReason = json.optString("reconciliationReason").takeIf(String::isNotBlank),
            reconciliationAttempts = json.optInt("reconciliationAttempts"),
            automaticAttempts = json.optInt("automaticAttempts"),
            manualAttempts = json.optInt("manualAttempts"),
            lastAttemptUtc = json.optLongOrNull("lastAttemptUtc"),
            nextAutomaticAttemptUtc = json.optLongOrNull("nextAutomaticAttemptUtc"),
            latestHistoryAttempts = json.optInt("latestHistoryAttempts"),
            fullHistoryAttempts = json.optInt("fullHistoryAttempts"),
            matchedPumpHistoryId = json.optLongOrNull("matchedPumpHistoryId"),
            matchedPumpHistoryTime = json.optLongOrNull("matchedPumpHistoryTime"),
            matchedRequestedSteps = json.optIntOrNull("matchedRequestedSteps"),
            matchedPerformedSteps = json.optIntOrNull("matchedPerformedSteps"),
            legacyMigrated = json.optBoolean("legacyMigrated"),
            operatorConfirmedNotDeliveredUtc = json.optLongOrNull("operatorConfirmedNotDeliveredUtc"),
            operatorConfirmation = json.optBoolean("operatorConfirmation"),
            operatorConfirmationBuildSha = json.optString("operatorConfirmationBuildSha").takeIf(String::isNotBlank),
        )
    }
}

data class ApexHistoryCandidate(
    val index: Int,
    val timestamp: Long,
    val requestedSteps: Int,
    val performedSteps: Int,
    val rawTimestampHex: String,
    val rawObjectLength: Int,
    val queryType: String,
    val resultPosition: Int,
)

data class ApexHistoryAnomalies(
    val newestTimestamp: Long?,
    val oldestTimestamp: Long?,
    val monotonicityViolations: Int,
    val staleSlots: Int,
    val duplicates: Int,
    val cursorJumps: Int,
)

sealed interface ApexReconciliationResult {
    data object NoUnresolved : ApexReconciliationResult
    data object DifferentPump : ApexReconciliationResult
    data object NeedLatestRetry : ApexReconciliationResult
    data object NeedFullHistory : ApexReconciliationResult
    data class Matched(val operation: ApexBolusOperation, val candidate: ApexHistoryCandidate) : ApexReconciliationResult
    data class StillUncertain(val operation: ApexBolusOperation) : ApexReconciliationResult
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (isNull(key) || !has(key)) null else optBoolean(key)
