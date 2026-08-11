package app.aaps.pump.apex.interfaces

import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand

interface ApexBluetoothCallback {
    fun onConnect(generation: Long)
    fun onDisconnect(generation: Long)
    fun onPumpCommand(generation: Long, command: PumpCommand)
}
