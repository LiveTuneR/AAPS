package app.aaps.core.interfaces.workflow

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries provenance and validity, never an insulin command or a persisted APS result. */
class CalculationRunContext(
    val generation: Long,
    val rawBgTimestamp: Long?,
    val decisionId: String,
    val isCurrent: () -> Boolean
) : AbstractCoroutineContextElement(Key), kotlinx.coroutines.ThreadContextElement<CalculationRunContext?> {
    override fun updateThreadContext(context: CoroutineContext): CalculationRunContext? = current.get().also { current.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: CalculationRunContext?) { current.set(oldState) }
    companion object Key : CoroutineContext.Key<CalculationRunContext> {
        /** Read-only provenance bridge for the queue's existing non-suspending admission section. */
        val current = ThreadLocal<CalculationRunContext?>()
    }
}
