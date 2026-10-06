package app.aaps.core.data.activity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityContextTest {
    @Test fun `fresh source check and eighteen minute historical workout remain distinct`() {
        val now = 1791198086670L
        val store = ActivityContextStore()
        store.accept(ActivityEvent("old", ActivitySource.PHONE, "other", ActivityCategory.OTHER,
            1791012980461L, 1791014069643L, 1791014443168L, 1791014443168L))
        store.sourceHealth(ActivityAccess.NO_DATA, successfulReadAt = now, checkedAt = now)
        val result = store.snapshot(now)
        assertEquals(now, result.lastReadAttempt)
        assertEquals(ActivityState.STALE_ACTIVITY, result.state)
        assertEquals(18L, (result.event!!.endTime!! - result.event!!.startTime) / 60_000)
        assertTrue(now - result.event!!.lastUpdatedAt > 2 * 86_400_000)
        assertFalse(result.usedForDosing)
        store.sourceHealth(ActivityAccess.PERMISSION_REQUIRED, checkedAt = now + 120_000)
        assertEquals(now, store.snapshot(now + 120_000).lastSuccessfulRead)
        assertEquals(now + 120_000, store.snapshot(now + 120_000).lastReadAttempt)
    }
    private val now = 1_780_000_000_000L
    private fun event(category: ActivityCategory = ActivityCategory.POOL_SWIMMING) = ActivityEvent(
        "workout", ActivitySource.SAMSUNG_HEALTH, "pool", category, now - 600_000,
        receivedAt = now, lastUpdatedAt = now - 60_000, sourceDevice = "watch", steps = 0)
    private fun store() = ActivityContextStore().apply { sourceHealth(ActivityAccess.AVAILABLE, now) }
    @Test fun `watch swimming walking and running do not require phone steps`() {
        for (category in listOf(ActivityCategory.POOL_SWIMMING, ActivityCategory.WALKING, ActivityCategory.RUNNING)) {
            val store = store()
            assertTrue(store.accept(event(category)))
            store.accept(event().copy(id = "phone-still", source = ActivitySource.PHONE, category = ActivityCategory.UNKNOWN, lastUpdatedAt = now, steps = 0))
            val context = store.snapshot(now)
            assertEquals(ActivityState.ACTIVE, context.state)
            assertEquals(category, context.event?.category)
            assertEquals(0L, context.event?.steps)
            assertFalse(context.usedForDosing)
        }
    }
    @Test fun `all activity observations remain shadow only`() {
        for (category in ActivityCategory.entries) {
            val store = store()
            store.accept(event(category).copy(heartRate = 176.0, latestHeartRate = 181.0, steps = 12_345))
            assertFalse(store.snapshot(now).usedForDosing)
        }
    }
    @Test fun `invalid heart rate cannot enter the observation cache`() {
        assertFalse(store().accept(event().copy(latestHeartRate = Double.NaN)))
        assertFalse(store().accept(event().copy(heartRate = -1.0)))
    }
    @Test fun `delayed ended activity is post activity then stale never active`() {
        val store = store()
        store.accept(event().copy(endTime = now - 120_000))
        assertEquals(ActivityState.POST_ACTIVITY, store.snapshot(now).state)
        assertEquals(ActivityState.STALE_ACTIVITY, store.snapshot(now + 3 * 3_600_000).state)
        assertEquals(600_000L, store.snapshot(now).event?.detectionLatencyMs)
    }
    @Test fun `duplicates restart and missing end never renew an old signal`() {
        val store = store()
        assertTrue(store.accept(event()))
        assertFalse(store.accept(event().copy(receivedAt = now + 1_000_000)))
        val restored = ActivityContextStore(store.export())
        restored.sourceHealth(ActivityAccess.AVAILABLE, now + 1_000_000)
        assertEquals(ActivityState.STALE_ACTIVITY, restored.snapshot(now + 1_000_000).state)
        assertEquals(now, restored.snapshot(now + 1_000_000).event?.receivedAt)
    }
    @Test fun `unavailable permissions and future clock fail to stale or none`() {
        val empty = ActivityContextStore()
        empty.sourceHealth(ActivityAccess.HEALTH_CONNECT_UNAVAILABLE)
        assertEquals(ActivityState.NONE, empty.snapshot(now).state)
        val store = store()
        store.accept(event())
        store.sourceHealth(ActivityAccess.PERMISSION_REQUIRED)
        assertEquals(ActivityState.STALE_ACTIVITY, store.snapshot(now).state)
        store.sourceHealth(ActivityAccess.AVAILABLE)
        assertTrue(store.snapshot(now - 1_000_000).clockSkew)
        assertEquals(ActivityState.STALE_ACTIVITY, store.snapshot(now - 1_000_000).state)
    }
}
