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
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.junit.jupiter.api.Assertions.assertTrue
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData

class RxBusLoggingTest {
    @Test fun `minute wear graph traffic has bounded routine event log volume`() {
        val schedulers = mock<AapsSchedulers>()
        whenever(schedulers.io).thenReturn(Schedulers.trampoline())
        val logger = mock<AAPSLogger>()
        var bytes = 0L
        doAnswer { bytes += it.getArgument<String>(1).toByteArray().size + 1; null }
            .whenever(logger).debug(eq(LTag.EVENTS), any<String>())
        val bus = RxBusImpl(schedulers, logger)
        val graph = EventData.GraphData(ArrayList((0 until 1440).map {
            EventData.SingleBg(dataset = 0, timeStamp = 1_700_000_000_000L + it * 60_000L, sgv = 120.0, high = 180.0, low = 70.0)
        }))
        // Synthetic comparison only: historical payload stringification versus actual current logger.
        val legacyBytesPerHour = 60L * ("Sending $graph\n".toByteArray().size)
        repeat(60) { bus.send(EventMobileToWear(graph)) }
        assertTrue(bytes < 4096, "EVENTS bytes/hour=$bytes")
        assertTrue(legacyBytesPerHour > bytes * 100)
        println("LogVolume category=EVENTS fixture=1440points-per-minute durationHours=1 beforeBytes=$legacyBytesPerHour afterBytes=$bytes excludesOtherCategories=true")
    }
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
