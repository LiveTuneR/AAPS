package app.aaps.core.interfaces.telemetry

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/** Read-only trace correlation. Deliberately carries no dosing validity, replay or retry authority. */
class PumpCommandRunContext(val requestId: String,val decisionId: String?,val generation: Long?) :
    AbstractCoroutineContextElement(Key),ThreadContextElement<PumpCommandRunContext?> {
    override fun updateThreadContext(context: CoroutineContext): PumpCommandRunContext? = current.get().also { current.set(this) }
    override fun restoreThreadContext(context: CoroutineContext,oldState: PumpCommandRunContext?) { current.set(oldState) }
    companion object Key : CoroutineContext.Key<PumpCommandRunContext> { val current=ThreadLocal<PumpCommandRunContext?>() }
}
