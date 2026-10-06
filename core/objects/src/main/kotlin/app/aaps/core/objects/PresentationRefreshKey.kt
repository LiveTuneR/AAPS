package app.aaps.core.objects

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.sharedPreferences.SP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Display-only equality proof, never used to skip a calculation or a pump command. No timers/queries. */
@Singleton
class PresentationRefreshKey @Inject constructor(
    private val calculator: IobCobCalculator,
    private val loop: Loop,
    private val plugins: ActivePlugin,
    private val config: Config,
    private val sp: SP,
    persistence: PersistenceLayer,
    @ApplicationScope scope: CoroutineScope,
) {
    private val databaseRevision = AtomicLong()
    init { scope.launch { persistence.observeAnyChange().collect { databaseRevision.incrementAndGet() } } }
    fun current(now: Long): List<Any?>? {
        val settings = sp.presentationRevision ?: return null
        if (!config.appInitialized) return null
        val ads = calculator.ads
        val bg = ads.lastBg()
        val pump = plugins.activePump
        return listOf(ads, bg?.timestamp, bg?.recalculated, loop.lastRun?.lastAPSRun, loop.lastRun?.lastTBREnact,
            databaseRevision.get(), settings, now / 60_000, pump, pump.baseBasalRate, pump.reservoirLevel.value,
            pump.batteryLevel.value, pump.isInitialized(), pump.isSuspended(), pump.isConnected())
    }
}
