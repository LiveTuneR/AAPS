package app.aaps.core.interfaces.rx.events

/** Presentation-only terminal signal, after loop invocation and prediction preparation. */
class EventCalculationCompleted(val generation: Long) : Event()
