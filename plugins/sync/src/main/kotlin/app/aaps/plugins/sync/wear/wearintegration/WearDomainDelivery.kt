package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData

/** Semantic keys precede serialization. Urgent protocol responses never enter this gate. */
internal class WearDomainDelivery {
    private val keys = mutableMapOf<String, Any>()
    private val sends = mutableMapOf<String, Long>()
    private var skipped = 0L
    private var resyncs = 0L
    data class Stats(val sends: Map<String, Long>, val skippedUnchanged: Long, val fullResyncs: Long)
    @Synchronized fun reset() { keys.clear(); resyncs++ }
    @Synchronized fun stats() = Stats(sends.toMap(), skipped, resyncs)

    @Synchronized fun accept(event: EventData, force: Boolean = false): Boolean {
        val (domain, key) = when (event) {
            // SingleBg overrides equals using only timestamp/color: its data-class text covers all fields.
            is EventData.FastStatus -> "FAST_STATUS" to listOf(event.bg?.toString(), event.status, event.predictions?.map { it.toString() })
            is EventData.Preferences -> "PREFERENCES" to event.copy(timeStamp = 0)
            is EventData.GraphData -> "GRAPH_HISTORY" to event.entries.map { it.toString() }
            is EventData.TreatmentData -> "TREATMENT_HISTORY" to listOf(event.temps, event.basals, event.boluses, event.predictions.map { it.toString() })
            is EventData.QuickWizard -> "QUICK_WIZARD" to event.entries.toList()
            is EventData.UserAction -> "USER_ACTIONS" to event.entries.map { it.copy(timeStamp = 0) }
            is EventData.SceneList -> "SCENES" to event.entries.map { it.copy(timeStamp = 0) }
            is EventData.ActiveSceneState -> "ACTIVE_SCENE" to event.active
            is EventData.RunningModeList -> "RUNNING_MODES" to event.states.toList()
            else -> return true
        }
        if (!force && keys[domain] == key) { skipped++; return false }
        keys[domain] = key
        sends[domain] = (sends[domain] ?: 0) + 1
        return true
    }
}
