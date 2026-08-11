package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.bluetooth.ApexTransport
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.concurrent.CopyOnWriteArrayList

class ApexCommDirectorTest : TestBase() {

    @Test
    fun `late transport callbacks cannot leave backoff or stopped state`() = runBlocking {
        val transport = FakeTransport()
        val director = ApexCommDirector(transport, aapsLogger, FakeDeviceInfo(), mock<ApexTrace>())
        director.setCallback(object : ApexCommDirector.Callback {
            override suspend fun onHandshake() = ApexCommDirector.HandshakeResult.READY
            override fun onDisconnected(reason: String) = Unit
            override suspend fun onPumpData(value: PumpObjectModel) = Unit
        })
        director.start()
        try {
            director.connect()
            awaitState(director) { it is ApexCommDirector.LinkState.Connecting }
            repeat(100) {
                if (transport.connectGenerations.isNotEmpty()) return@repeat
                delay(10L)
            }
            assertThat(transport.connectGenerations).containsExactly(1L)

            transport.connected(1L)
            awaitState(director) { it is ApexCommDirector.LinkState.Ready }

            transport.disconnected(1L)
            awaitState(director) { it is ApexCommDirector.LinkState.Backoff }
            transport.connected(1L)
            delay(100L)
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Backoff::class.java)

            director.stop()
            awaitState(director) { it is ApexCommDirector.LinkState.Stopped }
            transport.connected(1L)
            delay(100L)
            assertThat(director.linkState.value).isEqualTo(ApexCommDirector.LinkState.Stopped)
        } finally {
            director.shutdown()
        }
    }

    private suspend fun awaitState(
        director: ApexCommDirector,
        predicate: (ApexCommDirector.LinkState) -> Boolean,
    ) {
        repeat(100) {
            if (predicate(director.linkState.value)) return
            delay(10L)
        }
        throw AssertionError("Timed out waiting for state; current=${director.linkState.value}")
    }

    private class FakeTransport : ApexTransport {
        private var callback: ApexBluetoothCallback? = null
        val connectGenerations = CopyOnWriteArrayList<Long>()

        override fun setCallback(callback: ApexBluetoothCallback?) {
            this.callback = callback
        }

        override fun connect(generation: Long) {
            connectGenerations += generation
        }

        override fun disconnect() = Unit
        override fun shutdown() = Unit
        override suspend fun send(command: DeviceCommand) = true

        fun connected(generation: Long) = callback?.onConnect(generation)
        fun disconnected(generation: Long) = callback?.onDisconnect(generation)
    }

    private class FakeDeviceInfo : ApexDeviceInfo {
        override var serialNumber = "12345678"
        override val version: Version? = null
    }
}
