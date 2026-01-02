package com.valt.printertestapplication.printer

import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import kotlinx.coroutines.flow.StateFlow

interface BasePrinterManager {
    val connectionStatus: StateFlow<ConnectionStatus>
    val connectedDevice: StateFlow<PrinterDevice?>
    val availableDevices: StateFlow<List<PrinterDevice>>
    val isScanning: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(device: PrinterDevice): Boolean
    suspend fun disconnect()
    suspend fun printData(data: ByteArray): Boolean
    fun isConnected(): Boolean
    fun clearError()
}
