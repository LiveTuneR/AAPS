package app.aaps.core.interfaces.telemetry

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

enum class TherapyEventType {
    PROCESS_START, PROCESS_STOP, CLOCK_CHANGE, CGM, SCHEDULER, CALCULATION, APS_INPUT, APS_DECISION, CONSTRAINT,
    TBR_REQUEST, SMB_REQUEST, PUMP_QUEUE, PUMP_DISPATCH, PUMP_SENT, PUMP_RESULT, HISTORY_RECONCILIATION,
    THERAPY_EVENT, PUMP_STATE, SETTINGS_SNAPSHOT, SETTINGS_CHANGE, ACTIVITY, ERROR, TELEMETRY_STORAGE_PRESSURE
}

data class TherapyTelemetryHealth(
    val enabled: Boolean = true,
    val retentionDays: Int = 7,
    val bytesOnDisk: Long = 0,
    val writerDrops: Long = 0,
    val recoveredRecords: Long = 0,
    val corruptedRecords: Long = 0,
    val storagePressure: Boolean = false,
    val uncleanSessions: Long = 0,
    val lastErrorType: String? = null
)

interface TherapyTelemetry {
    val health: StateFlow<TherapyTelemetryHealth>
    /** Payload is copied before returning. This never controls dosing or retries a pump command. */
    fun record(type: TherapyEventType, data: JSONObject, generation: Long? = null, correlationId: String? = null)
    fun start()
    fun setRetentionDays(days: Int)
    suspend fun export(startUtc: Long, endUtc: Long, expectedCgmIntervalMs: Long? = null): File
}
