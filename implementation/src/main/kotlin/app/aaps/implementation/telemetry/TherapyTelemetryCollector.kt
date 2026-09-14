package app.aaps.implementation.telemetry

import app.aaps.core.data.model.*
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.telemetry.TherapyEventType
import app.aaps.core.interfaces.telemetry.TherapyTelemetry
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class TherapyTelemetryCollector @Inject constructor(
    private val telemetry: TherapyTelemetry,
    private val persistence: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val logger: AAPSLogger
) {
    private val started=AtomicBoolean()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val settingsLock=Mutex()

    fun start() {
        if (!started.compareAndSet(false,true)) return
        telemetry.start()
        scope.launch {
            try { settings("PROCESS_START"); pumpState() }
            catch (error: Exception) { logger.error(LTag.CORE,"Telemetry startup snapshot failed type=${error.javaClass.simpleName}") }
        }
        app.aaps.core.keys.TherapyPreferenceFlows.observe(preferences).forEach { (name,flow) ->
            var previous=flow.value
            flow.drop(1).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-settings") { value ->
                telemetry.record(TherapyEventType.SETTINGS_CHANGE,JSONObject().put("setting",name).put("oldValue",previous)
                    .put("newValue",value).put("source","PREFERENCE_FLOW"))
                previous=value
                settings("PREFERENCE_CHANGE")
            }
        }
        persistence.observeChanges(GV::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-GV") { values ->
            values.forEach { value ->
                val classification=activePlugin.activeIobCobCalculator.ads.classifyCompletedGlucose(value)
                telemetry.record(TherapyEventType.CGM,JSONObject().put("rawBgTimestamp",value.timestamp).put("glucoseMgdl",value.value)
                    .put("raw",value.raw ?: JSONObject.NULL).put("noise",value.noise ?: JSONObject.NULL).put("isValid",value.isValid)
                    .put("trend",value.trendArrow.name).put("sourceSensor",value.sourceSensor.name).put("rowId",value.id).put("version",value.version)
                    .put("displayUnits",preferences.get(StringKey.GeneralUnits))
                    .put("metadataOnly",classification==app.aaps.core.data.diagnostics.GlucoseChange.METADATA_ONLY)
                    .put("changeClassification",classification.name)
                    .put("cgmAgeMs",System.currentTimeMillis()-value.timestamp))
            }
        }
        persistence.observeChanges(CA::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-CA") { list -> list.forEach {
            timeline("CARBS",it.id,it.version,it.timestamp,it.isValid,JSONObject().put("carbs",it.amount).put("durationMs",it.duration))
        } }
        persistence.observeChanges(BS::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-BS") { list -> list.forEach {
            timeline(it.type.name,it.id,it.version,it.timestamp,it.isValid,JSONObject().put("amountU",it.amount).put("insulinType",it.iCfg.insulinLabel)
                .put("diaHours",it.iCfg.dia).put("peakMinutes",it.iCfg.peak).put("pumpId",it.ids.pumpId ?: JSONObject.NULL)
                .put("temporaryId",it.ids.temporaryId ?: JSONObject.NULL).put("model",it.ids.pumpType?.name ?: JSONObject.NULL))
        } }
        persistence.observeChanges(TB::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-TB") { list -> list.forEach {
            timeline("TBR",it.id,it.version,it.timestamp,it.isValid,JSONObject().put("rate",it.rate).put("isAbsolute",it.isAbsolute).put("durationMs",it.duration))
        } }
        persistence.observeChanges(EB::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-EB") { list -> list.forEach {
            timeline("EXTENDED_BOLUS",it.id,it.version,it.timestamp,it.isValid,JSONObject().put("amountU",it.amount).put("durationMs",it.duration))
        } }
        persistence.observeChanges(TT::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-TT") { list -> list.forEach {
            timeline("TEMP_TARGET",it.id,it.version,it.timestamp,it.isValid,JSONObject().put("targetLow",it.lowTarget).put("targetHigh",it.highTarget).put("durationMs",it.duration))
        } }
        persistence.observeChanges(EPS::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-EPS") { list -> list.forEach {
            timeline("EFFECTIVE_PROFILE",it.id,it.version,it.timestamp,it.isValid,JSONObject())
        }; settings("EFFECTIVE_PROFILE") }
        persistence.observeChanges(PS::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-PS") { list -> list.forEach {
            timeline("PROFILE_SWITCH",it.id,it.version,it.timestamp,it.isValid,JSONObject())
        }; settings("PROFILE_SWITCH") }
        persistence.observeChanges(TE::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-TE") { list -> list.forEach {
            timeline(it.type.name,it.id,it.version,it.timestamp,it.isValid,JSONObject())
        } }
        rxBus.toFlow(EventPumpStatusChanged::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-pump") {
            pumpState()
        }
        rxBus.toFlow(app.aaps.core.interfaces.rx.events.EventQueueChanged::class.java).collectResilient(scope,logger,LTag.CORE,streamName="telemetry-queue") {
            pumpState()
        }
    }

    private fun pumpState() {
        val pump=activePlugin.activePump
        val diagnostic=pump.readOnlyDiagnostics()
        telemetry.record(TherapyEventType.PUMP_STATE,JSONObject().put("model",pump.model().name).put("connected",pump.isConnected())
            .put("initialized",pump.isInitialized()).put("suspended",pump.isSuspended()).put("lastCommunication",pump.lastDataTime.value)
            .put("reservoirPumpUnits",pump.reservoirLevel.value.cU).put("battery",pump.batteryLevel.value ?: JSONObject.NULL)
            .put("firmware",diagnostic?.firmware ?: JSONObject.NULL).put("protocol",diagnostic?.protocol ?: JSONObject.NULL)
            .put("linkState",diagnostic?.linkState ?: JSONObject.NULL).put("linkGeneration",diagnostic?.generation ?: JSONObject.NULL)
            .put("queueDepth",diagnostic?.queuedCommands ?: JSONObject.NULL).put("pendingCommand",diagnostic?.pendingCommand ?: JSONObject.NULL)
            .put("pendingAgeMs",diagnostic?.pendingAgeMs ?: JSONObject.NULL).put("progressAgeMs",diagnostic?.progressAgeMs ?: JSONObject.NULL))
    }

    private fun timeline(type: String,id: Long,version: Int,timestamp: Long,valid: Boolean,values: JSONObject) {
        telemetry.record(TherapyEventType.THERAPY_EVENT,values.put("type",type).put("rowId",id).put("version",version)
            .put("eventTimestamp",timestamp).put("isValid",valid),correlationId="history:$type:$id:$version")
    }

    private suspend fun settings(source: String) = settingsLock.withLock {
        val profile=profileFunction.getProfile()
        val json=JSONObject().put("source",source).put("profileAvailable",profile!=null)
        val values=JSONArray()
        app.aaps.core.keys.TherapyPreferenceFlows.observe(preferences).forEach { (name,flow) -> values.put(JSONObject().put("setting",name).put("newValue",flow.value)) }
        json.put("changes",values).put("settingsSchemaVersion",1)
        val pump=activePlugin.activePump.pumpDescription
        json.put("pumpModel",pump.pumpType.name).put("bolusIncrement",pump.bolusStep).put("basalIncrement",pump.basalStep)
            .put("basalMaximumRate",pump.basalMaximumRate).put("basalMinimumRate",pump.basalMinimumRate)
            .put("maxTempAbsolute",pump.maxTempAbsolute).put("maxTempPercent",pump.maxTempPercent)
        json.put("profileName",profileFunction.getProfileName())
        profile?.let {
            fun values(array: Array<app.aaps.core.interfaces.profile.Profile.ProfileValue>) = JSONArray().apply {
                array.forEach { row -> put(JSONObject().put("seconds",row.timeAsSeconds).put("value",row.value)) }
            }
            json.put("profilePercentage",it.percentage).put("timeshift",it.timeshift).put("basalSchedule",values(it.getBasalValues()))
                .put("isfSchedule",values(it.getIsfsMgdlValues())).put("crSchedule",values(it.getIcsValues()))
                .put("targetSchedule",JSONArray().apply { it.getSingleTargetsMgdl().forEach { row ->
                    put(JSONObject().put("seconds",row.timeAsSeconds).put("value",row.value)
                        .put("low",it.getTargetLowMgdlTimeFromMidnight(row.timeAsSeconds))
                        .put("high",it.getTargetHighMgdlTimeFromMidnight(row.timeAsSeconds)))
                } })
                .put("diaHours",it.iCfg.dia).put("insulinType",it.iCfg.insulinLabel)
                .put("peakMinutes",it.iCfg.peak)
        }
        telemetry.record(TherapyEventType.SETTINGS_SNAPSHOT,json)
    }
}
