package com.valt.printertestapplication.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterState
import com.valt.printertestapplication.data.PrinterType
import com.valt.printertestapplication.printer.BasePrinterManager
import com.valt.printertestapplication.printer.BluetoothPrinterManager
import com.valt.printertestapplication.printer.InnerPrinterManager
import com.valt.printertestapplication.printer.LabelPrinterCommands
import com.valt.printertestapplication.printer.LabelPrinterManager
import com.valt.printertestapplication.R
import com.valt.printertestapplication.printer.UsbPrinterManager
import com.valt.printertestapplication.printer.WifiPrinterManager
import com.valt.printertestapplication.ui.components.ComposeViewCapture
import com.valt.printertestapplication.ui.components.OrderBitmapGenerator
import com.valt.printertestapplication.ui.components.OrderData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PrinterViewModel(private val context: Context) : ViewModel() {

    private val innerPrinterManager = InnerPrinterManager(context)
    private val usbPrinterManager = UsbPrinterManager(context)
    private val bluetoothPrinterManager = BluetoothPrinterManager(context)
    private val wifiPrinterManager = WifiPrinterManager(context)
    private val labelPrinterManager = LabelPrinterManager(context)

    private var currentManager: BasePrinterManager? = null

    private val _printerState = MutableStateFlow(PrinterState())
    val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private val _printResult = MutableStateFlow<PrintResult?>(null)
    val printResult: StateFlow<PrintResult?> = _printResult.asStateFlow()

    // Message received from printer (for Toast)
    private val _printerReceivedMessage = MutableStateFlow<String?>(null)
    val printerReceivedMessage: StateFlow<String?> = _printerReceivedMessage.asStateFlow()

    init {
        // Observe label printer incoming messages
        viewModelScope.launch {
            labelPrinterManager.toastMessage.collectLatest { message ->
                if (message != null) {
                    _printerReceivedMessage.value = message
                }
            }
        }
    }

    fun clearReceivedMessage() {
        _printerReceivedMessage.value = null
    }

    fun selectPrinterType(type: PrinterType) {
        currentManager = when (type) {
            PrinterType.INNER_PRINTER -> innerPrinterManager
            PrinterType.USB -> usbPrinterManager
            PrinterType.BLUETOOTH -> bluetoothPrinterManager
            PrinterType.WIFI_LAN -> wifiPrinterManager
            PrinterType.LABEL_PRINTER -> labelPrinterManager
        }

        _printerState.value = _printerState.value.copy(
            selectedType = type,
            connectionStatus = ConnectionStatus.DISCONNECTED,
            connectedDevice = null,
            availableDevices = emptyList(),
            isScanning = false,
            errorMessage = null
        )

        observeManager(currentManager!!)
    }

    private fun observeManager(manager: BasePrinterManager) {
        viewModelScope.launch {
            manager.connectionStatus.collectLatest { status ->
                _printerState.value = _printerState.value.copy(connectionStatus = status)
            }
        }
        viewModelScope.launch {
            manager.connectedDevice.collectLatest { device ->
                _printerState.value = _printerState.value.copy(connectedDevice = device)
            }
        }
        viewModelScope.launch {
            manager.availableDevices.collectLatest { devices ->
                _printerState.value = _printerState.value.copy(availableDevices = devices)
            }
        }
        viewModelScope.launch {
            manager.isScanning.collectLatest { scanning ->
                _printerState.value = _printerState.value.copy(isScanning = scanning)
            }
        }
        viewModelScope.launch {
            manager.errorMessage.collectLatest { error ->
                _printerState.value = _printerState.value.copy(errorMessage = error)
            }
        }
    }

    fun startScan() {
        viewModelScope.launch {
            currentManager?.startScan()
        }
    }

    fun stopScan() {
        viewModelScope.launch {
            currentManager?.stopScan()
        }
    }

    fun connect(device: PrinterDevice) {
        viewModelScope.launch {
            currentManager?.connect(device)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            currentManager?.disconnect()
        }
    }

    fun printData(data: ByteArray) {
        viewModelScope.launch {
            _printResult.value = PrintResult.Printing
            val success = currentManager?.printData(data) ?: false
            _printResult.value = if (success) PrintResult.Success else PrintResult.Error(
                currentManager?.errorMessage?.value ?: "Print failed"
            )
        }
    }

    fun printTestPage() {
        // Use label-specific commands for label printers
        if (_printerState.value.selectedType == PrinterType.LABEL_PRINTER) {
            printLabelTestPage(LabelPrinterCommands.LabelLanguage.TSPL)
        } else {
            val testData = buildTestPageData()
            printData(testData)
        }
    }

    fun printLabelTestPage(language: LabelPrinterCommands.LabelLanguage) {
        val testData = LabelPrinterCommands.getTestLabelData(language)
        printData(testData)
    }

    fun printLabelWithLanguage(text: String, language: LabelPrinterCommands.LabelLanguage) {
        val labelData = LabelPrinterCommands.getLabelData(text, language)
        printData(labelData)
    }

    fun printRawText(text: String) {
        val data = LabelPrinterCommands.buildRawLabel(text)
        printData(data)
    }

    fun printTscSimpleTest(text: String = "TEST") {
        viewModelScope.launch {
            _printResult.value = PrintResult.Printing
            
            // Check if printer supports reading (has IN endpoint)
            val canRead = labelPrinterManager.canReadFromPrinter()

            if (canRead) {
                // Check printer status first
                val status = labelPrinterManager.queryPrinterStatus()
                
                if (status != null) {

                    if (status.isPaperOut) {
                        _printResult.value = PrintResult.Error("Paper is out! Please load paper.")
                        return@launch
                    }
                    
                    if (status.isCoverOpen) {
                        _printResult.value = PrintResult.Error("Cover is open! Please close cover.")
                        return@launch
                    }
                    
                    if (status.hasError && status.rawStatus != 0) {
                        _printResult.value = PrintResult.Error("Printer error: ${status.statusMessage}")
                        return@launch
                    }
                }
            } else {
                Log.w("PrinterViewModel", "Printer is WRITE-ONLY (no IN endpoint) - skipping status check")
            }
            
            // Print
            val data = LabelPrinterCommands.buildTsplSimpleTest(text)
            val success = labelPrinterManager.printData(data)
            _printResult.value = if (success) PrintResult.Success else PrintResult.Error(
                labelPrinterManager.errorMessage.value ?: "Print failed"
            )
        }
    }

    fun printBitmap(bitmap: android.graphics.Bitmap) {
        val data = bitmapToTsplData(bitmap)
        printData(data)
    }

    private fun bitmapToTsplData(bitmap: android.graphics.Bitmap): ByteArray {
        // Convert bitmap to monochrome for label printing
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8  // Round up to nearest byte
        
        val bitmapData = ByteArray(widthBytes * height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = (android.graphics.Color.red(pixel) + 
                           android.graphics.Color.green(pixel) + 
                           android.graphics.Color.blue(pixel)) / 3
                
                // If pixel is dark (gray < 128), set bit to 1 (black)
                if (gray < 128) {
                    val byteIndex = y * widthBytes + (x / 8)
                    val bitIndex = 7 - (x % 8)
                    bitmapData[byteIndex] = (bitmapData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }
        
        return LabelPrinterCommands.buildTsplBitmap(bitmapData, widthBytes, height)
    }

    fun printUniversalTest() {
        val data = LabelPrinterCommands.buildUniversalTestLabel()
        printData(data)
    }

    fun printOrderLabel(orderText: String) {
        val data = LabelPrinterCommands.buildTsplLongText(orderText)
        printData(data)
    }

    fun printOrderView(order: OrderData) {
        // Use Canvas-based bitmap generation (works without window attachment)
        val bitmap = OrderBitmapGenerator.createOrderBitmap(
            order = order,
            widthPx = 384,
            context = context,
            logoResId = R.drawable.ic_launcher_foreground
        )
        printBitmap(bitmap)
    }

    // Print order view for ESC/POS printers (receipt printers)
    fun printOrderViewEscPos(order: com.valt.printertestapplication.ui.components.OrderData) {
        // Use Canvas-based bitmap generation
        val bitmap = OrderBitmapGenerator.createOrderBitmap(
            order = order,
            widthPx = 384,
            context = context,
            logoResId = R.drawable.ic_launcher_foreground
        )
        val data = LabelPrinterCommands.buildEscPosBitmap(bitmap)
        printData(data)
    }

    private fun buildTestPageData(): ByteArray {
        val builder = StringBuilder()
        
        // ESC/POS commands for basic formatting
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        // Initialize printer
        builder.append("${ESC}@")
        
        // Center align
        builder.append("${ESC}a${1.toChar()}")
        
        // Bold on
        builder.append("${ESC}E${1.toChar()}")
        builder.append("PRINTER TEST PAGE\n")
        builder.append("==================\n\n")
        
        // Bold off
        builder.append("${ESC}E${0.toChar()}")
        
        // Left align
        builder.append("${ESC}a${0.toChar()}")
        
        builder.append("Printer Type: ${_printerState.value.selectedType?.displayName ?: "Unknown"}\n")
        builder.append("Device: ${_printerState.value.connectedDevice?.name ?: "Unknown"}\n")
        builder.append("Address: ${_printerState.value.connectedDevice?.address ?: "Unknown"}\n\n")
        
        builder.append("--------------------------------\n")
        builder.append("Test Line 1: ABCDEFGHIJKLMNOPQRS\n")
        builder.append("Test Line 2: 0123456789!@#\$%^&*()\n")
        builder.append("Test Line 3: abcdefghijklmnopqrs\n")
        builder.append("--------------------------------\n\n")
        
        // Center align
        builder.append("${ESC}a${1.toChar()}")
        builder.append("*** TEST COMPLETE ***\n\n\n")
        
        // Cut paper (for printers that support it)
        builder.append("${GS}V${66.toChar()}${3.toChar()}")
        
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    fun printCustomData(text: String) {
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        val builder = StringBuilder()
        builder.append("${ESC}@") // Initialize
        builder.append(text)
        builder.append("\n\n\n")
        builder.append("${GS}V${66.toChar()}${3.toChar()}") // Cut
        
        printData(builder.toString().toByteArray(Charsets.UTF_8))
    }

    fun addWifiPrinter(name: String, ip: String, port: Int = 9100) {
        wifiPrinterManager.addManualPrinter(name, ip, port)
        if (_printerState.value.selectedType == PrinterType.WIFI_LAN) {
            startScan()
        }
    }

    fun addLabelNetworkPrinter(name: String, ip: String, port: Int = 9100) {
        labelPrinterManager.addNetworkPrinter(name, ip, port)
        if (_printerState.value.selectedType == PrinterType.LABEL_PRINTER) {
            startScan()
        }
    }

    fun clearError() {
        currentManager?.clearError()
        _printerState.value = _printerState.value.copy(errorMessage = null)
    }

    fun clearPrintResult() {
        _printResult.value = null
    }

    fun getConnectionStatusForType(type: PrinterType): ConnectionStatus {
        val manager = when (type) {
            PrinterType.INNER_PRINTER -> innerPrinterManager
            PrinterType.USB -> usbPrinterManager
            PrinterType.BLUETOOTH -> bluetoothPrinterManager
            PrinterType.WIFI_LAN -> wifiPrinterManager
            PrinterType.LABEL_PRINTER -> labelPrinterManager
        }
        return if (manager.isConnected()) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
    }

    override fun onCleared() {
        super.onCleared()
        (usbPrinterManager as? UsbPrinterManager)?.cleanup()
        (bluetoothPrinterManager as? BluetoothPrinterManager)?.cleanup()
        (labelPrinterManager as? LabelPrinterManager)?.cleanup()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PrinterViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PrinterViewModel(context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

sealed class PrintResult {
    object Printing : PrintResult()
    object Success : PrintResult()
    data class Error(val message: String) : PrintResult()
}
