package app.aaps.pump.apex.connectivity.bluetooth

class Configuration {
    companion object {
        // Gap between sending the next command after the previous one was executed by pump.
        // NOTE: For boluses, successful execution means only successful bolus start.
        const val COMMAND_GAP_MS = 1500L
        const val READ_ONLY_COMMAND_GAP_MS = 2000L

        // Pump heartbeats are unsolicited frames. The pump can ignore a command sent immediately after one.
        const val HEARTBEAT_COMMAND_GAP_MS = 2000L

        // When getting a complex value (values list), consider this time period of silence from pump
        // as a completed command.
        const val VALUE_COMPLETION_TIMEOUT = 500L

        // Timeout for pump to response to a command. When it fires, command is marked as failed.
        // Cause of buggy Bluetooth in pump, a connection to it may simply become "dead".
        // In such cases we try to reconnect to the pump.
        const val PUMP_RESPONSE_TIMEOUT = 10000L

        // Maximum number of commands in queue.
        const val COMM_BUFFERS_CAPACITY = 8

        // Timeout for command to be sent to pump from the commands queue.
        // Command will be deleted from queue and marked as failed if this fires.
        const val REQUEST_ISSUE_TIMEOUT = COMM_BUFFERS_CAPACITY * READ_ONLY_COMMAND_GAP_MS + 10000L
    }
}
