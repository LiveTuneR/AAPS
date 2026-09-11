package app.aaps.shared.impl.rx.bus

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.events.Event
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RxBusLoggingTest {
    private class PayloadEvent : Event() {
        override fun toString(): String = error("Payload must not be rendered for logging")
    }

    @Test
    fun `event delivery does not serialize its payload`() {
        val schedulers = mock<AapsSchedulers>()
        whenever(schedulers.io).thenReturn(Schedulers.trampoline())
        val logger = mock<AAPSLogger>()
        val bus = RxBusImpl(schedulers, logger)
        val observed = mutableListOf<PayloadEvent>()
        val subscription = bus.toObservable(PayloadEvent::class.java).subscribe { observed.add(it) }
        try {
            val event = PayloadEvent()
            bus.send(event)
            assertSame(event, observed.single())
            verify(logger).debug(LTag.EVENTS, "Sending type=PayloadEvent")
        } finally { subscription.dispose() }
    }
}
