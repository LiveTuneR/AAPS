package app.aaps.core.data.diagnostics

data class PumpDiagnosticState(
    val linkState: String,
    val generation: Long,
    val queuedCommands: Int,
    val pendingCommand: String?,
    val pendingAgeMs: Long?,
    val progressAgeMs: Long,
    val firmware: String?,
    val protocol: String?,
    val maskedSerial: String?
)
