package app.aaps.ui.compose.overview.enhanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.activity.ActivityState
import app.aaps.core.data.activity.ActivityAccess
import app.aaps.core.data.diagnostics.LoopHealthStatus
import app.aaps.core.data.model.TE
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale

data class DashboardField(val label: Int, val value: String?)
data class DashboardTile(val title: Int, val summary: String?, val fields: List<DashboardField>)
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
    val profile: String? = null
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
    private val activities: ActivityContextRepository
) : ViewModel() {
    val enabled = preferences.observe(BooleanKey.OverviewEnhanced)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val mutableState = MutableStateFlow(OverviewDashboardState())
    val state = mutableState.asStateFlow()
    private var lastLoggedHealth: LoopHealthStatus? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.observe(BooleanKey.OverviewEnhanced).collectLatest { enabled ->
                if (enabled) while (isActive) {
                    try { refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        mutableState.value = OverviewDashboardState()
                        logger.error(LTag.AUTOSENS, "Overview diagnostics unavailable: ${error.javaClass.simpleName}")
                    }
                    delay(30_000)
                }
            }
        }
    }

    private fun number(value: Double?): String? = value?.takeIf { it.isFinite() }?.let { String.format(Locale.getDefault(), "%.2f", it) }
    private fun time(value: Long?): String? = value?.takeIf { it > 0 }?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }
    private fun age(value: Long?, now: Long): String? = value?.takeIf { it > 0 && it <= now }?.let { rh.gs(R.string.apex7_minutes, (now - it) / 60_000) }
    private fun bool(value: Boolean) = rh.gs(if (value) R.string.apex7_yes else R.string.apex7_no)
    private fun milliseconds(value: Long?) = value?.takeIf { it >= 0 }?.let { rh.gs(R.string.apex7_milliseconds, it) }
    private fun compactAge(value: Long?, now: Long): String? = value?.takeIf { it > 0 && it <= now }?.let {
        val minutes = (now - it) / 60_000
        when {
            minutes >= 1440 -> rh.gs(R.string.apex7_days_hours, minutes / 1440, minutes % 1440 / 60)
            minutes >= 60 -> rh.gs(R.string.apex7_hours_minutes, minutes / 60, minutes % 60)
            else -> rh.gs(R.string.apex7_minutes, minutes)
        }
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
            tile(R.string.apex7_autoisf, request?.algorithm?.name,
                R.string.apex7_factor to null, R.string.apex7_base_isf to baseIsf,
                R.string.apex7_current_dynamic_isf to currentDynamicIsf, R.string.apex7_dosing_isf to dosingIsf,
                R.string.apex7_time to time(request?.date), R.string.apex7_age to age(request?.date, now),
                R.string.apex7_trace to activePlugin.activeAPS?.getSensitivityOverviewString()),
            tile(R.string.apex7_activity, activityState,
                R.string.apex7_shadow to rh.gs(R.string.apex7_shadow), R.string.apex7_access to rh.gs(when (activity.access) {
                    ActivityAccess.SDK_NOT_CONFIGURED -> R.string.apex7_sdk_missing
                    ActivityAccess.AVAILABLE -> R.string.apex7_access_available
                    ActivityAccess.UNAVAILABLE -> R.string.apex7_access_unavailable
                    ActivityAccess.PERMISSION_DENIED -> R.string.apex7_access_denied
                    ActivityAccess.ERROR -> R.string.apex7_access_error
                }),
                R.string.apex7_last_read to time(activity.lastSuccessfulRead),
                R.string.apex7_reachable to activity.watchReachable?.let { bool(it) },
                R.string.apex7_clock_skew to bool(activity.clockSkew),
                R.string.apex7_source to event?.source?.name, R.string.apex7_category to event?.let { "${it.category.name} / ${it.rawType}" },
                R.string.apex7_device to event?.sourceDevice, R.string.apex7_start to time(event?.startTime), R.string.apex7_end to time(event?.endTime),
                R.string.apex7_received to time(event?.receivedAt), R.string.apex7_updated to time(event?.lastUpdatedAt),
                R.string.apex7_age to age(event?.lastUpdatedAt, now), R.string.apex7_latency to milliseconds(event?.detectionLatencyMs),
                R.string.apex7_duration to event?.let { milliseconds((it.endTime ?: now) - it.startTime) },
                R.string.apex7_steps to event?.steps?.toString(), R.string.apex7_hr to number(event?.heartRate)),
            tile(R.string.apex7_iob, number(request?.iob?.iob)?.takeIf { recent(request?.date) }?.let { rh.gs(R.string.apex7_insulin_units, it) },
                R.string.apex7_total to number(request?.iob?.iob), R.string.apex7_basal to number(request?.iob?.basaliob),
                R.string.apex7_bolus to null, R.string.apex7_time to time(request?.date),
                R.string.apex7_insulin to profile?.iCfg?.insulinLabel, R.string.apex7_peak to profile?.iCfg?.peak?.toString(), R.string.apex7_dia to number(profile?.iCfg?.dia)),
            tile(R.string.apex7_isfcr, dosingIsf.takeIf { recent(request?.date) },
                R.string.apex7_base_isf to baseIsf, R.string.apex7_current_dynamic_isf to currentDynamicIsf,
                R.string.apex7_dosing_isf to dosingIsf, R.string.apex7_future_isf to isf(decision?.futureIsfMgdl),
                R.string.apex7_isf_basis to decision?.futureIsfBasis?.name,
                R.string.apex7_base_cr to number(profile?.getIc()), R.string.apex7_effective_cr to effectiveCr,
                R.string.apex7_profile to profile?.percentage?.toString(), R.string.apex7_time to time(request?.date)),
            tile(R.string.apex7_cob, number(cob?.second)?.takeIf { recent(cob?.first) }?.let { rh.gs(R.string.apex7_carb_units, it) },
                R.string.apex7_total to number(cob?.second), R.string.apex7_time to time(cob?.first), R.string.apex7_age to age(cob?.first, now),
                R.string.apex7_carbs_future to number(futureCarbs), R.string.apex7_carbs_last to time(lastCarb?.timestamp)),
            tile(R.string.apex7_smb, age(lastRun?.lastSMBEnact, now),
                R.string.apex7_enabled to bool(preferences.get(BooleanKey.ApsUseSmb)),
                R.string.apex7_condition_eligible to decision?.conditionEligible?.let { bool(it) },
                R.string.apex7_condition_reason to decision?.conditionReason?.name,
                R.string.apex7_block_reason to decision?.blockReason?.name,
                R.string.apex7_interval_waiting to decision?.intervalWaiting?.let { bool(it) },
                R.string.apex7_smb_cap to number(decision?.maxBolusU),
                R.string.apex7_smb_requested to number(request?.smb),
                R.string.apex7_smb_constrained to number(result?.smb),
                R.string.apex7_smb_reported_delivered to lastRun?.smbSetByPump?.takeIf { !it.queued }?.let { number(it.bolusDelivered) },
                R.string.apex7_last_smb to time(lastRun?.lastSMBEnact), R.string.apex7_time to time(result?.date),
                R.string.apex7_reason to listOfNotNull(result?.reason, result?.smbConstraint?.getReasons()).joinToString("\n").ifBlank { null }),
            tile(R.string.apex7_pump, pump.model().name,
                R.string.apex7_connection to bool(pump.isConnected()), R.string.apex7_reservoir to if (pumpKnown) number(pump.reservoirLevel.value.cU) else null,
                R.string.apex7_battery to if (pumpKnown) pump.batteryLevel.value?.toString() else null, R.string.apex7_sync to time(pump.lastDataTime.value),
                R.string.apex7_fsm to pumpDiagnostics?.linkState, R.string.apex7_generation to pumpDiagnostics?.generation?.toString(),
                R.string.apex7_pending to pumpDiagnostics?.pendingCommand, R.string.apex7_queued to pumpDiagnostics?.queuedCommands?.toString(),
                R.string.apex7_firmware to pumpDiagnostics?.firmware, R.string.apex7_protocol to pumpDiagnostics?.protocol,
                R.string.apex7_serial to pumpDiagnostics?.maskedSerial),
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
            // No structured final AutoISF factor exists yet. Never substitute an autosens ratio.
            autoIsf = null,
            activity = activityState.takeIf { activity.access == ActivityAccess.AVAILABLE },
            activityDetail = event?.let { listOfNotNull(it.category.name, compactAge(it.startTime, it.endTime ?: now)).joinToString(" / ") },
            pumpConnected = pump.isConnected().takeIf { pumpKnown },
            reservoir = if (pumpKnown) number(pump.reservoirLevel.value.cU)?.let { rh.gs(R.string.apex7_insulin_units, it) } else null,
            battery = if (pumpKnown) pump.batteryLevel.value?.let { "$it%" } else null,
            siteAge = compactAge(site?.timestamp, now),
            siteWarning = site?.timestamp?.let { now - it >= preferences.get(IntKey.OverviewCageWarning) * 3_600_000L } ?: false,
            sensorAge = compactAge(sensor?.timestamp, now),
            bgAge = compactAge(snapshot.newestRawBgTimestamp, now),
            loopAge = compactAge(snapshot.lastBgTriggeredRun, now),
            syncAge = compactAge(pump.lastDataTime.value, now),
            profile = profile?.percentage?.let { "$it%" }
        ))
    }
}
