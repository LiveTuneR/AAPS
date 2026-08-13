package app.aaps.pump.apex

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.connectivity.bluetooth.ApexTransport
import app.aaps.pump.apex.connectivity.bluetooth.Configuration
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.CancelBolus
import app.aaps.pump.apex.connectivity.commands.device.GetValue
import app.aaps.pump.apex.connectivity.commands.pump.AlarmObject
import app.aaps.pump.apex.connectivity.commands.pump.BasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.CommandResponse
import app.aaps.pump.apex.connectivity.commands.pump.Heartbeat
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObject
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.TDDEntry
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class ApexCommDirector @Inject constructor(
    private val apexBluetooth: ApexTransport,
    private val aapsLogger: AAPSLogger,
    private val apexDeviceInfo: ApexDeviceInfo,
    private val trace: ApexTrace,
) : ApexBluetoothCallback {

    private enum class CommandSafety { READ_ONLY, IDEMPOTENT, RECONCILE_REQUIRED }
    private enum class RequestPriority { CANCEL_BOLUS, START_BOLUS, NORMAL }
    private enum class RequestSlotPool { CANCEL_BOLUS, START_BOLUS, NORMAL }

    data class DiagnosticSnapshot(
        val state: String,
        val generation: Long,
        val desiredConnection: Boolean,
        val queuedCommands: Int,
        val pendingCommand: String?,
        val pendingAgeMs: Long?,
        val stateAgeMs: Long,
        val progressAgeMs: Long,
    )

    sealed interface LinkState {
        val generation: Long

        data object Stopped : LinkState { override val generation = 0L }
        data class Disconnected(override val generation: Long, val reason: String) : LinkState
        data class Connecting(override val generation: Long) : LinkState
        data class Handshaking(override val generation: Long) : LinkState
        data class Ready(override val generation: Long) : LinkState
        data class Incompatible(override val generation: Long, val reason: String) : LinkState
        data class Backoff(override val generation: Long, val attempt: Int, val delayMs: Long) : LinkState
    }

    enum class HandshakeResult { READY, RETRYABLE_FAILURE, INCOMPATIBLE }

    private sealed interface LinkEvent {
        data object Start : LinkEvent
        data object Stop : LinkEvent
        data class Connected(val generation: Long) : LinkEvent
        data class Disconnected(val generation: Long, val reason: String) : LinkEvent
        data class Frame(val generation: Long, val command: PumpCommand) : LinkEvent
        data class HandshakeFinished(val generation: Long, val result: HandshakeResult) : LinkEvent
        data class Retry(val generation: Long) : LinkEvent
        data class TransportFault(val generation: Long, val reason: String) : LinkEvent
    }

    private data class Request(
        val command: DeviceCommand,
        val result: CompletableDeferred<List<PumpObjectModel>?>,
        val operationId: Long,
        val safety: CommandSafety,
        val priority: RequestPriority,
        val slotPool: RequestSlotPool,
        val issued: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private data class Pending(
        val expected: PumpObject,
        val single: Boolean,
        val result: CompletableDeferred<List<PumpObjectModel>?>,
        val operationId: Long,
        val safety: CommandSafety,
        val commandName: String?,
        val values: MutableList<PumpObjectModel> = mutableListOf(),
        var completionJob: Job? = null,
    )

    private data class CommandGap(val reason: String, val requiredMs: Long)

    private var scope: CoroutineScope? = null
    private val events = Channel<LinkEvent>(Channel.UNLIMITED)
    private val requestSignal = Channel<Unit>(Channel.CONFLATED)
    private val requestQueueLock = Any()
    private val normalRequestSlots = Semaphore(Configuration.COMM_BUFFERS_CAPACITY)
    private val cancelBolusRequestSlots = Semaphore(1)
    private val startBolusRequestSlots = Semaphore(1)
    private val cancelBolusRequests = ArrayDeque<Request>()
    private val startBolusRequests = ArrayDeque<Request>()
    private val normalRequests = ArrayDeque<Request>()
    private val pumpData = Channel<PumpObjectModel>(Channel.UNLIMITED)
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Stopped)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private var listener: Callback? = null
    @Volatile private var desiredConnection = false
    private var activeGeneration = 0L
    private var reconnectAttempt = 0
    private val pendingLock = Any()
    private var pending: Pending? = null
    private var lastSendUptime = 0L
    @Volatile private var lastHeartbeatUptime = 0L
    private var handshakeJob: Job? = null
    private var reconnectJob: Job? = null
    private val queuedCommands = AtomicInteger(0)
    @Volatile private var diagnosticPendingCommand: String? = null
    @Volatile private var pendingStartedElapsedMs = 0L
    @Volatile private var stateEnteredElapsedMs = SystemClock.elapsedRealtime()
    @Volatile private var lastProgressElapsedMs = SystemClock.elapsedRealtime()

    fun setCallback(callback: Callback?) {
        listener = callback
    }

    fun start(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = newScope
        apexBluetooth.setCallback(this)
        newScope.launch { linkLoop() }
        newScope.launch { commandLoop() }
        newScope.launch { dataLoop() }
    }

    fun stop() {
        events.trySend(LinkEvent.Stop)
    }

    fun shutdown() {
        stop()
        cancelPending()
        failQueuedRequests()
        scope?.cancel()
        scope = null
        apexBluetooth.shutdown()
        _linkState.value = LinkState.Stopped
    }

    fun connect() {
        events.trySend(LinkEvent.Start)
    }

    fun disconnect() {
        events.trySend(LinkEvent.Stop)
    }

    fun diagnosticSnapshot(): DiagnosticSnapshot {
        val now = SystemClock.elapsedRealtime()
        val state = linkState.value
        val pendingCommand = diagnosticPendingCommand
        return DiagnosticSnapshot(
            state = state::class.simpleName ?: "Unknown",
            generation = state.generation,
            desiredConnection = desiredConnection,
            queuedCommands = queuedCommands.get(),
            pendingCommand = pendingCommand,
            pendingAgeMs = if (pendingCommand == null) null else (now - pendingStartedElapsedMs).coerceAtLeast(0L),
            stateAgeMs = (now - stateEnteredElapsedMs).coerceAtLeast(0L),
            progressAgeMs = (now - lastProgressElapsedMs).coerceAtLeast(0L),
        )
    }

    suspend fun request(value: GetValue.Value): List<PumpObjectModel>? =
        submit(GetValue(apexDeviceInfo, value))

    suspend fun execute(command: DeviceCommand): CommandResponse? =
        submit(command)?.singleOrNull() as? CommandResponse

    private suspend fun submit(command: DeviceCommand): List<PumpObjectModel>? {
        val deferred = CompletableDeferred<List<PumpObjectModel>?>()
        val operationId = trace.nextOperationId()
        val safety = classify(command)
        val priority = priority(command)
        val slotPool = when (priority) {
            RequestPriority.CANCEL_BOLUS -> RequestSlotPool.CANCEL_BOLUS
            RequestPriority.START_BOLUS  -> RequestSlotPool.START_BOLUS
            RequestPriority.NORMAL       -> RequestSlotPool.NORMAL
        }
        val request = Request(command, deferred, operationId, safety, priority, slotPool)
        val slotAcquired = withTimeoutOrNull(Configuration.REQUEST_ISSUE_TIMEOUT) {
            slots(request).acquire()
            true
        } == true
        if (!slotAcquired) {
            trace.record(
                "command_queue_capacity_timeout",
                activeGeneration,
                operationId,
                commandFields(command, "priority" to priority),
            )
            return null
        }
        trace.record(
            event = "command_queued",
            generation = activeGeneration,
            operationId = operationId,
            fields = commandFields(command, "safety" to safety, "priority" to priority),
        )
        enqueue(request)
        if (priority == RequestPriority.CANCEL_BOLUS) preemptReadOnlyForCancel(request)
        try {
            val issued = withTimeoutOrNull(Configuration.REQUEST_ISSUE_TIMEOUT) { request.issued.await() }
            if (issued != true && request.issued.complete(false)) {
                request.result.complete(null)
                trace.record(
                    "command_issue_timeout",
                    activeGeneration,
                    operationId,
                    commandFields(command, "priority" to priority),
                )
                requestSignal.trySend(Unit)
                return null
            }
            return deferred.await()
        } catch (error: CancellationException) {
            if (request.issued.complete(false)) {
                request.result.complete(null)
                requestSignal.trySend(Unit)
            }
            throw error
        }
    }

    private fun enqueue(request: Request) {
        synchronized(requestQueueLock) {
            when (request.priority) {
                RequestPriority.CANCEL_BOLUS -> cancelBolusRequests.addLast(request)
                RequestPriority.START_BOLUS  -> startBolusRequests.addLast(request)
                RequestPriority.NORMAL       -> normalRequests.addLast(request)
            }
            queuedCommands.incrementAndGet()
        }
        requestSignal.trySend(Unit)
    }

    private fun pollRequest(): Request? = synchronized(requestQueueLock) {
        while (true) {
            val request = when {
                cancelBolusRequests.isNotEmpty() -> cancelBolusRequests.removeFirst()
                startBolusRequests.isNotEmpty()  -> startBolusRequests.removeFirst()
                normalRequests.isNotEmpty()      -> normalRequests.removeFirst()
                else                             -> return@synchronized null
            }
            queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            slots(request).release()
            if (request.issued.complete(true)) return@synchronized request
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun hasQueuedRequests(): Boolean = synchronized(requestQueueLock) {
        cancelBolusRequests.isNotEmpty() || startBolusRequests.isNotEmpty() || normalRequests.isNotEmpty()
    }

    private fun discardExpiredRequests() {
        synchronized(requestQueueLock) {
            listOf(cancelBolusRequests, startBolusRequests, normalRequests).forEach { queue ->
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val request = iterator.next()
                    if (request.issued.isCompleted) {
                        iterator.remove()
                        queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                        slots(request).release()
                    }
                }
            }
        }
    }

    override fun onConnect(generation: Long) {
        trace.record("transport_connected", generation)
        events.trySend(LinkEvent.Connected(generation))
    }

    override fun onDisconnect(generation: Long) {
        trace.record("transport_disconnected", generation)
        events.trySend(LinkEvent.Disconnected(generation, "gatt_disconnected"))
    }

    override fun onPumpCommand(generation: Long, command: PumpCommand) {
        markProgress()
        trace.record(
            "frame_received",
            generation,
            fields = mapOf("object" to command.id?.name, "bytes" to command.objectData.size),
        )
        events.trySend(LinkEvent.Frame(generation, command))
    }

    private suspend fun linkLoop() {
        for (event in events) {
            when (event) {
                LinkEvent.Start -> {
                    desiredConnection = true
                    if (_linkState.value is LinkState.Stopped || _linkState.value is LinkState.Disconnected || _linkState.value is LinkState.Backoff) {
                        beginConnection()
                    }
                }

                LinkEvent.Stop -> {
                    desiredConnection = false
                    handshakeJob?.cancel()
                    handshakeJob = null
                    reconnectJob?.cancel()
                    reconnectJob = null
                    cancelPending()
                    failQueuedRequests()
                    apexBluetooth.disconnect()
                    transition(LinkState.Stopped)
                }

                is LinkEvent.Connected -> {
                    if (event.generation != activeGeneration || _linkState.value !is LinkState.Connecting) {
                        logStale("connected", event.generation)
                        continue
                    }
                    transition(LinkState.Handshaking(event.generation))
                    handshakeJob?.cancel()
                    handshakeJob = scope?.launch {
                        val success = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                            listener?.onHandshake() ?: HandshakeResult.RETRYABLE_FAILURE
                        } ?: HandshakeResult.RETRYABLE_FAILURE
                        events.send(LinkEvent.HandshakeFinished(event.generation, success))
                    }
                }

                is LinkEvent.HandshakeFinished -> {
                    if (event.generation != activeGeneration || _linkState.value !is LinkState.Handshaking) {
                        logStale("handshake", event.generation)
                        continue
                    }
                    handshakeJob = null
                    when (event.result) {
                        HandshakeResult.READY -> {
                            reconnectAttempt = 0
                            transition(LinkState.Ready(event.generation))
                        }
                        HandshakeResult.RETRYABLE_FAILURE -> scheduleReconnect("handshake_failed")
                        HandshakeResult.INCOMPATIBLE -> {
                            desiredConnection = false
                            apexBluetooth.disconnect()
                            transition(LinkState.Incompatible(event.generation, "firmware_protocol"))
                        }
                    }
                }

                is LinkEvent.Disconnected -> {
                    if (event.generation != activeGeneration || _linkState.value is LinkState.Stopped || _linkState.value is LinkState.Backoff || _linkState.value is LinkState.Incompatible) {
                        logStale("disconnected", event.generation)
                        continue
                    }
                    handshakeJob?.cancel()
                    handshakeJob = null
                    cancelPending()
                    if (desiredConnection) scheduleReconnect(event.reason)
                    else {
                        listener?.onDisconnected(event.reason)
                        transition(LinkState.Disconnected(event.generation, event.reason))
                    }
                }

                is LinkEvent.Frame -> {
                    if (event.generation != activeGeneration || (_linkState.value !is LinkState.Handshaking && _linkState.value !is LinkState.Ready)) {
                        logStale("frame", event.generation)
                        continue
                    }
                    handleFrame(event.command)
                }

                is LinkEvent.Retry -> {
                    if (desiredConnection && event.generation == activeGeneration && _linkState.value is LinkState.Backoff) beginConnection()
                }

                is LinkEvent.TransportFault -> {
                    if (event.generation != activeGeneration || _linkState.value is LinkState.Stopped || _linkState.value is LinkState.Backoff || _linkState.value is LinkState.Incompatible) {
                        logStale("fault", event.generation)
                        continue
                    }
                    if (desiredConnection) scheduleReconnect(event.reason)
                }
            }
        }
    }

    private fun beginConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        activeGeneration++
        transition(LinkState.Connecting(activeGeneration))
        trace.record("connect_begin", activeGeneration)
        apexBluetooth.connect(activeGeneration)
    }

    private fun scheduleReconnect(reason: String) {
        if (_linkState.value is LinkState.Backoff) return
        handshakeJob?.cancel()
        handshakeJob = null
        apexBluetooth.disconnect()
        listener?.onDisconnected(reason)
        reconnectAttempt++
        val delayMs = reconnectDelayMs(reconnectAttempt)
        transition(LinkState.Backoff(activeGeneration, reconnectAttempt, delayMs))
        trace.record(
            "reconnect_scheduled",
            activeGeneration,
            fields = mapOf("reason" to reason, "attempt" to reconnectAttempt, "delayMs" to delayMs),
        )
        aapsLogger.warn(LTag.PUMPCOMM, "Apex reconnect scheduled: reason=$reason attempt=$reconnectAttempt delayMs=$delayMs")
        val generation = activeGeneration
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(delayMs)
            events.send(LinkEvent.Retry(generation))
        }
    }

    private suspend fun commandLoop() {
        while (true) {
            requestSignal.receive()
            if (!awaitTransport()) {
                discardExpiredRequests()
                if (linkState.value is LinkState.Stopped || linkState.value is LinkState.Incompatible) failQueuedRequests()
                if (hasQueuedRequests()) requestSignal.trySend(Unit)
                continue
            }
            val request = pollRequest() ?: continue
            if (hasQueuedRequests()) requestSignal.trySend(Unit)

            val expected = PumpObject.fromDeviceCommand(request.command)
            if (expected == null) {
                aapsLogger.error(LTag.PUMPCOMM, "No response mapping for ${request.command}")
                request.result.complete(null)
                continue
            }

            val commandGap = awaitCommandGap(request)

            val single = request.command !is GetValue || request.command.value.singleValueReturn
            val current = Pending(
                expected = expected,
                single = single,
                result = request.result,
                operationId = request.operationId,
                safety = request.safety,
                commandName = commandName(request.command),
            )
            synchronized(pendingLock) {
                pending = current
                diagnosticPendingCommand = commandName(request.command)
            }
            pendingStartedElapsedMs = SystemClock.elapsedRealtime()
            markProgress()
            trace.record(
                "command_started",
                activeGeneration,
                request.operationId,
                commandFields(
                    request.command,
                    "safety" to request.safety,
                    "priority" to request.priority,
                    "gapReason" to commandGap.reason,
                    "requiredGapMs" to commandGap.requiredMs,
                ),
            )
            if (!apexBluetooth.send(request.command)) {
                trace.record("command_write_failed", activeGeneration, request.operationId)
                request.result.complete(null)
                clearPending(current)
                events.trySend(LinkEvent.TransportFault(activeGeneration, "write_failed"))
                continue
            }
            lastSendUptime = SystemClock.uptimeMillis()

            val timeout = if (single) Configuration.PUMP_RESPONSE_TIMEOUT else COMPLEX_RESPONSE_TIMEOUT_MS
            val response = withTimeoutOrNull(timeout) { request.result.await() }
            if (response == null && !request.result.isCompleted) {
                aapsLogger.error(LTag.PUMPCOMM, "Apex command timeout: ${request.command}; no automatic retry")
                request.result.complete(null)
                trace.record(
                    "command_timeout",
                    activeGeneration,
                    request.operationId,
                    commandFields(request.command, "safety" to request.safety, "expected" to expected.name),
                )
                events.trySend(LinkEvent.TransportFault(activeGeneration, "command_timeout"))
            } else if (response != null) {
                trace.record(
                    "command_completed",
                    activeGeneration,
                    request.operationId,
                    commandFields(request.command, "values" to response.size),
                )
            }
            clearPending(current)
            markProgress()
            current.completionJob?.cancel()
        }
    }

    private fun failQueuedRequests() {
        val failed = synchronized(requestQueueLock) {
            buildList {
                listOf(cancelBolusRequests, startBolusRequests, normalRequests).forEach { queue ->
                    while (queue.isNotEmpty()) {
                        val request = queue.removeFirst()
                        add(request)
                        queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                        slots(request).release()
                    }
                }
            }
        }
        failed.forEach { request ->
            request.issued.complete(false)
            request.result.complete(null)
        }
    }

    private suspend fun awaitTransport(): Boolean = withTimeoutOrNull(Configuration.REQUEST_ISSUE_TIMEOUT) {
        when (linkState.first {
            it is LinkState.Handshaking || it is LinkState.Ready || it is LinkState.Stopped || it is LinkState.Incompatible
        }) {
            is LinkState.Handshaking, is LinkState.Ready -> true
            else -> false
        }
    } ?: false

    private fun handleFrame(command: PumpCommand) {
        val id = command.id ?: run {
            aapsLogger.error(LTag.PUMPCOMM, "Apex frame has no command id")
            return
        }
        val type = PumpObject.findObject(id, command.objectData, aapsLogger) ?: return
        val value = decode(type, command) ?: return
        val validationError = value.validate()
        if (validationError != null) {
            aapsLogger.error(LTag.PUMPCOMM, "Invalid Apex $type: $validationError")
            return
        }
        if (type == PumpObject.Heartbeat) lastHeartbeatUptime = SystemClock.uptimeMillis()

        var matched = false
        synchronized(pendingLock) {
            val current = pending
            if (current == null || current.expected != type) return@synchronized
            matched = true

            if (type == PumpObject.BolusEntry && command.objectData.size >= 4 &&
                command.objectData[2].toInt() and 0xFF == 0xFF && command.objectData[3].toInt() and 0xFF == 0xFF
            ) {
                trace.record("command_response_end", activeGeneration, current.operationId, mapOf("object" to type.name))
                pending = null
                diagnosticPendingCommand = null
                current.result.complete(emptyList())
                return@synchronized
            }

            current.values += value
            trace.record(
                "command_response",
                activeGeneration,
                current.operationId,
                mapOf("object" to type.name, "count" to current.values.size),
            )
            if (current.single) {
                pending = null
                diagnosticPendingCommand = null
                current.result.complete(current.values.toList())
            } else {
                current.completionJob?.cancel()
                current.completionJob = scope?.launch {
                    delay(Configuration.VALUE_COMPLETION_TIMEOUT)
                    completePendingList(current)
                }
            }
        }
        if (!matched) {
            pumpData.trySend(value)
        }
    }

    private fun completePendingList(current: Pending) {
        synchronized(pendingLock) {
            if (pending !== current) return
            pending = null
            diagnosticPendingCommand = null
            current.result.complete(current.values.toList())
        }
    }

    private fun clearPending(current: Pending) {
        synchronized(pendingLock) {
            if (pending !== current) return
            pending = null
            diagnosticPendingCommand = null
        }
    }

    private fun cancelPending() {
        val current = synchronized(pendingLock) {
            val value = pending
            pending = null
            diagnosticPendingCommand = null
            value
        }
        current?.completionJob?.cancel()
        current?.result?.complete(null)
    }

    private fun preemptReadOnlyForCancel(cancelRequest: Request) {
        val current = synchronized(pendingLock) {
            val value = pending
            if (value?.safety != CommandSafety.READ_ONLY) return@synchronized null
            pending = null
            diagnosticPendingCommand = null
            value
        } ?: return
        current.completionJob?.cancel()
        current.result.complete(null)
        trace.record(
            "command_preempted_for_cancel",
            activeGeneration,
            current.operationId,
            mapOf(
                "command" to current.commandName,
                "cancelOperationId" to cancelRequest.operationId,
            ),
        )
        markProgress()
    }

    private fun slots(request: Request): Semaphore = when (request.slotPool) {
        RequestSlotPool.CANCEL_BOLUS -> cancelBolusRequestSlots
        RequestSlotPool.START_BOLUS  -> startBolusRequestSlots
        RequestSlotPool.NORMAL       -> normalRequestSlots
    }

    private fun decode(type: PumpObject, command: PumpCommand): PumpObjectModel? = when (type) {
        PumpObject.Heartbeat -> Heartbeat()
        PumpObject.CommandResponse -> CommandResponse(command)
        PumpObject.StatusV1 -> StatusV1(command, apexDeviceInfo)
        PumpObject.StatusV2 -> StatusV2(command)
        PumpObject.BasalProfile -> BasalProfile(command)
        PumpObject.AlarmEntry -> AlarmObject(command, apexDeviceInfo)
        PumpObject.TDDEntry -> TDDEntry(command, apexDeviceInfo)
        PumpObject.BolusEntry -> BolusEntry(command, apexDeviceInfo)
        PumpObject.FirmwareEntry -> Version(command)
        PumpObject.WizardStatus -> null
    }

    private fun transition(newState: LinkState) {
        val previous = _linkState.value
        if (previous == newState) return
        _linkState.value = newState
        stateEnteredElapsedMs = SystemClock.elapsedRealtime()
        markProgress()
        trace.record(
            "link_state",
            newState.generation,
            fields = mapOf("from" to previous::class.simpleName, "to" to newState::class.simpleName),
        )
        aapsLogger.debug(LTag.PUMPCOMM, "Apex LinkState: ${previous::class.simpleName} -> ${newState::class.simpleName} gen=${newState.generation}")
    }

    private suspend fun dataLoop() {
        for (value in pumpData) listener?.onPumpData(value)
    }

    private fun logStale(event: String, generation: Long) {
        trace.record("stale_event_ignored", generation, fields = mapOf("kind" to event, "activeGeneration" to activeGeneration))
        aapsLogger.warn(LTag.PUMPCOMM, "Ignored stale Apex $event: callbackGen=$generation activeGen=$activeGeneration")
    }

    private fun markProgress() {
        lastProgressElapsedMs = SystemClock.elapsedRealtime()
    }

    private suspend fun awaitCommandGap(request: Request): CommandGap {
        while (true) {
            val observedLastSend = lastSendUptime
            val observedHeartbeat = lastHeartbeatUptime
            val heartbeatIsLatest = observedHeartbeat > observedLastSend && request.priority != RequestPriority.CANCEL_BOLUS
            val gap = when {
                heartbeatIsLatest                    -> CommandGap("heartbeat", Configuration.HEARTBEAT_COMMAND_GAP_MS)
                request.safety == CommandSafety.READ_ONLY -> CommandGap("read_only", Configuration.READ_ONLY_COMMAND_GAP_MS)
                else                                 -> CommandGap("standard", Configuration.COMMAND_GAP_MS)
            }
            val lastRelevantActivity = if (request.priority == RequestPriority.CANCEL_BOLUS) {
                observedLastSend
            } else {
                maxOf(observedLastSend, observedHeartbeat)
            }
            val remainingMs = gap.requiredMs - (SystemClock.uptimeMillis() - lastRelevantActivity)
            if (remainingMs <= 0) return gap
            delay(remainingMs)
            if (lastSendUptime == observedLastSend && lastHeartbeatUptime == observedHeartbeat) return gap
        }
    }

    private fun commandFields(command: DeviceCommand, vararg fields: Pair<String, Any?>): Map<String, Any?> = buildMap {
        put("command", command::class.simpleName)
        if (command is GetValue) put("value", command.value.name)
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun commandName(command: DeviceCommand): String =
        if (command is GetValue) command.toString() else command::class.simpleName ?: command.toString()

    private fun classify(command: DeviceCommand): CommandSafety = when (command) {
        is GetValue -> CommandSafety.READ_ONLY
        else -> when (command::class.simpleName) {
            "Bolus", "TemporaryBasal", "ExtendedBolus", "CancelBolus", "CancelTemporaryBasal" -> CommandSafety.RECONCILE_REQUIRED
            else -> CommandSafety.IDEMPOTENT
        }
    }

    private fun priority(command: DeviceCommand): RequestPriority = when (command) {
        is CancelBolus -> RequestPriority.CANCEL_BOLUS
        is Bolus       -> RequestPriority.START_BOLUS
        else           -> RequestPriority.NORMAL
    }

    interface Callback {
        suspend fun onHandshake(): HandshakeResult
        fun onDisconnected(reason: String)
        suspend fun onPumpData(value: PumpObjectModel)
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val COMPLEX_RESPONSE_TIMEOUT_MS = 30_000L
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L

        internal fun reconnectDelayMs(attempt: Int): Long =
            (RECONNECT_BASE_MS * (1L shl (attempt.coerceAtLeast(1) - 1).coerceAtMost(4)))
                .coerceAtMost(RECONNECT_MAX_MS)
    }
}
