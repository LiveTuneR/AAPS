package app.aaps.pump.apex.connectivity.bluetooth

import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback

interface ApexTransport {
    fun setCallback(callback: ApexBluetoothCallback?)
    fun connect(generation: Long)
    fun disconnect()
    fun shutdown()
    suspend fun send(command: DeviceCommand): Boolean
}
