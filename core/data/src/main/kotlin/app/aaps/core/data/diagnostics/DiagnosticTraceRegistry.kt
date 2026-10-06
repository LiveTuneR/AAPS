package app.aaps.core.data.diagnostics

/** Two app-lifetime pump singletons; common data module avoids a telemetry → pump dependency cycle. */
object DiagnosticTraceRegistry {
    private val rings = mutableMapOf<String, BoundedDiagnosticTrace>()
    @Synchronized fun register(driver: String, ring: BoundedDiagnosticTrace) {
        require(driver in setOf("apex", "medtrum"))
        rings[driver] = ring
    }
    @Synchronized fun snapshots(): Map<String, BoundedDiagnosticTrace.Snapshot> = rings.mapValues { it.value.snapshot() }
}
