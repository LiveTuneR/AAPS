package app.aaps.core.data.diagnostics

/** Null means equality cannot be proven. Failed builds must not commit the key. */
class PresentationBuildGate {
    private var committed: Any? = null
    @Synchronized fun needsBuild(key: Any?) = key == null || key != committed
    @Synchronized fun commit(key: Any?) { committed = key }
    @Synchronized fun reset() { committed = null }
}
