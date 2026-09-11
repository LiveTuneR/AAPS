package app.aaps.ui.compose.overview.enhanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp")
class EnhancedOverviewContentTest {
    @get:Rule val compose = createComposeRule()
    private val titles = listOf(R.string.apex7_autoisf, R.string.apex7_activity, R.string.apex7_iob,
        R.string.apex7_isfcr, R.string.apex7_cob, R.string.apex7_smb, R.string.apex7_pump,
        R.string.apex7_site, R.string.apex7_sensor, R.string.apex7_loop)

    private fun render() {
        val state = OverviewDashboardState(titles.map { title ->
            DashboardTile(title, null, listOf(DashboardField(R.string.apex7_generation, if (title == R.string.apex7_loop) "12" else null)))
        })
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { EnhancedOverviewContent(state) } } }
    }

    private fun capture(name: String) {
        if (System.getenv("APEX7_CAPTURE_SCREENSHOTS") != "true") return
        compose.waitForIdle()
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val output = File("build/reports/apex7-screenshots/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun unknownFieldsAreNotReplacedWithZeroAndAllDetailsOpen() {
        render()
        capture("overview-en-portrait-synthetic")
        val context = RuntimeEnvironment.getApplication()
        for (title in titles) {
            compose.onNodeWithText(context.getString(title)).performScrollTo().performClick()
            capture("detail-${context.resources.getResourceEntryName(title)}-en-synthetic")
            compose.onNodeWithContentDescription(context.getString(R.string.apex7_close)).assertIsDisplayed().performClick()
            compose.waitForIdle()
        }
        compose.onAllNodesWithText(context.getString(R.string.apex7_unknown)).assertCountEquals(10)
    }

    @Test @Config(qualifiers = "ru-w800dp-h480dp")
    fun russianLandscapeKeepsDetailsReachable() {
        render()
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(context.getString(R.string.apex7_loop)).performScrollTo().performClick()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.apex7_close)).assertIsDisplayed()
        capture("loop-ru-landscape-synthetic")
    }

}
