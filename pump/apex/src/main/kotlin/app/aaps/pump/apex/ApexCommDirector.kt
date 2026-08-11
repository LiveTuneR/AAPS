package app.aaps.pump.apex

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.connectivity.bluetooth.ApexTransport
import app.aaps.pump.apex.connectivity.bluetooth.Configuration
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
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
    )

    private data class Pending(
        val expected: PumpObject,
        val single: Boolean,
        val result: CompletableDeferred<List<PumpObjectModel>?>,
        val operationId: Long,
        val values: MutableList<PumpObjectModel> = mutableListOf(),
        var completionJob: Job? = null,
    )

    private var scope: CoroutineScope? = null
    private val events = Channel<LinkEvent>(Channel.UNLIMITED)
    private val requests = Channel<Request>(Configuration.COMM_BUFFERS_CAPACITY)
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

    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        trace.record(
            event = "command_queued",
            generation = activeGeneration,
            operationId = operationId,
            fields = mapOf("command" to command::class.simpleName, "safety" to safety),
        )
        queuedCommands.incrementAndGet()
        try {
            requests.send(Request(command, deferred, operationId, safety))
        } catch (error: CancellationException) {
            queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            throw error
        }
        return deferred.await()
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
                    while (true) {
                        val queued = requests.tryReceive().getOrNull() ?: break
                        queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                        queued.result.complete(null)
                    }
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
        for (request in requests) {
            queuedCommands.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            if (!awaitTransport()) {
                request.result.complete(null)
                continue
            }

            val expected = PumpObject.fromDeviceCommand(request.command)
            if (expected == null) {
                aapsLogger.error(LTag.PUMPCOMM, "No response mapping for ${request.command}")
                request.result.complete(null)
                continue
            }

            val now = SystemClock.uptimeMillis()
            val remainingGap = Configuration.COMMAND_GAP_MS - (now - lastSendUptime)
            if (remainingGap > 0) delay(remainingGap)

            val single = request.command !is GetValue || request.command.value.singleValueReturn
            val current = Pending(expected, single, request.result, request.operationId)
            synchronized(pendingLock) {
                pending = current
                diagnosticPendingCommand = request.command::class.simpleName
            }
            pendingStartedElapsedMs = SystemClock.elapsedRealtime()
            markProgress()
            trace.record(
                "command_started",
                activeGeneration,
                request.operationId,
                mapOf("command" to request.command::class.simpleName, "safety" to request.safety),
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
                    mapOf("command" to request.command::class.simpleName, "safety" to request.safety),
                )
                events.trySend(LinkEvent.TransportFault(activeGeneration, "command_timeout"))
            } else if (response != null) {
                trace.record(
                    "command_completed",
                    activeGeneration,
                    request.operationId,
                    mapOf("command" to request.command::class.simpleName, "values" to response.size),
                )
            }
            clearPending(current)
            markProgress()
            current.completionJob?.cancel()
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

    private fun classify(command: DeviceCommand): CommandSafety = when (command) {
        is GetValue -> CommandSafety.READ_ONLY
        else -> when (command::class.simpleName) {
            "Bolus", "TemporaryBasal", "ExtendedBolus", "CancelBolus", "CancelTemporaryBasal" -> CommandSafety.RECONCILE_REQUIRED
            else -> CommandSafety.IDEMPOTENT
        }
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
