package app.aaps.ui.compose.wizardDialog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.aaps.core.data.model.BolusWizardData
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.BolusWizard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
internal class WizardDialogViewModelTest {

    @Mock private lateinit var constraintChecker: ConstraintsChecker
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var profileRepository: ProfileRepository
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var ch: ConcentrationHelper
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var config: Config
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var runningModeGuard: RunningModeGuard
    @Mock private lateinit var automation: Automation
    @Mock private lateinit var wizardExecutor: WizardExecutor
    @Mock private lateinit var rxBus: RxBus

    private val bolusWizardProvider: Provider<BolusWizard> = mock()

    private lateinit var sut: WizardDialogViewModel
    private val mainDispatcher = StandardTestDispatcher()
    private val previewDispatcher = StandardTestDispatcher(mainDispatcher.scheduler, "WizardPreview")
    private val store = ViewModelStore()
    private val template: BolusWizard = mock()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // init { viewModelScope.launch { initialize() } } is deferred by StandardTestDispatcher, so construction
        // touches no collaborators and the internal BolusWizard stays null; the pure state flips below don't need it.
        Dispatchers.setMain(mainDispatcher)
        sut = WizardDialogViewModel(
            SavedStateHandle(), bolusWizardProvider, constraintChecker, profileFunction, profileUtil,
            profileRepository, activePlugin, ch, iobCobCalculator, persistenceLayer, preferences, config,
            rh, dateUtil, decimalFormatter, aapsLogger, runningModeGuard, automation, wizardExecutor, rxBus,
            CoroutineScope(previewDispatcher)
        )
        store.put("wizard", sut)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        mainDispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private suspend fun setupPreview(answer: suspend (Int) -> BolusWizard = { result(it) }) {
        val profiles: ProfileStore = mock()
        whenever(profiles.getProfileList()).thenReturn(arrayListOf())
        whenever(profileRepository.profile).thenReturn(MutableStateFlow(profiles))
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(profileFunction.getProfile()).thenReturn(mock<EffectiveProfile>())
        whenever(profileFunction.getProfileName()).thenReturn("Active")
        whenever(rh.gs(app.aaps.core.ui.R.string.active)).thenReturn("Active")
        val pump: PumpWithConcentration = mock()
        whenever(pump.pumpDescription).thenReturn(PumpDescription())
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(iobCobCalculator.ads).thenReturn(mock<AutosensDataStore>())
        whenever(constraintChecker.getMaxCarbsAllowed()).thenReturn(ConstraintObject(200, aapsLogger))
        whenever(constraintChecker.getMaxBolusAllowed()).thenReturn(ConstraintObject(10.0, aapsLogger))
        whenever(constraintChecker.applyCarbsConstraints(any())).thenAnswer { it.getArgument<ConstraintObject<Int>>(0) }
        whenever(ch.bolusStep(any())).thenReturn(0.05)
        whenever(bolusWizardProvider.get()).thenReturn(template)
        // Match ALL arguments, including doCalc's defaults; no production formula is replaced.
        whenever(template.doCalc(
            any(), any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).doSuspendableAnswer { invocation -> answer(invocation.getArgument(3)) }
    }

    private fun result(carbs: Int, insulin: Double = 2.0): BolusWizard {
        val data = BolusWizardData(
            timeStamp = 1L, carbs = carbs, cob = 0.0, sens = 50.0, ic = 10.0, trend = 0.0,
            insulinFromBG = 0.0, insulinFromCarbs = insulin, insulinFromBolusIOB = 0.3,
            insulinFromBasalIOB = 0.1, insulinFromCorrection = 0.0, insulinFromSuperBolus = 0.0,
            insulinFromCOB = 0.0, insulinFromTrend = 0.0, calculatedTotalInsulin = insulin,
            totalBeforePercentageAdjustment = insulin, carbsEquivalent = 0.0, insulinAfterConstraints = insulin,
            calculatedPercentage = 100, calculatedCorrection = 0.0, percentageCorrection = 100,
            useBg = true, useCob = false, includeBolusIOB = true, includeBasalIOB = true,
            useTT = true, useTrend = false, useSuperBolus = false
        )
        val wizard: BolusWizard = mock()
        whenever(wizard.data).thenReturn(data)
        whenever(wizard.insulinAfterConstraints).thenReturn(insulin)
        whenever(wizard.carbs).thenReturn(carbs)
        return wizard
    }

    @Test
    fun `updateNotes and toggleAlarm update the state`() {
        sut.updateNotes("wizard note")
        sut.toggleAlarm(true)

        assertThat(sut.uiState.value.notes).isEqualTo("wizard note")
        assertThat(sut.uiState.value.alarmChecked).isTrue()
    }

    @Test fun `opening calculates on background dispatcher without duplicate initial IOB reads`() = runTest(mainDispatcher) {
        var context: ContinuationInterceptor? = null
        var calculations = 0
        setupPreview {
            context = currentCoroutineContext()[ContinuationInterceptor]
            calculations++
            result(it)
        }
        advanceUntilIdle()
        assertThat(context).isSameInstanceAs(previewDispatcher)
        assertThat(calculations).isEqualTo(1)
        assertThat(sut.uiState.value.hasResult).isTrue()
        assertThat(sut.uiState.value.isCalculating).isFalse()
        assertThat(sut.uiState.value.totalIOB).isWithin(0.00001).of(-0.4)
        verify(iobCobCalculator, never()).calculateIobFromBolus()
        verify(iobCobCalculator, never()).calculateIobFromTempBasalsIncludingConvertedExtended()
    }

    @Test fun `late cancelled preview cannot overwrite new inputs or enable an older result`() = runTest(mainDispatcher) {
        val oldRelease = CompletableDeferred<Unit>()
        val latestRelease = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        setupPreview {
            if (it == 10) {
                oldStarted.complete(Unit)
                // Model a collaborator that returns late even after cancellation.
                withContext(NonCancellable) { oldRelease.await() }
            }
            if (it == 30) latestRelease.await()
            result(it, if (it == 10) 1.0 else 3.0)
        }
        advanceUntilIdle()
        sut.updateCarbs(10)
        runCurrent()
        assertThat(oldStarted.isCompleted).isTrue()
        assertThat(sut.uiState.value.isCalculating).isTrue()
        assertThat(sut.hasAction()).isFalse()
        sut.updateCarbs(30)
        runCurrent()
        oldRelease.complete(Unit)
        runCurrent()
        // Completing the cancelled generation must not clear the new generation's loading state.
        assertThat(sut.uiState.value.isCalculating).isTrue()
        assertThat(sut.hasAction()).isFalse()
        latestRelease.complete(Unit)
        advanceUntilIdle()
        assertThat(sut.uiState.value.effectiveCarbs).isEqualTo(30)
        assertThat(sut.uiState.value.totalInsulin).isEqualTo(3.0)
        assertThat(sut.hasAction()).isTrue()
        assertThat(sut.uiState.value.carbs).isEqualTo(30)
        assertThat(sut.uiState.value.effectiveCarbs).isEqualTo(30)
        assertThat(sut.uiState.value.totalInsulin).isEqualTo(3.0)
        assertThat(sut.uiState.value.isCalculating).isFalse()
    }

    @Test fun `rapid edits calculate only the last queued preview and never prepare delivery`() = runTest(mainDispatcher) {
        val calculated = mutableListOf<Int>()
        setupPreview { calculated += it; result(it) }
        advanceUntilIdle()
        calculated.clear()
        for (carbs in 1..100) sut.updateCarbs(carbs)
        sut.deliverManualWizard()
        assertThat(sut.uiState.value.okVisible).isFalse()
        assertThat(sut.hasAction()).isFalse()
        advanceUntilIdle()
        assertThat(calculated).containsExactly(100)
        assertThat(sut.uiState.value.effectiveCarbs).isEqualTo(100)
        verify(wizardExecutor, never()).prepare(any(), any())
    }

    @Test fun `preview failure clears actionable result and a new input can recover`() = runTest(mainDispatcher) {
        setupPreview { if (it == 20) error("DB read failed") else result(it) }
        advanceUntilIdle()
        sut.updateCarbs(20)
        advanceUntilIdle()
        assertThat(sut.uiState.value.calculationFailed).isTrue()
        assertThat(sut.uiState.value.hasResult).isFalse()
        assertThat(sut.uiState.value.isCalculating).isFalse()
        assertThat(sut.hasAction()).isFalse()
        sut.deliverManualWizard()
        verify(wizardExecutor, never()).prepare(any(), any())
        sut.updateCarbs(30)
        advanceUntilIdle()
        assertThat(sut.uiState.value.calculationFailed).isFalse()
        assertThat(sut.hasAction()).isTrue()
    }

    @Test fun `closing screen cancels preview although its dispatcher comes from application scope`() = runTest(mainDispatcher) {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        setupPreview {
            started.complete(Unit)
            try { release.await() } finally { cancelled = true }
            result(it)
        }
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        store.clear()
        runCurrent()
        assertThat(cancelled).isTrue()
        assertThat(sut.uiState.value.hasResult).isFalse()
        assertThat(sut.hasAction()).isFalse()
    }
}
