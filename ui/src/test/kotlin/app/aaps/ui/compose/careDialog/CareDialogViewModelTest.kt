package app.aaps.ui.compose.careDialog

import androidx.lifecycle.SavedStateHandle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import kotlinx.coroutines.runBlocking
import app.aaps.ui.R

@OptIn(ExperimentalCoroutinesApi::class)
internal class CareDialogViewModelTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var batchExecutor: BatchExecutor
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var translator: Translator
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var aapsLogger: AAPSLogger

    private lateinit var sut: CareDialogViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenReturn(0.0)
        sut = CareDialogViewModel(
            SavedStateHandle(), persistenceLayer, batchExecutor, profileFunction, profileUtil,
            glucoseStatusProvider, translator, preferences, rh, dateUtil, aapsLogger,
            CoroutineScope(UnconfinedTestDispatcher())
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `updateBgValue sets the value and auto-switches the meter from sensor to finger`() {
        assertThat(sut.uiState.value.meterType).isEqualTo(TE.MeterType.SENSOR)

        sut.updateBgValue(120.0)

        assertThat(sut.uiState.value.bgValue).isEqualTo(120.0)
        assertThat(sut.uiState.value.meterType).isEqualTo(TE.MeterType.FINGER)
    }

    @Test
    fun `updateDuration and updateNotes update the state`() {
        sut.updateDuration(30.0)
        sut.updateNotes("check")

        assertThat(sut.uiState.value.duration).isEqualTo(30.0)
        assertThat(sut.uiState.value.notes).isEqualTo("check")
    }

    @Test
    fun `updateEventTime records the time and marks it changed`() {
        sut.updateEventTime(123_456L)

        assertThat(sut.uiState.value.eventTime).isEqualTo(123_456L)
        assertThat(sut.uiState.value.eventTimeChanged).isTrue()
    }

    @Test fun `sensor plan is persisted through existing batch with the confirmed start and duration`() = runBlocking {
        val start = 1_791_288_000_000L
        val end = start + 14 * 86_400_000L
        whenever(dateUtil.now()).thenReturn(start)
        whenever(dateUtil.dateAndTimeString(end)).thenReturn("20 Oct 2026 12:00")
        whenever(rh.gs(R.string.confirm_treatment)).thenReturn("Confirm")
        whenever(rh.gs(R.string.apex7_sensor_plan_confirmation, "20 Oct 2026 12:00")).thenReturn("Replacement: 20 Oct 2026 12:00")
        whenever(rh.gs(app.aaps.core.ui.R.string.careportal)).thenReturn("Careportal")
        whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Prepared(1))
        whenever(batchExecutor.commit(any(), any(), any(), any())).thenReturn(ActionProgress.Applied)
        val vm = CareDialogViewModel(
            SavedStateHandle(mapOf("eventTypeOrdinal" to CareportalEventType.SENSOR_INSERT.ordinal)),
            persistenceLayer, batchExecutor, profileFunction, profileUtil, glucoseStatusProvider, translator,
            preferences, rh, dateUtil, aapsLogger, CoroutineScope(UnconfinedTestDispatcher())
        )
        vm.updatePlannedSensorEnd(end)
        vm.buildConfirmationSummary()
        vm.updatePlannedSensorEnd(end + 86_400_000L) // must not alter the confirmed record
        vm.confirmAndSave()
        val captured = argumentCaptor<List<BatchAction>>()
        verify(batchExecutor).prepare(captured.capture(), any(), any())
        val event = captured.firstValue.single() as BatchAction.TherapyEvent
        assertThat(event.teType).isEqualTo(TE.Type.SENSOR_CHANGE)
        assertThat(event.timestamp).isEqualTo(start)
        assertThat(event.durationMinutes).isEqualTo(14 * 24 * 60)
    }
}
