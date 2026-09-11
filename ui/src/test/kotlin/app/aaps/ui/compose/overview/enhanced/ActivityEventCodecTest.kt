package app.aaps.ui.compose.overview.enhanced

import app.aaps.core.data.activity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityEventCodecTest {
    @Test fun `cache roundtrip preserves timestamps zero steps and missing end`() {
        val event = ActivityEvent("test", ActivitySource.SAMSUNG_HEALTH, "pool", ActivityCategory.POOL_SWIMMING,
            1000, receivedAt = 2000, lastUpdatedAt = 1900, steps = 0)
        assertEquals(listOf(event), ActivityEventCodec.decode(ActivityEventCodec.encode(listOf(event))))
        assertTrue(ActivityEventCodec.decode("invalid").isEmpty())
        assertTrue(ActivityEventCodec.decode(null).isEmpty())
    }
}
