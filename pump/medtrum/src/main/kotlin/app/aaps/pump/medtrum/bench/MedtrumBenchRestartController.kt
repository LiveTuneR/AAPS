package app.aaps.pump.medtrum.bench

import android.os.SystemClock
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.diagnostics.MedtrumBleTrace
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.services.MedtrumService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedtrumBenchRestartController @Inject constructor(
    private val preferences: Preferences,
    private val pump: MedtrumPump,
    private val trace: MedtrumBleTrace,
    private val journal: MedtrumBenchRestartJournal
) {
    private val _status = MutableStateFlow(BenchRestartStatus())
    val status: StateFlow<BenchRestartStatus> = _status.asStateFlow()

    init {
        journal.markInterruptedIfNeeded()?.let { campaignId ->
            _status.value = BenchRestartStatus(campaignId, BenchRestartState.INTERRUPTED, "Previous campaign was interrupted; no write was resumed")
            trace.record("bench_restart_interrupted", mapOf("campaignId" to campaignId, "autoResume" to false))
        }
    }

    fun execute(command: MedtrumBenchRestartCommand, service: MedtrumService?): BenchRestartResult {
        val campaignId = UUID.randomUUID().toString()
        journal.begin(campaignId)
        trace.record("bench_restart_requested", mapOf("campaignId" to campaignId))
        val io = RealBleProductionIo(service, pump)
        val report = BenchRestartReport(campaignId)
        val campaign = BenchRestartCampaign(io) { event ->
            val enriched = event.copy(
                fields = event.fields + mapOf(
                    "campaignId" to campaignId,
                    "deviceSerialFingerprint" to fingerprint(pump.pumpSN),
                    "firmware" to pump.swVersion,
                    "monotonicMs" to SystemClock.elapsedRealtime()
                )
            )
            report.record(enriched)
            _status.value = BenchRestartStatus(campaignId, enriched.state, enriched.fields["reason"]?.toString().orEmpty())
            journal.update(campaignId, enriched)
            trace.record(enriched.name, enriched.fields)
        }
        val result = campaign.run(
            BenchRestartRequest(
                experimentalEnabled = preferences.get(MedtrumBooleanKey.MedtrumBenchRestartExperimental),
                queueSafe = command.queueWasSafe,
                bolusSafe = command.bolusWasSafe
            )
        )
        _status.value = BenchRestartStatus(campaignId, BenchRestartState.EXPORT, result.message)
        journal.update(campaignId, BenchRestartState.EXPORT)
        trace.record("bench_restart_export_created", mapOf("campaignId" to campaignId, "terminalState" to result.state.name))
        val archive = runCatching {
            runBlocking {
                trace.export(
                    mapOf(
                        "bench_restart_summary.json" to report.toJson(result),
                        "bench_restart_summary.md" to report.toMarkdown(result)
                    )
                )
            }
        }.getOrNull()
        journal.update(campaignId, result.state)
        _status.value = BenchRestartStatus(campaignId, result.state, result.message + (archive?.let { " (${it.name})" } ?: ""))
        return result
    }

    private class RealBleProductionIo(
        private val service: MedtrumService?,
        private val pump: MedtrumPump
    ) : BenchRestartIo {
        override fun synchronize(): Boolean {
            pump.clearDeviceReportedSessionTelemetry()
            return service?.readBenchRestartBaseline(includeHistory = false) == true
        }

        override fun readHistory(): Boolean = service?.readBenchRestartBaseline(includeHistory = true) == true

        override fun snapshot(): BenchRestartSnapshot = BenchRestartSnapshot(
            firmware = pump.swVersion,
            deviceType = pump.deviceType,
            supportedModel = pump.pumpType() == PumpType.MEDTRUM_NANO,
            pumpState = pump.pumpState,
            connectionState = pump.connectionState.name,
            patchId = pump.patchId,
            localPatchStartTime = pump.patchStartTime,
            deviceReportedStartTime = pump.deviceReportedPatchStartTime,
            deviceReportedStartTimeAvailable = pump.deviceReportedPatchStartTimeAvailable,
            deviceReportedPatchAge = pump.deviceReportedPatchAge,
            deviceReportedPatchAgeAvailable = pump.deviceReportedPatchAgeAvailable,
            reservoir = pump.reservoir,
            batteryA = pump.batteryVoltage_A,
            batteryB = pump.batteryVoltage_B,
            currentSequence = pump.currentSequenceNumber,
            syncedSequence = pump.syncedSequenceNumber,
            sessionTokenFingerprint = tokenFingerprint(pump.patchSessionToken),
            basalType = pump.lastBasalType.name,
            basalRate = pump.lastBasalRate,
            activeAlarms = pump.activeAlarms.map { it.name },
            desiredPatchExpiration = pump.desiredPatchExpiration
        )

        override fun readPatchSettings(): BenchPatchSettings = BenchPatchSettings(
            alarmSetting = pump.desiredAlarmSetting.code.toInt() and 0xFF,
            hourlyMaxInsulin = pump.desiredHourlyMaxInsulin,
            dailyMaxInsulin = pump.desiredDailyMaxInsulin,
            expirationEnabled = pump.desiredPatchExpiration,
            autoSuspendEnabled = 0,
            autoSuspendTime = 12,
            lowSuspend = 0,
            predictiveLowSuspend = 0,
            predictiveLowSuspendRange = 30
        )

        override fun transmitSettings(settings: BenchPatchSettings): BenchWriteResult =
            service?.sendBenchSetPatch(settings) ?: unavailableService()

        override fun transmitActivate(): BenchWriteResult = service?.sendBenchActivate() ?: unavailableService()

        override fun readOnlyRecovery(): Boolean = service?.recoverBenchReadOnly() == true

        override fun waitForTimerObservation() = SystemClock.sleep(TIMER_OBSERVATION_MS)

        private fun unavailableService(): BenchWriteResult = BenchWriteResult(
            success = false,
            transmitted = false,
            transport = "REAL_BLE",
            failureReason = "Medtrum service unavailable"
        )

        private fun tokenFingerprint(token: Long): String {
            val bytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(token).array()
            return MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
        }

        companion object {
            private const val TIMER_OBSERVATION_MS = 1_500L
        }
    }

    private fun fingerprint(value: Long): String {
        val bytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
        return MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
    }
}
