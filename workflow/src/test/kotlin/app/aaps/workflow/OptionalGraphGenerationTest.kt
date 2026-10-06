package app.aaps.workflow

import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.shared.tests.TestBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class OptionalGraphGenerationTest : TestBase() {
    private fun data() = PrepareGraphDataWorker.PrepareGraphData(mock(), mock(), mock(), mock(), "test", 0, false, true, true, false)

    @Test fun `no display can read before the calculation snapshot is ready`() {
        val chain = WorkflowChainData(aapsLogger)
        val gen = chain.startMain(data(), mock())
        assertNull(chain.graphFor(gen))
        assertTrue(chain.graphReady(gen))
        assertNotNull(chain.graphFor(gen))
    }

    @Test fun `history fences and replacement prevent old display publication`() {
        val chain = WorkflowChainData(aapsLogger)
        val first = chain.startMain(data(), mock())
        chain.graphReady(first)
        var publications = 0
        assertTrue(chain.publishGraphIfCurrent(first, { false }) { publications++ })
        chain.invalidate(MAIN_CALCULATION)
        assertFalse(chain.publishGraphIfCurrent(first, { false }) { publications++ })
        val next = chain.startMain(data(), mock())
        chain.graphReady(next)
        assertFalse(chain.graphReady(first))
        assertFalse(chain.publishGraphIfCurrent(first, { false }) { publications++ })
        assertFalse(chain.publishGraphIfCurrent(next, { true }) { publications++ })
        assertTrue(chain.publishGraphIfCurrent(next, { false }) { publications++ })
        assertEquals(2, publications)
    }

    @Test fun `suspended optional graph never owns the mandatory calculation mutex`() = runTest {
        val chain = WorkflowChainData(aapsLogger)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val graph = launch { chain.withGraphCalculation { entered.complete(Unit); release.await() } }
        entered.await()
        var calculated = false
        chain.withMainCalculation { calculated = true }
        assertTrue(calculated)
        release.complete(Unit)
        graph.join()
    }
}
