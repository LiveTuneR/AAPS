package app.aaps.ui.compose.wizardDialog

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.utils.DecimalFormatter
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR

/** Robolectric composable test for [WizardDialogContent]: renders + fires navigate-back (Close). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WizardDialogContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val decimalFormatter: DecimalFormatter = mock()

    private lateinit var closeLabel: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        closeLabel = ctx.getString(CoreUiR.string.close)
    }

    @Test
    fun rendersAndFiresNavigateBack() {
        var back = false
        render(WizardDialogUiState()) { back = true }
        compose.onNodeWithContentDescription(closeLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(closeLabel).performClick()
        assertThat(back).isTrue()
    }

    @Test fun staleActionIsDisabledWhilePreviewIsLoading() {
        render(WizardDialogUiState(okVisible = true, hasResult = true, isCalculating = true))
        val loading = RuntimeEnvironment.getApplication().getString(CoreUiR.string.loading)
        compose.onNodeWithText(loading).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun failedPreviewShowsAnExplanation() {
        render(WizardDialogUiState(calculationFailed = true))
        val message = RuntimeEnvironment.getApplication().getString(app.aaps.ui.R.string.wizard_preview_failed)
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    private fun render(state: WizardDialogUiState, onBack: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                WizardDialogContent(
                    uiState = state,
                    decimalFormatter = decimalFormatter,
                    unitsLabel = "mg/dl",
                    onBgChange = {},
                    onCarbsChange = {},
                    onAddCarbs = {},
                    onCarbsTypeChange = {},
                    onPercentageChange = {},
                    onDirectCorrectionChange = {},
                    onCarbTimeChange = {},
                    onNotesChange = {},
                    onProfileSelect = {},
                    onBgToggle = {},
                    onTTToggle = {},
                    onTrendToggle = {},
                    onIOBToggle = {},
                    onCOBToggle = {},
                    onAlarmToggle = {},
                    onCalculationExpandToggle = {},
                    onNavigateBack = onBack,
                    onConfirmClick = {},
                    onSettingsClick = {}
                )
            }
        }
    }
}
