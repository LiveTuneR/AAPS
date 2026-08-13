package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.bluetooth.ApexTransport
import app.aaps.pump.apex.connectivity.bluetooth.Configuration
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.CancelBolus
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.device.GetValue
import app.aaps.pump.apex.connectivity.commands.device.UpdateSystemState
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class ApexCommDirectorTest : TestBase() {

    @Test
    fun `late transport callbacks cannot leave backoff or stopped state`() = runTest {
        val fixture = fixture()
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()
            assertThat(transport.connectGenerations).containsExactly(1L)

            transport.connected(1L)
            runCurrent()
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Ready::class.java)

            transport.disconnected(1L)
            runCurrent()
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Backoff::class.java)

            transport.connected(1L)
            runCurrent()
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Backoff::class.java)

            director.stop()
            runCurrent()
            transport.connected(1L)
            runCurrent()
            assertThat(director.linkState.value).isEqualTo(ApexCommDirector.LinkState.Stopped)
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `cancel and bolus overtake queued reads when transport becomes ready`() = runTest {
        val fixture = fixture(autoRespondToWrites = true)
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()

            repeat(Configuration.COMM_BUFFERS_CAPACITY) {
                backgroundScope.launch { director.request(GetValue.Value.StatusV2) }
            }
            backgroundScope.launch { director.execute(Bolus(fixture.info, 230)) }
            backgroundScope.launch { director.execute(CancelBolus(fixture.info)) }
            runCurrent()
            assertThat(director.diagnosticSnapshot().queuedCommands)
                .isEqualTo(Configuration.COMM_BUFFERS_CAPACITY + 2)

            transport.connected(1L)
            runCurrent()
            advanceTimeBy(Configuration.COMMAND_GAP_MS * 2 + 1)
            runCurrent()

            assertThat(transport.sent.take(2).map { it::class.simpleName })
                .containsExactly("CancelBolus", "Bolus")
                .inOrder()
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `cancel preempts an in flight read`() = runTest {
        val fixture = fixture(autoRespondToWrites = true)
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()
            transport.connected(1L)
            runCurrent()

            val read = async { director.request(GetValue.Value.StatusV2) }
            runCurrent()
            advanceTimeBy(Configuration.READ_ONLY_COMMAND_GAP_MS + 1)
            runCurrent()
            assertThat(transport.sent.map { it::class.simpleName }).containsExactly("GetValue")

            val cancel = async { director.execute(CancelBolus(fixture.info)) }
            runCurrent()
            assertThat(read.await()).isNull()

            advanceTimeBy(Configuration.COMMAND_GAP_MS + 1)
            runCurrent()
            assertThat(transport.sent.map { it::class.simpleName })
                .containsExactly("GetValue", "CancelBolus")
                .inOrder()
            assertThat(cancel.await()).isNotNull()
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `normal command waits after an unsolicited heartbeat`() = runTest {
        val fixture = fixture(autoRespondToWrites = true)
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()
            transport.connected(1L)
            runCurrent()
            transport.heartbeat(1L)
            runCurrent()

            val result = async { director.execute(UpdateSystemState(fixture.info, false)) }
            runCurrent()
            assertThat(transport.sent).isEmpty()

            advanceTimeBy(Configuration.HEARTBEAT_COMMAND_GAP_MS / 2)
            runCurrent()
            assertThat(transport.sent).isEmpty()

            advanceTimeBy(Configuration.HEARTBEAT_COMMAND_GAP_MS / 2 + 1)
            runCurrent()
            assertThat(transport.sent.map { it::class.simpleName }).containsExactly("UpdateSystemState")
            assertThat(result.await()).isNotNull()
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `read command uses the wider read only gap`() = runTest {
        val fixture = fixture(autoRespondToWrites = true)
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()
            transport.connected(1L)
            runCurrent()

            val command = async { director.execute(UpdateSystemState(fixture.info, false)) }
            runCurrent()
            assertThat(command.await()).isNotNull()

            backgroundScope.launch { director.request(GetValue.Value.StatusV2) }
            runCurrent()
            advanceTimeBy(Configuration.COMMAND_GAP_MS + 1)
            runCurrent()
            assertThat(transport.sent.map { it::class.simpleName }).containsExactly("UpdateSystemState")

            advanceTimeBy(Configuration.READ_ONLY_COMMAND_GAP_MS - Configuration.COMMAND_GAP_MS)
            runCurrent()
            assertThat(transport.sent.map { it::class.simpleName })
                .containsExactly("UpdateSystemState", "GetValue")
                .inOrder()
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `queued request expires without a transport and releases its slot`() = runTest {
        val fixture = fixture()
        val director = fixture.director
        try {
            director.connect()
            runCurrent()
            val result = async { director.execute(UpdateSystemState(fixture.info, false)) }
            runCurrent()

            advanceTimeBy(Configuration.REQUEST_ISSUE_TIMEOUT + 1)
            runCurrent()

            assertThat(result.await()).isNull()
            assertThat(director.diagnosticSnapshot().queuedCommands).isEqualTo(0)
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    @Test
    fun `disconnect completes an in flight command and late response is ignored`() = runTest {
        val fixture = fixture(autoRespondToWrites = false)
        val director = fixture.director
        val transport = fixture.transport
        try {
            director.connect()
            runCurrent()
            transport.connected(1L)
            runCurrent()

            val result = async { director.execute(UpdateSystemState(fixture.info, false)) }
            runCurrent()
            advanceTimeBy(Configuration.COMMAND_GAP_MS + 1)
            runCurrent()
            assertThat(transport.sent).hasSize(1)

            transport.disconnected(1L)
            runCurrent()
            assertThat(result.await()).isNull()
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Backoff::class.java)

            transport.response(1L)
            runCurrent()
            assertThat(director.linkState.value).isInstanceOf(ApexCommDirector.LinkState.Backoff::class.java)
        } finally {
            director.shutdown()
            runCurrent()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(autoRespondToWrites: Boolean = false): Fixture {
        val transport = FakeTransport(autoRespondToWrites)
        val info = FakeDeviceInfo()
        val director = ApexCommDirector(transport, aapsLogger, info, mock<ApexTrace>())
        director.setCallback(object : ApexCommDirector.Callback {
            override suspend fun onHandshake() = ApexCommDirector.HandshakeResult.READY
            override fun onDisconnected(reason: String) = Unit
            override suspend fun onPumpData(value: PumpObjectModel) = Unit
        })
        director.start(StandardTestDispatcher(testScheduler))
        runCurrent()
        return Fixture(director, transport, info)
    }

    private data class Fixture(
        val director: ApexCommDirector,
        val transport: FakeTransport,
        val info: FakeDeviceInfo,
    )

    private class FakeTransport(
        private val autoRespondToWrites: Boolean,
    ) : ApexTransport {
        private var callback: ApexBluetoothCallback? = null
        private var generation = 0L
        val connectGenerations = CopyOnWriteArrayList<Long>()
        val sent = CopyOnWriteArrayList<DeviceCommand>()

        override fun setCallback(callback: ApexBluetoothCallback?) {
            this.callback = callback
        }

        override fun connect(generation: Long) {
            this.generation = generation
            connectGenerations += generation
        }

        override fun disconnect() = Unit
        override fun shutdown() = Unit

        override suspend fun send(command: DeviceCommand): Boolean {
            sent += command
            if (autoRespondToWrites && command !is GetValue) response(generation)
            return true
        }

        fun connected(generation: Long) = callback?.onConnect(generation)
        fun disconnected(generation: Long) = callback?.onDisconnect(generation)
        fun response(generation: Long) = callback?.onPumpCommand(generation, acceptedResponse())
        fun heartbeat(generation: Long) = callback?.onPumpCommand(
            generation,
            PumpCommand(ubyteArrayOf(0xaau, 0x06u, 0x00u, 0xa5u, 0x01u, 0x00u, 0x81u, 0xa2u).toByteArray()),
        )

        private fun acceptedResponse(): PumpCommand {
            val data = ubyteArrayOf(0xaau, 0x0au, 0x00u, 0xa1u, 0x55u, 0xaau, 0x00u, 0x00u, 0x00u, 0x00u).toByteArray()
            val checksum = PumpCommand(data).calculatedChecksum()
            data[data.lastIndex - 1] = checksum[0]
            data[data.lastIndex] = checksum[1]
            return PumpCommand(data)
        }
    }

    private class FakeDeviceInfo : ApexDeviceInfo {
        override var serialNumber = "12345678"
        override val version: Version? = null
    }
}
