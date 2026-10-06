package app.aaps.plugins.sync.nsclientV3.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NetworkWakeScopeTest {
    @Test fun `idle is free and failure releases bounded lease`() {
        var acquired = 0; var released = 0
        val scope = NetworkWakeScope { timeout ->
            assertEquals(30_000L, timeout); acquired++
            AutoCloseable { released++ }
        }
        assertEquals(0, acquired)
        assertThrows(IllegalStateException::class.java) { scope.lease().use { error("network failed") } }
        assertEquals(1, released)
    }
    @Test fun `destroy closes concurrent operations once and blocks new leases`() {
        var released = 0; var acquired = 0
        val scope = NetworkWakeScope { acquired++; AutoCloseable { released++ } }
        val one = scope.lease(); val two = scope.lease()
        one.close(); one.close()
        assertEquals(1, released)
        scope.close(); two.close(); scope.close()
        scope.lease().close()
        assertEquals(2, acquired); assertEquals(2, released)
    }
}
