package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mock
import app.aaps.core.data.model.TT
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verifyBlocking
import org.mockito.Mockito.timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var scenes: SceneAutomationApi

    private lateinit var wearPlugin: WearPlugin
    private lateinit var rateLimit: RateLimit

    @BeforeEach fun prepare() {
        rateLimit = RateLimit(dateUtil)
        wearPlugin = WearPlugin(aapsLogger, rh, aapsSchedulers, preferences, fabricPrivacy, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config, BolusProgressData(ch, rh, CoroutineScope(Dispatchers.Unconfined)), persistenceLayer, scenes)
    }

    @Test fun `first real temp target notification reaches wear without dropping or quiet period`() = runBlocking {
        val changes = MutableSharedFlow<List<TT>>()
        val subscribed = CountDownLatch(1)
        whenever(persistenceLayer.observeChanges(TT::class.java)).thenReturn(changes.onSubscription { subscribed.countDown() })
        whenever(scenes.scenesFlow).thenReturn(MutableStateFlow(""))
        whenever(scenes.activeFlow).thenReturn(flowOf(false))
        wearPlugin.onStart()
        try {
            assertTrue(subscribed.await(3, TimeUnit.SECONDS), "TT observer not attached")
            changes.emit(listOf(TT(timestamp=now, duration=60_000, lowTarget=100.0, highTarget=110.0, reason=TT.Reason.ACTIVITY)))
            verifyBlocking(dataHandlerMobile, timeout(3_000)) { resendData("TempTargetChange", false) }
        } finally { wearPlugin.onStop() }
    }
}
