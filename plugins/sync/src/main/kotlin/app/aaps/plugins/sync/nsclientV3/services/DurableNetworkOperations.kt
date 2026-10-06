package app.aaps.plugins.sync.nsclientV3.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Lease is acquired before dispatch, released after DB work or cancellation, including never-started jobs. */
internal class DurableNetworkOperations(private val scope: CoroutineScope, private val wake: NetworkWakeScope) : AutoCloseable {
    private val jobs = mutableSetOf<Job>()
    private var closed = false

    @Synchronized fun launch(block: suspend () -> Unit) {
        if (closed) return
        val lease = wake.lease()
        val job = try { scope.launch(start = CoroutineStart.LAZY) { block() } }
        catch (error: Throwable) { lease.close(); throw error }
        jobs.add(job)
        job.invokeOnCompletion {
            lease.close()
            synchronized(this) { jobs.remove(job) }
        }
        job.start()
    }

    @Synchronized override fun close() {
        closed = true
        jobs.toList().forEach { it.cancel() }
    }
}
