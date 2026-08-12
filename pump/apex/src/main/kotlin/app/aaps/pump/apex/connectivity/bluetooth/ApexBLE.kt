package app.aaps.pump.apex.connectivity.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import app.aaps.pump.apex.utils.keys.ApexStringKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ApexBLE @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val context: Context,
    private val apexDriverStatus: ApexDriverStatus,
    private val trace: ApexTrace,
) : ApexTransport {
    companion object {
        private val READ_SERVICE = ParcelUuid.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val WRITE_SERVICE = ParcelUuid.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")
        private val READ_UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        private val WRITE_UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val REQUESTED_MTU = 512
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_SETTLE_MS = 1_000L
    }

    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ApexBluetooth").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transportMutex = Mutex()

    @Volatile private var callback: ApexBluetoothCallback? = null
    @Volatile private var _status = Status.DISCONNECTED
    val status: Status get() = _status

    private var activeGeneration = 0L
    private var disconnectNotifiedGeneration = Long.MIN_VALUE
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var lastCommand: PumpCommand? = null
    private var connectTimeoutJob: kotlinx.coroutines.Job? = null
    private var writeAck: CompletableDeferred<Int>? = null
    private var scanning = false
    private var scanCallback: ScanCallback? = null

    override fun setCallback(callback: ApexBluetoothCallback?) {
        this.callback = callback
    }

    override fun connect(generation: Long) {
        scope.launch { transportMutex.withLock { connectInternal(generation) } }
    }

    override fun disconnect() {
        scope.launch { transportMutex.withLock { closeCurrent(notify = false) } }
    }

    override fun shutdown() {
        scope.launch {
            transportMutex.withLock { closeCurrent(notify = false) }
            scope.cancel()
            dispatcher.close()
        }
    }

    override suspend fun send(command: DeviceCommand): Boolean = withContext(dispatcher) {
        transportMutex.withLock { sendInternal(command) }
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(generation: Long) {
        if (generation <= 0L) return
        closeCurrent(notify = false)
        activeGeneration = generation
        trace.record("ble_connect_requested", generation)
        disconnectNotifiedGeneration = Long.MIN_VALUE
        lastCommand = null

        val serial = preferences.get(ApexStringKey.SerialNumber)
        if (serial.isBlank()) {
            failCurrent("serial_missing")
            return
        }
        if (!isBluetoothUsable()) {
            failCurrent("bluetooth_unavailable")
            return
        }

        setStatus(Status.CONNECTING)
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Connecting)
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_status == Status.CONNECTING && activeGeneration == generation) {
                failCurrent("connect_timeout")
            }
        }

        val address = preferences.get(ApexStringKey.BluetoothAddress)
        if (address.isBlank()) startScan(serial) else connectAddress(address)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(serial: String) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            failCurrent("scanner_unavailable")
            return
        }
        val filters = listOf(
            ScanFilter.Builder().setDeviceName("APEX$serial").build(),
            ScanFilter.Builder().setServiceUuid(READ_SERVICE).build(),
            ScanFilter.Builder().setServiceUuid(WRITE_SERVICE).build(),
        )
        val generation = activeGeneration
        val callback = createScanCallback(generation)
        try {
            scanCallback = callback
            scanning = true
            scanner.startScan(filters, scanSettings, callback)
            trace.record("ble_scan_started", generation)
            aapsLogger.debug(LTag.PUMPBTCOMM, "Apex scan started gen=$generation")
        } catch (error: Exception) {
            scanCallback = null
            scanning = false
            aapsLogger.error(LTag.PUMPBTCOMM, "Apex scan start failed: ${error.message}")
            failCurrent("scan_start_failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAddress(address: String) {
        val adapter = bluetoothAdapter ?: run {
            failCurrent("adapter_unavailable")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                failCurrent("gatt_create_failed")
                return
            }
            bluetoothGatt = gatt
            trace.record("ble_gatt_created", activeGeneration)
            aapsLogger.debug(LTag.PUMPBTCOMM, "Apex GATT created gen=$activeGeneration")
        } catch (error: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Apex GATT setup failed: ${error.message}")
            preferences.put(ApexStringKey.BluetoothAddress, "")
            failCurrent("gatt_setup_failed")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendInternal(command: DeviceCommand): Boolean {
        val gatt = bluetoothGatt
        val characteristic = writeCharacteristic
        val generation = activeGeneration
        if (_status != Status.CONNECTED || gatt == null || characteristic == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Apex write rejected: transport is not ready")
            return false
        }

        val data = command.serialize()
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var start = 0
        trace.record(
            "ble_write_started",
            activeGeneration,
            fields = mapOf("command" to command::class.simpleName, "bytes" to data.size, "chunkSize" to chunkSize),
        )
        while (start < data.size) {
            if (gatt !== bluetoothGatt || generation != activeGeneration) return false
            val end = min(start + chunkSize, data.size)
            val chunk = data.copyOfRange(start, end)
            val ack = CompletableDeferred<Int>()
            writeAck = ack
            aapsLogger.debug(LTag.PUMPBTCOMM, "DEVICE[$start] -> ${chunk.toHex()}")

            val issued = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (error: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Apex write failed: ${error.message}")
                false
            }

            if (!issued) {
                writeAck = null
                failCurrent("write_not_issued")
                return false
            }
            val writeStatus = withTimeoutOrNull(WRITE_TIMEOUT_MS) { ack.await() }
            writeAck = null
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                failCurrent(if (writeStatus == null) "write_timeout" else "write_status_$writeStatus")
                return false
            }
            start = end
        }
        return true
    }

    private fun onPumpData(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        scope.launch {
            if (!isCurrent(gatt) || characteristic.uuid != READ_UUID) return@launch
            aapsLogger.debug(LTag.PUMPBTCOMM, "PUMP <- ${value.toHex()}")
            try {
                if (lastCommand?.isCompleteCommand() == false) {
                    lastCommand?.update(value)
                } else if (value.size > PumpCommand.MIN_SIZE) {
                    lastCommand = PumpCommand(value)
                } else {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Invalid Apex frame length=${value.size}")
                    return@launch
                }

                while (lastCommand?.isCompleteCommand() == true) {
                    val command = lastCommand ?: break
                    if (!command.verify()) {
                        aapsLogger.error(LTag.PUMPBTCOMM, "Invalid Apex checksum id=${command.id?.name}")
                        lastCommand = null
                        return@launch
                    }
                    callback?.onPumpCommand(activeGeneration, command)
                    lastCommand = command.trailing
                }
            } catch (error: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Malformed Apex frame: ${error.message}")
                lastCommand = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionState(gatt: BluetoothGatt, gattStatus: Int, newState: Int) {
        if (!isCurrent(gatt)) {
            closeStale(gatt)
            return
        }
        if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
            failCurrent("gatt_status_$gattStatus")
            return
        }
        when (newState) {
            BluetoothGatt.STATE_CONNECTED -> {
                if (!gatt.discoverServices()) failCurrent("discover_not_issued")
            }
            BluetoothGatt.STATE_DISCONNECTED -> failCurrent("gatt_disconnected")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleServicesDiscovered(gatt: BluetoothGatt, gattStatus: Int) {
        if (!isCurrent(gatt)) return
        if (gattStatus != BluetoothGatt.GATT_SUCCESS ||
            gatt.getService(READ_SERVICE.uuid) == null ||
            gatt.getService(WRITE_SERVICE.uuid) == null
        ) {
            failCurrent("services_missing_$gattStatus")
            return
        }
        if (!gatt.requestMtu(REQUESTED_MTU)) failCurrent("mtu_not_issued")
    }

    @SuppressLint("MissingPermission")
    private fun handleMtuChanged(gatt: BluetoothGatt, negotiatedMtu: Int, gattStatus: Int) {
        if (!isCurrent(gatt)) return
        writeCharacteristic = gatt.getService(WRITE_SERVICE.uuid)?.getCharacteristic(WRITE_UUID)
        readCharacteristic = gatt.getService(READ_SERVICE.uuid)?.getCharacteristic(READ_UUID)
        val read = readCharacteristic
        if (gattStatus != BluetoothGatt.GATT_SUCCESS || writeCharacteristic == null || read == null) {
            failCurrent("mtu_failed_$gattStatus")
            return
        }
        mtu = negotiatedMtu
        val descriptor = read.getDescriptor(CCC_UUID)
        if (descriptor == null || !gatt.setCharacteristicNotification(read, true) || !writeDescriptor(gatt, descriptor)) {
            failCurrent("notification_not_issued")
        }
    }

    private suspend fun handleDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
        if (!isCurrent(gatt) || descriptor.uuid != CCC_UUID) return
        if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
            failCurrent("notification_failed_$gattStatus")
            return
        }
        delay(NOTIFICATION_SETTLE_MS)
        if (!isCurrent(gatt) || _status != Status.CONNECTING) return
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        setStatus(Status.CONNECTED)
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
        callback?.onConnect(activeGeneration)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

    private fun isCurrent(gatt: BluetoothGatt): Boolean = gatt === bluetoothGatt

    @SuppressLint("MissingPermission")
    private fun failCurrent(reason: String) {
        val generation = activeGeneration
        aapsLogger.error(LTag.PUMPBTCOMM, "Apex transport failure: $reason gen=$generation")
        trace.record("ble_failure", generation, fields = mapOf("reason" to reason))
        closeCurrent(notify = false)
        if (disconnectNotifiedGeneration != generation) {
            disconnectNotifiedGeneration = generation
            callback?.onDisconnect(generation)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrent(notify: Boolean) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        writeAck?.complete(BluetoothGatt.GATT_FAILURE)
        writeAck = null
        stopScan()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
        lastCommand = null
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) { }
            try { gatt.close() } catch (_: Exception) { }
        }
        setStatus(Status.DISCONNECTED)
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
        if (notify && disconnectNotifiedGeneration != activeGeneration) {
            disconnectNotifiedGeneration = activeGeneration
            callback?.onDisconnect(activeGeneration)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeStale(gatt: BluetoothGatt) {
        try { gatt.disconnect() } catch (_: Exception) { }
        try { gatt.close() } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        val callback = scanCallback
        scanCallback = null
        try { callback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) { }
        scanning = false
    }

    private fun setStatus(status: Status) {
        if (_status == status) return
        aapsLogger.debug(LTag.PUMPBTCOMM, "Apex transport: ${_status.name} -> ${status.name} gen=$activeGeneration")
        trace.record("ble_state", activeGeneration, fields = mapOf("from" to _status, "to" to status))
        _status = status
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothUsable(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Bluetooth adapter is unavailable or disabled")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Bluetooth permissions are missing")
                return false
            }
        } else if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Location permission for BLE scan is missing")
            return false
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch { handleConnectionState(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch { handleServicesDiscovered(gatt, status) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch { handleMtuChanged(gatt, mtu, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch { handleDescriptorWrite(gatt, descriptor, status) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            scope.launch {
                if (isCurrent(gatt) && characteristic.uuid == WRITE_UUID) writeAck?.complete(status)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onPumpData(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onPumpData(gatt, characteristic, value)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onPumpData(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onPumpData(gatt, characteristic, value)
        }
    }

    private fun createScanCallback(generation: Long): ScanCallback {
        lateinit var callback: ScanCallback
        callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scope.launch {
                    if (generation != activeGeneration || _status != Status.CONNECTING || !scanning || scanCallback !== callback) return@launch
                    val expectedName = "APEX${preferences.get(ApexStringKey.SerialNumber)}"
                    if (result.device.name != expectedName) return@launch
                    stopScan()
                    preferences.put(ApexStringKey.BluetoothAddress, result.device.address)
                    connectAddress(result.device.address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    if (generation != activeGeneration || !scanning || scanCallback !== callback) return@launch
                    failCurrent("scan_failed_$errorCode")
                }
            }
        }
        return callback
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED;

        fun toLocalString(rh: ResourceHelper): String = when (this) {
            DISCONNECTED -> rh.gs(app.aaps.pump.apex.R.string.overview_connection_status_disconnected)
            CONNECTING -> rh.gs(app.aaps.pump.apex.R.string.overview_connection_status_connecting)
            CONNECTED -> rh.gs(app.aaps.pump.apex.R.string.overview_connection_status_connected)
        }
    }
}
