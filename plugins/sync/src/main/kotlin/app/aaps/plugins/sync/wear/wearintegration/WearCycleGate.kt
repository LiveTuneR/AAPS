package app.aaps.plugins.sync.wear.wearintegration

/** Only a successfully built terminal frame consumes its generation. Urgent traffic is separate. */
internal class WearCycleGate {
    private var generation: Long? = null
    fun needsBuild(value: Long?) = value == null || generation?.let { value > it } != false
    fun committed(value: Long?) { if (value != null) generation = value }
    fun reset() { generation = null }
}
