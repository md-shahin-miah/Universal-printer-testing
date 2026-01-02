package com.valt.printertestapplication.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothPrinterManager(private val context: Context) : BasePrinterManager {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevice = MutableStateFlow<PrinterDevice?>(null)
    override val connectedDevice: StateFlow<PrinterDevice?> = _connectedDevice.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<PrinterDevice>>(emptyList())
    override val availableDevices: StateFlow<List<PrinterDevice>> = _availableDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val discoveredDevices = mutableListOf<PrinterDevice>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (hasBluetoothPermission()) {
                            val printerDevice = PrinterDevice(
                                name = it.name ?: "Unknown Device",
                                address = it.address,
                                type = PrinterType.BLUETOOTH
                            )
                            if (!discoveredDevices.any { d -> d.address == printerDevice.address }) {
                                discoveredDevices.add(printerDevice)
                                _availableDevices.value = discoveredDevices.toList()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(discoveryReceiver, filter)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startScan() {
        if (!hasBluetoothPermission()) {
            _errorMessage.value = "Bluetooth permissions not granted"
            return
        }

        if (bluetoothAdapter == null) {
            _errorMessage.value = "Bluetooth not available"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _errorMessage.value = "Bluetooth is disabled"
            return
        }

        _isScanning.value = true
        _errorMessage.value = null
        discoveredDevices.clear()

        withContext(Dispatchers.IO) {
            try {
                val pairedDevices = bluetoothAdapter.bondedDevices
                pairedDevices?.forEach { device ->
                    val printerDevice = PrinterDevice(
                        name = device.name ?: "Unknown Device",
                        address = device.address,
                        type = PrinterType.BLUETOOTH
                    )
                    discoveredDevices.add(printerDevice)
                }
                _availableDevices.value = discoveredDevices.toList()

                if (bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.cancelDiscovery()
                }
                bluetoothAdapter.startDiscovery()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        if (hasBluetoothPermission()) {
            bluetoothAdapter?.cancelDiscovery()
        }
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: PrinterDevice): Boolean {
        if (!hasBluetoothPermission()) {
            _errorMessage.value = "Bluetooth permissions not granted"
            return false
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null

        return withContext(Dispatchers.IO) {
            try {
                bluetoothAdapter?.cancelDiscovery()

                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                if (bluetoothDevice == null) {
                    _errorMessage.value = "Device not found"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@withContext false
                }

                val socket = bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                bluetoothSocket = socket
                outputStream = socket.outputStream

                _connectedDevice.value = device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                true
            } catch (e: IOException) {
                _errorMessage.value = "Connection failed: ${e.message}"
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            } catch (e: Exception) {
                _errorMessage.value = "Connection failed: ${e.message}"
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.close()
                bluetoothSocket?.close()
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                outputStream = null
                bluetoothSocket = null
                _connectedDevice.value = null
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    override suspend fun printData(data: ByteArray): Boolean {
        if (!isConnected()) {
            _errorMessage.value = "Printer not connected"
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
                true
            } catch (e: Exception) {
                _errorMessage.value = "Print failed: ${e.message}"
                false
            }
        }
    }

    override fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true && _connectionStatus.value == ConnectionStatus.CONNECTED
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}
