package com.valt.printertestapplication.printer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

class LabelPrinterManager(private val context: Context) : BasePrinterManager {

    companion object {
        private const val TAG = "LabelPrinterManager"
        private const val ACTION_USB_PERMISSION = "com.valt.printertestapplication.LABEL_USB_PERMISSION"
        private const val PRINTER_CLASS = 7
        private const val DEFAULT_PORT = 9100
        private const val CONNECTION_TIMEOUT = 10000
    }

    enum class ConnectionMode {
        USB,
        NETWORK
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // USB connection
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null      // OUT endpoint for writing
    private var usbEndpointIn: UsbEndpoint? = null    // IN endpoint for reading
    private var currentUsbDevice: UsbDevice? = null

    // Network connection
    private var socket: Socket? = null
    private var networkOutputStream: OutputStream? = null
    private var networkInputStream: InputStream? = null
    
    // Read listener
    private val _receivedData = MutableStateFlow<ByteArray?>(null)
    val receivedData: StateFlow<ByteArray?> = _receivedData.asStateFlow()
    
    // Message for Toast display
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    private var isReadingEnabled = false
    private var readJob: kotlinx.coroutines.Job? = null

    private var currentMode: ConnectionMode? = null

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

    private val manualNetworkDevices = mutableListOf<PrinterDevice>()

    private var permissionContinuation: ((Boolean) -> Unit)? = null
    private var pendingDevice: PrinterDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission result: granted=$granted, device=${device?.deviceName}")
                    permissionContinuation?.invoke(granted && device != null)
                    permissionContinuation = null
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(context, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override suspend fun startScan() {
        _isScanning.value = true
        _errorMessage.value = null

        withContext(Dispatchers.IO) {
            try {
                val devices = mutableListOf<PrinterDevice>()

                // Scan ALL USB devices - label printers often don't use printer class
                val deviceList = usbManager.deviceList
                Log.d(TAG, "Found ${deviceList.size} USB devices")
                
                deviceList.values.forEach { device ->
                    Log.d(TAG, "USB Device: ${device.productName}, VID=${device.vendorId}, PID=${device.productId}")
                    
                    // Check if device has any bulk OUT endpoint (more lenient detection)
                    if (hasBulkOutEndpoint(device) || isPrinterDevice(device)) {
                        devices.add(
                            PrinterDevice(
                                name = "${device.productName ?: "USB Device (${device.vendorId}:${device.productId})"} (USB)",
                                address = "usb:${device.deviceName}",
                                type = PrinterType.LABEL_PRINTER
                            )
                        )
                    }
                }

                // Add manual network printers
                devices.addAll(manualNetworkDevices)

                _availableDevices.value = devices
                Log.d(TAG, "Total available devices: ${devices.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _errorMessage.value = "Failed to scan: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun hasBulkOutEndpoint(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun isPrinterDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == PRINTER_CLASS) {
                return true
            }
        }
        return false
    }

    override suspend fun stopScan() {
        _isScanning.value = false
    }

    fun addNetworkPrinter(name: String, ipAddress: String, port: Int = DEFAULT_PORT) {
        val device = PrinterDevice(
            name = "$name (Network)",
            address = "net:$ipAddress:$port",
            type = PrinterType.LABEL_PRINTER
        )
        if (!manualNetworkDevices.any { it.address == device.address }) {
            manualNetworkDevices.add(device)
            _availableDevices.value = _availableDevices.value + device
        }
    }

    override suspend fun connect(device: PrinterDevice): Boolean {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null
        pendingDevice = device

        return withContext(Dispatchers.IO) {
            try {
                when {
                    device.address.startsWith("usb:") -> connectUsb(device)
                    device.address.startsWith("net:") -> connectNetwork(device)
                    else -> {
                        _errorMessage.value = "Unknown connection type"
                        _connectionStatus.value = ConnectionStatus.ERROR
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _errorMessage.value = "Connection failed: ${e.message}"
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            }
        }
    }

    private suspend fun connectUsb(device: PrinterDevice): Boolean {
        val devicePath = device.address.removePrefix("usb:")
        val usbDevice = usbManager.deviceList[devicePath]

        if (usbDevice == null) {
            _errorMessage.value = "USB device not found"
            _connectionStatus.value = ConnectionStatus.ERROR
            return false
        }

        // Request permission if not granted
        if (!usbManager.hasPermission(usbDevice)) {
            Log.d(TAG, "Requesting USB permission for ${usbDevice.deviceName}")
            val granted = requestUsbPermission(usbDevice)
            if (!granted) {
                _errorMessage.value = "USB permission denied. Please try again."
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return false
            }
        }

        // Try to connect - first try printer class interfaces, then any bulk endpoint
        return tryConnectToDevice(usbDevice, device)
    }

    private suspend fun requestUsbPermission(usbDevice: UsbDevice): Boolean {
        return withTimeoutOrNull(30000L) {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = { granted ->
                    if (continuation.isActive) {
                        continuation.resume(granted)
                    }
                }
                
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).apply {
                        setPackage(context.packageName)
                    },
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(usbDevice, permissionIntent)
            }
        } ?: false
    }

    private fun tryConnectToDevice(usbDevice: UsbDevice, device: PrinterDevice): Boolean {
        Log.d(TAG, "Trying to connect to device with ${usbDevice.interfaceCount} interfaces")
        
        // First try: Look for printer class interface
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            Log.d(TAG, "Interface $i: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}")
            
            if (intf.interfaceClass == PRINTER_CLASS) {
                val result = tryConnectToInterface(usbDevice, intf, device)
                if (result) return true
            }
        }

        // Second try: Any interface with bulk OUT endpoint
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            val result = tryConnectToInterface(usbDevice, intf, device)
            if (result) return true
        }

        _errorMessage.value = "Could not find compatible endpoint"
        _connectionStatus.value = ConnectionStatus.ERROR
        return false
    }

    private fun tryConnectToInterface(usbDevice: UsbDevice, intf: UsbInterface, device: PrinterDevice): Boolean {
        var outEndpoint: UsbEndpoint? = null
        var inEndpoint: UsbEndpoint? = null
        
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "SCANNING USB ENDPOINTS for interface ${intf.id}")
        Log.d(TAG, "Interface class: ${intf.interfaceClass}, subclass: ${intf.interfaceSubclass}")
        Log.d(TAG, "Total endpoints: ${intf.endpointCount}")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        
        // Find both OUT and IN endpoints
        for (j in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(j)
            val typeStr = when (endpoint.type) {
                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                else -> "UNKNOWN(${endpoint.type})"
            }
            val dirStr = when (endpoint.direction) {
                UsbConstants.USB_DIR_OUT -> "OUT (Host→Device)"
                UsbConstants.USB_DIR_IN -> "IN (Device→Host)"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "  Endpoint $j: type=$typeStr, direction=$dirStr, address=${endpoint.address}, maxPacket=${endpoint.maxPacketSize}")
            
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_OUT -> {
                        outEndpoint = endpoint
                        Log.d(TAG, "    → Found BULK OUT endpoint!")
                    }
                    UsbConstants.USB_DIR_IN -> {
                        inEndpoint = endpoint
                        Log.d(TAG, "    → Found BULK IN endpoint!")
                    }
                }
            }
        }
        
        Log.d(TAG, "─────────────────────────────────────────────────────────────")
        Log.d(TAG, "SUMMARY: OUT=${outEndpoint != null}, IN=${inEndpoint != null}")
        if (inEndpoint == null) {
            Log.w(TAG, "⚠️ NO IN ENDPOINT - This printer is UNIDIRECTIONAL (write-only)")
            Log.w(TAG, "⚠️ Status queries will NOT work - printer cannot send data back")
        }
        Log.d(TAG, "─────────────────────────────────────────────────────────────")
        
        // We need at least the OUT endpoint
        if (outEndpoint != null) {
            val connection = usbManager.openDevice(usbDevice)
            if (connection != null) {
                if (connection.claimInterface(intf, true)) {
                    usbConnection = connection
                    usbInterface = intf
                    usbEndpoint = outEndpoint
                    usbEndpointIn = inEndpoint  // May be null if not available
                    currentUsbDevice = usbDevice
                    currentMode = ConnectionMode.USB

                    _connectedDevice.value = device.copy(connectionStatus = ConnectionStatus.CONNECTED)
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    Log.d(TAG, "✓ Connected successfully via interface ${intf.id}")
                    Log.d(TAG, "  OUT endpoint: ${outEndpoint.endpointNumber} (for printing)")
                    
                    if (inEndpoint != null) {
                        Log.d(TAG, "  IN endpoint: ${inEndpoint.endpointNumber} (for reading status)")
                        _toastMessage.value = "Connected (bidirectional)"
                        startReadLoop()
                    } else {
                        Log.w(TAG, "  IN endpoint: NOT AVAILABLE")
                        Log.w(TAG, "  ⚠️ Printer is WRITE-ONLY - cannot read status")
                        _toastMessage.value = "Connected (write-only, no status)"
                    }
                    
                    return true
                } else {
                    Log.w(TAG, "Failed to claim interface")
                    connection.close()
                }
            } else {
                Log.w(TAG, "Failed to open device")
            }
        }
        return false
    }

    private fun connectNetwork(device: PrinterDevice): Boolean {
        val addressPart = device.address.removePrefix("net:")
        val parts = addressPart.split(":")
        val ip = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_PORT else DEFAULT_PORT

        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)

        socket = newSocket
        networkOutputStream = newSocket.getOutputStream()
        networkInputStream = newSocket.getInputStream()  // For reading responses
        currentMode = ConnectionMode.NETWORK

        _connectedDevice.value = device.copy(connectionStatus = ConnectionStatus.CONNECTED)
        _connectionStatus.value = ConnectionStatus.CONNECTED
        Log.d(TAG, "Network connected with input stream available for reading")
        
        // Start reading from printer in background
        startReadLoop()
        
        return true
    }
    
    // Unified read loop for all connection types
    private fun startReadLoop() {
        readJob?.cancel()
        readJob = CoroutineScope(Dispatchers.IO).launch {
            isReadingEnabled = true
            Log.d(TAG, "Started read loop for mode: $currentMode")
            
            while (isReadingEnabled && isConnected()) {
                try {
                    val data = when (currentMode) {
                        ConnectionMode.USB -> readFromUsbNonBlocking()
                        ConnectionMode.NETWORK -> readFromNetworkNonBlocking()
                        null -> null
                    }
                    Log.d(TAG, "startReadLoop: -------->${data.toString()}")
                    if (data != null && data.isNotEmpty()) {
                        logReceivedData(data)
                        _receivedData.value = data
                        
                        // Set toast message for UI
                        val message = "Printer: ${String(data, Charsets.UTF_8).trim()}"
                        Log.d(TAG, "startReadLoop: -------->$message")
                        _toastMessage.value = message
                    }
                    
                    Thread.sleep(100)  // Poll every 100ms
                } catch (e: Exception) {
                    if (isReadingEnabled) {
                        Log.e(TAG, "Error in read loop", e)
                    }
                }
            }
            Log.d(TAG, "Read loop stopped")
        }
    }
    
    // Non-blocking USB read
    private fun readFromUsbNonBlocking(): ByteArray? {
        val endpoint = usbEndpointIn ?: return null
        val connection = usbConnection ?: return null
        
        val buffer = ByteArray(endpoint.maxPacketSize)
        val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, 50) // 50ms timeout
        
        return if (bytesRead > 0) {
            buffer.copyOf(bytesRead)
        } else {
            null
        }
    }
    
    // Non-blocking Network read
    private fun readFromNetworkNonBlocking(): ByteArray? {
        val inputStream = networkInputStream ?: return null
        
        return try {
            if (inputStream.available() > 0) {
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    buffer.copyOf(bytesRead)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun disconnect() {
        isReadingEnabled = false  // Stop any read loops
        readJob?.cancel()
        readJob = null
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Disconnecting from printer, mode=$currentMode")
                when (currentMode) {
                    ConnectionMode.USB -> {
                        usbInterface?.let { usbConnection?.releaseInterface(it) }
                        usbConnection?.close()
                        usbConnection = null
                        usbInterface = null
                        usbEndpoint = null
                        usbEndpointIn = null
                        currentUsbDevice = null
                    }
                    ConnectionMode.NETWORK -> {
                        networkInputStream?.close()
                        networkOutputStream?.close()
                        socket?.close()
                        networkInputStream = null
                        networkOutputStream = null
                        socket = null
                    }
                    null -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            } finally {
                currentMode = null
                _connectedDevice.value = null
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    override suspend fun printData(data: ByteArray): Boolean {
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "PRINT DATA - isConnected=${isConnected()}, mode=$currentMode")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "Total data size: ${data.size} bytes")
        
        // Log full hex dump (in chunks of 32 bytes per line)
        Log.d(TAG, "─── HEX DUMP ───")
        data.toList().chunked(32).forEachIndexed { index, chunk ->
            val hexLine = chunk.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "[${String.format("%04d", index * 32)}] $hexLine")
        }
        
        // Log as string (full content)
        Log.d(TAG, "─── STRING DATA ───")
        val stringData = String(data, Charsets.UTF_8)
        stringData.lines().forEachIndexed { index, line ->
            Log.d(TAG, "[$index] $line")
        }
        
        // Log control characters breakdown
        Log.d(TAG, "─── CONTROL CHARS ───")
        val controlChars = data.filter { it < 0x20 || it == 0x7F.toByte() }
        Log.d(TAG, "Control chars found: ${controlChars.size}")
        Log.d(TAG, "ESC (0x1B): ${data.count { it == 0x1B.toByte() }}")
        Log.d(TAG, "LF (0x0A): ${data.count { it == 0x0A.toByte() }}")
        Log.d(TAG, "CR (0x0D): ${data.count { it == 0x0D.toByte() }}")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        
        if (!isConnected()) {
            _errorMessage.value = "Printer not connected"
            Log.e(TAG, "Print failed: not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                when (currentMode) {
                    ConnectionMode.USB -> {
                        val endpoint = usbEndpoint
                        val connection = usbConnection
                        
                        if (endpoint == null || connection == null) {
                            Log.e(TAG, "USB endpoint or connection is null")
                            _errorMessage.value = "USB connection lost"
                            return@withContext false
                        }
                        
                        val maxPacketSize = endpoint.maxPacketSize
                        Log.d(TAG, "USB endpoint maxPacketSize: $maxPacketSize")
                        Log.d(TAG, "Sending ${data.size} bytes via USB bulk transfer")
                        
                        // Send data in chunks if larger than max packet size
                        var totalSent = 0
                        var offset = 0
                        
                        while (offset < data.size) {
                            val chunkSize = minOf(maxPacketSize, data.size - offset)
                            val chunk = data.copyOfRange(offset, offset + chunkSize)
                            
                            val result = connection.bulkTransfer(endpoint, chunk, chunk.size, 5000)
                            Log.d(TAG, "Bulk transfer chunk result: $result (sent $chunkSize bytes from offset $offset)")
                            
                            if (result < 0) {
                                Log.e(TAG, "USB transfer failed at offset $offset, result: $result")
                                _errorMessage.value = "USB transfer failed (code: $result)"
                                return@withContext false
                            }
                            
                            totalSent += result
                            offset += chunkSize
                            
                            // Small delay between chunks
                            if (offset < data.size) {
                                Thread.sleep(10)
                            }
                        }
                        
                        Log.d(TAG, "Total bytes sent: $totalSent")
                        
                        // Give printer time to process and release buffer
                        Thread.sleep(100)
                        
                        true
                    }
                    ConnectionMode.NETWORK -> {
                        Log.d(TAG, "Sending ${data.size} bytes via network")
                        networkOutputStream?.write(data)
                        networkOutputStream?.flush()
                        true
                    }
                    null -> {
                        _errorMessage.value = "No connection mode set"
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Print failed", e)
                _errorMessage.value = "Print failed: ${e.message}"
                false
            }
        }
    }

    override fun isConnected(): Boolean {
        return when (currentMode) {
            ConnectionMode.USB -> usbConnection != null
            ConnectionMode.NETWORK -> socket?.isConnected == true && socket?.isClosed == false
            null -> false
        } && _connectionStatus.value == ConnectionStatus.CONNECTED
    }

    override fun clearError() {
        _errorMessage.value = null
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    // ==================== READ METHODS ====================
    
    // Read data from printer (single read with timeout)
    suspend fun readData(timeoutMs: Int = 1000): ByteArray? {
        if (!isConnected()) {
            Log.e(TAG, "Cannot read: not connected")
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                when (currentMode) {
                    ConnectionMode.USB -> readFromUsb(timeoutMs)
                    ConnectionMode.NETWORK -> readFromNetwork(timeoutMs)
                    null -> null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read failed", e)
                null
            }
        }
    }
    
    private fun readFromUsb(timeoutMs: Int): ByteArray? {
        val endpoint = usbEndpointIn
        val connection = usbConnection
        
        if (endpoint == null) {
            Log.w(TAG, "No USB IN endpoint available - printer may not support reading")
            return null
        }
        
        if (connection == null) {
            Log.e(TAG, "USB connection is null")
            return null
        }
        
        val buffer = ByteArray(endpoint.maxPacketSize)
        val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        
        return if (bytesRead > 0) {
            val data = buffer.copyOf(bytesRead)
            logReceivedData(data)
            _receivedData.value = data
            data
        } else {
            Log.d(TAG, "USB read: no data (result=$bytesRead)")
            null
        }
    }
    
    private fun readFromNetwork(timeoutMs: Int): ByteArray? {
        val inputStream = networkInputStream ?: return null
        val sock = socket ?: return null
        
        sock.soTimeout = timeoutMs
        
        return try {
            val available = inputStream.available()
            if (available > 0) {
                val buffer = ByteArray(available)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    logReceivedData(data)
                    _receivedData.value = data
                    data
                } else null
            } else {
                // Try to read with timeout
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    logReceivedData(data)
                    _receivedData.value = data
                    data
                } else null
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "Network read timeout - no data")
            null
        }
    }
    
    private fun logReceivedData(data: ByteArray) {
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "RECEIVED DATA FROM PRINTER")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "Total received: ${data.size} bytes")
        
        // Log hex dump
        Log.d(TAG, "─── HEX DUMP ───")
        data.toList().chunked(32).forEachIndexed { index, chunk ->
            val hexLine = chunk.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "[${String.format("%04d", index * 32)}] $hexLine")
        }
        
        // Log as string
        Log.d(TAG, "─── STRING DATA ───")
        val stringData = String(data, Charsets.UTF_8)
        Log.d(TAG, stringData)
        
        // Log as ASCII with control char names
        Log.d(TAG, "─── ASCII (with control chars) ───")
        val asciiData = data.map { byte ->
            when {
                byte == 0x00.toByte() -> "<NUL>"
                byte == 0x0A.toByte() -> "<LF>"
                byte == 0x0D.toByte() -> "<CR>"
                byte == 0x1B.toByte() -> "<ESC>"
                byte < 0x20 -> "<${byte.toInt().toString(16).uppercase()}>"
                else -> byte.toInt().toChar().toString()
            }
        }.joinToString("")
        Log.d(TAG, asciiData)
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
    }
    
    // Start continuous reading from printer
    suspend fun startContinuousRead(intervalMs: Long = 500) {
        if (!isConnected()) {
            Log.e(TAG, "Cannot start reading: not connected")
            return
        }
        
        isReadingEnabled = true
        Log.d(TAG, "Starting continuous read from printer...")
        
        withContext(Dispatchers.IO) {
            while (isReadingEnabled && isConnected()) {
                try {
                    readData(100)  // Short timeout for polling
                    Thread.sleep(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous read", e)
                }
            }
            Log.d(TAG, "Continuous read stopped")
        }
    }
    
    // Stop continuous reading
    fun stopContinuousRead() {
        isReadingEnabled = false
        Log.d(TAG, "Stopping continuous read...")
    }
    
    // Check if IN endpoint is available for reading
    fun canReadFromPrinter(): Boolean {
        return when (currentMode) {
            ConnectionMode.USB -> usbEndpointIn != null
            ConnectionMode.NETWORK -> networkInputStream != null
            null -> false
        }
    }

    // ==================== PRINTER STATUS METHODS ====================
    
    // Printer status StateFlow
    private val _printerStatus = MutableStateFlow<LabelPrinterCommands.PrinterStatus?>(null)
    val printerStatus: StateFlow<LabelPrinterCommands.PrinterStatus?> = _printerStatus.asStateFlow()
    
    // Query printer status (TSC/TSPL) - tries multiple command formats
    suspend fun queryPrinterStatus(): LabelPrinterCommands.PrinterStatus? {
        if (!isConnected()) {
            Log.e(TAG, "Cannot query status: not connected")
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                Log.d(TAG, "QUERYING PRINTER STATUS")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                Log.d(TAG, "Connection mode: $currentMode")
                Log.d(TAG, "USB IN endpoint available: ${usbEndpointIn != null}")
                
                // Try all status commands until one works
                val allCommands = LabelPrinterCommands.TsplStatus.getAllStatusCommands()
                
                for ((name, command) in allCommands) {
                    Log.d(TAG, "─── Trying command: $name ───")
                    Log.d(TAG, "Command bytes: ${command.joinToString(" ") { "%02X".format(it) }}")
                    
                    val response = sendCommandAndRead(command, 300)
                    
                    if (response != null && response.isNotEmpty()) {
                        Log.d(TAG, "✓ Got response from $name!")
                        Log.d(TAG, "Response HEX: ${response.joinToString(" ") { "%02X".format(it) }}")
                        Log.d(TAG, "Response STR: ${String(response, Charsets.UTF_8)}")
                        
                        val status = parseStatusResponse(response)
                        _printerStatus.value = status
                        _toastMessage.value = "Status ($name): ${status.getStatusText()}"
                        
                        Log.d(TAG, "═══════════════════════════════════════════════════════════")
                        return@withContext status
                    } else {
                        Log.d(TAG, "✗ No response from $name")
                    }
                    
                    Thread.sleep(100)  // Small delay between attempts
                }
                
                Log.w(TAG, "No response from any status command")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                
                // Return a default "unknown" status
                val unknownStatus = LabelPrinterCommands.PrinterStatus(
                    isReady = true,  // Assume ready if no status
                    statusMessage = "Status query not supported"
                )
                _printerStatus.value = unknownStatus
                _toastMessage.value = "Status: Unknown (no response)"
                unknownStatus
                
            } catch (e: Exception) {
                Log.e(TAG, "Error querying status", e)
                null
            }
        }
    }
    
    // Send command and read response with multiple retry attempts
    private fun sendCommandAndRead(command: ByteArray, waitMs: Long): ByteArray? {
        try {
            val inEndpoint = usbEndpointIn
            val connection = usbConnection
            
            // For USB: First clear any pending data in the IN buffer
            if (currentMode == ConnectionMode.USB && inEndpoint != null && connection != null) {
                val clearBuffer = ByteArray(64)
                val cleared = connection.bulkTransfer(inEndpoint, clearBuffer, clearBuffer.size, 50)
                if (cleared > 0) {
                    Log.d(TAG, "Cleared $cleared bytes from USB IN buffer before query")
                }
            }
            
            // Send command
            when (currentMode) {
                ConnectionMode.USB -> {
                    val endpoint = usbEndpoint ?: return null
                    val conn = usbConnection ?: return null
                    val sent = conn.bulkTransfer(endpoint, command, command.size, 1000)
                    Log.d(TAG, "USB sent: $sent bytes")
                    if (sent < 0) {
                        Log.e(TAG, "USB send failed!")
                        return null
                    }
                }
                ConnectionMode.NETWORK -> {
                    networkOutputStream?.write(command)
                    networkOutputStream?.flush()
                    Log.d(TAG, "Network sent: ${command.size} bytes")
                }
                null -> return null
            }
            
            // Try reading multiple times with increasing timeout
            val readAttempts = 3
            val timeouts = listOf(100L, 200L, 300L)
            
            for (attempt in 0 until readAttempts) {
                Thread.sleep(timeouts[attempt])
                
                val response = when (currentMode) {
                    ConnectionMode.USB -> {
                        if (inEndpoint != null && connection != null) {
                            val buffer = ByteArray(64)
                            // Use longer timeout for USB read
                            val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 1000)
                            Log.d(TAG, "USB read attempt ${attempt + 1}: $bytesRead bytes (timeout: 1000ms)")
                            if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                        } else {
                            Log.w(TAG, "No USB IN endpoint - cannot read status")
                            null
                        }
                    }
                    ConnectionMode.NETWORK -> {
                        val inputStream = networkInputStream
                        if (inputStream != null && inputStream.available() > 0) {
                            val buffer = ByteArray(256)
                            val bytesRead = inputStream.read(buffer)
                            Log.d(TAG, "Network read attempt ${attempt + 1}: $bytesRead bytes")
                            if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                        } else {
                            Log.d(TAG, "Network read attempt ${attempt + 1}: no data available")
                            null
                        }
                    }
                    null -> null
                }
                
                if (response != null && response.isNotEmpty()) {
                    return response
                }
            }
            
            Log.d(TAG, "No response after $readAttempts attempts")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendCommandAndRead", e)
            return null
        }
    }
    
    // Legacy read method for backward compatibility
    private fun readFromUsbNonBlockingLegacy(): ByteArray? {
        val inEndpoint = usbEndpointIn
        val connection = usbConnection
        if (inEndpoint != null && connection != null) {
            val buffer = ByteArray(64)
            val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 500)
            return if (bytesRead > 0) buffer.copyOf(bytesRead) else null
        }
        return null
    }
    
    private fun readFromNetworkNonBlockingLegacy(): ByteArray? {
        val inputStream = networkInputStream
        if (inputStream != null && inputStream.available() > 0) {
            val buffer = ByteArray(256)
            val bytesRead = inputStream.read(buffer)
            return if (bytesRead > 0) buffer.copyOf(bytesRead) else null
        }
        return null
    }
    
    // Old sendCommandAndRead kept for reference - REMOVE LATER
    private fun sendCommandAndReadOld(command: ByteArray, waitMs: Long): ByteArray? {
        try {
            // Send command
            when (currentMode) {
                ConnectionMode.USB -> {
                    val endpoint = usbEndpoint ?: return null
                    val connection = usbConnection ?: return null
                    val sent = connection.bulkTransfer(endpoint, command, command.size, 1000)
                    Log.d(TAG, "USB sent: $sent bytes")
                }
                ConnectionMode.NETWORK -> {
                    networkOutputStream?.write(command)
                    networkOutputStream?.flush()
                    Log.d(TAG, "Network sent: ${command.size} bytes")
                }
                null -> return null
            }
            
            // Wait for response
            Thread.sleep(waitMs)
            
            // Read response
            return when (currentMode) {
                ConnectionMode.USB -> {
                    val inEndpoint = usbEndpointIn
                    val connection = usbConnection
                    if (inEndpoint != null && connection != null) {
                        val buffer = ByteArray(64)
                        val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 500)
                        Log.d(TAG, "USB read: $bytesRead bytes")
                        if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                    } else {
                        Log.w(TAG, "No USB IN endpoint - cannot read status")
                        null
                    }
                }
                ConnectionMode.NETWORK -> {
                    val inputStream = networkInputStream
                    if (inputStream != null && inputStream.available() > 0) {
                        val buffer = ByteArray(256)
                        val bytesRead = inputStream.read(buffer)
                        Log.d(TAG, "Network read: $bytesRead bytes")
                        if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                    } else {
                        Log.d(TAG, "Network: no data available")
                        null
                    }
                }
                null -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendCommandAndRead", e)
            return null
        }
    }
    
    // Parse status response bytes
    private fun parseStatusResponse(response: ByteArray): LabelPrinterCommands.PrinterStatus {
        if (response.isEmpty()) {
            return LabelPrinterCommands.PrinterStatus(statusMessage = "No response")
        }
        
        // TSC printers typically return a single status byte or status string
        val firstByte = response[0]
        val statusInt = firstByte.toInt() and 0xFF
        
        // Check if it's a TSC status byte format
        val isPaperOut = (statusInt and 0x04) != 0
        val isRibbonOut = (statusInt and 0x08) != 0
        val isPaused = (statusInt and 0x10) != 0
        val isPrinting = (statusInt and 0x20) != 0
        val isCoverOpen = (statusInt and 0x40) != 0
        val hasError = (statusInt and 0x80) != 0
        
        // Also check string response
        val responseStr = String(response, Charsets.UTF_8).trim().lowercase()
        val paperOutFromString = responseStr.contains("paper") && (responseStr.contains("out") || responseStr.contains("empty") || responseStr.contains("end"))
        
        return LabelPrinterCommands.PrinterStatus(
            isReady = !isPaperOut && !hasError && !isCoverOpen,
            isPaperOut = isPaperOut || paperOutFromString,
            isRibbonOut = isRibbonOut,
            isPaused = isPaused,
            isPrinting = isPrinting,
            isCoverOpen = isCoverOpen,
            hasError = hasError,
            rawStatus = statusInt,
            statusMessage = responseStr
        )
    }
    
    // Query printer info
    suspend fun queryPrinterInfo(): String? {
        if (!isConnected()) return null
        
        return withContext(Dispatchers.IO) {
            try {
                val infoCommand = LabelPrinterCommands.TsplStatus.queryInfo()
                Log.d(TAG, "Querying printer info...")
                
                when (currentMode) {
                    ConnectionMode.USB -> {
                        val endpoint = usbEndpoint ?: return@withContext null
                        val connection = usbConnection ?: return@withContext null
                        connection.bulkTransfer(endpoint, infoCommand, infoCommand.size, 1000)
                    }
                    ConnectionMode.NETWORK -> {
                        networkOutputStream?.write(infoCommand)
                        networkOutputStream?.flush()
                    }
                    null -> return@withContext null
                }
                
                Thread.sleep(300)
                
                val response = when (currentMode) {
                    ConnectionMode.USB -> readFromUsbNonBlocking()
                    ConnectionMode.NETWORK -> readFromNetworkNonBlocking()
                    null -> null
                }
                
                if (response != null && response.isNotEmpty()) {
                    val info = String(response, Charsets.UTF_8).trim()
                    Log.d(TAG, "Printer info: $info")
                    _toastMessage.value = "Printer: $info"
                    info
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying printer info", e)
                null
            }
        }
    }
    
    // Check if paper is available
    suspend fun isPaperAvailable(): Boolean {
        val status = queryPrinterStatus()
        return status?.isPaperOut == false
    }
}
