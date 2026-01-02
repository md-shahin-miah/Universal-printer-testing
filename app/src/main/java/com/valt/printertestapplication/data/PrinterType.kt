package com.valt.printertestapplication.data

enum class PrinterType(val displayName: String, val description: String) {
    INNER_PRINTER("Inner Printer", "Built-in device printer"),
    USB("USB Printer", "Connect via USB cable"),
    BLUETOOTH("Bluetooth Printer", "Connect via Bluetooth"),
    WIFI_LAN("WiFi/LAN Printer", "Connect via network"),
    LABEL_PRINTER("Label Printer", "USB/Network label printer")
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class PrinterDevice(
    val name: String,
    val address: String,
    val type: PrinterType,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
)

data class PrinterState(
    val selectedType: PrinterType? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectedDevice: PrinterDevice? = null,
    val availableDevices: List<PrinterDevice> = emptyList(),
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)
