package app.aaps.implementation.telemetry

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.telemetry.TherapyEventType
import app.aaps.core.interfaces.telemetry.TherapyTelemetry
import app.aaps.core.interfaces.workflow.CalculationRunContext
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Identity/provenance only: no command replay, dose cache, ownership decisions or retry authority. */
@Singleton
class CommandTelemetry @Inject constructor(private val telemetry: TherapyTelemetry,private val logger: AAPSLogger) {
    private data class Evidence(val requestId: String,val decisionId: String?,val generation: Long?,val queuedAt: Long)
    private val commands=WeakHashMap<Command,Evidence>()
    @Synchronized fun context(command: Command): kotlin.coroutines.CoroutineContext = commands[command]?.let {
        app.aaps.core.interfaces.telemetry.PumpCommandRunContext(it.requestId,it.decisionId,it.generation)
    } ?: kotlin.coroutines.EmptyCoroutineContext

    @Synchronized fun queued(command: Command,depth: Int) = safe {
        val run=CalculationRunContext.current.get()
        val evidence=Evidence(UUID.randomUUID().toString(),run?.decisionId,run?.generation,System.currentTimeMillis())
        commands[command]=evidence
        emit(command,evidence,TherapyEventType.PUMP_QUEUE,JSONObject().put("queueDepth",depth).put("queuedAt",evidence.queuedAt))
    }
    @Synchronized fun dispatched(command: Command) = safe {
        val evidence=commands[command] ?: return@safe
        emit(command,evidence,TherapyEventType.PUMP_DISPATCH,JSONObject().put("dispatchAt",System.currentTimeMillis()).put("queuedAt",evidence.queuedAt))
    }
    @Synchronized fun result(command: Command,result: PumpEnactResult) = safe {
        val evidence=commands[command] ?: return@safe
        emit(command,evidence,TherapyEventType.PUMP_RESULT,JSONObject().put("resultAt",System.currentTimeMillis())
            .put("success",result.success).put("enacted",result.enacted).put("queued",result.queued)
            .put("reportedDeliveredU",if (result.success || result.enacted || result.bolusDelivered > 0) result.bolusDelivered else JSONObject.NULL)
            .put("rate",result.absolute).put("percent",result.percent)
            .put("durationMinutes",result.duration).put("ambiguous",JSONObject.NULL).put("reason",result.comment))
        commands.remove(command)
    }
    @Synchronized fun dropped(command: Command,reason: String) = safe {
        val evidence=commands.remove(command) ?: return@safe
        emit(command,evidence,TherapyEventType.PUMP_RESULT,JSONObject().put("resultAt",System.currentTimeMillis())
            .put("reason",reason).put("rejected",true).put("enacted",false).put("sent",false))
    }
    @Synchronized fun failed(command: Command,type: String) = safe {
        val evidence=commands.remove(command) ?: return@safe
        emit(command,evidence,TherapyEventType.ERROR,JSONObject().put("errorType",type).put("ambiguous",JSONObject.NULL)
            .put("reason","EXECUTION_INTERRUPTED_RECONCILIATION_REQUIRED"))
    }
    private fun emit(command: Command,evidence: Evidence,type: TherapyEventType,data: JSONObject) {
        telemetry.record(type,data.put("commandType",command.commandType.name).put("requestId",evidence.requestId)
            .put("decisionId",evidence.decisionId ?: JSONObject.NULL).put("source","COMMAND_QUEUE"),evidence.generation,evidence.decisionId ?: evidence.requestId)
    }
    private fun safe(block: () -> Unit) {
        try { block() } catch (error: Exception) { logger.error(LTag.PUMPQUEUE,"Command telemetry failed type=${error.javaClass.simpleName}") }
    }
}
