package app.aaps.pump.apex.connectivity.bluetooth

import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback

interface ApexTransport {
    fun setCallback(callback: ApexBluetoothCallback?)
    fun connect(generation: Long)
    fun disconnect()
    fun shutdown()
    suspend fun send(command: DeviceCommand, onFirstWriteIssued: (() -> Unit)? = null): ApexTransportWriteOutcome
}

enum class ApexTransportWriteOutcome {
    NOT_ISSUED,
    ISSUED_CONFIRMED_BY_GATT,
    ISSUED_OUTCOME_UNKNOWN,
}

internal fun rejectedWriteOutcome(anyChunkIssued: Boolean): ApexTransportWriteOutcome =
    if (anyChunkIssued) ApexTransportWriteOutcome.ISSUED_OUTCOME_UNKNOWN else ApexTransportWriteOutcome.NOT_ISSUED
