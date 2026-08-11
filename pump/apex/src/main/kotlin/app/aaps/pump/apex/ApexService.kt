package app.aaps.pump.apex

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.connectivity.bluetooth.ApexBLE
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.CancelBolus
import app.aaps.pump.apex.connectivity.commands.device.CancelTemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.device.ExtendedBolus
import app.aaps.pump.apex.connectivity.commands.device.GetValue
import app.aaps.pump.apex.connectivity.commands.device.RequestHeartbeat
import app.aaps.pump.apex.connectivity.commands.device.SetConnectionProfile
import app.aaps.pump.apex.connectivity.commands.device.SyncDateTime
import app.aaps.pump.apex.connectivity.commands.device.TemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.UpdateBasalProfileRates
import app.aaps.pump.apex.connectivity.commands.device.UpdateSystemState
import app.aaps.pump.apex.connectivity.commands.device.UpdateUsedBasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.AlarmObject
import app.aaps.pump.apex.connectivity.commands.pump.BasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.CommandResponse
import app.aaps.pump.apex.connectivity.commands.pump.Heartbeat
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObject
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.TDDEntry
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import dagger.android.DaggerService
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
class ApexService: DaggerService(), ApexCommDirector.Callback {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var commDirector: ApexCommDirector
    @Inject lateinit var apexDeviceInfo: ApexDeviceInfo
    @Inject lateinit var apexPumpPlugin: ApexPumpPlugin
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var pump: ApexPump
    @Inject lateinit var config: Config
    @Inject lateinit var status: ApexDriverStatus
    @Inject lateinit var bolusProgressData: BolusProgressData
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var trace: ApexTrace

    companion object {
        const val COMMAND_RESPONSE_TIMEOUT = 5000L
        const val SINGLE_VALUE_RESPONSE_TIMEOUT = 5000L
        const val COMPLEX_VALUE_RESPONSE_TIMEOUT = 30000L

        const val USED_BASAL_PATTERN_INDEX = 7
        const val HEARTBEAT_PERIOD_MINUTES = 2
        const val DIAGNOSTICS_WATCHDOG_PERIOD_MS = 30_000L
        const val DIAGNOSTICS_SNAPSHOT_PERIOD_MS = 5 * 60_000L
        const val DIAGNOSTICS_PENDING_STALL_MS = 45_000L
        const val DIAGNOSTICS_CONNECT_STALL_MS = 40_000L
        const val DIAGNOSTICS_HANDSHAKE_STALL_MS = 75_000L
        const val DIAGNOSTICS_READY_IDLE_MS = 5 * 60_000L
        const val DIAGNOSTICS_ARCHIVE_COOLDOWN_MS = 5 * 60_000L
        val FIRST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_9
        val LAST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_12
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var unreachableJob: Job? = null
    private var diagnosticsWatchdogJob: Job? = null

    private var lastBolusDateTime = DateTime(0)
    private var lastConnectedTimestamp = System.currentTimeMillis()


    val isReadyForExecutingCommands: Boolean get() = commDirector.linkState.value is ApexCommDirector.LinkState.Ready

    val lastConnected: Long
        get() = if (connectionStatus != ApexBLE.Status.CONNECTED) {
            lastConnectedTimestamp
        } else System.currentTimeMillis()

    private suspend fun getValue(value: GetValue.Value): List<PumpObjectModel>? =
        commDirector.request(value)?.also { objects ->
            objects.forEach { processObject(it) }
        }

    private suspend fun executeWithResponse(command: DeviceCommand): CommandResponse? =
        commDirector.execute(command)?.also { processObject(it) }

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMP, "Service created")
        commDirector.setCallback(this)
        commDirector.start()
        pump.isInitialized = false
        trace.capturePreviousExit()
        trace.record("service_created")

        pump.serialNumber = apexDeviceInfo.serialNumber
        preferences.observe(ApexStringKey.SerialNumber).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onSerialChanged()
        }
        preferences.observe(ApexStringKey.FirmwareVer).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onFwVerChanged()
        }
        preferences.observe(ApexBooleanKey.CalculateBatteryPercentage).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onBatteryStuffChanged()
        }
        preferences.observe(ApexStringKey.CalcBatteryType).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onBatteryStuffChanged()
        }
        preferences.observe(ApexDoubleKey.BatteryLowVoltage).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onBatteryStuffChanged()
        }
        preferences.observe(ApexDoubleKey.BatteryHighVoltage).drop(1).collectResilient(serviceScope, aapsLogger, LTag.PUMP) {
            onBatteryStuffChanged()
        }
        startDiagnosticsWatchdog()
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.PUMP, "Service destroyed")
        trace.record("service_destroyed")
        commDirector.stop()
        commDirector.setCallback(null)
        pump.isInitialized = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onSerialChanged() {
        pump.serialNumber = apexDeviceInfo.serialNumber
        preferences.put(ApexStringKey.BluetoothAddress, "")
        disconnect()
        startConnection()
    }

    private fun onFwVerChanged() {
        disconnect()
        startConnection()
    }

    private fun onBatteryStuffChanged() {
        serviceScope.launch { getStatus("ApexService-onBatteryStuffChanged", force = true) }
    }

    private fun startDiagnosticsWatchdog() {
        diagnosticsWatchdogJob?.cancel()
        diagnosticsWatchdogJob = serviceScope.launch {
            var lastSnapshotElapsed = 0L
            var lastArchiveElapsed = 0L
            while (isActive) {
                delay(DIAGNOSTICS_WATCHDOG_PERIOD_MS)
                val now = SystemClock.elapsedRealtime()
                val snapshot = commDirector.diagnosticSnapshot()
                if (now - lastSnapshotElapsed >= DIAGNOSTICS_SNAPSHOT_PERIOD_MS) {
                    lastSnapshotElapsed = now
                    trace.record("watchdog_snapshot", snapshot.generation, fields = snapshot.toFields())
                }

                val stallReason = when {
                    snapshot.pendingAgeMs != null && snapshot.pendingAgeMs >= DIAGNOSTICS_PENDING_STALL_MS -> "pending_command"
                    snapshot.state == "Connecting" && snapshot.stateAgeMs >= DIAGNOSTICS_CONNECT_STALL_MS -> "connecting"
                    snapshot.state == "Handshaking" && snapshot.stateAgeMs >= DIAGNOSTICS_HANDSHAKE_STALL_MS -> "handshake"
                    snapshot.state == "Ready" && snapshot.progressAgeMs >= DIAGNOSTICS_READY_IDLE_MS -> "ready_without_progress"
                    else -> null
                }
                if (stallReason != null && now - lastArchiveElapsed >= DIAGNOSTICS_ARCHIVE_COOLDOWN_MS) {
                    lastArchiveElapsed = now
                    trace.record("watchdog_stall", snapshot.generation, fields = snapshot.toFields() + ("reason" to stallReason))
                    trace.captureThreadDump(stallReason)
                    runCatching { trace.export() }
                        .onFailure { error -> trace.record("watchdog_export_failed", fields = mapOf("error" to error::class.simpleName)) }
                    notificationManager.post(NotificationId.APEX_PROTOCOL_ERROR, rh.gs(R.string.diagnostic_stall_detected))
                }
            }
        }
    }

    private fun ApexCommDirector.DiagnosticSnapshot.toFields(): Map<String, Any?> = mapOf(
        "state" to state,
        "desiredConnection" to desiredConnection,
        "queuedCommands" to queuedCommands,
        "pendingCommand" to pendingCommand,
        "pendingAgeMs" to pendingAgeMs,
        "stateAgeMs" to stateAgeMs,
        "progressAgeMs" to progressAgeMs,
    )

    //////// Public methods

    private fun selectedFirmwareVersion(): FirmwareVersion =
        FirmwareVersion.entries.firstOrNull { it.name == preferences.get(ApexStringKey.FirmwareVer) }
            ?: FirmwareVersion.AUTO

    private fun isExperimentalControlEnabled(): Boolean = ApexCompatibility.isControlEligible(
        version = pump.firmwareVersion,
        selectedVersion = selectedFirmwareVersion(),
        enabled = preferences.get(ApexBooleanKey.EnableExperimentalControl),
    )

    private fun isExperimentalControlAllowed(command: String, caller: String): Boolean {
        val version = pump.firmwareVersion
        val automaticVersion = selectedFirmwareVersion() == FirmwareVersion.AUTO
        val controlToggleEnabled = preferences.get(ApexBooleanKey.EnableExperimentalControl)
        val allowed = isExperimentalControlEnabled()
        if (!allowed) {
            aapsLogger.error(LTag.PUMP, "Blocked Apex therapy command=$command caller=$caller")
            trace.record(
                "therapy_command_blocked",
                generation = linkState.generation,
                fields = mapOf(
                    "command" to command,
                    "caller" to caller,
                    "controlToggleEnabled" to controlToggleEnabled,
                    "automaticVersion" to automaticVersion,
                    "knownLegacyVersion" to (version?.let(ApexCompatibility::isKnownLegacyVersion) ?: false),
                ),
            )
            notificationManager.post(NotificationId.APEX_PROTOCOL_ERROR, rh.gs(R.string.diagnostic_control_blocked))
        }
        return allowed
    }

    suspend fun checkPump(caller: String, optimize: Boolean = false): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "checkPump - $caller")

        try {
            status.addAction(ApexDriverStatus.Action.CheckingPump, R.string.action_checking_pump)
            return getStatus("ApexService-checkPump", optimize)
        } finally {
            status.removeAction(ApexDriverStatus.Action.CheckingPump)
        }
    }

    suspend fun syncDateTime(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "syncDateTime - $caller")
        if (!isExperimentalControlAllowed("SyncDateTime", caller)) return false

        status.addAction(ApexDriverStatus.Action.UpdatingDateTime, R.string.action_updating_dt)
        val response = executeWithResponse(SyncDateTime(apexDeviceInfo, DateTime.now()))
        status.removeAction(ApexDriverStatus.Action.UpdatingDateTime)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[syncDateTime caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to sync time: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun requestHeartbeat(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "requestHeartbeat - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "requestHeartbeat isn't supported yet.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.RequestingHeartbeat, R.string.action_requesting_heartbeat)
        val response = executeWithResponse(RequestHeartbeat(apexDeviceInfo, HEARTBEAT_PERIOD_MINUTES))
        status.removeAction(ApexDriverStatus.Action.RequestingHeartbeat)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[requestHeartbeat caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to notify about connection: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun bolus(dbi: DetailedBolusInfo, caller: String): ApexPump.InProgressBolus? {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus - $caller")
        if (!isExperimentalControlAllowed("Bolus", caller)) return null
        if (dbi.insulin > pump.maxBolus) {
            aapsLogger.error(LTag.PUMP, "[bolus caller=$caller] Requested ${dbi.insulin}U is greater than maximum set ${pump.maxBolus}")
            return null
        }

        val doseRaw = (dbi.insulin / 0.025).roundToInt()
        val temporaryId = DateTime.now().withSecondOfMinute(59).withMillisOfSecond(0).millis

        val action = if (dbi.bolusType == BS.Type.SMB)
            ApexDriverStatus.Action.SettingMicroBolus
        else
            ApexDriverStatus.Action.SettingBolus

        if (!checkPump("ApexService-bolus", optimize = true)) return null

        val inProgress = ApexPump.InProgressBolus(
            requestedDose = dbi.insulin,
            temporaryId = temporaryId,
            detailedBolusInfo = dbi,
            lockHistory = true,
        )
        val syncResult = runCatching {
            pumpSync.addBolusWithTempId(
                timestamp = dbi.timestamp,
                amount = PumpInsulin(dbi.insulin),
                temporaryId = temporaryId,
                type = dbi.bolusType,
                pumpSerial = apexDeviceInfo.serialNumber,
                pumpType = PumpType.APEX_TRUCARE_III,
            )
        }.getOrElse { error ->
            aapsLogger.error(LTag.PUMP, "Failed to create temporary bolus record", error)
            false
        }
        if (!syncResult) {
            trace.record("bolus_temp_record_failed", generation = linkState.generation)
            return null
        }
        pump.inProgressBolus = inProgress
        aapsLogger.debug(LTag.PUMP, "Initial bolus [${dbi.insulin}U] sync succeeded")

        status.addAction(action, rh.gs(
            if (dbi.bolusType == BS.Type.SMB)
                R.string.action_setting_smb
            else
                R.string.action_setting_bolus,
            dbi.insulin,
        ))
        val response = executeWithResponse(Bolus(apexDeviceInfo, doseRaw))
        status.removeAction(action)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[bolus caller=$caller] Timed out while trying to communicate with the pump")
            inProgress.failed = true
            inProgress.useFallbackDose = true
            inProgress.lockHistory = false
            inProgress.completion.complete(Unit)
            trace.record(
                "bolus_start_uncertain",
                generation = linkState.generation,
                fields = mapOf("requestedSteps" to doseRaw),
            )
            return inProgress
        }

        if (response.code == CommandResponse.Code.Invalid) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Cannot begin bolus while in special mode")
            createSpecialModeAlarm()
            rejectBolusStart(inProgress)
            return null
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to begin bolus: ${response.code.name}")
            rejectBolusStart(inProgress)
            return null
        }

        // Pump sets boluses in steps of 0.025U/s for boluses <=1U, 0.05U/s for boluses >1U.
        // Add 15s as time for getting bolus info.
        val maxReasonableBolusTime = (
            if (dbi.insulin <= 1.0)
                dbi.insulin / 0.025
            else
                dbi.insulin / 0.05
        ).roundToLong() + 15
        pump.inProgressBolus?.let { activeBolus ->
            val completed = withTimeoutOrNull(maxReasonableBolusTime * 1000) {
                activeBolus.completion.await()
                true
            } ?: false
            if (!completed) {
                aapsLogger.error(LTag.PUMPCOMM, "Bolus completion timeout; reconciling from pump history")
                activeBolus.useFallbackDose = true
                getBoluses("ApexService-bolus-timeout")
                if (pump.inProgressBolus === activeBolus) {
                    activeBolus.failed = true
                    activeBolus.completion.complete(Unit)
                }
            }
        }

        return inProgress
    }

    private suspend fun rejectBolusStart(inProgress: ApexPump.InProgressBolus) {
        inProgress.failed = true
        inProgress.lockHistory = false
        inProgress.completion.complete(Unit)
        runCatching {
            pumpSync.syncBolusWithTempId(
                timestamp = inProgress.detailedBolusInfo.timestamp,
                amount = PumpInsulin(0.0),
                temporaryId = inProgress.temporaryId,
                type = inProgress.detailedBolusInfo.bolusType,
                pumpId = null,
                pumpType = PumpType.APEX_TRUCARE_III,
                pumpSerial = apexDeviceInfo.serialNumber,
            )
        }.onFailure { error ->
            aapsLogger.error(LTag.PUMP, "Failed to clear rejected temporary bolus record", error)
            trace.record("bolus_rejection_reconcile_failed", generation = linkState.generation)
        }
        if (pump.inProgressBolus === inProgress) pump.inProgressBolus = null
    }

    suspend fun extendedBolus(dose: Double, durationMinutes: Int, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "extendedBolus - $caller")
        if (!isExperimentalControlAllowed("ExtendedBolus", caller)) return false
        val doseRaw = (dose / 0.025).roundToInt()

        val durationRaw = durationMinutes / 15
        if (durationMinutes % 15 > 0) aapsLogger.warn(LTag.PUMPCOMM, "[extendedBolus caller=$caller] Bolus duration is not aligned to 15 minute steps! Rounded down.")

        if (!checkPump("ApexService-extendedBolus", optimize = true)) return false

        status.addAction(
            ApexDriverStatus.Action.SettingExtendedBolus,
            rh.gs(R.string.action_setting_ext_bolus, dose, durationMinutes)
        )
        val response = executeWithResponse(ExtendedBolus(apexDeviceInfo, doseRaw, durationRaw))
        status.removeAction(ApexDriverStatus.Action.SettingExtendedBolus)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[extendedBolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to begin extended bolus: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun temporaryBasal(dose: Double, durationMinutes: Int, type: PumpSync.TemporaryBasalType? = null, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "temporaryBasal - $caller")
        if (!isExperimentalControlAllowed("TemporaryBasal", caller)) return false
        if (dose > pump.maxBasal) {
            aapsLogger.error(LTag.PUMP, "[temporaryBasal caller=$caller] Requested ${dose}U is greater than maximum set ${pump.maxBasal}U")
            return false
        }

        val doseRaw = (dose / 0.025).roundToInt()

        val durationRaw = durationMinutes / 15
        if (durationMinutes % 15 > 0) aapsLogger.warn(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Bolus duration is not aligned to 15 minute steps! Rounded down.")

        if (!checkPump("ApexService-temporaryBasal", optimize = true)) return false

        status.addAction(
            ApexDriverStatus.Action.SettingTBR,
            rh.gs(R.string.action_setting_tbr, dose, durationMinutes)
        )
        val response = executeWithResponse(TemporaryBasal(apexDeviceInfo, true, durationRaw, doseRaw))
        status.removeAction(ApexDriverStatus.Action.SettingTBR)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to start temporary basal: ${response.code.name}")
            return false
        }

        val id = System.currentTimeMillis()
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = id,
            pumpId = id,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            rate = PumpRate(dose),
            duration = durationMinutes.toLong() * 60 * 1000,
            isAbsolute = true,
            type = type,
        )

        if (type == PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND) {
            aapsLogger.debug(LTag.PUMP, "Emulated pump suspend detected - disconnecting pump after the queue ends.")
            onEmulatedSuspend()
        }

        aapsLogger.debug(LTag.PUMP, "Started TBR ${dose}U for ${durationMinutes}min by $caller")
        getStatus("ApexService-temporaryBasal")
        return true
    }

    suspend fun cancelBolus(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelBolus - $caller")

        pump.inProgressBolus?.let {
            // Communication would take longer than just finishing the bolus.
            if (it.requestedDose - it.currentDose < 0.05) {
                aapsLogger.debug(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Skipping, progress ${it.currentDose} / ${it.requestedDose} U")
                return@cancelBolus true
            }
        } ?: return true

        status.addAction(ApexDriverStatus.Action.CancelingBolus, R.string.action_canceling_bolus)
        val response = executeWithResponse(CancelBolus(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.CancelingBolus)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel bolus: ${response.code.name}")
            return false
        }

        onBolusFailed(true)
        getStatus("ApexService-cancelBolus")
        return true
    }

    fun cancelBolusAsync(caller: String) {
        serviceScope.launch {
            cancelBolus(caller)
        }
    }

    suspend fun cancelTemporaryBasal(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelTemporaryBasal - $caller")
        if (!isExperimentalControlAllowed("CancelTemporaryBasal", caller)) return false
        if (!pump.isTBRunning) return true

        status.addAction(ApexDriverStatus.Action.CancelingTBR, R.string.action_canceling_tbr)
        val response = executeWithResponse(CancelTemporaryBasal(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.CancelingTBR)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[cancelTemporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel temporary basal: ${response.code.name}")
            return false
        }

        val stop = System.currentTimeMillis()
        pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = stop,
            endPumpId = stop,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
        )

        getStatus("ApexService-cancelTBR")
        return true
    }

    suspend fun updateSettings(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSettings - $caller")
        if (!isExperimentalControlAllowed("UpdateSettings", caller)) return false

        status.addAction(ApexDriverStatus.Action.UpdatingSettings, R.string.action_updating_settings)
        val response = executeWithResponse(
            pump.lastV1!!.toUpdateSettingsV1(
                apexDeviceInfo,
                AlarmLength.valueOf(preferences.get(ApexStringKey.AlarmSoundLength)),
                maxSingleBolus = (preferences.get(ApexDoubleKey.MaxBolus) / 0.025).roundToInt(),
                maxBasalRate = (preferences.get(ApexDoubleKey.MaxBasal) / 0.025).roundToInt(),
                enableAdvancedBolus = false,
            )
        )
        status.removeAction(ApexDriverStatus.Action.UpdatingSettings)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSettings caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code == CommandResponse.Code.Invalid && ApexCompatibility.isFirmware11Protocol412(pump.firmwareVersion)) {
            aapsLogger.warn(LTag.PUMPCOMM, "[caller=$caller] UpdateSettings returned Invalid for firmware 1.1/protocol 4.12; command is non-critical")
            trace.record(
                "firmware_compatibility_fallback",
                generation = linkState.generation,
                fields = mapOf("command" to "UpdateSettings", "response" to "Invalid", "firmware" to "1.1", "protocol" to "4.12"),
            )
            return true
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update settings: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun updateSystemState(suspend: Boolean, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSystemState - $caller")
        if (!isExperimentalControlAllowed("UpdateSystemState", caller)) return false

        status.addAction(ApexDriverStatus.Action.UpdatingSystemState, R.string.action_updating_sys_state)
        val response = executeWithResponse(UpdateSystemState(apexDeviceInfo, suspend))
        status.removeAction(ApexDriverStatus.Action.UpdatingSystemState)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSystemState caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update system state: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun setConnectionProfile(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "setConnectionProfile - $caller")
        if (!isExperimentalControlAllowed("SetConnectionProfile", caller)) return false

        status.addAction(ApexDriverStatus.Action.UpdatingConnectionProfile, R.string.action_updating_connection)
        val response = executeWithResponse(SetConnectionProfile(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.UpdatingConnectionProfile)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[setConnectionProfile caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to set connection profile: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun updateBasalPatternIndex(id: Int, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateBasalPatternIndex - $caller")
        if (!isExperimentalControlAllowed("UpdateBasalPatternIndex", caller)) return false

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileIndex, R.string.action_setting_basal_no)
        val preferProto411Format = pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true
        var response = executeWithResponse(UpdateUsedBasalProfile(apexDeviceInfo, id, preferProto411Format))
        if (response?.code == CommandResponse.Code.Invalid) {
            val fallbackFormat = !preferProto411Format
            aapsLogger.warn(
                LTag.PUMPCOMM,
                "[updateBasalPatternIndex caller=$caller] valueId ${if (preferProto411Format) "0x34" else "0x04"} returned Invalid; retrying ${if (fallbackFormat) "0x34" else "0x04"}",
            )
            trace.record(
                "firmware_compatibility_fallback",
                generation = linkState.generation,
                fields = mapOf(
                    "command" to "UpdateUsedBasalProfile",
                    "response" to "Invalid",
                    "firstFormat" to if (preferProto411Format) "0x34" else "0x04",
                    "retryFormat" to if (fallbackFormat) "0x34" else "0x04",
                ),
            )
            response = executeWithResponse(UpdateUsedBasalProfile(apexDeviceInfo, id, fallbackFormat))
        }
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileIndex)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern index: ${response.code.name}")
            return false
        }

        if (!commandQueue.isRunning(Command.CommandType.BASAL_PROFILE) && "ApexPumpPlugin" !in caller) {
            aapsLogger.debug(LTag.PUMPCOMM, "Firing profile switch changed event")
            aapsLogger.debug(LTag.PUMP, "Basal profile index updated")
        }
        return true
    }

    suspend fun updateCurrentBasalPattern(doses: List<Double>, caller: String): Boolean {
        require(doses.size == 48)

        aapsLogger.debug(LTag.PUMPCOMM, "updateCurrentBasalPattern - $caller")
        if (!isExperimentalControlAllowed("UpdateBasalProfile", caller)) return false

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileContents, R.string.action_setting_basal_profile)
        val response = executeWithResponse(UpdateBasalProfileRates(
            apexDeviceInfo,
            doses.map { (it / 0.025).roundToInt() }
        ))
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileContents)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern index: ${response.code.name}")
            return false
        }

        return true
    }

    suspend fun getTDDs(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getTDDs - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "TDDs are unreliable on 6.25 and older!")
            return false
        }

        status.addAction(ApexDriverStatus.Action.GettingTDDs, R.string.action_getting_tdds)
        val response = getValue(GetValue.Value.TDDs)
        status.removeAction(ApexDriverStatus.Action.GettingTDDs)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getTDDs caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    suspend fun getBoluses(caller: String, isFullHistory: Boolean = false): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getBoluses - $caller")

        if (pump.inProgressBolus?.lockHistory == true) {
            aapsLogger.info(LTag.PUMPCOMM, "Pump history is locked. Bolus is in progress.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.GettingBoluses, R.string.action_getting_boluses)
        val response = getValue((if (isFullHistory) GetValue.Value.BolusHistory else GetValue.Value.LatestBoluses))
        status.removeAction(ApexDriverStatus.Action.GettingBoluses)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBoluses full=$isFullHistory caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    suspend fun getStatus(caller: String, optimize: Boolean = false, force: Boolean = false): Boolean {
        if ("ApexPumpPlugin" in caller) {
            // Otherwise the message "Updating pump status" will stuck.
            serviceScope.launch {
                delay(1000)
                status.updateStatus()
            }
        }

        if (abs(DateTime.now().millis - pump.dateTime.millis) < 25000 && !force) {
            aapsLogger.debug(LTag.PUMPCOMM, "Status is already fresh, skipping unnecessary update.")
            return true
        }

        val hasV2 = pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true

        aapsLogger.debug(LTag.PUMPCOMM, "getStatus - $caller")

        status.addAction(ApexDriverStatus.Action.GettingStatus,
             if (hasV2) rh.gs(R.string.action_getting_status_v, 1)
             else rh.gs(R.string.action_getting_status)
        )
        val responseV1 = getValue(GetValue.Value.StatusV1)
        if (!hasV2 || responseV1 == null) status.removeAction(ApexDriverStatus.Action.GettingStatus)

        if (responseV1 == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getStatus caller=$caller] V1 | Timed out while trying to communicate with the pump")
            return false
        }

        if (hasV2) {
            status.updateAction(ApexDriverStatus.Action.GettingStatus, rh.gs(R.string.action_getting_status_v, 2))
            val responseV2 = getValue(GetValue.Value.StatusV2)
            status.removeAction(ApexDriverStatus.Action.GettingStatus)

            if (responseV2 == null) {
                aapsLogger.error(LTag.PUMPCOMM, "[getStatus caller=$caller] V2 | Timed out while trying to communicate with the pump")
                return false
            }
        }

        return true
    }

    suspend fun getBasalProfiles(caller: String): Map<Int, List<Double>>? {
        val ret = mutableMapOf<Int, List<Double>>()
        aapsLogger.debug(LTag.PUMPCOMM, "getBasalProfiles - $caller")

        status.addAction(ApexDriverStatus.Action.GettingBasalProfiles, R.string.action_getting_basal)
        val response = getValue(GetValue.Value.BasalProfiles)
        status.removeAction(ApexDriverStatus.Action.GettingBasalProfiles)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBasalProfiles caller=$caller] Timed out while trying to communicate with the pump")
            return null
        }

        for (i in response) {
            require(i is BasalProfile)
            ret[i.index] = i.rates.map { it * 0.025 }
        }

        pump.updateBasalProfiles(ret)
        return ret
    }

    //////// Public values

    val linkState: ApexCommDirector.LinkState
        get() = commDirector.linkState.value

    val connectionStatus: ApexBLE.Status
        get() = when (linkState) {
            is ApexCommDirector.LinkState.Ready -> ApexBLE.Status.CONNECTED
            is ApexCommDirector.LinkState.Connecting,
            is ApexCommDirector.LinkState.Handshaking,
            is ApexCommDirector.LinkState.Backoff -> ApexBLE.Status.CONNECTING
            is ApexCommDirector.LinkState.Disconnected,
            is ApexCommDirector.LinkState.Incompatible,
            ApexCommDirector.LinkState.Stopped -> ApexBLE.Status.DISCONNECTED
        }

    //////// Pump commands handlers

    @Synchronized
    private fun onEmulatedSuspend() {
        serviceScope.launch {
            delay(2500)
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnecting)
            while (commandQueue.size() != 0) {
                aapsLogger.debug(LTag.PUMP, "Waiting for queue to end")
                delay(250)
            }
            aapsLogger.debug(LTag.PUMP, "Disconnecting")
            disconnect()
        }
    }

    private fun onBolusProgress(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus progress $dose")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            val isSMB = it.detailedBolusInfo.bolusType == BS.Type.SMB

            status.updateOrAddAction(
                if (isSMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing,
                rh.gs(
                    if (isSMB)
                        R.string.action_bolusing_smb
                    else
                        R.string.action_bolusing,
                    it.currentDose,
                    it.requestedDose
                )
            )

            bolusProgressData.updateProgress(
                percent = (it.currentDose / it.requestedDose * 100).roundToInt(),
                status = rh.gs(R.string.status_delivering, dose),
                delivered = PumpInsulin(dose)
            )
        }
    }

    private suspend fun onBolusCompleted(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus completed")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )
            bolusProgressData.updateProgress(100, rh.gs(R.string.status_delivered, dose), PumpInsulin(dose))

            // Request new bolus history to fixup bolus ID.
            getBoluses("ApexService-onBolusCompleted")
        }
    }

    private suspend fun onBolusFailed(cancelled: Boolean = false) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus failed (cancelled? $cancelled)")
        pump.inProgressBolus?.let {
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )

            if (cancelled) {
                it.cancelled = true
                bolusProgressData.updateProgress(
                    percent = (it.currentDose / it.requestedDose * 100).roundToInt(),
                    status = rh.gs(R.string.status_bolus_cancelled),
                    delivered = PumpInsulin(it.currentDose)
                )
            }

            if (it.currentDose >= 0.025) {
                it.failed = true
                // Request new bolus history to fixup bolus ID and delivered amount.
                getBoluses("ApexService-onBolusFailed")
            } else {
                aapsLogger.debug(LTag.PUMPCOMM, "bolus entirely failed!")
                it.failed = true
                it.completion.complete(Unit)
                pump.inProgressBolus = null
            }
        }
    }

    private suspend fun onCommandResponse(response: CommandResponse) {
        aapsLogger.debug(LTag.PUMPCOMM, "got command response - ${response.code.name} / ${response.dose}")
        trace.record(
            "pump_command_response",
            generation = linkState.generation,
            fields = mapOf("code" to response.code.name, "doseSteps" to response.dose),
        )
        when (response.code) {
            CommandResponse.Code.Accepted, CommandResponse.Code.Invalid -> Unit
            CommandResponse.Code.StandardBolusProgress -> onBolusProgress(response.dose * 0.025)
            CommandResponse.Code.ExtendedBolusProgress -> return
            CommandResponse.Code.Completed             -> onBolusCompleted(response.dose * 0.025)
            else                                       -> return
        }
    }

    private suspend fun onAlarmsChanged(update: ApexPump.StatusUpdate) {
        val prev = update.previous?.alarms
        // Alarm was dismissed
        if (!prev.isNullOrEmpty() && update.current.alarms.isEmpty()) {
            notificationManager.dismiss(NotificationId.PUMP_ERROR)
            notificationManager.dismiss(NotificationId.PUMP_WARNING)
        }

        // New alarms
        if (prev.isNullOrEmpty() && update.current.alarms.isNotEmpty()) {
            var anyUrgent = false

            for (alarm in update.current.alarms) {
                val name = when (alarm) {
                    Alarm.NoDosage, Alarm.NoDelivery -> rh.gs(R.string.alarm_occlusion)
                    Alarm.NoReservoir -> rh.gs(R.string.alarm_reservoir_empty)
                    Alarm.DeadBattery -> rh.gs(R.string.alarm_battery_dead)
                    Alarm.LowBattery -> rh.gs(R.string.alarm_w_battery_low)
                    Alarm.LowReservoir -> rh.gs(R.string.alarm_w_reservoir_low)
                    Alarm.EncoderError, Alarm.FRAMError, Alarm.ClockError, Alarm.TimeError,
                    Alarm.TimeAnomalyError, Alarm.MotorAbnormal, Alarm.MotorPowerAbnormal,
                    Alarm.MotorError -> rh.gs(R.string.alarm_hardware_fault, alarm.name)
                    Alarm.Unknown -> rh.gs(R.string.alarm_unknown_error)
                    Alarm.CheckGlucose -> rh.gs(R.string.alarm_check_bg)
                    else -> rh.gs(R.string.alarm_unknown_error_name, alarm.name)
                }
                val isUrgent = when(alarm) {
                    Alarm.LowBattery, Alarm.LowReservoir, Alarm.CheckGlucose -> false
                    else -> true
                }
                if (isUrgent) anyUrgent = true

                notificationManager.post(
                    if (isUrgent) NotificationId.PUMP_ERROR else NotificationId.PUMP_WARNING,
                    rh.gs(R.string.alarm_label, name),
                    if (isUrgent) NotificationLevel.URGENT else NotificationLevel.NORMAL,
                )
                pumpSync.insertAnnouncement(
                    error = rh.gs(R.string.alarm_label, name),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                )
            }

            if (anyUrgent && pump.isBolusing) {
                // Pump sends early heartbeat while bolusing if there's an error while bolusing.
                aapsLogger.error(LTag.PUMP, "Bolus has failed!")
                onBolusFailed()
            }
        }
    }

    private suspend fun onBasalChanged(update: ApexPump.StatusUpdate) {
        if (update.current.basal == null) {
            notificationManager.post(
                NotificationId.PUMP_SUSPENDED,
                rh.gs(R.string.notification_pump_is_suspended),
                if (pump.isBolusing) NotificationLevel.URGENT else NotificationLevel.NORMAL,
            )
            commandQueue.loadEvents()
            return
        } else {
            notificationManager.dismiss(NotificationId.PUMP_SUSPENDED)
        }
    }

    private suspend fun onSettingsChanged(update: ApexPump.StatusUpdate) {
        if (pump.settingsAreUnadvised && preferences.get(ApexDoubleKey.MaxBasal) != 0.0 && preferences.get(ApexDoubleKey.MaxBolus) != 0.0) updateSettings("ApexService-onSettingsChanged")
        if (update.current.currentBasalPattern != USED_BASAL_PATTERN_INDEX) updateBasalPatternIndex(USED_BASAL_PATTERN_INDEX, "ApexService-onSettingsChanged")
    }

    private suspend fun onBatteryChanged(update: ApexPump.StatusUpdate) {
        val cur = update.current
        update.previous?.let { old ->
            // Percentage became higher - battery was changed.
            if (cur.batteryLevel.percentage - 26 > old.batteryLevel.percentage && preferences.get(ApexBooleanKey.LogBatteryChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.PUMP_BATTERY_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged battery change")
            }
        }
    }

    private suspend fun onReservoirChanged(update: ApexPump.StatusUpdate) {
        val cur = update.current
        update.previous?.let { old ->
            // Reservoir level became higher - insulin was changed.
            if (cur.reservoirLevel - 2 > old.reservoirLevel && preferences.get(ApexBooleanKey.LogInsulinChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.INSULIN_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged insulin change")
            }
        }
    }

    private fun onSystemStateChanged(v1: StatusV1) {
        if (v1.isLocked) {
            notificationManager.post(NotificationId.APEX_PUMP_LOCKED, rh.gs(R.string.pump_is_locked))
        } else {
            notificationManager.dismiss(NotificationId.APEX_PUMP_LOCKED)
        }

        if (v1.isDefaultBasal) {
            notificationManager.post(NotificationId.WRONG_PUMP_DATA, rh.gs(R.string.pump_is_using_ref_basal), NotificationLevel.URGENT)
        } else {
            notificationManager.dismiss(NotificationId.WRONG_PUMP_DATA)
        }
    }

    private suspend fun onStatusV1(status: StatusV1) {
        val update = pump.updateFromV1(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V1 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        preferences.put(ApexDoubleKey.MaxBasal, update.current.maxBasal)
        preferences.put(ApexDoubleKey.MaxBolus, update.current.maxBolus)
        apexPumpPlugin.updatePumpDescription()

        onAlarmsChanged(update)
        onBasalChanged(update)
        onReservoirChanged(update)
        onSystemStateChanged(status)

        // We may retrieve the forgotten in V1 alarm length from V2.
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == false) {
            onSettingsChanged(update)
            onBatteryChanged(update)
        }

        rxBus.send(EventApexPumpDataChanged())
    }

    private suspend fun onStatusV2(status: StatusV2) {
        val update = pump.updateFromV2(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V2 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        preferences.put(ApexStringKey.AlarmSoundLength, status.alarmLength!!.name)

        onSettingsChanged(update)
        onBatteryChanged(update)

        rxBus.send(EventApexPumpDataChanged())
    }

    private suspend fun onHeartbeat() {
        aapsLogger.debug(LTag.PUMPCOMM, "Got heartbeat")
        if (connectionStatus == ApexBLE.Status.DISCONNECTED) {
            aapsLogger.error(LTag.PUMPCOMM, "BUG: Got heartbeat but pump is disconnected!")
            return
        }

        status.addAction(ApexDriverStatus.Action.Heartbeat, R.string.action_heartbeat)
        if (!getStatus("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        if (!getBoluses("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        status.removeAction(ApexDriverStatus.Action.Heartbeat)

        status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
    }

    private fun onVersion(version: Version) {
        aapsLogger.debug(LTag.PUMPCOMM, "Got version - $version")
        trace.record(
            "pump_version",
            generation = linkState.generation,
            fields = mapOf(
                "firmwareMajor" to version.firmwareMajor,
                "firmwareMinor" to version.firmwareMinor,
                "protocolMajor" to version.protocolMajor,
                "protocolMinor" to version.protocolMinor,
                "supportedByLegacyMatrix" to version.isSupported(FIRST_SUPPORTED_PROTO, LAST_SUPPORTED_PROTO),
            ),
        )
    }

    private suspend fun onBolusEntry(entry: BolusEntry) {
        // Extended bolus entries do not have duration stored, do not use them.
        if (entry.extendedDose > 0) return

        aapsLogger.debug(LTag.PUMP, "Processing bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] on ${entry.dateTime}")

        if (entry.dateTime > lastBolusDateTime) {
            lastBolusDateTime = entry.dateTime
            pump.lastBolus = entry
            rxBus.send(EventApexPumpDataChanged())
        }

        // Find the bolus in history and sync it.
        // Pump may round up boluses, use 0.11 for failsafe.
        pump.inProgressBolus?.let {
            val delta = abs(entry.dateTime.millis - it.temporaryId)
            // Pump saves all boluses like they were issued on the 59th second of minute.
            // Considering that in the condition.
            if (delta <= 1000 || (delta in 57001..62999)) {
                aapsLogger.debug(LTag.PUMP, "Syncing current bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U]")
                val deltaU = abs(entry.standardDose * 0.025 - if (it.useFallbackDose) it.requestedDose else it.currentDose)
                if (!(it.cancelled || it.failed) && deltaU > 0.11) {
                    aapsLogger.debug(LTag.PUMP, "Not this bolus: $delta > 0.11")
                    return
                }

                val syncResult = pumpSync.syncBolusWithTempId(
                    timestamp = entry.dateTime.millis,
                    temporaryId = it.temporaryId,
                    amount = PumpInsulin(entry.standardPerformed * 0.025),
                    pumpId = entry.dateTime.millis,
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = it.detailedBolusInfo.bolusType,
                )
                aapsLogger.debug(LTag.PUMP, "Final bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] sync succeeded? $syncResult")
                if (!syncResult) {
                    pumpSync.syncBolusWithPumpId(
                        timestamp = entry.dateTime.millis,
                        pumpId = entry.dateTime.millis,
                        amount = PumpInsulin(entry.standardPerformed * 0.025),
                        pumpType = PumpType.APEX_TRUCARE_III,
                        pumpSerial = apexDeviceInfo.serialNumber,
                        type = it.detailedBolusInfo.bolusType,
                    )
                }
                it.completion.complete(Unit)
                if (pump.inProgressBolus === it) pump.inProgressBolus = null

                getStatus("ApexService-updateAfterBolus")
                return
            }
            if (entry.index < 2) return
        }

        // Otherwise, just sync the bolus with the DB
        pumpSync.syncBolusWithPumpId(
            timestamp = entry.dateTime.millis,
            pumpId = entry.dateTime.millis,
            amount = PumpInsulin(entry.standardPerformed * 0.025),
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            type = null,
        )
        aapsLogger.debug(LTag.PUMP, "Synced bolus ${entry.standardPerformed * 0.025}U on ${entry.dateTime}")
    }

    // !! Unreliable on 6.25 firmware, TODO: think about solution
    private suspend fun onTDDEntry(entry: TDDEntry) {
        // Ignore unreliable TDDs on 6.25 and older FWs
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) return

        pumpSync.createOrUpdateTotalDailyDose(
            timestamp = entry.dateTime.millis,
            pumpId = entry.dateTime.millis,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            bolusAmount = entry.bolus * 0.025,
            basalAmount = entry.basal * 0.025 + entry.temporaryBasal * 0.025,
            totalAmount = entry.total * 0.025,
        )
        aapsLogger.debug(LTag.PUMP, "Synced TDD ${entry.total * 0.025}U on ${entry.dateTime}")
    }

    //////// BLE

    private suspend fun onInitialConnection() {
        preferences.put(ApexDoubleKey.MaxBasal, 0.0)
        preferences.put(ApexDoubleKey.MaxBolus, 0.0)
        pumpSync.connectNewPump()

        if (isExperimentalControlEnabled()) {
            setConnectionProfile("ApexService-onInitialConnection")
        } else {
            trace.record("connection_profile_skipped", linkState.generation, fields = mapOf("reason" to "control_disabled"))
        }
    }

    fun startConnection() {
        if (apexDeviceInfo.serialNumber.isEmpty()) return
        commDirector.connect()
    }

    private var lastDisconnect = 0L
    fun disconnect(isReconnect: Boolean = false) {
        if (SystemClock.uptimeMillis() - lastDisconnect < 15000 && isReconnect) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Last disconnect was not long ago, skipping this one")
            return
        }

        lastDisconnect = SystemClock.uptimeMillis()
        commDirector.disconnect()
        if (isReconnect)
            commDirector.connect()
    }


    private suspend fun performHandshake(): ApexCommDirector.HandshakeResult {
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "onConnect")
            status.addAction(ApexDriverStatus.Action.Initializing, R.string.action_initializing)

            val prefFw = selectedFirmwareVersion()
            if (prefFw == FirmwareVersion.AUTO) {
                status.addAction(ApexDriverStatus.Action.GettingVersion, R.string.action_getting_version)
                val version = getValue(GetValue.Value.Version)?.firstOrNull()
                status.removeAction(ApexDriverStatus.Action.GettingVersion)

                if (version !is Version) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get version - disconnecting.")
                    return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
                }

                aapsLogger.debug(LTag.PUMPCOMM, version.toString())
                pump.firmwareVersion = version
            } else {
                pump.firmwareVersion = Version(prefFw.major, prefFw.minor, prefFw.protocolVersion)
                aapsLogger.debug(LTag.PUMPCOMM, "Manual version: ${prefFw.name}")
            }

            val version = pump.firmwareVersion!!
            onVersion(version)
            if (!version.isSupported(FIRST_SUPPORTED_PROTO, LAST_SUPPORTED_PROTO)) {
                aapsLogger.error(LTag.PUMPCOMM, "Unsupported protocol v${version.protocolMajor}.${version.protocolMinor} - disconnecting.")
                notificationManager.post(NotificationId.UNSUPPORTED_FIRMWARE, rh.gs(R.string.notification_pump_unsupported))
                trace.record("compatibility_block", linkState.generation, fields = mapOf("reason" to "protocol_range"))
                pump.isInitialized = false
                return ApexCommDirector.HandshakeResult.INCOMPATIBLE
            }
            if (!ApexCompatibility.isKnownLegacyVersion(version)) {
                aapsLogger.error(LTag.PUMPCOMM, "Unknown Apex firmware/protocol pair - diagnostic connection stopped.")
                notificationManager.post(NotificationId.UNSUPPORTED_FIRMWARE, rh.gs(R.string.notification_pump_unsupported))
                trace.record("compatibility_block", linkState.generation, fields = mapOf("reason" to "unknown_pair"))
                pump.isInitialized = false
                return ApexCommDirector.HandshakeResult.INCOMPATIBLE
            }

            if (isExperimentalControlEnabled()) {
                if (!syncDateTime("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to sync date and time - disconnecting.")
                    return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
                }
            } else {
                trace.record("date_sync_skipped", linkState.generation, fields = mapOf("reason" to "control_disabled"))
            }

            if (apexDeviceInfo.serialNumber != preferences.get(ApexStringKey.LastConnectedSerialNumber)) {
                onInitialConnection()
                preferences.put(ApexStringKey.LastConnectedSerialNumber, apexDeviceInfo.serialNumber)
            }

            if (!getStatus("BLE-onConnect")) {
                aapsLogger.error(LTag.PUMPCOMM, "Failed to get status - disconnecting.")
                return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
            }
            if (getBasalProfiles("BLE-onConnect") == null) {
                aapsLogger.error(LTag.PUMPCOMM, "Failed to get basal profiles - disconnecting.")
                return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
            }
            if (!getBoluses("BLE-onConnect")) {
                aapsLogger.error(LTag.PUMPCOMM, "Failed to get boluses - disconnecting.")
                return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
            }

            if (
                isExperimentalControlEnabled() &&
                pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) == true
            ) {
                if (!requestHeartbeat("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to notify about connection - disconnecting.")
                    return ApexCommDirector.HandshakeResult.RETRYABLE_FAILURE
                }
            } else {
                spawnHeartbeatLoop()
            }

            unreachableJob?.cancel()
            unreachableJob = null
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
            pump.isInitialized = true
            return ApexCommDirector.HandshakeResult.READY
        } finally {
            status.removeAction(ApexDriverStatus.Action.Initializing)
        }
    }

    private fun spawnHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                val now = DateTime.now()
                val msTillNextMinute = now.withSecondOfMinute(5).plus(HEARTBEAT_PERIOD_MINUTES * 60000L).millis - now.millis
                delay(msTillNextMinute)

                if (commDirector.linkState.value !is ApexCommDirector.LinkState.Ready) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Pump has been disconnected. Stopping thread")
                    return@launch
                }

                aapsLogger.debug(LTag.PUMPCOMM, "Triggering fake heartbeat")
                onHeartbeat()
            }
        }
    }

    private fun handleDisconnect() {
        aapsLogger.debug(LTag.PUMPCOMM, "onDisconnect")
        pump.isInitialized = false
        pump.inProgressBolus?.lockHistory = false
        pump.inProgressBolus?.useFallbackDose = true
        heartbeatJob?.cancel()
        lastConnectedTimestamp = System.currentTimeMillis()

        if (unreachableJob == null) {
            unreachableJob = serviceScope.launch {
                delay(120_000)
                if (commDirector.linkState.value is ApexCommDirector.LinkState.Ready) return@launch
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, rh.gs(R.string.error_pump_unreachable))
                aapsLogger.error(LTag.PUMP, "Pump unreachable!")
            }
        }
    }

    private fun createSpecialModeAlarm() {
        uiInteraction.runAlarm(
            rh.gs(R.string.bolus_error_pump_special_mode),
            rh.gs(R.string.pump_special_mode),
            app.aaps.core.ui.R.raw.boluserror
        )
    }

    private suspend fun processObject(obj: PumpObjectModel) {
        when (obj) {
            is CommandResponse -> onCommandResponse(obj)
            is StatusV1 -> onStatusV1(obj)
            is StatusV2 -> onStatusV2(obj)
            is Heartbeat -> onHeartbeat()
            is BolusEntry -> onBolusEntry(obj)
            is TDDEntry -> onTDDEntry(obj)
            is BasalProfile -> pump.updateBasalProfile(obj.index, obj.rates.map { it * 0.025 })
            else -> {}
        }
    }

    override suspend fun onHandshake(): ApexCommDirector.HandshakeResult {
        return performHandshake()
    }

    override fun onDisconnected(reason: String) {
        aapsLogger.debug(LTag.PUMPCOMM, "Apex disconnected: $reason")
        handleDisconnect()
    }

    override suspend fun onPumpData(value: PumpObjectModel) {
        processObject(value)
    }

    //////// Binder

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder {
        aapsLogger.debug(LTag.PUMP, "Binding service")
        return binder
    }

    inner class LocalBinder : Binder() {
        val serviceInstance: ApexService
            get() = this@ApexService
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        aapsLogger.debug(LTag.PUMP, "Service started")
        return START_STICKY
    }
}
