package app.aaps.pump.apex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.compose.ApexComposeContent
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.pump.apex.utils.toApexReadableProfile
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
class ApexPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue,
    val context: Context,
    preferences: Preferences,
    val rxBus: RxBus,
    val aapsSchedulers: AapsSchedulers,
    val fabricPrivacy: FabricPrivacy,
    val pumpEnactResultProvider: Provider<PumpEnactResult>,
    val dateUtil: DateUtil,
    val pump: ApexPump,
    val config: Config,
    private val constraintsChecker: ConstraintsChecker,
    private val commDirector: ApexCommDirector,
    private val trace: ApexTrace,
): PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .composeContent { plugin ->
            ApexComposeContent(
                pluginName = rh.gs(R.string.apex_plugin_name),
                plugin = plugin as ApexPumpPlugin,
                pump = pump,
                commDirector = commDirector,
                trace = trace,
                preferences = preferences,
            )
        }
        .icon(Icons.Filled.Bluetooth)
        .pluginName(R.string.apex_plugin_name)
        .shortName(R.string.apex_plugin_shortname)
        .description(R.string.apex_plugin_description),
    ownPreferences = listOf(
        ApexBooleanKey::class.java,
        ApexDoubleKey::class.java,
        ApexStringKey::class.java
    ),
    aapsLogger, rh, preferences, commandQueue
), Pump, PluginConstraints {

    private val disposable = CompositeDisposable()

    private var service: ApexService? = null
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            service = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as ApexService.LocalBinder
            this@ApexPumpPlugin.service = mLocalBinder.serviceInstance.also {
                it.startConnection()
            }
        }
    }


    override suspend fun onStart() {
        aapsLogger.debug(LTag.PUMP, "Starting APEX plugin")
        super.onStart()
        context.bindService(Intent(context, ApexService::class.java), connection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(connection) }, fabricPrivacy::logException)
        aapsLogger.debug(LTag.PUMP, "Started APEX plugin")
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "Stopping APEX plugin")

        service?.disconnect()
        context.unbindService(connection)
        disposable.clear()

        super.onStop()
        aapsLogger.debug(LTag.PUMP, "Stopped APEX plugin")
    }

    override val isFakingTempsByExtendedBoluses = false
    override fun canHandleDST() = false

    override fun manufacturer() = ManufacturerType.Apex
    override fun model() = PumpType.APEX_TRUCARE_III
    override fun readOnlyDiagnostics(): app.aaps.core.data.diagnostics.PumpDiagnosticState {
        val snapshot = commDirector.diagnosticSnapshot()
        val version = pump.firmwareVersion
        return app.aaps.core.data.diagnostics.PumpDiagnosticState(
            snapshot.state, snapshot.generation, snapshot.queuedCommands, snapshot.pendingCommand,
            snapshot.pendingAgeMs, snapshot.progressAgeMs,
            version?.let { "${it.firmwareMajor}.${it.firmwareMinor}" },
            version?.let { "${it.protocolMajor}.${it.protocolMinor}" },
            serialNumber().takeIf { it.isNotBlank() }?.let { "***${it.takeLast(3)}" }
        )
    }
    override fun serialNumber() = preferences.get(ApexStringKey.LastConnectedSerialNumber)

    override val baseBasalRate: PumpRate
        get() = PumpRate(pump.basal?.rate ?: 0.0)
    override val reservoirLevel: StateFlow<PumpInsulin> = pump.reservoirFlow
    override val batteryLevel: StateFlow<Int?> = pump.batteryFlow
    override val lastDataTime: StateFlow<Long> = pump.lastDataTimeFlow
    override val lastBolusTime: StateFlow<Long?> = pump.lastBolusTimeFlow
    override val lastBolusAmount: StateFlow<PumpInsulin?> = pump.lastBolusAmountFlow
    override val pumpDescription = PumpDescription().fillFor(model())

    override fun isBusy(): Boolean {
        val snapshot = commDirector.diagnosticSnapshot()
        return pump.isBolusing || snapshot.pendingCommand != null || snapshot.queuedCommands > 0 ||
            service?.linkState is ApexCommDirector.LinkState.Handshaking
    }
    override fun isSuspended() = pump.isSuspended
    override fun isInitialized() = pump.isInitialized && service != null
    override fun isConnecting() = when (service?.linkState) {
        is ApexCommDirector.LinkState.Connecting,
        is ApexCommDirector.LinkState.Handshaking,
        is ApexCommDirector.LinkState.Backoff -> true
        else -> false
    }
    override fun isHandshakeInProgress() = service?.linkState is ApexCommDirector.LinkState.Handshaking
    override fun isConnected() = service?.linkState is ApexCommDirector.LinkState.Ready
    override fun isBatteryChangeLoggingEnabled() = preferences.get(ApexBooleanKey.LogBatteryChange)
    // We should be always connected to the pump.
    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered connect: $reason")
        service?.startConnection()
    }
    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered disconnect: $reason")
    }
    override fun stopConnecting() {
        aapsLogger.debug(LTag.PUMP, "Triggered stopConnecting")
        if (isConnecting()) service?.disconnect()
    }

    fun reconnectForUi() {
        service?.disconnect()
        service?.startConnection()
    }

    suspend fun refreshForUi() {
        service?.getStatus("ApexComposeContent", force = true)
    }

    fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        val now = System.currentTimeMillis()
        if (!isInitialized() || !isConnected()) return JSONObject()

        val date = pump.dateTime
        if (date.millis + 60 * 60 * 1000L < System.currentTimeMillis()) {
            return JSONObject()
        }
        val status = pump.status!!

        val pumpJson = JSONObject()
        val statusJson = JSONObject()
        val extendedJson = JSONObject()
        try {
            statusJson.put(
                "status", if (!isSuspended()) "normal"
                else if (isInitialized() && isSuspended()) "suspended"
                else "inactive"
            )
            statusJson.put("timestamp", dateUtil.toISOString(date.millis))
            val lastBolus = pump.lastBolus
            if (lastBolus != null) {
                extendedJson.put("lastBolus", dateUtil.dateAndTimeString(lastBolus.dateTime.millis))
                extendedJson.put("lastBolusAmount", lastBolus.standardPerformed * 0.025)
            }
            if (status.tbr != null) {
                extendedJson.put("TempBasalAbsoluteRate", status.tbr.rate ?: (baseBasalRate.cU * status.tbr.percentage!! / 100.0))
                extendedJson.put("TempBasalStart", dateUtil.dateAndTimeString(now - status.tbr.elapsedMinutes * 1000))
                extendedJson.put("TempBasalRemaining", status.tbr.elapsedMinutes - status.tbr.durationMinutes)
            }
            extendedJson.put("BaseBasalRate", baseBasalRate.cU)
            try {
                extendedJson.put("ActiveProfile", profileName)
            } catch (_: Exception) {}
            pumpJson.put("status", statusJson)
            pumpJson.put("extended", extendedJson)
            pumpJson.put("reservoir", status.reservoirLevel.toInt())
            pumpJson.put("clock", dateUtil.toISOString(now))
        } catch (e: JSONException) {
            aapsLogger.error(LTag.PUMP, "Unhandled exception: $e")
        }
        return pumpJson
    }

    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        if (!isInitialized() || !isConnected()) return rh.gs(app.aaps.pump.common.R.string.pump_status_not_initialized)
        val status = pump.status!!

        val ret = "${rh.gs(R.string.status_conn_status)}: ${service!!.connectionStatus.toLocalString(rh)}\n" +
            "${rh.gs(R.string.status_pump_status)}: ${status.getPumpStatus(rh)}\n" +
            "${rh.gs(R.string.status_last_bolus)}: ${pump.lastBolus?.toShortLocalString(rh) ?: "-"}\n" +
            "${rh.gs(R.string.status_tbr)}: ${status.getTBR(rh)}\n" +
            "${rh.gs(R.string.status_basal)}: ${status.getBasal(rh)}\n" +
            "${rh.gs(R.string.status_reservoir)}: ${status.getReservoirLevel(rh)}\n" +
            "${rh.gs(R.string.status_battery)}: ${status.getBatteryLevel(rh)}"

        aapsLogger.debug(LTag.PUMP, "Short status: $ret")
        return ret
    }

    fun updatePumpDescription() {
        aapsLogger.debug(LTag.PUMP, "Updating pump description")
        pumpDescription.maxTempAbsolute = pump.maxBasal
        pumpDescription.basalMaximumRate = pump.maxBasal
    }

    override suspend fun loadTDDs(): PumpEnactResult {
        val ret = pumpEnactResultProvider.get()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        val status = service!!.getTDDs("ApexPumpPlugin-loadTDDs")
        return ret.apply {
            success = status
            enacted = status
        }
    }

    override suspend fun getPumpStatus(reason: String) {
        if (!isInitialized() || !isConnected()) return
        aapsLogger.debug(LTag.PUMP, "Requested pump status cause of $reason")
        if (!service!!.getStatus("ApexPumpPlugin-getPumpStatus")) return
    }

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        val ret = pumpEnactResultProvider.get()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        var result = service!!.updateBasalPatternIndex(ApexService.USED_BASAL_PATTERN_INDEX, "ApexPumpPlugin-setNewBasalProfile")
        if (!result) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_failed_to_switch_basal_profile_index)
            }
        }

        result = service!!.updateCurrentBasalPattern(profile.toApexReadableProfile(), "ApexPumpPlugin-setNewBasalProfile")
        if (!result) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_failed_to_update_basal_profile)
            }
        }

        return ret.apply {
            success = true
            enacted = true
        }
    }

    @Synchronized
    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (!isInitialized() || !isConnected()) return true
        val pumpBasalProfile = pump.basalProfiles[ApexService.USED_BASAL_PATTERN_INDEX] ?: return true
        if (pumpBasalProfile.size != 48) return true
        for (i in 0..<48) {
            val profileBasal = profile.getBasalTimeFromMidnight(i * 30 * 60)
            val pumpBasal = pumpBasalProfile[i]
            val deviation = DoseStepSize.Apex.getStepSizeForAmount(profileBasal) * 0.8
            val diff = abs(pumpBasal - profileBasal)
            if (diff > deviation) {
                aapsLogger.info(LTag.PUMP, "Profiles are not same ($diff > $deviation): profile[${String.format("%02d", i / 2)}:${String.format("%02d", (i % 2) * 30)}] $profileBasal != pump[$i] $pumpBasal")
                return false
            }
        }
        return true
    }

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Insulin value must be greater than 0
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }
        val pumpEnactResult = pumpEnactResultProvider.get()
        detailedBolusInfo.insulin = constraintsChecker
            .applyBolusConstraints(ConstraintObject(detailedBolusInfo.insulin, aapsLogger))
            .value()

        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val bolus = service!!.bolus(detailedBolusInfo, "ApexPumpPlugin-deliverTreatment")
            ?: return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_bolus_start_failed)
            }

        return pumpEnactResult.apply {
            success = !bolus.failed
            enacted = bolus.currentDose > 0.024
            bolusDelivered = bolus.currentDose
        }
    }

    @Synchronized
    override fun stopBolusDelivering() {
        aapsLogger.debug(LTag.PUMP, "Cancel bolus")
        if (!isInitialized() || !isConnected()) {
            aapsLogger.debug(LTag.PUMP, "Cannot stop bolus, pump is disconnected")
            return
        }
        service!!.cancelBolusAsync("ApexPumpPlugin-stopBolusDelivering")
    }

    override suspend fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val pumpEnactResult = pumpEnactResultProvider.get()
        val rate = absoluteRate
        val duration = durationInMinutes - durationInMinutes % 15

        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = pump.status!!
        if (enforceNew && status.tbr != null) {
            val result = service!!.cancelTemporaryBasal("ApexPumpPlugin-setTempBasal")
            if (!result) {
                return pumpEnactResult.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.error_tbr_cancel_failed)
                }
            }
        }

        val result = service!!.temporaryBasal(rate, duration, tbrType, "ApexPumpPlugin-setTempBasal")
        if (!result) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_set_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        return pumpEnactResultProvider.get().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_only_absolute_supported)
        }
    }

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val pumpEnactResult = pumpEnactResultProvider.get()
        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = service!!.cancelTemporaryBasal("ApexPumpPlugin-cancelTempBasal")
        if (!status) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_cancel_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        // Not yet supported
        return pumpEnactResultProvider.get().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    override suspend fun cancelExtendedBolus(): PumpEnactResult {
        // Not yet supported
        return pumpEnactResultProvider.get().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        if (!isInitialized() || !isConnected()) return
        service!!.syncDateTime("ApexService-timezoneOrDSTChanged")
    }

    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        insulin.setIfSmaller(pump.maxBolus, rh.gs(app.aaps.core.ui.R.string.limitingbolus, pump.maxBolus, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)

        // Pump starts a "No dosage" alarm on 5.0U reservoir level.
        // Add 0.1U here to not trigger alarm.
        val realReservoirLevel = pump.reservoirLevel - 5.1
        insulin.setIfSmaller(realReservoirLevel, rh.gs(app.aaps.core.ui.R.string.limitingbolus, realReservoirLevel, rh.gs(R.string.pumplimit_reservoir)), this)
        return insulin
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        absoluteRate.setIfSmaller(pump.maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, pump.maxBasal, rh.gs(app.aaps.core.ui.R.string.pumplimit)), this)
        return super.applyBasalConstraints(absoluteRate, profile)
    }

    override fun hasPreferences(): Boolean = true

    override fun getPreferenceScreenContent(): PreferenceSubScreenDef {
        val versions = FirmwareVersion.realValues.filter {
            if (it.engineeringModeOnly)
                config.isEngineeringMode()
            else
                true
        }

        val firmwareEntries = linkedMapOf(FirmwareVersion.AUTO.name to rh.gs(R.string.auto)).apply {
            versions.forEach { put(it.name, it.displayName) }
        }

        return PreferenceSubScreenDef(
            key = "apex_settings",
            titleResId = R.string.apex_settings,
            items = listOf(
                ApexStringKey.SerialNumber,
                ApexStringKey.FirmwareVer.withEntries(firmwareEntries),
                ApexStringKey.AlarmSoundLength,
                ApexBooleanKey.CalculateBatteryPercentage,
                ApexStringKey.CalcBatteryType,
                ApexDoubleKey.BatteryLowVoltage,
                ApexDoubleKey.BatteryHighVoltage,
                ApexDoubleKey.MaxBasal,
                ApexDoubleKey.MaxBolus,
                ApexBooleanKey.LogInsulinChange,
                ApexBooleanKey.LogBatteryChange,
                ApexBooleanKey.HideSerial,
                ApexBooleanKey.EnableExperimentalControl,
            ),
            icon = pluginDescription.icon
        )
    }
}
