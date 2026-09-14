package app.aaps.core.data.diagnostics

import app.aaps.core.data.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GlucoseChangeClassifierTest {
    private val bg = GV(id = 1, timestamp = 1000, value = 100.0, raw = null, noise = null, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)
    @Test fun `only known sync fields are metadata`() {
        assertEquals(GlucoseChange.METADATA_ONLY, GlucoseChangeClassifier.classify(bg, bg.copy(version = 2, ids = IDs(nightscoutId = "remote"))))
        for (changed in listOf(bg.copy(value = 101.0), bg.copy(timestamp = 2000), bg.copy(isValid = false),
            bg.copy(raw = 90.0), bg.copy(noise = 1.0), bg.copy(trendArrow = TrendArrow.SINGLE_UP), bg.copy(utcOffset = 1), bg.copy(ids = IDs(pumpSerial = "changed")))) {
            assertEquals(GlucoseChange.THERAPY_RELEVANT, GlucoseChangeClassifier.classify(bg, changed))
        }
        assertEquals(GlucoseChange.UNKNOWN, GlucoseChangeClassifier.classify(null, bg))
        assertEquals(GlucoseChange.UNKNOWN, GlucoseChangeClassifier.classify(bg, bg.copy(id = 2)))
    }
}
