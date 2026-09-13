package app.aaps.core.data.diagnostics

data class PumpDiagnosticState(
    val linkState: String,
    val generation: Long?,
    val queuedCommands: Int,
    val pendingCommand: String?,
    val pendingAgeMs: Long?,
    val progressAgeMs: Long?,
    val firmware: String?,
    val protocol: String?,
    val maskedSerial: String?,
    val bolusReconciliationRequired: Boolean = false,
    val bolusOperationId: String? = null,
    val bolusState: String? = null,
    val bolusRequestedU: Double? = null,
    val bolusLiveCompletedU: Double? = null,
    val bolusHistoryConfirmedU: Double? = null,
    val bolusOperationCreatedUtc: Long? = null,
    val bolusLastReconciliationUtc: Long? = null,
)
