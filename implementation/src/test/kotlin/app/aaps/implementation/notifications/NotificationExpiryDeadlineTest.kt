package app.aaps.implementation.notifications

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationExpiryDeadlineTest {
    @Test fun `empty and persistent notices have no periodic wakeup`() {
        assertNull(notificationExpiryDelay(emptyList(), false, 0))
        assertNull(notificationExpiryDelay(listOf(0), false, 0))
    }
    @Test fun `active expiry and validity checks keep original cadence and exact earliest boundary`() {
        assertEquals(30_000L, notificationExpiryDelay(emptyList(), true, 0))
        assertEquals(1001L, notificationExpiryDelay(listOf(1000, 100_000), false, 0))
        assertEquals(1L, notificationExpiryDelay(listOf(1000), false, 2000))
        assertEquals(30_000L, notificationExpiryDelay(listOf(100_000), false, 0))
    }
}
