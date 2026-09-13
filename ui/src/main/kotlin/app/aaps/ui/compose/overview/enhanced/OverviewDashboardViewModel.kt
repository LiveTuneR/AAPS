package app.aaps.ui.compose.overview.enhanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.activity.ActivityState
import app.aaps.core.data.activity.ActivityAccess
import app.aaps.core.data.diagnostics.LoopHealthStatus
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.BS
import app.aaps.core.data.activity.ActivityCategory
import app.aaps.core.data.activity.ActivitySource
import app.aaps.core.interfaces.aps.AlgorithmDecisionSnapshot
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.*
import kotlinx.coroutines.channels.Channel
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.loopHealthSnapshot
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import java.util.Locale

data class DashboardField(val label: Int, val value: String?)
data class DashboardTile(val title: Int, val summary: String?, val fields: List<DashboardField>)

enum class OverviewSmbState { ON, WAIT, BLOCKED, OFF, UNKNOWN }

fun overviewAdjustmentFactor(decision: app.aaps.core.interfaces.aps.AlgorithmDecisionSnapshot?): String? =
    decision?.let { if (it.algorithm == "AUTO_ISF") it.autoIsfFactor else if (it.dynamicIsf) it.dynIsfAdjustmentFactor else null }
        ?.takeIf { it.isFinite() && it > 0 }
        ?.let { String.format(Locale.getDefault(),"%.0f%%",it*100) }

fun overviewSmbState(decision: app.aaps.core.interfaces.aps.AlgorithmDecisionSnapshot?): OverviewSmbState = when {
    decision == null -> OverviewSmbState.UNKNOWN
    !decision.smbConfigured -> OverviewSmbState.OFF
    decision.blockReason != null || decision.conditionEligible == false -> OverviewSmbState.BLOCKED
    decision.conditionEligible != true -> OverviewSmbState.UNKNOWN
    decision.intervalWaiting == true -> OverviewSmbState.WAIT
    decision.intervalWaiting == false -> OverviewSmbState.ON
    else -> OverviewSmbState.UNKNOWN
}
data class OverviewVitals(
    val units: String? = null,
    val isf: String? = null,
    val baseIsf: String? = null,
    val cr: String? = null,
    val autoIsf: String? = null,
    val activity: String? = null,
    val activityDetail: String? = null,
    val pumpConnected: Boolean? = null,
    val reservoir: String? = null,
    val battery: String? = null,
    val siteAge: String? = null,
    val siteWarning: Boolean = false,
    val sensorAge: String? = null,
    val bgAge: String? = null,
    val loopAge: String? = null,
    val syncAge: String? = null,
    val profile: String? = null,
    val algorithmTitle: Int = R.string.apex7_smb,
    val activityUpdatedAt: Long? = null,
    val smbState: OverviewSmbState = OverviewSmbState.UNKNOWN,
    val bgTimestamp: Long? = null,
    val loopTimestamp: Long? = null,
    val syncTimestamp: Long? = null,
    val decisionTimestamp: Long? = null,
    val activityPermissionRequired: Boolean = false,
)

data class OverviewDashboardState(val tiles: List<DashboardTile> = emptyList(), val capturedAt: Long? = null, val vitals: OverviewVitals = OverviewVitals())

@HiltViewModel
class OverviewDashboardViewModel @Inject constructor(
    private val calculator: IobCobCalculator,
    private val loop: Loop,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val persistence: PersistenceLayer,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val logger: AAPSLogger,
    private val activities: ActivityContextRepository,
    private val rxBus: RxBus
) : ViewModel() {
    val enabled = preferences.observe(BooleanKey.OverviewEnhanced)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val mutableState = MutableStateFlow(OverviewDashboardState())
    val state = mutableState.asStateFlow()
    private var lastLoggedHealth: LoopHealthStatus? = null
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        listOf(EventAPSCalculationFinished::class.java,EventLoopUpdateGui::class.java,EventPumpStatusChanged::class.java,
            EventQueueChanged::class.java,EventAutosensCalculationFinished::class.java,EventRefreshOverview::class.java,
            EventInitializationChanged::class.java).forEach { eventClass ->
            rxBus.toFlow(eventClass).collectResilient(viewModelScope,logger,LTag.CORE,streamName="overview-event") { refreshRequests.trySend(Unit) }
        }
        fun <T: Any> observe(type: Class<T>) {
            persistence.observeChanges(type).collectResilient(viewModelScope,logger,LTag.CORE,streamName="overview-history") { refreshRequests.trySend(Unit) }
        }
        observe(TT::class.java); observe(EPS::class.java); observe(PS::class.java); observe(TE::class.java); observe(CA::class.java)
        activities.changes.collectResilient(viewModelScope,logger,LTag.CORE,streamName="overview-activity") { refreshRequests.trySend(Unit) }
        viewModelScope.launch(Dispatchers.IO) {
            preferences.observe(BooleanKey.OverviewEnhanced).collectLatest { enabled ->
                if (enabled) {
                    activities.refresh()
                    var lastActivityRefresh = System.currentTimeMillis()
                    while (true) {
                        withTimeoutOrNull(2_000L) { refreshRequests.receive() }
                        try { refresh() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            mutableState.value = OverviewDashboardState()
                            logger.error(LTag.AUTOSENS, "Overview diagnostics unavailable: ${error.javaClass.simpleName}")
                        }
                        if (System.currentTimeMillis() - lastActivityRefresh >= 120_000L) {
                            activities.refresh()
                            lastActivityRefresh = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }

    private fun number(value: Double?): String? = value?.takeIf { it.isFinite() }?.let { String.format(Locale.getDefault(), "%.2f", it) }
    private fun time(value: Long?): String? = value?.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }
    private fun age(value: Long?, now: Long): String? = value?.takeIf { it > 0 && it <= now }?.let { rh.gs(R.string.apex7_minutes, (now - it) / 60_000) }
    private fun bool(value: Boolean) = rh.gs(if (value) R.string.apex7_yes else R.string.apex7_no)
    private fun milliseconds(value: Long?) = value?.takeIf { it >= 0 }?.let { rh.gs(R.string.apex7_milliseconds, it) }
    private fun durationSeconds(value: Double?): String? = value?.takeIf { it.isFinite() && it >= 0 }?.let {
        if (it >= 60.0 && it % 60.0 == 0.0) rh.gs(R.string.apex7_duration_minutes, (it / 60.0).toInt())
        else rh.gs(R.string.apex7_duration_seconds, it.toInt())
    }
    private fun compactAge(value: Long?, now: Long): String? = value?.takeIf { it > 0 && it <= now }?.let {
        val minutes = (now - it) / 60_000
        when {
            minutes >= 1440 -> rh.gs(R.string.apex7_days_hours, minutes / 1440, minutes % 1440 / 60)
            minutes >= 60 -> rh.gs(R.string.apex7_hours_minutes, minutes / 60, minutes % 60)
            else -> rh.gs(R.string.apex7_minutes, minutes)
        }
    }

    fun refreshActivity() { viewModelScope.launch(Dispatchers.IO) { activities.refresh(); refreshRequests.trySend(Unit) } }
    val activityPermissions: Set<String> get() = activities.requiredPermissions

    private fun reason(value: AlgorithmDecisionSnapshot.Reason?): String? = value?.let {
        val id = when (it) {
            AlgorithmDecisionSnapshot.Reason.INPUT_CONSTRAINT -> R.string.apex7_reason_input_constraint
            AlgorithmDecisionSnapshot.Reason.HIGH_TT_BLOCK -> R.string.apex7_reason_high_tt_block
            AlgorithmDecisionSnapshot.Reason.ALWAYS -> R.string.apex7_reason_always
            AlgorithmDecisionSnapshot.Reason.COB -> R.string.apex7_reason_cob
            AlgorithmDecisionSnapshot.Reason.RECENT_CARBS -> R.string.apex7_reason_recent_carbs
            AlgorithmDecisionSnapshot.Reason.TT -> R.string.apex7_reason_tt
            AlgorithmDecisionSnapshot.Reason.NO_CONDITION -> R.string.apex7_reason_no_condition
            AlgorithmDecisionSnapshot.Reason.PREDICTED_LOW -> R.string.apex7_reason_predicted_low
            AlgorithmDecisionSnapshot.Reason.EXCESSIVE_DELTA -> R.string.apex7_reason_excessive_delta
            AlgorithmDecisionSnapshot.Reason.IOB -> R.string.apex7_reason_iob
            AlgorithmDecisionSnapshot.Reason.INVALID_INPUT -> R.string.apex7_reason_invalid_input
            AlgorithmDecisionSnapshot.Reason.AUTO_FULL_LOOP -> R.string.apex7_reason_auto_full_loop
            AlgorithmDecisionSnapshot.Reason.AUTO_LOOP_DISABLED -> R.string.apex7_reason_auto_loop_disabled
        }
        "${rh.gs(id)}\n${it.name}"
    }

    private fun isfBasis(value: AlgorithmDecisionSnapshot.IsfBasis?): String? = value?.let {
        rh.gs(when (it) {
            AlgorithmDecisionSnapshot.IsfBasis.BLENDED_CURRENT_MIN_PREDICTED -> R.string.apex7_isf_basis_blended
            AlgorithmDecisionSnapshot.IsfBasis.CURRENT_BG -> R.string.apex7_isf_basis_current
            AlgorithmDecisionSnapshot.IsfBasis.MIN_PREDICTED_BG -> R.string.apex7_isf_basis_predicted
        })
    }

    private fun activityName(value: ActivityCategory?): String? = value?.let {
        rh.gs(when (it) {
            ActivityCategory.WALKING -> R.string.apex7_activity_walking
            ActivityCategory.RUNNING -> R.string.apex7_activity_running
            ActivityCategory.CYCLING -> R.string.apex7_activity_cycling
            ActivityCategory.POOL_SWIMMING -> R.string.apex7_activity_pool
            ActivityCategory.OPEN_WATER_SWIMMING -> R.string.apex7_activity_open_water
            ActivityCategory.OTHER -> R.string.apex7_activity_other
            ActivityCategory.UNKNOWN -> R.string.apex7_reason_unknown
        })
    }

    private fun activitySource(event: app.aaps.core.data.activity.ActivityEvent?): String? = event?.let {
        val label = when (it.source) {
            ActivitySource.SAMSUNG_HEALTH -> rh.gs(R.string.apex7_source_samsung)
            ActivitySource.PHONE -> rh.gs(R.string.apex7_source_phone)
            else -> rh.gs(R.string.apex7_reason_unknown)
        }
        listOfNotNull(label, it.sourcePackage).joinToString("\n")
    }

    private fun pumpState(value: String?): String? = value?.let {
        rh.gs(when (it) {
            "Ready" -> R.string.apex7_pump_ready
            "Connecting" -> R.string.apex7_pump_connecting
            "Handshaking" -> R.string.apex7_pump_handshaking
            "Backoff" -> R.string.apex7_pump_backoff
            "Disconnected" -> R.string.apex7_pump_disconnected
            "Stopped" -> R.string.apex7_pump_stopped
            "Incompatible" -> R.string.apex7_pump_incompatible
            else -> R.string.apex7_status_unknown
        })
    }

    private fun commandName(value: String?): String = value?.let {
        rh.gs(when (it) {
            "Bolus" -> R.string.apex7_command_bolus
            "CancelBolus" -> R.string.apex7_command_cancel_bolus
            "TemporaryBasal" -> R.string.apex7_command_tbr
            "CancelTemporaryBasal" -> R.string.apex7_command_cancel_tbr
            "GetValue" -> R.string.apex7_command_get_value
            else -> R.string.apex7_status_unknown
        })
    } ?: rh.gs(R.string.apex7_none)

    private fun smbStateText(value: OverviewSmbState): String = rh.gs(when (value) {
        OverviewSmbState.ON -> R.string.apex7_smb_on
        OverviewSmbState.WAIT -> R.string.apex7_smb_wait
        OverviewSmbState.BLOCKED -> R.string.apex7_smb_blocked
        OverviewSmbState.OFF -> R.string.apex7_smb_off
        OverviewSmbState.UNKNOWN -> R.string.apex7_smb_unknown
    })

    private fun bolusState(value: String?): String? = value?.let {
        rh.gs(when (it) {
            "PREPARED" -> R.string.apex7_bolus_state_prepared
            "COMMAND_SENT" -> R.string.apex7_bolus_state_sent
            "ACCEPTED" -> R.string.apex7_bolus_state_accepted
            "DELIVERING" -> R.string.apex7_bolus_state_delivering
            "LIVE_COMPLETED" -> R.string.apex7_bolus_state_live_completed
            "HISTORY_CONFIRMING" -> R.string.apex7_bolus_state_history
            "CONFIRMED_DELIVERED" -> R.string.apex7_bolus_state_confirmed
            "REJECTED_BEFORE_DELIVERY", "DEFINITELY_NOT_DELIVERED" -> R.string.apex7_bolus_state_not_delivered
            "CANCELLED_CONFIRMED" -> R.string.apex7_bolus_state_cancelled
            "PARTIALLY_DELIVERED_CONFIRMED" -> R.string.apex7_bolus_state_partial
            "DELIVERY_UNCERTAIN" -> R.string.apex7_bolus_state_uncertain
            "RECONCILIATION_REQUIRED" -> R.string.apex7_bolus_state_reconciliation
            else -> R.string.apex7_status_unknown
        })
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val profile = profileFunction.getProfile()
        val lastRun = loop.lastRun
        val request = lastRun?.request
        val decision = (request?.rawData() as? RT)?.decision
        val result = lastRun?.constraintsProcessed
        val snapshot = calculator.loopHealthSnapshot(loop)
        val health = snapshot.status(now)
        if (health != lastLoggedHealth) {
            logger.debug(LTag.AUTOSENS, "LoopHealth status=$health rawAgeMs=${snapshot.age(snapshot.newestRawBgTimestamp, now)} loopAgeMs=${snapshot.age(snapshot.lastBgTriggeredRun, now)} autosensAgeMs=${snapshot.age(snapshot.autosensLastDataTimestamp, now)} generation=${snapshot.activeWorkflowGeneration} skips=${snapshot.supersededAdsPublishSkipCount}")
            lastLoggedHealth = health
        }
        val pump = activePlugin.activePump
        val pumpDiagnostics = pump.readOnlyDiagnostics()
        val pumpKnown = pump.isInitialized()
        val site = persistence.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val sensor = persistence.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        val lastCarb = persistence.getNewestCarbs()
        val futureCarbs = persistence.getCarbsFromTimeExpanded(now, true).filter { it.timestamp > now }.sumOf { it.amount }
        val ads = calculator.ads
        val cob = synchronized(ads.dataLock) {
            ads.autosensDataTable.let { table -> if (table.size() > 0) table.valueAt(table.size() - 1).let { it.time to it.cob } else null }
        }
        val activity = activities.snapshot(now)
        val activityState = rh.gs(when (activity.state) {
            ActivityState.ACTIVE -> R.string.apex7_state_active
            ActivityState.POST_ACTIVITY -> R.string.apex7_state_post
            ActivityState.STALE_ACTIVITY -> R.string.apex7_state_stale
            ActivityState.NONE -> R.string.apex7_state_none
        })
        val event = activity.event
        val lastSmb = persistence.getNewestBolusOfType(BS.Type.SMB)
        val units = profileFunction.getUnits()
        fun isf(value: Double?) = value?.takeIf { it > 0 }?.let { number(profileUtil.fromMgdlToUnits(it, units)) }?.let { rh.gs(R.string.apex7_isf_units, it, units.asText) }
        fun recent(timestamp: Long?) = timestamp != null && now - timestamp in 0..660_000L
        fun tile(title: Int, summary: String?, vararg fields: Pair<Int, String?>) = DashboardTile(title, summary, fields.map { DashboardField(it.first, it.second) })
        val baseIsf = isf(profile?.getProfileIsfMgdl())
        val currentDynamicIsf = isf(decision?.currentDynamicIsfMgdl)
        val dosingIsf = isf(decision?.insulinReqIsfMgdl)
        val effectiveCr = number(request?.oapsProfile?.carb_ratio ?: request?.oapsProfileAutoIsf?.carb_ratio)
        val healthText = rh.gs(when (health) {
            LoopHealthStatus.UNKNOWN -> R.string.apex7_status_unknown
            LoopHealthStatus.HEALTHY -> R.string.apex7_status_healthy
            LoopHealthStatus.CALCULATING -> R.string.apex7_status_calculating
            LoopHealthStatus.DEGRADED -> R.string.apex7_status_degraded
            LoopHealthStatus.STALE -> R.string.apex7_status_stale
        })
        mutableState.value = OverviewDashboardState(listOf(
            tile(R.string.apex7_autoisf, when {
                request?.algorithm == app.aaps.core.interfaces.aps.APSResult.Algorithm.AUTO_ISF -> rh.gs(R.string.apex7_autoisf)
                decision?.dynamicIsf == true -> rh.gs(R.string.apex7_disf)
                request != null -> rh.gs(R.string.apex7_smb)
                else -> null
            },
                R.string.apex7_factor to number(if (decision?.algorithm=="AUTO_ISF") decision.autoIsfFactor else decision?.dynIsfAdjustmentFactor), R.string.apex7_base_isf to baseIsf,
                R.string.apex7_current_dynamic_isf to currentDynamicIsf, R.string.apex7_dosing_isf to dosingIsf,
                R.string.apex7_future_isf to isf(decision?.futureIsfMgdl),
                R.string.apex7_isf_basis to isfBasis(decision?.futureIsfBasis),
                R.string.apex7_tdd to number(decision?.tddU), R.string.apex7_insulin_divisor to decision?.insulinDivisor?.toString(),
                R.string.apex7_time to time(request?.date), R.string.apex7_age to age(request?.date, now),
                R.string.apex7_trace to activePlugin.activeAPS?.getSensitivityOverviewString()),
            tile(R.string.apex7_activity, activityState,
                R.string.apex7_shadow to rh.gs(R.string.apex7_shadow), R.string.apex7_access to rh.gs(when (activity.access) {
                    ActivityAccess.AVAILABLE -> R.string.apex7_access_available
                    ActivityAccess.NO_DATA -> R.string.apex7_access_no_data
                    ActivityAccess.HEALTH_CONNECT_UNAVAILABLE -> R.string.apex7_access_unavailable
                    ActivityAccess.PERMISSION_REQUIRED -> R.string.apex7_access_denied
                    ActivityAccess.ERROR -> R.string.apex7_access_error
                }),
                R.string.apex7_last_read to time(activity.lastSuccessfulRead),
                R.string.apex7_reachable to activity.watchReachable?.let { bool(it) } ?: rh.gs(R.string.apex7_watch_reachability_unavailable),
                R.string.apex7_clock_skew to bool(activity.clockSkew),
                R.string.apex7_source to activitySource(event), R.string.apex7_category to event?.let { "${activityName(it.category)}\n${it.rawType}" },
                R.string.apex7_device to event?.sourceDevice, R.string.apex7_start to time(event?.startTime), R.string.apex7_end to time(event?.endTime),
                R.string.apex7_received to time(event?.receivedAt), R.string.apex7_updated to time(event?.lastUpdatedAt),
                R.string.apex7_age to age(event?.lastUpdatedAt, now), R.string.apex7_latency to milliseconds(event?.detectionLatencyMs),
                R.string.apex7_duration to event?.let { milliseconds((it.endTime ?: now) - it.startTime) },
                R.string.apex7_read_latency to milliseconds(activity.readLatencyMs), R.string.apex7_steps to event?.steps?.toString(),
                R.string.apex7_hr to number(event?.heartRate), R.string.apex7_latest_hr to number(event?.latestHeartRate)),
            tile(R.string.apex7_iob, number(request?.iob?.iob)?.takeIf { recent(request?.date) }?.let { rh.gs(R.string.apex7_insulin_units, it) },
                R.string.apex7_total to number(request?.iob?.iob), R.string.apex7_basal to number(request?.iob?.basaliob),
                R.string.apex7_bolus to null, R.string.apex7_time to time(request?.date),
                R.string.apex7_insulin to profile?.iCfg?.insulinLabel, R.string.apex7_peak to profile?.iCfg?.peak?.toString(), R.string.apex7_dia to number(profile?.iCfg?.dia)),
            tile(R.string.apex7_isfcr, dosingIsf.takeIf { recent(request?.date) },
                R.string.apex7_base_isf to baseIsf, R.string.apex7_current_dynamic_isf to currentDynamicIsf,
                R.string.apex7_dosing_isf to dosingIsf, R.string.apex7_future_isf to isf(decision?.futureIsfMgdl),
                R.string.apex7_isf_basis to isfBasis(decision?.futureIsfBasis),
                R.string.apex7_base_cr to number(profile?.getIc()), R.string.apex7_effective_cr to effectiveCr,
                R.string.apex7_profile to profile?.percentage?.toString(), R.string.apex7_time to time(request?.date)),
            tile(R.string.apex7_cob, number(cob?.second)?.takeIf { recent(cob?.first) }?.let { rh.gs(R.string.apex7_carb_units, it) },
                R.string.apex7_total to number(cob?.second), R.string.apex7_time to time(cob?.first), R.string.apex7_age to age(cob?.first, now),
                R.string.apex7_carbs_future to number(futureCarbs), R.string.apex7_carbs_last to time(lastCarb?.timestamp)),
            tile(R.string.apex7_smb, age(lastSmb?.timestamp, now),
                R.string.apex7_enabled to bool(preferences.get(BooleanKey.ApsUseSmb)),
                R.string.apex7_smb_state to smbStateText(if (pumpDiagnostics?.bolusReconciliationRequired == true) OverviewSmbState.BLOCKED else overviewSmbState(decision.takeIf { recent(request?.date) })),
                R.string.apex7_condition_eligible to decision?.conditionEligible?.let { bool(it) },
                R.string.apex7_condition_reason to reason(decision?.conditionReason),
                R.string.apex7_block_reason to if (pumpDiagnostics?.bolusReconciliationRequired == true) rh.gs(R.string.apex7_bolus_unreconciled) else reason(decision?.blockReason),
                R.string.apex7_interval_waiting to decision?.intervalWaiting?.let { bool(it) },
                R.string.apex7_smb_interval to durationSeconds(decision?.intervalSeconds),
                R.string.apex7_smb_until_allowed to decision?.let { d -> if (d.intervalWaiting == true) durationSeconds(((d.intervalSeconds ?: 0.0) - (d.lastBolusAgeSeconds ?: 0.0)).coerceAtLeast(0.0)) else rh.gs(R.string.apex7_not_required) },
                R.string.apex7_smb_cap to number(decision?.maxBolusU),
                R.string.apex7_smb_requested to number(request?.smb),
                R.string.apex7_smb_constrained to number(result?.smb),
                R.string.apex7_smb_pump_command to when {
                    pumpDiagnostics?.bolusReconciliationRequired == true -> rh.gs(R.string.apex7_command_unresolved)
                    lastRun?.smbSetByPump?.queued == true -> rh.gs(R.string.apex7_command_queued)
                    lastRun?.smbSetByPump?.success == true -> rh.gs(R.string.apex7_command_confirmed)
                    lastRun?.smbSetByPump != null -> rh.gs(R.string.apex7_command_sent)
                    else -> rh.gs(R.string.apex7_command_not_requested)
                },
                R.string.apex7_smb_reported_delivered to lastRun?.smbSetByPump?.takeIf { !it.queued }?.let { number(it.bolusDelivered) },
                R.string.apex7_smb_confirmed_history to number(lastSmb?.amount),
                R.string.apex7_last_smb to time(lastSmb?.timestamp), R.string.apex7_time to time(result?.date),
                R.string.apex7_reason to rh.gs(R.string.apex7_bolus_unreconciled).takeIf { pumpDiagnostics?.bolusReconciliationRequired == true }),
            DashboardTile(R.string.apex7_pump, if (pumpDiagnostics?.bolusReconciliationRequired == true) rh.gs(R.string.apex7_bolus_uncertain) else if (pumpDiagnostics != null) rh.gs(R.string.apex7_pump_model_apex) else null, buildList {
                fun addField(label: Int, value: String?) { add(DashboardField(label,value)) }
                addField(R.string.apex7_connection,bool(pump.isConnected())); addField(R.string.apex7_reservoir,if(pumpKnown) number(pump.reservoirLevel.value.cU) else null)
                addField(R.string.apex7_battery,if(pumpKnown) pump.batteryLevel.value?.toString() else null); addField(R.string.apex7_sync,time(pump.lastDataTime.value))
                pumpDiagnostics?.let { d ->
                    addField(R.string.apex7_fsm,pumpState(d.linkState)); addField(R.string.apex7_generation,d.generation?.toString())
                    addField(R.string.apex7_pending,commandName(d.pendingCommand)); addField(R.string.apex7_queued,d.queuedCommands.toString())
                    addField(R.string.apex7_firmware,d.firmware); addField(R.string.apex7_protocol,d.protocol); addField(R.string.apex7_serial,d.maskedSerial)
                    addField(R.string.apex7_bolus_reconciliation,if(d.bolusReconciliationRequired) rh.gs(R.string.apex7_bolus_uncertain) else rh.gs(R.string.apex7_not_required))
                    if(d.bolusReconciliationRequired) {
                        addField(R.string.apex7_bolus_state,bolusState(d.bolusState)); addField(R.string.apex7_bolus_requested,number(d.bolusRequestedU))
                        addField(R.string.apex7_bolus_live,number(d.bolusLiveCompletedU) ?: rh.gs(R.string.apex7_no_data)); addField(R.string.apex7_bolus_history,number(d.bolusHistoryConfirmedU) ?: rh.gs(R.string.apex7_no_data))
                        addField(R.string.apex7_bolus_operation_age,age(d.bolusOperationCreatedUtc,now)); addField(R.string.apex7_bolus_last_reconcile,time(d.bolusLastReconciliationUtc) ?: rh.gs(R.string.apex7_no_data))
                        addField(R.string.apex7_bolus_gate,rh.gs(R.string.apex7_enabled_state))
                    }
                }
            }),
            tile(R.string.apex7_site, age(site?.timestamp, now), R.string.apex7_time to time(site?.timestamp),
                R.string.apex7_warning to preferences.get(IntKey.OverviewCageWarning).toString(),
                R.string.apex7_critical to preferences.get(IntKey.OverviewCageCritical).toString(), R.string.apex7_remaining to null),
            tile(R.string.apex7_sensor, age(sensor?.timestamp, now), R.string.apex7_start to time(sensor?.timestamp),
                R.string.apex7_raw to time(snapshot.newestRawBgTimestamp), R.string.apex7_age to age(snapshot.newestRawBgTimestamp, now),
                R.string.apex7_expiry to null, R.string.apex7_remaining to null),
            tile(R.string.apex7_loop, healthText,
                R.string.apex7_raw to time(snapshot.newestRawBgTimestamp), R.string.apex7_bucket to time(snapshot.newestBucketedBgTimestamp),
                R.string.apex7_calculation to time(snapshot.lastCalculationSuccessTimestamp), R.string.apex7_loop_run to time(snapshot.lastBgTriggeredRun),
                R.string.apex7_enact to time(snapshot.lastEnactTimestamp), R.string.apex7_ads to time(snapshot.autosensLastDataTimestamp),
                R.string.apex7_table to snapshot.autosensDataTableSize.toString(), R.string.apex7_missing to snapshot.firstMissingIndex?.toString(),
                R.string.apex7_generation to snapshot.activeWorkflowGeneration?.toString(), R.string.apex7_job to snapshot.currentWorkflowJob,
                R.string.apex7_duration to milliseconds(snapshot.calculationDuration(now)), R.string.apex7_anchor to time(snapshot.referenceTime),
                R.string.apex7_phase to snapshot.currentSensorPhaseOffsetMs?.toString(), R.string.apex7_skipped to snapshot.supersededAdsPublishSkipCount.toString(),
                R.string.apex7_metadata to snapshot.duplicateGlucoseMetadataEventCount.toString(), R.string.apex7_changes to snapshot.therapyRelevantGlucoseUpdateCount.toString())
        ), now, OverviewVitals(
            units = units.asText,
            isf = decision?.currentDynamicIsfMgdl?.takeIf { it > 0 && recent(request?.date) }?.let { number(profileUtil.fromMgdlToUnits(it, units)) },
            baseIsf = profile?.getProfileIsfMgdl()?.let { number(profileUtil.fromMgdlToUnits(it, units)) },
            cr = effectiveCr.takeIf { recent(request?.date) },
            // Use only the factor belonging to the ISF actually consumed by the algorithm.
            autoIsf = overviewAdjustmentFactor(decision.takeIf { recent(request?.date) }),
            algorithmTitle = when {
                request?.algorithm == app.aaps.core.interfaces.aps.APSResult.Algorithm.AUTO_ISF -> R.string.apex7_autoisf
                decision?.dynamicIsf == true -> R.string.apex7_disf
                else -> R.string.apex7_smb
            },
            activity = when (activity.access) {
                ActivityAccess.AVAILABLE -> activityName(event?.category) ?: rh.gs(R.string.apex7_activity_none)
                ActivityAccess.NO_DATA -> rh.gs(R.string.apex7_activity_none)
                ActivityAccess.PERMISSION_REQUIRED -> rh.gs(R.string.apex7_activity_permission)
                ActivityAccess.HEALTH_CONNECT_UNAVAILABLE, ActivityAccess.ERROR -> rh.gs(R.string.apex7_activity_unavailable)
            },
            activityDetail = event?.let { listOfNotNull(activitySource(it)?.substringBefore('\n'),activityName(it.category),compactAge(it.startTime,it.endTime ?: now)).joinToString(" · ") },
            activityUpdatedAt = event?.lastUpdatedAt ?: activity.lastSuccessfulRead,
            smbState = if (pumpDiagnostics?.bolusReconciliationRequired == true) OverviewSmbState.BLOCKED else overviewSmbState(decision.takeIf { recent(request?.date) }),
            bgTimestamp = snapshot.newestRawBgTimestamp,
            loopTimestamp = snapshot.lastCalculationSuccessTimestamp,
            syncTimestamp = pump.lastDataTime.value,
            decisionTimestamp = request?.date,
            pumpConnected = pump.isConnected().takeIf { pumpKnown },
            reservoir = if (pumpKnown) number(pump.reservoirLevel.value.cU)?.let { rh.gs(R.string.apex7_insulin_units, it) } else null,
            battery = if (pumpKnown) pump.batteryLevel.value?.let { "$it%" } else null,
            siteAge = compactAge(site?.timestamp, now),
            siteWarning = site?.timestamp?.let { now - it >= preferences.get(IntKey.OverviewCageWarning) * 3_600_000L } ?: false,
            sensorAge = compactAge(sensor?.timestamp, now),
            bgAge = compactAge(snapshot.newestRawBgTimestamp, now),
            loopAge = compactAge(snapshot.lastCalculationSuccessTimestamp, now),
            syncAge = compactAge(pump.lastDataTime.value, now),
            profile = profile?.percentage?.let { "$it%" },
            activityPermissionRequired = activity.access == ActivityAccess.PERMISSION_REQUIRED,
        ))
    }
}
