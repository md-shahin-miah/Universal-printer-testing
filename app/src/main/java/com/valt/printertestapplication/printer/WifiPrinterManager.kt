package com.valt.printertestapplication.printer

import android.content.Context
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class WifiPrinterManager(private val context: Context) : BasePrinterManager {

    companion object {
        private const val DEFAULT_PORT = 9100
        private const val CONNECTION_TIMEOUT = 10000
    }

    private var socket: Socket? = null
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

    private val manualDevices = mutableListOf<PrinterDevice>()

    override suspend fun startScan() {
        _isScanning.value = true
        _errorMessage.value = null

        withContext(Dispatchers.IO) {
            try {
                _availableDevices.value = manualDevices.toList()
            } finally {
                _isScanning.value = false
            }
        }
    }

    override suspend fun stopScan() {
        _isScanning.value = false
    }

    fun addManualPrinter(name: String, ipAddress: String, port: Int = DEFAULT_PORT) {
        val device = PrinterDevice(
            name = name,
            address = "$ipAddress:$port",
            type = PrinterType.WIFI_LAN
        )
        if (!manualDevices.any { it.address == device.address }) {
            manualDevices.add(device)
            _availableDevices.value = manualDevices.toList()
        }
    }

    fun removeManualPrinter(address: String) {
        manualDevices.removeAll { it.address == address }
        _availableDevices.value = manualDevices.toList()
    }

    override suspend fun connect(device: PrinterDevice): Boolean {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null

        return withContext(Dispatchers.IO) {
            try {
                val parts = device.address.split(":")
                val ip = parts[0]
                val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_PORT else DEFAULT_PORT

                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)

                socket = newSocket
                outputStream = newSocket.getOutputStream()

                _connectedDevice.value = device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                true
            } catch (e: Exception) {
                _errorMessage.value = "Connection failed: ${e.message}"
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            }
        }
    }

    suspend fun connectByIp(name: String, ipAddress: String, port: Int = DEFAULT_PORT): Boolean {
        val device = PrinterDevice(
            name = name,
            address = "$ipAddress:$port",
            type = PrinterType.WIFI_LAN
        )
        return connect(device)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                outputStream = null
                socket = null
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
        return socket?.isConnected == true && 
               socket?.isClosed == false && 
               _connectionStatus.value == ConnectionStatus.CONNECTED
    }

    override fun clearError() {
        _errorMessage.value = null
    }
}
