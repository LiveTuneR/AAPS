package app.aaps.ui.compose.overview.enhanced

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.*
import app.aaps.ui.compose.overview.graphs.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.aaps.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w400dp-h850dp")
class EnhancedOverviewContentTest {
    @get:Rule val compose = createComposeRule()
    private val titles = listOf(R.string.apex7_autoisf, R.string.apex7_activity, R.string.apex7_iob,
        R.string.apex7_isfcr, R.string.apex7_cob, R.string.apex7_smb, R.string.apex7_pump,
        R.string.apex7_site, R.string.apex7_sensor, R.string.apex7_loop)

    private val now = 1_789_200_000_000L
    private fun render(warning: Boolean = false, missingActivity: Boolean = false, disconnected: Boolean = false, missingAll: Boolean = false, tempTarget: Boolean = false, smb: OverviewSmbState = OverviewSmbState.ON) {
        val english = RuntimeEnvironment.getApplication().resources.configuration.locales[0].language == "en"
        val state = OverviewDashboardState(titles.map { title ->
            DashboardTile(title, null, listOf(DashboardField(R.string.apex7_generation, if (title == R.string.apex7_loop) "12" else null)))
        }.map { tile -> tile.copy(summary = if (missingAll) null else when (tile.title) { R.string.apex7_iob -> "1,6 Е"; R.string.apex7_cob -> "22 г"; else -> null }) }, now,
            if (missingAll) OverviewVitals() else OverviewVitals(
                units = if (english) "mmol/L" else "ммоль/л", isf = "2,4", baseIsf = "2,7", cr = "6,4", autoIsf = "2,4", algorithmTitle = R.string.apex7_disf,
                activity = if (missingActivity) null else if (english) "light" else "лёгкая", activityDetail = if (missingActivity) null else if (english) "Walking / 24 min" else "Ходьба · 24 мин",
                activityUpdatedAt = if (missingActivity) null else now - 32_000L, smbState = smb,
                pumpConnected = !disconnected, reservoir = "142 Е", battery = "87%", siteAge = "2 д 10 ч", siteWarning = warning,
                sensorAge = "5 д 16 ч", bgAge = if (warning) "14 мин" else "42 с", loopAge = "38 с", syncAge = "23 с", profile = "100%"))
        val bg = BgInfoUiState(if (missingAll) null else BgInfoData(5.6, "5,6", BgRange.IN_RANGE, warning, now - 120_000,
            TrendArrow.FLAT, "Ровно", 0.0, "0,0", null, null, null, null), if (warning) "14 мин назад" else "2 мин назад")
        val vm = mock<GraphViewModel>()
        val points = (0..72).map { index -> BgDataPoint(now - 120_000L - (72 - index) * 300_000L, 5.6 + kotlin.math.sin((72 - index) / 8.0), BgRange.IN_RANGE, BgType.BUCKETED) }
        whenever(vm.graphConfigFlow).thenReturn(MutableStateFlow(GraphConfig(bgOverlays = listOf(SeriesType.PREDICTIONS), iobOverlays = emptyList())))
        whenever(vm.nowTimestamp).thenReturn(MutableStateFlow(now))
        whenever(vm.derivedTimeRange).thenReturn(MutableStateFlow((now - 21_600_000L) to (now + 3_600_000L)))
        whenever(vm.bgInfoState).thenReturn(MutableStateFlow(bg))
        whenever(vm.bgReadingsFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.bucketedDataFlow).thenReturn(MutableStateFlow(points))
        whenever(vm.predictionsFlow).thenReturn(MutableStateFlow((1..12).map { BgDataPoint(now + it * 300_000L, 5.6 - it * 0.05, BgRange.IN_RANGE, BgType.IOB_PREDICTION) }))
        whenever(vm.chartConfigFlow).thenReturn(MutableStateFlow(ChartConfig(10.0, 3.9)))
        whenever(vm.tempTargetFlow).thenReturn(MutableStateFlow(TempTargetDisplayData(if (tempTarget) "6,0 - 7,0" else "5,5 - 6,3", if (tempTarget) TempTargetState.ACTIVE else TempTargetState.NONE, now - 60_000, if (tempTarget) 3_600_000 else 0)))
        whenever(vm.basalGraphFlow).thenReturn(MutableStateFlow(BasalGraphData(points.map { GraphDataPoint(it.timestamp, 1.1) }, points.map { GraphDataPoint(it.timestamp, 1.1) }, 1.1)))
        whenever(vm.targetLineFlow).thenReturn(MutableStateFlow(TargetLineData(points.map { GraphDataPoint(it.timestamp, if (tempTarget) 6.5 else 5.9) })))
        whenever(vm.epsGraphFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.activityGraphFlow).thenReturn(MutableStateFlow(ActivityGraphData(emptyList(), emptyList())))
        whenever(vm.treatmentGraphFlow).thenReturn(MutableStateFlow(TreatmentGraphData(emptyList(), emptyList(), emptyList(), emptyList())))
        whenever(vm.runningModeGraphFlow).thenReturn(MutableStateFlow(RunningModeGraphData(emptyList())))
        whenever(vm.iobGraphFlow).thenReturn(MutableStateFlow(IobGraphData(points.mapIndexed { index, p -> GraphDataPoint(p.timestamp, 1.6 + kotlin.math.sin((72 - index) / 8.0)) }, emptyList())))
        whenever(vm.cobGraphFlow).thenReturn(MutableStateFlow(CobGraphData(points.mapIndexed { i, p -> GraphDataPoint(p.timestamp, 22.0 * i / 72) }, emptyList())))
        val preferences = mock<Preferences>()
        whenever(preferences.observe(StringKey.GeneralDarkMode)).thenReturn(MutableStateFlow("dark"))
        compose.setContent { CompositionLocalProvider(LocalPreferences provides preferences) { AapsTheme { Surface {
            EnhancedOverviewContent(state, bg, if (missingAll) null else if (tempTarget) "6,0 - 7,0 до 13:30" else "5,5 - 6,3", targetActive = tempTarget, smbEnabled = !warning,
                modeNotice = if (warning) "Цикл приостановлен" else null,
                graphs = { GraphsSection(vm, false, minimumBgHeight = 180, referenceStyle = true) })
        } } } }
    }

    private fun capture(name: String) {
        if (System.getenv("APEX7_CAPTURE_SCREENSHOTS") != "true") return
        compose.waitForIdle()
        // Robolectric does not complete Compose's hardware forceRedraw handshake.
        // Render the actual topmost test window, including a modal when present.
        val bitmap = compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/reports/apex7-screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun unknownFieldsAreNotReplacedWithZeroAndAllDetailsOpen() {
        render(missingAll = true)
        capture("overview-missing-data-fixture")
        val context = RuntimeEnvironment.getApplication()
        for (title in titles) {
            compose.onNodeWithTag("detail-$title").performScrollTo().performClick()
            capture("detail-${context.resources.getResourceEntryName(title)}-en-synthetic")
            compose.onNodeWithContentDescription(context.getString(R.string.apex7_close)).assertIsDisplayed().performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("detail-${R.string.apex7_autoisf}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.apex7_unknown)).assertExists()
    }

    @Test @Config(qualifiers = "ru-w800dp-h480dp")
    fun russianLandscapeKeepsDetailsReachable() {
        render()
        val context = RuntimeEnvironment.getApplication()
        capture("overview-landscape-fixture")
        compose.onNodeWithTag("detail-${R.string.apex7_loop}").performScrollTo().performClick()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.apex7_close)).assertIsDisplayed()
        capture("loop-ru-landscape-synthetic")
    }

    @Test fun portraitNormalMatchesReferenceHierarchy() {
        render()
        val bg = compose.onNodeWithTag("overview-bg").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("detail-${R.string.apex7_target_short}").fetchSemanticsNode().boundsInRoot
        val activity = compose.onNodeWithTag("detail-${R.string.apex7_activity}").fetchSemanticsNode().boundsInRoot
        val pump = compose.onNodeWithTag("detail-${R.string.apex7_pump}").fetchSemanticsNode().boundsInRoot
        val graph = compose.onNodeWithTag("overview-graphs").fetchSemanticsNode().boundsInRoot
        assertTrue(bg.right <= target.left)
        assertTrue(activity.top >= bg.bottom)
        assertTrue(pump.top >= activity.bottom)
        assertTrue(graph.top > pump.bottom)
        assertTrue(graph.top < 550 * RuntimeEnvironment.getApplication().resources.displayMetrics.density)
        capture("overview-portrait-normal-fixture")
    }
    @Test fun portraitWarnings() { render(warning = true); capture("overview-portrait-warnings-fixture") }
    @Test fun portraitSmbWait() { render(smb = OverviewSmbState.WAIT); compose.onNodeWithText("SMB WAIT").assertExists(); capture("overview-portrait-wait-fixture") }
    @Test fun portraitSmbOff() { render(smb = OverviewSmbState.OFF); compose.onNodeWithText("SMB OFF").assertExists(); capture("overview-portrait-off-fixture") }
    @Test @Config(qualifiers = "en-w400dp-h850dp")
    fun englishPortrait() { render(); capture("overview-portrait-en-fixture") }
    @Test fun largeFontKeepsSingleStatusRow() {
        RuntimeEnvironment.setFontScale(1.5f)
        try { render(); capture("overview-large-font-fixture") }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }
    @Test fun activityAgeAndAdaptiveTileAreVisibleAndStatusesShareOneRow() {
        render()
        compose.onNodeWithTag("activity-updated", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("dISF").assertIsDisplayed()
        val statuses = listOf(R.string.apex7_smb, R.string.apex7_loop, R.string.apex7_sync_short, R.string.apex7_profile)
            .map { compose.onNodeWithTag("detail-$it").fetchSemanticsNode().boundsInRoot }
        assertTrue(statuses.all { kotlin.math.abs(it.top - statuses.first().top) < 1f })
        assertTrue(statuses.all { kotlin.math.abs(it.bottom - statuses.first().bottom) < 1f })
        capture("overview-status-and-activity-fixture")
    }
    @Test fun portraitMissingActivity() { render(missingActivity = true); capture("overview-portrait-no-activity-fixture") }
    @Test fun portraitDisconnectedPump() { render(disconnected = true); capture("overview-portrait-disconnected-fixture") }
    @Test fun rightLegendsAndRangeSelection() {
        render()
        val bgLegend = compose.onNodeWithTag("legend-bg").fetchSemanticsNode().boundsInRoot
        val iobLegend = compose.onNodeWithTag("legend-iob").fetchSemanticsNode().boundsInRoot
        val cobLegend = compose.onNodeWithTag("legend-secondary-0").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(bgLegend.left - iobLegend.left) < 1f)
        assertTrue(kotlin.math.abs(bgLegend.left - cobLegend.left) < 1f)
        for (hours in listOf(3, 12, 24, 6)) {
            compose.onNodeWithTag("graph-range-$hours").performScrollTo().performClick().assertIsSelected()
        }
        capture("overview-range-controls-fixture")
    }
    @Test fun activeTemporaryTargetKeepsValueAndExpiry() {
        render(tempTarget = true)
        compose.onNodeWithText("Врем. цель").assertExists()
        compose.onNodeWithText("6,0 - 7,0 до 13:30").assertExists()
        capture("overview-temp-target-fixture")
    }

}
