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
import java.io.File
import java.io.FileOutputStream

class InnerPrinterManager(private val context: Context) : BasePrinterManager {

    companion object {
        private val INNER_PRINTER_PATHS = listOf(
            "/dev/ttyS1",
            "/dev/ttyS2",
            "/dev/ttyS3",
            "/dev/ttyMT0",
            "/dev/ttyMT1",
            "/dev/usb/lp0",
            "/dev/usblp0"
        )
    }

    private var printerPath: String? = null
    private var outputStream: FileOutputStream? = null

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

    override suspend fun startScan() {
        _isScanning.value = true
        _errorMessage.value = null

        withContext(Dispatchers.IO) {
            try {
                val availablePrinters = mutableListOf<PrinterDevice>()

                INNER_PRINTER_PATHS.forEach { path ->
                    val file = File(path)
                    if (file.exists()) {
                        availablePrinters.add(
                            PrinterDevice(
                                name = "Inner Printer (${file.name})",
                                address = path,
                                type = PrinterType.INNER_PRINTER
                            )
                        )
                    }
                }

                if (availablePrinters.isEmpty()) {
                    availablePrinters.add(
                        PrinterDevice(
                            name = "Inner Printer (Simulated)",
                            address = "simulated",
                            type = PrinterType.INNER_PRINTER
                        )
                    )
                }

                _availableDevices.value = availablePrinters
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    override suspend fun stopScan() {
        _isScanning.value = false
    }

    override suspend fun connect(device: PrinterDevice): Boolean {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null

        return withContext(Dispatchers.IO) {
            try {
                if (device.address == "simulated") {
                    printerPath = device.address
                    _connectedDevice.value = device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    return@withContext true
                }

                val file = File(device.address)
                if (!file.exists()) {
                    _errorMessage.value = "Printer device not found"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@withContext false
                }

                outputStream = FileOutputStream(file)
                printerPath = device.address

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

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                outputStream = null
                printerPath = null
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
                if (printerPath == "simulated") {
                    // Simulated printing - just return success
                    return@withContext true
                }

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
        return printerPath != null && _connectionStatus.value == ConnectionStatus.CONNECTED
    }

    override fun clearError() {
        _errorMessage.value = null
    }
}
