package app.aaps.pump.apex.bolus

import android.content.Context
import android.os.SystemClock
import app.aaps.pump.apex.diagnostics.ApexTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ApexBolusCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val trace: ApexTrace,
) {
    companion object {
        const val HISTORY_TIME_TOLERANCE_MS = 90_000L
        const val STALE_SLOT_THRESHOLD_MS = 24 * 60 * 60 * 1000L
        const val MAX_LATEST_ATTEMPTS = 2
    }

    private val store = ApexBolusJournalStore(File(context.filesDir, "apex/bolus-operations.json"))
    private val lock = Any()
    private var operations = runCatching { store.load() }.getOrElse { error ->
        trace.record("bolus_journal_corrupt", fields = mapOf("errorType" to error.javaClass.simpleName))
        mutableListOf(
            ApexBolusOperation(
                operationUuid = "journal-corrupt-${UUID.randomUUID()}",
                pumpIdentityHash = "UNKNOWN_JOURNAL_CORRUPT",
                firmware = null,
                protocol = null,
                createdUtc = System.currentTimeMillis(),
                requestedTimestamp = System.currentTimeMillis(),
                temporaryId = -1L,
                bolusType = "UNKNOWN",
                requestedUnits = 0.0,
                encodedSteps = 0,
                caller = "journal_recovery",
                queueCommandIdentity = null,
                commandGeneration = null,
                state = ApexBolusState.RECONCILIATION_REQUIRED,
            ),
        )
    }
    private val _active = MutableStateFlow(operations.lastOrNull { it.unresolved })
    val active: StateFlow<ApexBolusOperation?> = _active.asStateFlow()

    val safetyGateActive: Boolean get() = synchronized(lock) { operations.any(ApexBolusOperation::unresolved) }

    fun prepare(
        pumpIdentityHash: String,
        firmware: String?,
        protocol: String?,
        requestedTimestamp: Long,
        temporaryId: Long,
        bolusType: String,
        requestedUnits: Double,
        encodedSteps: Int,
        caller: String,
        queueCommandIdentity: String?,
        commandGeneration: Long?,
    ): ApexBolusOperation? = synchronized(lock) {
        if (operations.any(ApexBolusOperation::unresolved)) {
            trace.record("bolus_safety_gate_enabled", fields = mapOf("reason" to "unresolved_operation"))
            return null
        }
        val operation = ApexBolusOperation(
            operationUuid = UUID.randomUUID().toString(),
            pumpIdentityHash = pumpIdentityHash,
            firmware = firmware,
            protocol = protocol,
            createdUtc = System.currentTimeMillis(),
            requestedTimestamp = requestedTimestamp,
            temporaryId = temporaryId,
            bolusType = bolusType,
            requestedUnits = requestedUnits,
            encodedSteps = encodedSteps,
            caller = caller,
            queueCommandIdentity = queueCommandIdentity,
            commandGeneration = commandGeneration,
        )
        operations += operation
        persistLocked()
        _active.value = operation.copy()
        trace.record("bolus_operation_created", fields = operation.traceFields())
        trace.record("bolus_journal_persisted", fields = operation.traceFields())
        operation.copy()
    }

    /** Called by the transport owner immediately before the only permitted physical write. */
    fun beginTransportWrite(operationUuid: String, steps: Int, generation: Long): Boolean = synchronized(lock) {
        val operation = operations.find { it.operationUuid == operationUuid } ?: return false
        if (operation.state != ApexBolusState.PREPARED || operation.encodedSteps != steps) return false
        operation.transportGeneration = generation
        operation.commandSentUtc = System.currentTimeMillis()
        operation.commandSentElapsed = SystemClock.elapsedRealtime()
        operation.state = ApexBolusState.COMMAND_SENT
        persistAndPublishLocked(operation)
        trace.record("bolus_command_sent", generation, fields = operation.traceFields())
        true
    }

    fun markWriteFailed(operationUuid: String) = update(operationUuid) {
        it.state = ApexBolusState.DEFINITELY_NOT_DELIVERED
        trace.record("bolus_write_definitely_failed", it.transportGeneration, fields = it.traceFields())
    }

    fun markAccepted(operationUuid: String, generation: Long) = update(operationUuid) {
        if (it.state.terminal) return@update
        it.acceptedObserved = true
        it.acceptedUtc = System.currentTimeMillis()
        it.transportGeneration = generation
        it.state = ApexBolusState.ACCEPTED
        trace.record("bolus_accepted", generation, fields = it.traceFields())
    }

    fun markProgress(operationUuid: String, steps: Int, generation: Long) = update(operationUuid) {
        if (it.state.terminal) return@update
        it.highestProgressSteps = maxOf(it.highestProgressSteps, steps)
        it.state = ApexBolusState.DELIVERING
        trace.record("bolus_progress", generation, fields = it.traceFields() + ("progressSteps" to steps))
    }

    fun markLiveCompleted(operationUuid: String, steps: Int, generation: Long) = update(operationUuid) {
        if (it.state.terminal) return@update
        it.completedObserved = true
        it.completedSteps = steps
        it.highestProgressSteps = maxOf(it.highestProgressSteps, steps)
        it.state = ApexBolusState.HISTORY_CONFIRMING
        trace.record("bolus_live_completed", generation, fields = it.traceFields())
    }

    fun markTimeoutOrDisconnect(operationUuid: String, generation: Long, reason: String) = update(operationUuid) {
        if (it.state.terminal) return@update
        it.timedOut = reason.contains("timeout", ignoreCase = true)
        it.state = ApexBolusState.DELIVERY_UNCERTAIN
        trace.record("bolus_delivery_uncertain", generation, fields = it.traceFields() + ("reason" to reason))
        trace.record("bolus_safety_gate_enabled", generation, fields = mapOf("operationUuid" to it.operationUuid, "reason" to reason))
    }

    fun rejectBeforeDelivery(operationUuid: String) = update(operationUuid) {
        it.state = ApexBolusState.REJECTED_BEFORE_DELIVERY
        trace.record("bolus_rejected_before_delivery", it.transportGeneration, fields = it.traceFields())
    }

    fun markCancelledConfirmed(operationUuid: String) = update(operationUuid) {
        it.cancelled = true
        it.matchedPerformedSteps = 0
        it.state = ApexBolusState.CANCELLED_CONFIRMED
        trace.record("bolus_cancelled_confirmed", it.transportGeneration, fields = it.traceFields())
        trace.record("bolus_safety_gate_cleared", it.transportGeneration, fields = mapOf("operationUuid" to it.operationUuid))
    }

    fun reconcile(
        pumpIdentityHash: String,
        queryType: String,
        candidates: List<ApexHistoryCandidate>,
        generation: Long,
    ): ApexReconciliationResult = synchronized(lock) {
        val operation = operations.lastOrNull { it.unresolved } ?: return ApexReconciliationResult.NoUnresolved
        if (operation.pumpIdentityHash != pumpIdentityHash) {
            trace.record("bolus_reconciliation_different_pump", generation, fields = operation.traceFields())
            return ApexReconciliationResult.DifferentPump
        }
        operation.lastReconciliationUtc = System.currentTimeMillis()
        operation.reconciliationAttempts++
        if (queryType == "LatestBoluses") operation.latestHistoryAttempts++ else operation.fullHistoryAttempts++
        operation.state = ApexBolusState.RECONCILIATION_REQUIRED
        trace.record("bolus_history_query", generation, fields = operation.traceFields() + ("queryType" to queryType) + ("entryCount" to candidates.size))

        val anomalies = classifyHistory(candidates)
        trace.record(
            "bolus_history_sanity",
            generation,
            fields = mapOf(
                "queryType" to queryType,
                "newestTimestamp" to anomalies.newestTimestamp,
                "oldestTimestamp" to anomalies.oldestTimestamp,
                "monotonicityViolations" to anomalies.monotonicityViolations,
                "staleSlots" to anomalies.staleSlots,
                "duplicates" to anomalies.duplicates,
                "cursorJumps" to anomalies.cursorJumps,
            ),
        )
        candidates.forEach { candidate ->
            trace.record(
                "bolus_history_candidate",
                generation,
                fields = operation.traceFields() + candidate.traceFields(),
            )
        }

        val matches = candidates.filter { candidate ->
            candidate.requestedSteps == operation.encodedSteps &&
                minOf(
                    abs(candidate.timestamp - operation.temporaryId),
                    abs(candidate.timestamp - operation.requestedTimestamp),
                ) <= HISTORY_TIME_TOLERANCE_MS
        }
        val match = matches.singleOrNull()
        if (match != null) {
            operation.matchedPumpHistoryId = match.timestamp
            operation.matchedPumpHistoryTime = match.timestamp
            operation.matchedRequestedSteps = match.requestedSteps
            operation.matchedPerformedSteps = match.performedSteps
            operation.state = when {
                match.performedSteps == 0 -> ApexBolusState.CANCELLED_CONFIRMED
                match.performedSteps < match.requestedSteps -> ApexBolusState.PARTIALLY_DELIVERED_CONFIRMED
                else -> ApexBolusState.CONFIRMED_DELIVERED
            }
            persistAndPublishLocked(operation)
            trace.record("bolus_history_match", generation, fields = operation.traceFields() + match.traceFields())
            trace.record("bolus_reconciliation_resolved", generation, fields = operation.traceFields())
            trace.record("bolus_safety_gate_cleared", generation, fields = mapOf("operationUuid" to operation.operationUuid))
            return ApexReconciliationResult.Matched(operation.copy(), match)
        }

        operation.state = ApexBolusState.DELIVERY_UNCERTAIN
        persistAndPublishLocked(operation)
        trace.record("bolus_history_not_found", generation, fields = operation.traceFields() + ("queryType" to queryType))
        trace.record("bolus_reconciliation_still_uncertain", generation, fields = operation.traceFields())
        return when {
            queryType == "LatestBoluses" && operation.latestHistoryAttempts < MAX_LATEST_ATTEMPTS -> ApexReconciliationResult.NeedLatestRetry
            queryType == "LatestBoluses" -> ApexReconciliationResult.NeedFullHistory
            else -> ApexReconciliationResult.StillUncertain(operation.copy())
        }
    }

    fun currentForPump(pumpIdentityHash: String): ApexBolusOperation? = synchronized(lock) {
        operations.lastOrNull { it.unresolved && it.pumpIdentityHash == pumpIdentityHash }?.copy()
    }

    fun current(): ApexBolusOperation? = synchronized(lock) { operations.lastOrNull(ApexBolusOperation::unresolved)?.copy() }

    private inline fun update(operationUuid: String, block: (ApexBolusOperation) -> Unit) = synchronized(lock) {
        val operation = operations.find { it.operationUuid == operationUuid } ?: return@synchronized
        block(operation)
        persistAndPublishLocked(operation)
    }

    private fun persistAndPublishLocked(operation: ApexBolusOperation) {
        persistLocked()
        _active.value = operation.takeIf(ApexBolusOperation::unresolved)?.copy()
    }

    private fun persistLocked() = store.save(operations)

    private fun ApexBolusOperation.traceFields(): Map<String, Any?> = mapOf(
        "operationUuid" to operationUuid,
        "pumpIdentityHash" to pumpIdentityHash,
        "state" to state.name,
        "requestedU" to requestedUnits,
        "requestedSteps" to encodedSteps,
        "highestProgressSteps" to highestProgressSteps,
        "completedSteps" to completedSteps,
        "acceptedObserved" to acceptedObserved,
        "completedObserved" to completedObserved,
        "transportGeneration" to transportGeneration,
        "matchedHistoryTime" to matchedPumpHistoryTime,
        "matchedRequestedSteps" to matchedRequestedSteps,
        "matchedPerformedSteps" to matchedPerformedSteps,
    )

    private fun ApexHistoryCandidate.traceFields(): Map<String, Any?> = mapOf(
        "historyIndex" to index,
        "historyTimestamp" to timestamp,
        "pumpRequestedSteps" to requestedSteps,
        "pumpPerformedSteps" to performedSteps,
        "rawTimestampBytes" to rawTimestampHex,
        "rawObjectLength" to rawObjectLength,
        "queryType" to queryType,
        "resultPosition" to resultPosition,
    )
}

internal fun classifyHistory(entries: List<ApexHistoryCandidate>): ApexHistoryAnomalies {
    val ordered = entries.map(ApexHistoryCandidate::timestamp)
    val violations = ordered.zipWithNext().count { (left, right) -> right > left }
    val stale = ordered.withIndex().count { (position, value) ->
        val previous = ordered.getOrNull(position - 1)
        val next = ordered.getOrNull(position + 1)
        previous != null && next != null && previous - value > ApexBolusCoordinator.STALE_SLOT_THRESHOLD_MS && next - value > ApexBolusCoordinator.STALE_SLOT_THRESHOLD_MS
    }
    val duplicates = entries.size - entries.distinctBy { listOf(it.index, it.timestamp, it.requestedSteps, it.performedSteps) }.size
    val jumps = entries.zipWithNext().count { (left, right) -> abs(left.index - right.index) > 1 }
    return ApexHistoryAnomalies(ordered.maxOrNull(), ordered.minOrNull(), violations, stale, duplicates, jumps)
}

internal class ApexBolusJournalStore(private val file: File) {
    fun load(): List<ApexBolusOperation> {
        val source = when {
            file.exists() -> file
            backup().exists() -> backup()
            else -> return emptyList()
        }
        val root = JSONObject(source.readText())
        require(root.optInt("schemaVersion", -1) == 1) { "Unsupported Apex bolus journal schema" }
        val array = root.getJSONArray("operations")
        return (0 until array.length()).map { ApexBolusOperation.fromJson(array.getJSONObject(it)) }
    }

    fun save(operations: List<ApexBolusOperation>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.new")
        val payload = JSONObject().put("schemaVersion", 1).put("operations", JSONArray().apply { operations.forEach { put(it.toJson()) } }).toString()
        FileOutputStream(temp).use { output ->
            output.write(payload.toByteArray())
            output.flush()
            output.fd.sync()
        }
        val backup = backup()
        if (file.exists()) Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            backup.delete()
        } catch (error: Exception) {
            if (!file.exists() && backup.exists()) Files.move(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            throw error
        }
    }

    private fun backup() = File(file.parentFile, "${file.name}.bak")
}
