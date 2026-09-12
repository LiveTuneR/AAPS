package app.aaps.plugins.main.iob

import app.aaps.core.data.diagnostics.GlucoseChange
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.implementation.iob.AutosensDataObject
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompletedGlucoseAndPruningTest : TestBaseWithProfile() {
    private fun reading() = GV(id = 1, timestamp = 1_000_000, value = 120.0, raw = null, noise = null,
        sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)

    @Test fun `only completed equality can suppress metadata writeback`() {
        val store = AutosensDataStoreObject()
        val old = reading()
        store.bgReadings = listOf(old)
        val updated = old.copy(version = 2, ids = old.ids.copy(nightscoutId = "synthetic"))
        assertEquals(GlucoseChange.UNKNOWN, store.classifyCompletedGlucose(updated))
        store.markCalculationCompleted()
        assertEquals(GlucoseChange.METADATA_ONLY, store.classifyCompletedGlucose(updated))
        assertEquals(GlucoseChange.UNKNOWN, store.clone().classifyCompletedGlucose(updated))
        for (changed in listOf(updated.copy(value = 121.0), updated.copy(noise = 1.0),
            updated.copy(timestamp = 1_000_001), updated.copy(sourceSensor = SourceSensor.DEXCOM_G6_NATIVE))) {
            assertEquals(GlucoseChange.THERAPY_RELEVANT, store.classifyCompletedGlucose(changed))
        }
        val newRow = updated.copy(id = 2, timestamp = 1_060_000)
        val required = listOf(updated, newRow).filter { store.classifyCompletedGlucose(it) != GlucoseChange.METADATA_ONLY }
        assertEquals(listOf(newRow), required)
        old.value = 200.0
        assertEquals(GlucoseChange.METADATA_ONLY, store.classifyCompletedGlucose(updated))
        store.newHistoryData(0, aapsLogger, dateUtil)
        assertEquals(GlucoseChange.UNKNOWN, store.classifyCompletedGlucose(updated))
    }

    @Test fun `prune removes consecutive old rows preserving cut and deep snapshots`() {
        val store = AutosensDataStoreObject()
        val start = 1_000_000L
        for (i in 0..1000) store.autosensDataTable.put(start + i * 300_000L, AutosensDataObject(aapsLogger, preferences, dateUtil))
        val snapshot = store.clone()
        val cut = start + 900 * 300_000L
        store.pruneOlderThan(cut)
        assertEquals(101, store.autosensDataTable.size())
        assertEquals(1001, snapshot.autosensDataTable.size())
        for (i in 0..100) assertEquals(cut + i * 300_000L, store.autosensDataTable.keyAt(i))
        store.pruneOlderThan(start - 86_400_000L)
        assertEquals(101, store.autosensDataTable.size())
    }
}
