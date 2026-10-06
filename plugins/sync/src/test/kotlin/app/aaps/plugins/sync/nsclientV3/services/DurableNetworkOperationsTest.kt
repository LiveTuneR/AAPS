package app.aaps.plugins.sync.nsclientV3.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DurableNetworkOperationsTest {
    @Test fun `SGV treatment deletion and profile retain ownership through asynchronous commit`() = runTest {
        var held = 0
        val wake = NetworkWakeScope { held++; AutoCloseable { held-- } }
        val operations = DurableNetworkOperations(backgroundScope, wake)
        val gates = List(4) { CompletableDeferred<Unit>() }
        val committed = mutableListOf<Int>()
        gates.forEachIndexed { index, gate -> operations.launch { gate.await(); committed.add(index) } }
        assertEquals(4, held) // before the consumer has even been scheduled
        runCurrent(); assertEquals(4, held); assertTrue(committed.isEmpty())
        gates[0].complete(Unit); runCurrent(); assertEquals(3, held); assertEquals(listOf(0), committed)
        operations.close(); runCurrent(); assertEquals(0, held)
        assertEquals(listOf(0), committed) // cancelled RAM records cannot advance a cursor
    }

    @Test fun `destroy before consumer starts closes every lease exactly once`() = runTest {
        var closes = 0
        val wake = NetworkWakeScope { AutoCloseable { closes++ } }
        val operations = DurableNetworkOperations(backgroundScope, wake)
        repeat(10) { operations.launch { fail("cancelled consumer must never run") } }
        operations.close(); wake.close(); runCurrent()
        assertEquals(10, closes); assertEquals(0, wake.stats().active)
        operations.launch { fail("closed owner") }; assertEquals(10L, wake.stats().count)
    }

    @Test fun `lease statistics distinguish time limit from healthy completion without idle polling`() {
        var time = 0L; var closes = 0
        val wake = NetworkWakeScope(now = { time }, acquire = { AutoCloseable { closes++ } })
        val a = wake.lease(); time = 30_001; a.close(); a.close()
        val b = wake.lease(); time += 20; b.close()
        assertEquals(1L, wake.stats().timedOut)
        assertEquals(30_021L, wake.stats().totalMs); assertEquals(2, closes)
    }
}
