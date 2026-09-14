package app.aaps.core.interfaces.rx

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ResilientCollectTest {
    @Test
    fun `failed item does not discard next event`() = runTest {
        val received = mutableListOf<Int>()
        flowOf(1, 2).collectResilient(this, mock<AAPSLogger>(), LTag.AUTOSENS, streamName = "test") {
            if (it == 1) error("synthetic failure")
            received.add(it)
        }.join()
        assertEquals(listOf(2), received)
    }

    @Test
    fun `cancellation is never treated as recoverable input failure`() = runTest {
        val received = mutableListOf<Int>()
        val job = flowOf(1, 2).collectResilient(this, mock<AAPSLogger>(), LTag.AUTOSENS) {
            received.add(it)
            throw CancellationException("shutdown")
        }
        job.join()
        assertEquals(listOf(1), received)
        assertEquals(true, job.isCancelled)
    }
}
