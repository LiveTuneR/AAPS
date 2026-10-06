package app.aaps.ui.compose.careDialog

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE

@Immutable
data class CareDialogUiState(
    val eventType: CareportalEventType = CareportalEventType.BGCHECK,

    // BG section (visible for BGCHECK, QUESTION, ANNOUNCEMENT)
    val meterType: TE.MeterType = TE.MeterType.FINGER,
    val bgValue: Double = 0.0,

    // Duration section (visible for NOTE, EXERCISE)
    val duration: Double = 0.0,

    // Notes section
    val notes: String = "",

    // Date/Time (always visible)
    val eventTime: Long = System.currentTimeMillis(),
    val eventTimeChanged: Boolean = false,
    // User-chosen replacement plan, stored as the sensor-change event's duration.
    val plannedSensorEnd: Long? = null,

    // Config values
    val glucoseUnits: GlucoseUnit = GlucoseUnit.MGDL,
    val showNotesFromPreferences: Boolean = false,
    val siteRotationManageCgm: Boolean = false,

    // Site rotation (visible for SENSOR_INSERT when siteRotationManageCgm enabled)
    val siteLocation: TE.Location = TE.Location.NONE,
    val siteArrow: TE.Arrow = TE.Arrow.NONE,
    val lastSiteLocationString: String? = null,
    val selectedSiteLocationString: String? = null,
    val siteRotationEntries: List<TE> = emptyList()
)

val CareDialogUiState.sensorPlanValid: Boolean
    get() = plannedSensorEnd == null || plannedSensorDurationMinutes != null

val CareDialogUiState.plannedSensorDurationMinutes: Int?
    get() {
        val end = plannedSensorEnd ?: return null
        if (eventType != CareportalEventType.SENSOR_INSERT || end <= eventTime) return null
        val difference = try { Math.subtractExact(end, eventTime) } catch (_: ArithmeticException) { return null }
        val minutes = difference / 60_000L
        return minutes.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

/** BG section visible for BGCHECK, QUESTION, ANNOUNCEMENT */
val CareDialogUiState.showBgSection: Boolean
    get() = eventType in setOf(
        CareportalEventType.BGCHECK,
        CareportalEventType.QUESTION,
        CareportalEventType.ANNOUNCEMENT
    )

/** Duration section visible for NOTE, EXERCISE */
val CareDialogUiState.showDurationSection: Boolean
    get() = eventType in setOf(
        CareportalEventType.NOTE,
        CareportalEventType.EXERCISE
    )

/** Site rotation section visible for SENSOR_INSERT when CGM site rotation is enabled */
val CareDialogUiState.showSiteRotationSection: Boolean
    get() = eventType == CareportalEventType.SENSOR_INSERT && siteRotationManageCgm

/** Notes always visible for NOTE, QUESTION, ANNOUNCEMENT, EXERCISE (independent of prefs) */
val CareDialogUiState.showNotesSection: Boolean
    get() = eventType in setOf(
        CareportalEventType.NOTE,
        CareportalEventType.QUESTION,
        CareportalEventType.ANNOUNCEMENT,
        CareportalEventType.EXERCISE
    ) || showNotesFromPreferences
