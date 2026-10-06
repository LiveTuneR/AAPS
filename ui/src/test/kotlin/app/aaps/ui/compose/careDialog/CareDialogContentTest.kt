package app.aaps.ui.compose.careDialog

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.ui.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric composable test for [CareDialogContent] (BG Check): renders the meter-type options and
 *  fires the meter-type callback — headless on the JVM, no emulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CareDialogContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var meterLabel: String
    private lateinit var sensorLabel: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        meterLabel = ctx.getString(R.string.bg_meter)
        sensorLabel = ctx.getString(R.string.bg_sensor)
    }

    @Test
    fun rendersBgCheckMeterOptionsAndFiresMeterTypeChange() {
        var meterChanged = false
        compose.setContent {
            MaterialTheme {
                CareDialogContent(
                    uiState = CareDialogUiState(),
                    eventType = CareportalEventType.BGCHECK,
                    dateString = "2024-01-01",
                    timeString = "12:00",
                    onMeterTypeChange = { meterChanged = true },
                    onBgValueChange = {},
                    onDurationChange = {},
                    onNotesChange = {},
                    onNavigateBack = {},
                    onConfirmClick = {},
                    onDateClick = {},
                    onTimeClick = {}
                )
            }
        }

        compose.onNodeWithText(meterLabel).assertIsDisplayed()
        compose.onNodeWithText(sensorLabel).assertIsDisplayed()

        compose.onNodeWithText(sensorLabel).performClick()
        assertThat(meterChanged).isTrue()
    }

    @Test fun sensorPlanShowsSelectedEndAndKeepsDateAndTimeEditable() {
        var dateClicks = 0; var timeClicks = 0
        val start = 1_791_288_000_000L
        compose.setContent { MaterialTheme {
            CareDialogContent(
                uiState = CareDialogUiState(eventType = CareportalEventType.SENSOR_INSERT, eventTime = start,
                    plannedSensorEnd = start + 14 * 86_400_000L),
                eventType = CareportalEventType.SENSOR_INSERT, dateString = "2026-10-06", timeString = "12:00",
                onMeterTypeChange = {}, onBgValueChange = {}, onDurationChange = {}, onNotesChange = {},
                onNavigateBack = {}, onConfirmClick = {}, onDateClick = {}, onTimeClick = {},
                plannedEndDateString = "2026-10-20", plannedEndTimeString = "18:30",
                onEndDateClick = { dateClicks++ }, onEndTimeClick = { timeClicks++ }
            )
        } }
        compose.onNodeWithText("2026-10-20").assertIsDisplayed().performClick()
        compose.onNodeWithText("18:30").assertIsDisplayed().performClick()
        assertThat(dateClicks).isEqualTo(1)
        assertThat(timeClicks).isEqualTo(1)
    }
}
