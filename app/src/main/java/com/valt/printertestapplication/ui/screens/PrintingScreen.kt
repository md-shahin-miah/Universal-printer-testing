package com.valt.printertestapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.valt.printertestapplication.data.ConnectionStatus
import com.valt.printertestapplication.data.PrinterDevice
import com.valt.printertestapplication.data.PrinterType
import com.valt.printertestapplication.printer.LabelPrinterCommands
import com.valt.printertestapplication.ui.components.OrderData
import com.valt.printertestapplication.ui.components.OrderItem
import com.valt.printertestapplication.viewmodel.PrintResult
import com.valt.printertestapplication.viewmodel.PrinterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintingScreen(
    viewModel: PrinterViewModel,
    printerType: PrinterType,
    onBack: () -> Unit
) {
    val printerState by viewModel.printerState.collectAsState()
    val printResult by viewModel.printResult.collectAsState()
    val printerReceivedMessage by viewModel.printerReceivedMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddPrinterDialog by remember { mutableStateOf(false) }
    var showPrintDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    // Show Toast when printer sends data
    LaunchedEffect(printerReceivedMessage) {
        printerReceivedMessage?.let { message ->
            snackbarHostState.showSnackbar("📥 $message")
            viewModel.clearReceivedMessage()
        }
    }

    LaunchedEffect(printResult) {
        when (val result = printResult) {
            is PrintResult.Success -> {
                snackbarHostState.showSnackbar("Print successful!")
                viewModel.clearPrintResult()
            }
            is PrintResult.Error -> {
                snackbarHostState.showSnackbar("Print failed: ${result.message}")
                viewModel.clearPrintResult()
            }
            else -> {}
        }
    }

    LaunchedEffect(printerState.errorMessage) {
        printerState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = printerType.displayName,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startScan() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    if (printerType == PrinterType.WIFI_LAN || printerType == PrinterType.LABEL_PRINTER) {
                        IconButton(onClick = { showAddPrinterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Printer"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (printerState.connectionStatus == ConnectionStatus.CONNECTED) {
                FloatingActionButton(
                    onClick = { showPrintDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "Print"
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Connection Status Card
            ConnectionStatusCard(printerState.connectionStatus, printerState.connectedDevice)

            Spacer(modifier = Modifier.height(16.dp))

            // Connected Device Actions
            if (printerState.connectionStatus == ConnectionStatus.CONNECTED) {
                ConnectedDeviceActions(
                    device = printerState.connectedDevice,
                    onDisconnect = { viewModel.disconnect() },
                    onPrintTest = { viewModel.printTestPage() },
                    isPrinting = printResult == PrintResult.Printing
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Available Devices
            Text(
                text = "Available Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (printerState.isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Scanning for devices...")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(printerState.availableDevices) { device ->
                    DeviceCard(
                        device = device,
                        isConnected = printerState.connectedDevice?.address == device.address,
                        isConnecting = printerState.connectionStatus == ConnectionStatus.CONNECTING,
                        onClick = {
                            if (printerState.connectedDevice?.address != device.address) {
                                viewModel.connect(device)
                            }
                        }
                    )
                }

                if (printerState.availableDevices.isEmpty() && !printerState.isScanning) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No devices found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = { viewModel.startScan() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan Again")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Printer Dialog
    if (showAddPrinterDialog) {
        AddPrinterDialog(
            printerType = printerType,
            onDismiss = { showAddPrinterDialog = false },
            onAdd = { name, ip, port ->
                if (printerType == PrinterType.WIFI_LAN) {
                    viewModel.addWifiPrinter(name, ip, port)
                } else {
                    viewModel.addLabelNetworkPrinter(name, ip, port)
                }
                showAddPrinterDialog = false
            }
        )
    }

    // Print Dialog - show label-specific dialog for label printers
    if (showPrintDialog) {
        if (printerType == PrinterType.LABEL_PRINTER) {
            LabelPrintDialog(
                onDismiss = { showPrintDialog = false },
                onPrintTest = { language ->
                    viewModel.printLabelTestPage(language)
                    showPrintDialog = false
                },
                onPrintCustom = { text, language ->
                    viewModel.printLabelWithLanguage(text, language)
                    showPrintDialog = false
                },
                onPrintRaw = { text ->
                    viewModel.printRawText(text)
                    showPrintDialog = false
                },
                onPrintTscSimple = { text ->
                    viewModel.printTscSimpleTest(text)
                    showPrintDialog = false
                },
                onPrintUniversal = {
                    viewModel.printUniversalTest()
                    showPrintDialog = false
                },
                onPrintOrder = { text ->
                    viewModel.printOrderLabel(text)
                    showPrintDialog = false
                },
                onPrintOrderView = {
                    // Sample order for testing
                    val sampleOrder = OrderData(
                        orderId = "12345",
                        date = "2024-12-24 17:00",
                        customerName = "John Doe",
                        items = listOf(
                            OrderItem("Burger", 2, 15.99),
                            OrderItem("Fries", 1, 4.99),
                           OrderItem("Drink", 2, 3.50)
                        ),
                        subtotal = 43.97,
                        tax = 3.52,
                        total = 47.49
                    )
                    viewModel.printOrderView(sampleOrder)
                    showPrintDialog = false
                }
            )
        } else {
            PrintDialog(
                onDismiss = { showPrintDialog = false },
                onPrintTest = {
                    viewModel.printTestPage()
                    showPrintDialog = false
                },
                onPrintOrderView = {
                    // Sample order for ESC/POS printers
                    val sampleOrder = com.valt.printertestapplication.ui.components.OrderData(
                        orderId = "12345",
                        date = "2024-12-24 17:00",
                        customerName = "John Doe",
                        items = listOf(
                            com.valt.printertestapplication.ui.components.OrderItem("Burger", 2, 15.99),
                            com.valt.printertestapplication.ui.components.OrderItem("Fries", 1, 4.99),
                            com.valt.printertestapplication.ui.components.OrderItem("Drink", 2, 3.50)
                        ),
                        subtotal = 43.97,
                        tax = 3.52,
                        total = 47.49
                    )
                    viewModel.printOrderViewEscPos(sampleOrder)
                    showPrintDialog = false
                },
                onPrintCustom = { text ->
                    viewModel.printCustomData(text)
                    showPrintDialog = false
                }
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(status: ConnectionStatus, device: PrinterDevice?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                ConnectionStatus.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                ConnectionStatus.CONNECTING -> Color(0xFFFFC107).copy(alpha = 0.1f)
                ConnectionStatus.ERROR -> Color(0xFFF44336).copy(alpha = 0.1f)
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when (status) {
                            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
                            ConnectionStatus.ERROR -> Color(0xFFF44336)
                            ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    ConnectionStatus.CONNECTED -> Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White
                    )
                    ConnectionStatus.CONNECTING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    ConnectionStatus.ERROR -> Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White
                    )
                    ConnectionStatus.DISCONNECTED -> Icon(
                        Icons.Default.Print,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = when (status) {
                        ConnectionStatus.CONNECTED -> "Connected"
                        ConnectionStatus.CONNECTING -> "Connecting..."
                        ConnectionStatus.ERROR -> "Connection Error"
                        ConnectionStatus.DISCONNECTED -> "Not Connected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (device != null && status == ConnectionStatus.CONNECTED) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectedDeviceActions(
    device: PrinterDevice?,
    onDisconnect: () -> Unit,
    onPrintTest: () -> Unit,
    isPrinting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Connected: ${device?.name ?: "Unknown"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = device?.address ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPrintTest,
                    enabled = !isPrinting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isPrinting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Print, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Print")
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFF44336)
                    )
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: PrinterDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isConnected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Connected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun AddPrinterDialog(
    printerType: PrinterType,
    onDismiss: () -> Unit,
    onAdd: (name: String, ip: String, port: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9100") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${if (printerType == PrinterType.LABEL_PRINTER) "Label" else "Network"} Printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Printer Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portNum = port.toIntOrNull() ?: 9100
                    onAdd(name.ifEmpty { "Network Printer" }, ip, portNum)
                },
                enabled = ip.isNotEmpty()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PrintDialog(
    onDismiss: () -> Unit,
    onPrintTest: () -> Unit,
    onPrintCustom: (String) -> Unit,
    onPrintOrderView: () -> Unit = {}
) {
    var customText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print Options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPrintTest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Test Page")
                }

                // Print Order View (formatted receipt image)
                Button(
                    onClick = onPrintOrderView,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9C27B0)
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Sample Order (View)")
                }

                Text(
                    text = "Or print custom text:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Custom Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPrintCustom(customText) },
                enabled = customText.isNotEmpty()
            ) {
                Text("Print Custom")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LabelPrintDialog(
    onDismiss: () -> Unit,
    onPrintTest: (LabelPrinterCommands.LabelLanguage) -> Unit,
    onPrintCustom: (String, LabelPrinterCommands.LabelLanguage) -> Unit,
    onPrintRaw: (String) -> Unit,
    onPrintTscSimple: (String) -> Unit = {},
    onPrintUniversal: () -> Unit = {},
    onPrintOrder: (String) -> Unit = {},
    onPrintOrderView: () -> Unit = {}
) {
    var customText by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf(LabelPrinterCommands.LabelLanguage.TSPL) }

    val languages = LabelPrinterCommands.LabelLanguage.entries

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label Printer Options") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select printer language:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                // Language selector - fixed 100dp height with scroll
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    languages.forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selectedLanguage == language)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { selectedLanguage = language }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (language) {
                                    LabelPrinterCommands.LabelLanguage.ZPL -> "ZPL (Zebra)"
                                    LabelPrinterCommands.LabelLanguage.TSPL -> "TSPL (TSC)"
                                    LabelPrinterCommands.LabelLanguage.EPL -> "EPL (Eltron)"
                                    LabelPrinterCommands.LabelLanguage.CPCL -> "CPCL (Mobile)"
                                    LabelPrinterCommands.LabelLanguage.ESC_POS -> "ESC/POS"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (selectedLanguage == language) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Print Order View (formatted receipt)
                Button(
                    onClick = onPrintOrderView,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9C27B0)
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Sample Order (View)")
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Universal Test button (works with most label printers)
                OutlinedButton(
                    onClick = onPrintUniversal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF2196F3)
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Universal Test (Any Printer)")
                }

                Spacer(modifier = Modifier.height(4.dp))

                // TSC Simple Test button - uses custom text if entered
                Button(
                    onClick = { onPrintTscSimple(if (customText.isNotEmpty()) customText else "TEST") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TSC Print${if (customText.isNotEmpty()) " (Custom)" else " Test"}")
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = { onPrintTest(selectedLanguage) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Test Label")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Or print custom text:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Label Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Print Order button (for continuous white labels)
                Button(
                    onClick = { onPrintOrder(customText) },
                    enabled = customText.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800)
                    )
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Order (Continuous Label)")
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onPrintRaw(customText) },
                        enabled = customText.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Raw Text")
                    }
                    Button(
                        onClick = { onPrintCustom(customText, selectedLanguage) },
                        enabled = customText.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Formatted")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
