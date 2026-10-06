package app.aaps.ui.compose.careDialog

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.ui.compose.overview.enhanced.overviewSensorPlannedEnd
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SensorPlanTest {
    private val start = 1_791_288_000_000L
    private val end = start + 14 * 86_400_000L

    @Test fun `chosen end survives existing therapy duration and reload`() {
        val state = CareDialogUiState(eventType = CareportalEventType.SENSOR_INSERT, eventTime = start, plannedSensorEnd = end)
        assertTrue(state.sensorPlanValid)
        val stored = TE(timestamp = start, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL,
            duration = state.plannedSensorDurationMinutes!!.toLong() * 60_000)
        assertEquals(end, overviewSensorPlannedEnd(stored))
        // Reloaded event still retains its end after expiry; presentation can show overdue.
        assertEquals(end, overviewSensorPlannedEnd(stored.copy()))
    }

    @Test fun `invalid end cannot produce a negative or overflowing duration`() {
        for (endValue in listOf(start, start - 60_000, Long.MAX_VALUE)) {
            val state = CareDialogUiState(eventType = CareportalEventType.SENSOR_INSERT, eventTime = start, plannedSensorEnd = endValue)
            assertFalse(state.sensorPlanValid)
            assertNull(state.plannedSensorDurationMinutes)
        }
        assertNull(overviewSensorPlannedEnd(TE(timestamp = Long.MAX_VALUE, duration = 60_000,
            type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)))
    }

    @Test fun `unset legacy timer and non sensor event remain distinct from a plan`() {
        val state = CareDialogUiState(eventType = CareportalEventType.SENSOR_INSERT, eventTime = start)
        assertTrue(state.sensorPlanValid)
        assertNull(state.plannedSensorDurationMinutes)
        assertNull(overviewSensorPlannedEnd(TE(timestamp = start, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)))
        assertNull(state.copy(eventType = CareportalEventType.EXERCISE, plannedSensorEnd = end).plannedSensorDurationMinutes)
    }
}
