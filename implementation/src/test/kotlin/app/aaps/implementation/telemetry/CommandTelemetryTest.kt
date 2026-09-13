package app.aaps.implementation.telemetry

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.telemetry.PumpCommandRunContext
import app.aaps.core.interfaces.telemetry.TherapyEventType
import app.aaps.core.interfaces.telemetry.TherapyTelemetry
import app.aaps.core.interfaces.workflow.CalculationRunContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import javax.inject.Provider

class CommandTelemetryTest {
    private class Action : Command {
        var executions=0
        var callbacks=0
        val outcome=mock<PumpEnactResult>()
        override val commandType=Command.CommandType.SMB_BOLUS
        override val pumpEnactResultProvider=Provider { outcome }
        override val callback=object : Callback() { override fun run() { callbacks++ } }
        override suspend fun execute(): PumpEnactResult { executions++; return outcome }
        override fun status()="SMB"
        override fun log()="SMB"
    }

    @Test fun `decision queue driver and result share identity without executing twice`() = runTest {
        val events=mutableListOf<Pair<TherapyEventType,JSONObject>>()
        val sink=mock<TherapyTelemetry>()
        doAnswer { events.add(it.getArgument<TherapyEventType>(0) to it.getArgument<JSONObject>(1)); null }
            .whenever(sink).record(any(),any(),anyOrNull(),anyOrNull())
        val trace=CommandTelemetry(sink,mock<AAPSLogger>())
        val action=Action()
        withContext(CalculationRunContext(42,1234,"decision-42") { true }) { trace.queued(action,1) }
        assertNull(CalculationRunContext.current.get())
        trace.dispatched(action)
        withContext(trace.context(action)) {
            assertEquals("decision-42",PumpCommandRunContext.current.get()?.decisionId)
            assertEquals(42L,PumpCommandRunContext.current.get()?.generation)
            action.executeWithCallback { trace.result(action,it) }
        }
        trace.result(action,action.outcome)
        assertNull(PumpCommandRunContext.current.get())
        assertEquals(1,action.executions)
        assertEquals(1,action.callbacks)
        assertEquals(listOf(TherapyEventType.PUMP_QUEUE,TherapyEventType.PUMP_DISPATCH,TherapyEventType.PUMP_RESULT),events.map { it.first })
        assertEquals(1,events.map { it.second.getString("requestId") }.distinct().size)
        assertTrue(events.all { it.second.getString("decisionId")=="decision-42" })
        assertTrue(events.last().second.isNull("reportedDeliveredU"))
    }

    @Test fun `failed diagnostic sink cannot suppress callback or repeat insulin`() = runTest {
        val action=Action()
        action.executeWithCallback { throw IllegalStateException("telemetry unavailable") }
        assertEquals(1,action.executions)
        assertEquals(1,action.callbacks)
        assertSame(action.outcome,action.callback.result)
    }

    @Test fun `dropped command is terminal evidence and never sent`() {
        val sink=mock<TherapyTelemetry>()
        val trace=CommandTelemetry(sink,mock<AAPSLogger>())
        val action=Action()
        trace.queued(action,1)
        trace.dropped(action,"SUPERSEDED")
        trace.dispatched(action)
        trace.failed(action,"late callback")
        verify(sink,times(1)).record(eq(TherapyEventType.PUMP_QUEUE),any(),anyOrNull(),anyOrNull())
        verify(sink,times(1)).record(eq(TherapyEventType.PUMP_RESULT),argThat { !getBoolean("sent") && getBoolean("rejected") },anyOrNull(),anyOrNull())
        verifyNoMoreInteractions(sink)
        assertEquals(0,action.executions)
    }
}
