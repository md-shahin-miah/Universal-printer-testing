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
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class UsbPrinterManager(private val context: Context) : BasePrinterManager {

    companion object {
        private const val TAG = "UsbPrinterManager"
        private const val ACTION_USB_PERMISSION = "com.valt.printertestapplication.USB_PERMISSION"
        private const val PRINTER_CLASS = 7
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpoint: UsbEndpoint? = null
    private var currentUsbDevice: UsbDevice? = null

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

    private var permissionContinuation: ((Boolean) -> Unit)? = null

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
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    override suspend fun startScan() {
        _isScanning.value = true
        _errorMessage.value = null

        withContext(Dispatchers.IO) {
            try {
                val deviceList = usbManager.deviceList
                val printers = mutableListOf<PrinterDevice>()
                
                Log.d(TAG, "Found ${deviceList.size} USB devices")

                deviceList.values.forEach { device ->
                    Log.d(TAG, "Checking device: ${device.productName}, VID=${device.vendorId}, PID=${device.productId}")
                    if (isPrinterDevice(device) || hasBulkOutEndpoint(device)) {
                        printers.add(
                            PrinterDevice(
                                name = device.productName ?: "USB Printer (${device.vendorId}:${device.productId})",
                                address = device.deviceName,
                                type = PrinterType.USB
                            )
                        )
                    }
                }

                _availableDevices.value = printers
                Log.d(TAG, "Found ${printers.size} printer devices")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _errorMessage.value = "Failed to scan USB devices: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
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

    override suspend fun stopScan() {
        _isScanning.value = false
    }

    override suspend fun connect(device: PrinterDevice): Boolean {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null

        return withContext(Dispatchers.IO) {
            try {
                val usbDevice = usbManager.deviceList[device.address]
                if (usbDevice == null) {
                    _errorMessage.value = "Device not found"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@withContext false
                }

                if (!usbManager.hasPermission(usbDevice)) {
                    Log.d(TAG, "Requesting USB permission for ${usbDevice.deviceName}")
                    val granted = requestUsbPermission(usbDevice)
                    if (!granted) {
                        _errorMessage.value = "USB permission denied. Please try again."
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return@withContext false
                    }
                }

                connectToDevice(usbDevice, device)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _errorMessage.value = "Connection failed: ${e.message}"
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            }
        }
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

    private fun connectToDevice(usbDevice: UsbDevice, printerDevice: PrinterDevice): Boolean {
        Log.d(TAG, "Connecting to device with ${usbDevice.interfaceCount} interfaces")
        
        // First try: printer class interfaces
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            Log.d(TAG, "Interface $i: class=${intf.interfaceClass}")
            
            if (intf.interfaceClass == PRINTER_CLASS) {
                if (tryConnectInterface(usbDevice, intf, printerDevice)) return true
            }
        }

        // Second try: any interface with bulk OUT
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (tryConnectInterface(usbDevice, intf, printerDevice)) return true
        }

        _errorMessage.value = "Could not find printer endpoint"
        _connectionStatus.value = ConnectionStatus.ERROR
        return false
    }

    private fun tryConnectInterface(usbDevice: UsbDevice, intf: UsbInterface, printerDevice: PrinterDevice): Boolean {
        for (j in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(j)
            Log.d(TAG, "  Endpoint $j: type=${endpoint.type}, direction=${endpoint.direction}")
            
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == UsbConstants.USB_DIR_OUT
            ) {
                val connection = usbManager.openDevice(usbDevice)
                if (connection != null) {
                    if (connection.claimInterface(intf, true)) {
                        usbConnection = connection
                        usbInterface = intf
                        usbEndpoint = endpoint
                        currentUsbDevice = usbDevice

                        _connectedDevice.value = printerDevice.copy(connectionStatus = ConnectionStatus.CONNECTED)
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        Log.d(TAG, "Connected successfully")
                        return true
                    } else {
                        Log.w(TAG, "Failed to claim interface")
                        connection.close()
                    }
                } else {
                    Log.w(TAG, "Failed to open device")
                }
            }
        }
        return false
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Disconnecting")
                usbInterface?.let { usbConnection?.releaseInterface(it) }
                usbConnection?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            } finally {
                usbConnection = null
                usbInterface = null
                usbEndpoint = null
                currentUsbDevice = null
                _connectedDevice.value = null
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    override suspend fun printData(data: ByteArray): Boolean {
        Log.d(TAG, "printData called, isConnected=${isConnected()}")
        
        if (!isConnected()) {
            _errorMessage.value = "Printer not connected"
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val endpoint = usbEndpoint
                val connection = usbConnection
                
                if (endpoint == null || connection == null) {
                    _errorMessage.value = "USB connection lost"
                    return@withContext false
                }

                Log.d(TAG, "Sending ${data.size} bytes via bulk transfer")
                val result = connection.bulkTransfer(endpoint, data, data.size, 10000)
                Log.d(TAG, "Bulk transfer result: $result")
                
                if (result < 0) {
                    _errorMessage.value = "Print failed: Transfer error (code: $result)"
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Print failed", e)
                _errorMessage.value = "Print failed: ${e.message}"
                false
            }
        }
    }

    override fun isConnected(): Boolean {
        return usbConnection != null && _connectionStatus.value == ConnectionStatus.CONNECTED
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
}
