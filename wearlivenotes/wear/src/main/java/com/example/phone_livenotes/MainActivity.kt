package com.example.phone_livenotes

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// BLE Service and Characteristic UUIDs (must match phone app)
private val SERVICE_UUID: UUID = UUID.fromString("a9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
private val CHARACTERISTIC_DATA_UUID: UUID = UUID.fromString("b9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
private val CHARACTERISTIC_COMMAND_UUID: UUID = UUID.fromString("c9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val DEFAULT_PROJECT_NAME = "Watch Capture"
private const val DEFAULT_PROJECT_COLOR = "#80CBC4"

data class NotePayload(
    val id: Long? = null,
    val title: String,
    val content: String,
    val projectName: String? = null,
    val projectColor: String? = null,
    val updatedAt: Long? = null
)

data class ProjectPayload(
    val id: Long? = null,
    val name: String,
    val color: String
)

data class NotesEnvelope(
    val notes: List<NotePayload>? = null,
    val projects: List<ProjectPayload>? = null
)

class MainActivity : ComponentActivity() {
    private val notesState = mutableStateListOf<NotePayload>()
    private val projectsState = mutableStateListOf<ProjectPayload>()
    private val statusState = mutableStateOf("Scanning...")
    private val scanningState = mutableStateOf(false)
    private val watchConnectedState = mutableStateOf(false)
    private val notifyEnabledState = mutableStateOf(false)
    private val lastErrorState = mutableStateOf("")
    private var connectWatchdogJob: Job? = null
    private var retryJob: Job? = null
    private val logState = mutableStateListOf<String>()
    private var classicSocket: BluetoothSocket? = null
    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )
    private val gson = Gson()
    
    // BLE Components
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    
    // Characteristics
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    
    private var isScanning = false
    private var scanFallbackJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        ensurePermissionsAndMaybeStart()

        setContent {
            OledTheme {
                WatchUI(
                    notes = notesState,
                    projects = projectsState,
                    status = statusState.value,
                    scanning = scanningState.value,
                    watchConnected = watchConnectedState.value,
                    notifyEnabled = notifyEnabledState.value,
                    bluetoothOn = bluetoothAdapter?.isEnabled == true,
                    recentLogs = logState,
                    lastError = lastErrorState.value,
                    onSend = ::sendToPhone,
                    onRefresh = {
                        ensurePermissionsAndMaybeStart()
                        requestState()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectGatt()
    }

    private fun ensurePermissionsAndMaybeStart() {
        if (!ensureBluetoothOn()) return
        if (hasAllPermissions()) {
            startBleScanning()
        startRetryLoop()
        startClassicFallback()
            startClassicFallback()
        } else {
            statusState.value = "Permissions required"
            ActivityCompat.requestPermissions(this, requiredPermissions, 1)
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun ensureBluetoothOn(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter != null && !adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                statusState.value = "Turn on Bluetooth"
                log("Requested Bluetooth enable")
            } catch (_: Exception) {
                statusState.value = "Enable Bluetooth to sync"
            }
            return false
        }
        return true
    }

    private fun startClassicFallback() {
        if (!hasAllPermissions()) return
        val adapter = bluetoothAdapter ?: return
        adapter.bondedDevices?.forEach { device ->
            try {
                val socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                classicSocket = socket
                log("Classic connected to ${device.address}")
                listenClassic(socket)
                return
            } catch (e: Exception) {
                log("Classic connect failed: ${e.message}")
                try { classicSocket?.close() } catch (_: Exception) {}
                classicSocket = null
            }
        }
    }

    private fun listenClassic(socket: BluetoothSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = socket.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val envelope = gson.fromJson(line, NotesEnvelope::class.java)
                    envelope.projects?.let { projects ->
                        runOnUiThread {
                            projectsState.clear()
                            projectsState.addAll(projects)
                        }
                    }
                    envelope.notes?.let { notes ->
                        runOnUiThread {
                            notesState.clear()
                            notesState.addAll(notes)
                            markSynced()
                        }
                    }
                }
            } catch (e: Exception) {
                log("Classic listen failed: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
                if (classicSocket == socket) classicSocket = null
            }
        }
    }

    private fun log(message: String) {
        val entry = "${nowTime()} $message"
        logState.add(0, entry)
        if (logState.size > 50) logState.removeLast()
    }
    
    private fun startBleScanning() {
        if (isScanning) return
        
        if (!hasAllPermissions()) {
            statusState.value = "BLE permission required"
            lastErrorState.value = "Grant Bluetooth permissions"
            ActivityCompat.requestPermissions(this, requiredPermissions, 1)
            return
        }
        log("Scanning start (filtered)")
        
        // Scan specifically for our service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        isScanning = true
        scanningState.value = true
        statusState.value = "Scanning for phone..."
        Log.i("BLE", "Started BLE scan")

        // Fallback: if filtered scan finds nothing, retry once with a broad scan
        scanFallbackJob?.cancel()
        scanFallbackJob = CoroutineScope(Dispatchers.Main).launch {
            delay(8000)
            if (isScanning && bluetoothGatt == null) {
                Log.w("BLE", "No device found with filtered scan; retrying with broad scan")
                stopBleScanning()
                statusState.value = "Scanning (broad)..."
                log("Fallback broad scan")
                bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
                isScanning = true
                scanningState.value = true
            }
        }
    }
    
    private fun stopBleScanning() {
        if (!isScanning) return
        
        if (!hasAllPermissions()) {
            return
        }
        
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        scanningState.value = false
        scanFallbackJob?.cancel()
        Log.i("BLE", "Stopped BLE scan")
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasAllPermissions()) {
                return
            }
            
            val device = result.device
            Log.i("BLE", "Found device: ${device.address}")
            
            // Stop scanning and connect
            stopBleScanning()
            connectToDevice(device)
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed with error: $errorCode")
            statusState.value = "Scan failed"
            
            // Retry scanning after delay
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                if (!isScanning) {
                    startBleScanning()
                }
            }
        }
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasAllPermissions()) {
            return
        }

        statusState.value = "Connecting..."
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        Log.i("BLE", "Connecting to device: ${device.address}")
        connectWatchdogJob?.cancel()
        connectWatchdogJob = CoroutineScope(Dispatchers.Main).launch {
            delay(8000)
            if (!notifyEnabledState.value) {
                statusState.value = "Service missing"
                lastErrorState.value = "Timeout waiting for services"
                disconnectGatt()
                startBleScanning()
                startClassicFallback()
                log("Watchdog timeout waiting for services; restarting scan/classic")
            }
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasAllPermissions()) {
                return
            }
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("BLE", "Connected to phone, discovering services...")
                    runOnUiThread {
                        statusState.value = "Connected - Syncing..."
                        watchConnectedState.value = true
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("BLE", "Disconnected from phone")
                    runOnUiThread {
                        statusState.value = "Disconnected"
                        watchConnectedState.value = false
                    }
                    dataCharacteristic = null
                    commandCharacteristic = null
                    notifyEnabledState.value = false
                    connectWatchdogJob?.cancel()
                    
                    // Retry connection after delay
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000)
                        startBleScanning()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!hasAllPermissions()) {
                return
            }
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    dataCharacteristic = service.getCharacteristic(CHARACTERISTIC_DATA_UUID)
                    commandCharacteristic = service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID)

                    Log.i("BLE", "Found service and characteristics")

                    // Enable notifications on data characteristic
                    dataCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)

                        // Write to descriptor to enable notifications
                        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }

                    runOnUiThread {
                        statusState.value = "Connected - Syncing..."
                        watchConnectedState.value = true
                    }

                    // Request initial state
                    requestState()
                } else {
                    Log.e("BLE", "Service not found")
                    runOnUiThread {
                        statusState.value = "Service missing"
                        lastErrorState.value = "GATT service not found"
                    }
                    disconnectGatt()
                    startBleScanning()
                }
            } else {
                Log.e("BLE", "Service discovery failed: $status")
                runOnUiThread {
                    statusState.value = "Discovery failed"
                    lastErrorState.value = "Discovery failed: $status"
                }
                disconnectGatt()
                startBleScanning()
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_DATA_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    try {
                        val json = String(data, Charsets.UTF_8)
                        val envelope = gson.fromJson(json, NotesEnvelope::class.java)
                        
                        envelope.projects?.let { projects ->
                            runOnUiThread {
                                projectsState.clear()
                                projectsState.addAll(projects)
                            }
                        }
                        
                        envelope.notes?.let { notes ->
                            runOnUiThread {
                                notesState.clear()
                                notesState.addAll(notes)
                            }
                        }
                        
                        Log.i("BLE", "Received update: ${notesState.size} notes, ${projectsState.size} projects")
                        runOnUiThread { markSynced() }
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to parse notification", e)
                    }
                }
            }
        }
        
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHARACTERISTIC_DATA_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    try {
                        val json = String(data, Charsets.UTF_8)
                        val envelope = gson.fromJson(json, NotesEnvelope::class.java)
                        
                        envelope.projects?.let { projects ->
                            runOnUiThread {
                                projectsState.clear()
                                projectsState.addAll(projects)
                            }
                        }
                        
                        envelope.notes?.let { notes ->
                            runOnUiThread {
                                notesState.clear()
                                notesState.addAll(notes)
                            }
                        }
                        
                        Log.i("BLE", "Read data: ${notesState.size} notes, ${projectsState.size} projects")
                        runOnUiThread { markSynced() }
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to parse read data", e)
                    }
                }
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Write successful")
                log("Characteristic write success")
            } else {
                Log.e("BLE", "Write failed: $status")
                log("Characteristic write failed: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Descriptor write successful - notifications enabled")
                notifyEnabledState.value = true
                connectWatchdogJob?.cancel()
                // Now request initial state
                requestState()
                log("Notifications enabled; requesting state")
            } else {
                Log.e("BLE", "Descriptor write failed: $status")
                notifyEnabledState.value = false
                lastErrorState.value = "Descriptor write failed: $status"
                disconnectGatt()
                startBleScanning()
                log("Descriptor write failed: $status")
            }
        }
    }
    
    private fun requestState() {
        if (!hasAllPermissions()) {
            statusState.value = "BLE permission required"
            lastErrorState.value = "Grant Bluetooth permissions"
            ActivityCompat.requestPermissions(this, requiredPermissions, 1)
            return
        }
        
        commandCharacteristic?.let { char ->
            val request = gson.toJson(mapOf("request_state" to true))
            char.value = request.toByteArray(Charsets.UTF_8)
            bluetoothGatt?.writeCharacteristic(char)
            Log.i("BLE", "Requested state from phone")
            statusState.value = "Connected - Syncing..."
            lastErrorState.value = ""
        }
    }

    private fun sendToPhone(text: String, projectName: String?) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val payload = NotePayload(
            title = if (text.length > 30) text.take(30) + "..." else text,
            content = text,
            projectName = projectName?.ifBlank { DEFAULT_PROJECT_NAME } ?: DEFAULT_PROJECT_NAME,
            projectColor = projectsState.firstOrNull { it.name == projectName }?.color ?: DEFAULT_PROJECT_COLOR
        )
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                commandCharacteristic?.let { char ->
                    val json = gson.toJson(mapOf("new_note" to payload))
                    char.value = json.toByteArray(Charsets.UTF_8)
                    bluetoothGatt?.writeCharacteristic(char)
                    
                    Log.i("BLE", "Sent note to phone")
                    runOnUiThread { statusState.value = "Connected - Syncing..." }
                    
                    // Request updated state after a brief delay
                    delay(500)
                    requestState()
                }
            } catch (e: Exception) {
                Log.e("BLE", "Failed to send note", e)
                runOnUiThread {
                    statusState.value = "Send failed"
                    lastErrorState.value = "Send failed: ${e.message}"
                }
            }
        }
    }
    
    private fun disconnectGatt() {
        if (!hasAllPermissions()) {
            return
        }
        
        stopBleScanning()
        scanFallbackJob?.cancel()
        connectWatchdogJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        watchConnectedState.value = false
        notifyEnabledState.value = false
        try { classicSocket?.close() } catch (_: Exception) {}
        classicSocket = null
        log("Disconnected GATT/cleanup")
    }

    private fun startRetryLoop() {
        retryJob?.cancel()
        retryJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(10000)
                if (!watchConnectedState.value || !notifyEnabledState.value) {
                    if (lastErrorState.value.isBlank()) {
                        lastErrorState.value = "Retrying connection..."
                    }
                    disconnectGatt()
                    startBleScanning()
                    startClassicFallback()
                    log("Retry loop: restarting scan/classic")
                }
            }
        }
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun markSynced() {
        statusState.value = "Connected - Synced @ ${nowTime()}"
    }
}

@Composable
fun OledTheme(content: @Composable () -> Unit) {
    val colors = Colors(
        primary = Color.White,
        primaryVariant = Color.White,
        secondary = Color.White,
        secondaryVariant = Color.White,
        background = Color.Black,
        surface = Color.Black,
        error = Color(0xFFFF5252),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
        onError = Color.Black
    )
    MaterialTheme(colors = colors, content = content)
}

@Composable
fun WatchUI(
    notes: List<NotePayload>,
    projects: List<ProjectPayload>,
    status: String,
    scanning: Boolean,
    watchConnected: Boolean,
    notifyEnabled: Boolean,
    bluetoothOn: Boolean,
    recentLogs: List<String>,
    lastError: String,
    onSend: (String, String?) -> Unit,
    onRefresh: () -> Unit
) {
    val listState: ScalingLazyListState = rememberScalingLazyListState()
    var quickNote by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf(projects.firstOrNull()?.name ?: DEFAULT_PROJECT_NAME) }
    var projectColor by remember { mutableStateOf(projects.firstOrNull()?.color ?: DEFAULT_PROJECT_COLOR) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val projectCounts = notes.groupingBy { it.projectName ?: DEFAULT_PROJECT_NAME }.eachCount()
    val filteredNotes = notes.filter { selectedFilter == null || (it.projectName ?: DEFAULT_PROJECT_NAME) == selectedFilter }

    var showDebug by remember { mutableStateOf(false) }

    androidx.wear.compose.material.Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            anchorType = ScalingLazyListAnchorType.ItemStart
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "Live Notes",
                        style = MaterialTheme.typography.title3,
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Capture & sync markdown",
                        style = MaterialTheme.typography.caption2,
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item { 
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    StatusChipCompact(
                        status = status,
                        scanning = scanning,
                        notifyEnabled = notifyEnabled,
                        bluetoothOn = bluetoothOn,
                        onRequestPermissions = onRefresh,
                        onRequestBluetooth = onRefresh,
                        onToggleDebug = { showDebug = !showDebug }
                    )
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF222222), contentColor = Color.White),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(14.dp))
                    }
                }
            }
            item {
                if (showDebug) {
                    DebugCard(
                        status = status,
                        scanning = scanning,
                        watchConnected = watchConnected,
                        notifyEnabled = notifyEnabled,
                        lastError = lastError,
                        recent = recentLogs
                    )
                }
            }
            item {
                QuickComposeCard(
                    text = quickNote,
                    projectName = selectedProject,
                    projectColor = projectColor,
                    onProjectTapped = {
                        val next = projects.firstOrNull { it.name != selectedProject }
                            ?: ProjectPayload(name = DEFAULT_PROJECT_NAME, color = DEFAULT_PROJECT_COLOR)
                        selectedProject = next.name
                        projectColor = next.color
                    },
                    onTextChange = { quickNote = it },
                    onSend = {
                        if (quickNote.isNotBlank()) {
                            onSend(quickNote, selectedProject)
                            quickNote = ""
                        }
                    }
                )
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Projects", color = Color.Gray, style = MaterialTheme.typography.caption2)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val allCount = notes.size
                        CompactChip(
                            onClick = { selectedFilter = null },
                            label = { Text("All ($allCount)", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(
                                backgroundColor = if (selectedFilter == null) Color(0xFF1E1E1E) else Color(0xFF121212),
                                contentColor = Color.White
                            )
                        )
                        projects.forEach { project ->
                            val count = projectCounts[project.name] ?: 0
                            CompactChip(
                                onClick = { selectedFilter = project.name },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color.fromHex(project.color), CircleShape)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("${project.name} ($count)", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(
                                    backgroundColor = if (selectedFilter == project.name) Color(0xFF1E1E1E) else Color(0xFF121212),
                                    contentColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (projects.isNotEmpty()) {
                        Text("Tags", color = Color.Gray, style = MaterialTheme.typography.caption2)
                        Spacer(Modifier.height(4.dp))
                        projects.forEach { project ->
                            val count = projectCounts[project.name] ?: 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F0F0F), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color.fromHex(project.color), CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(project.name, color = Color.White, style = MaterialTheme.typography.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("$count", color = Color.White, style = MaterialTheme.typography.caption2)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            items(filteredNotes.size) { index ->
                NoteCardWatch(note = filteredNotes[index])
            }
        }
    }
}

@Composable
fun StatusChipCompact(status: String) {
    val connected = status.contains("Connected", true)
    val color = if (connected) Color(0xFF4CAF50) else Color(0xFFFFC107)
    val detail = when {
        status.contains("Service missing", true) -> "Phone service/advertising not found. Open phone app and ensure sync is running."
        status.contains("Syncing", true) -> "Sync in progress with phone."
        status.contains("Synced", true) -> "Phone and watch are synced."
        status.contains("Disconnected", true) -> "No phone connection. Ensure Bluetooth and permissions are on."
        status.contains("Scanning", true) -> "Looking for phone advertiser."
        else -> status
    }
    
    CompactChip(
        onClick = {},
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("$status - tap for info", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption2)
            }
        },
        colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(
            backgroundColor = Color(0xFF111111),
            contentColor = Color.White
        ),
        border = androidx.wear.compose.material.ChipDefaults.chipBorder(BorderStroke(1.dp, color.copy(alpha = 0.5f))),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(4.dp))
    Text(detail, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.caption3, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun StatusChipCompact(
    status: String,
    scanning: Boolean,
    notifyEnabled: Boolean,
    bluetoothOn: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onToggleDebug: () -> Unit
) {
    val connected = status.contains("Connected", true)
    val color = if (connected) Color(0xFF4CAF50) else Color(0xFFFFC107)
    val detail = when {
        status.contains("Service missing", true) -> "Phone service/advertising not found. Open phone app and ensure sync is running."
        status.contains("Syncing", true) -> "Sync in progress with phone."
        status.contains("Synced", true) -> "Phone and watch are synced."
        status.contains("Disconnected", true) -> "No phone connection. Ensure Bluetooth and permissions are on."
        status.contains("Scanning", true) -> "Looking for phone advertiser."
        else -> status
    }
    
    CompactChip(
        onClick = {
            if (!notifyEnabled && !bluetoothOn) {
                onRequestBluetooth()
                return@CompactChip
            }
            if (!notifyEnabled) {
                onRequestPermissions()
                return@CompactChip
            }
            onToggleDebug()
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(4.dp))
                val notifyText = if (notifyEnabled) "notif on" else "notif off"
                val scanText = if (scanning) "scan" else "idle"
                Text("$status • $scanText/$notifyText", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption2)
            }
        },
        colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(
            backgroundColor = Color(0xFF111111),
            contentColor = Color.White
        ),
        border = androidx.wear.compose.material.ChipDefaults.chipBorder(BorderStroke(1.dp, color.copy(alpha = 0.5f))),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(4.dp))
    Text(detail, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.caption3, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun DebugCard(
    status: String,
    scanning: Boolean,
    watchConnected: Boolean,
    notifyEnabled: Boolean,
    lastError: String,
    recent: List<String>
) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color(0xFF0F0F0F)),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Debug", style = MaterialTheme.typography.caption2, color = Color(0xFFB0BEC5))
            Spacer(Modifier.height(4.dp))
            DebugRow("Status", status)
            DebugRow("Scanning", if (scanning) "Yes" else "No")
            DebugRow("Watch link", if (watchConnected) "Connected" else "Not connected")
            DebugRow("Notifications", if (notifyEnabled) "Enabled" else "Not enabled")
            if (lastError.isNotBlank()) {
                DebugRow("Last error", lastError)
            }
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Recent", style = MaterialTheme.typography.caption2, color = Color(0xFFB0BEC5))
                recent.take(5).forEach {
                    Text(it, style = MaterialTheme.typography.caption2, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.caption2, color = Color(0xFFB0BEC5))
        Text(value, style = MaterialTheme.typography.caption2, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun QuickComposeCard(
    text: String,
    projectName: String,
    projectColor: String,
    onProjectTapped: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val accent = Color.fromHex(projectColor)
    Card(
        onClick = onSend,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color.Black),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Create note",
                style = MaterialTheme.typography.caption2,
                color = Color(0xFFB0BEC5)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Project", style = MaterialTheme.typography.caption2, color = Color(0xFFB0BEC5))
                    Spacer(Modifier.height(4.dp))
                    CompactChip(
                        onClick = onProjectTapped,
                        label = { Text(projectName, style = MaterialTheme.typography.caption2, color = Color.Black) },
                        colors = androidx.wear.compose.material.ChipDefaults.chipColors(backgroundColor = accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = onSend,
                    enabled = true,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = accent,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(40.dp).width(64.dp)
                ) {
                    Text("Open", style = MaterialTheme.typography.caption2)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tap Open to edit the note", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.caption2)
        }
    }
}

@Composable
fun NoteCardWatch(note: NotePayload) {
    val accent = Color.fromHex(note.projectColor)
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color(0xFF0D0D0D)),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(accent, CircleShape)
                    )
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        color = Color.White,
                        style = MaterialTheme.typography.title3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = note.projectName ?: DEFAULT_PROJECT_NAME,
                    style = MaterialTheme.typography.caption2,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            MarkdownTextWatch(
                text = note.content,
                accent = accent,
                bodyColor = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun MarkdownTextWatch(text: String, accent: Color, bodyColor: Color) {
    val content = remember(text, accent, bodyColor) { markdownToAnnotatedString(text, accent, bodyColor) }
    Text(content, color = bodyColor, style = MaterialTheme.typography.caption2, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun markdownToAnnotatedString(raw: String, accent: Color, bodyColor: Color): AnnotatedString {
    if (raw.isBlank()) return AnnotatedString("No content yet.")
    val lines = raw.trim().lines()
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("### ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) {
                        append(line.removePrefix("### ").trim())
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)) {
                        append(line.removePrefix("## ").trim())
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)) {
                        append(line.removePrefix("# ").trim())
                    }
                }
                line.startsWith("- ") -> {
                    append("- ")
                    appendInlineMarkdown(line.removePrefix("- "), accent, bodyColor, this)
                }
                else -> appendInlineMarkdown(line, accent, bodyColor, this)
            }
            if (index < lines.lastIndex) append("\n")
        }
    }
}

private fun appendInlineMarkdown(
    text: String,
    accent: Color,
    bodyColor: Color,
    builder: AnnotatedString.Builder
) {
    val regex = Regex("(\\*\\*[^*]+\\*\\*|_[^_]+_|`[^`]+`)")
    var index = 0
    val matches = regex.findAll(text)
    matches.forEach { match ->
        if (match.range.first > index) {
            builder.append(text.substring(index, match.range.first))
        }
        val raw = match.value
        val inner = when {
            raw.startsWith("**") -> raw.removePrefix("**").removeSuffix("**")
            raw.startsWith("_") -> raw.removePrefix("_").removeSuffix("_")
            else -> raw.removePrefix("`").removeSuffix("`")
        }
        val style = when {
            raw.startsWith("**") -> SpanStyle(fontWeight = FontWeight.Bold, color = bodyColor)
            raw.startsWith("_") -> SpanStyle(fontStyle = FontStyle.Italic, color = bodyColor)
            else -> SpanStyle(fontFamily = FontFamily.Monospace, color = accent)
        }
        builder.pushStyle(style)
        builder.append(inner)
        builder.pop()
        index = match.range.last + 1
    }
    if (index < text.length) builder.append(text.substring(index))
}

private fun Color.Companion.fromHex(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF80CBC4)
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF80CBC4)
    }
}
