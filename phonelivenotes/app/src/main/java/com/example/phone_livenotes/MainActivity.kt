package com.example.phone_livenotes

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.absoluteValue

// --- BLE Configuration ---
val SERVICE_UUID: UUID = UUID.fromString("a9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
val CHARACTERISTIC_DATA_UUID: UUID = UUID.fromString("b9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
val CHARACTERISTIC_COMMAND_UUID: UUID = UUID.fromString("c9e86dbd-0cd6-4a4a-a904-3622fa6b49f4")
val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// --- Configuration ---
const val DEFAULT_SERVER_IP = "192.168.10.161"
const val DEFAULT_PORT = 8000

private val PROJECT_PALETTE = listOf(
    "#90CAF9", "#FFB74D", "#A5D6A7", "#F48FB1", "#CE93D8", "#FFE082", "#80CBC4", "#B0BEC5"
)

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
    private val connectionStatus = mutableStateOf("Disconnected")
    private val gson = Gson()

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    
    // BLE Components
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    
    // Characteristics for BLE communication
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var prefs: android.content.SharedPreferences
    private val cacheFile: File by lazy { File(filesDir, "cached_state.json") }
    
    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("livenotes_prefs", Context.MODE_PRIVATE)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        loadCachedState()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            if (granted.values.all { it }) {
                startServices()
            } else {
                connectionStatus.value = "Permissions required"
            }
        }
        if (hasAllPermissions()) {
            startServices()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }

        setContent {
            // Define a strict OLED dark theme
            val oledScheme = darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF121212),
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color(0xFFE0E0E0),
                primary = Color(0xFF90CAF9),
                secondary = Color(0xFF80CBC4),
                outline = Color(0xFF424242)
            )
            
            MaterialTheme(colorScheme = oledScheme) {
                // Force the entire app to have a black background
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    PhoneUI(
                        notes = notesState,
                        projects = projectsState,
                        status = connectionStatus.value,
                        onSend = ::sendNote,
                        onSaveSettings = { saveSettings(it.first, it.second) },
                        currentIp = getCurrentIp(),
                        currentPort = getCurrentPort()
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasAllPermissions()) startServices()
    }

    override fun onStop() {
        super.onStop()
        stopServices()
    }

    override fun onDestroy() {
        stopServices()
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startServices() {
        connectWebSocket()
        startBleGattServer()
    }

    private fun stopServices() {
        teardownWebSocket()
        stopBleGattServer()
    }

    private fun getCurrentIp(): String = prefs.getString("server_ip", DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    private fun getCurrentPort(): Int = prefs.getInt("server_port", DEFAULT_PORT)

    private fun saveSettings(ip: String, port: Int) {
        with(prefs.edit()) {
            putString("server_ip", ip)
            putInt("server_port", port)
            apply()
        }
        teardownWebSocket()
        connectWebSocket()
    }

    private fun connectWebSocket() {
        if (webSocket != null) return
        val url = "ws://${getCurrentIp()}:${getCurrentPort()}/ws"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { connectionStatus.value = "Connected to server" }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val envelope = gson.fromJson(text, NotesEnvelope::class.java)
                    envelope.projects?.let { projects ->
                        runOnUiThread {
                            projectsState.clear()
                            projectsState.addAll(projects.sortedBy { it.name.lowercase(Locale.getDefault()) })
                        }
                        persistCache(notesState, projectsState)
                    }
                    envelope.notes?.let { notes ->
                        runOnUiThread {
                            notesState.clear()
                            notesState.addAll(notes)
                            sendToWatchViaBLE(notesState, projectsState)
                        }
                        persistCache(notes, projectsState)
                    }
                } catch (e: Exception) {
                    Log.e("WS", "Parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { connectionStatus.value = "WS Error: ${t.message}" }
            }
        })
    }

    private fun sendNote(note: NotePayload) {
        if (note.title.isBlank() || note.content.isBlank()) return
        val cleanedProject = note.projectName?.ifBlank { "General" } ?: "General"
        val projectColor = note.projectColor ?: projectColorFor(cleanedProject)
        val payload = note.copy(
            title = note.title.trim(),
            content = note.content.trim(),
            projectName = cleanedProject,
            projectColor = projectColor
        )
        webSocket?.send(gson.toJson(mapOf("new_note" to payload)))
        // Optimistically update local state and mirror to watch
        notesState.add(0, payload)
        sendToWatchViaBLE(notesState, projectsState)
        persistCache(notesState, projectsState)
        saveLocally(payload)
    }

    private fun saveLocally(note: NotePayload) {
        val file = File(filesDir, "QuickNotes.md")
        val header = "\n## ${note.title}\nProject: ${note.projectName ?: "General"}\n\n"
        file.appendText(header + note.content.trim() + "\n")
    }

    // ========== BLE GATT Server Implementation ==========
    
    private fun startBleGattServer() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            connectionStatus.value = "BLE permission missing"
            return
        }
        
        try {
            // Create GATT service
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            
            // Data characteristic (for sending data to watch) - READ + NOTIFY
            dataCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            // Add Client Characteristic Configuration Descriptor for notifications
            val configDescriptor = BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            dataCharacteristic?.addDescriptor(configDescriptor)
            
            // Command characteristic (for receiving data from watch) - WRITE
            commandCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_COMMAND_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            service.addCharacteristic(dataCharacteristic)
            service.addCharacteristic(commandCharacteristic)
            
            // Open GATT server
            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
            gattServer?.addService(service)
            
            // Start advertising
            startBleAdvertising()
            
            Log.i("BLE", "GATT Server started successfully")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to start GATT server", e)
            connectionStatus.value = "BLE Error: ${e.message}"
        }
    }
    
    private fun startBleAdvertising() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.i("BLE", "Started advertising")
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BLE", "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising failed with code: $errorCode")
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    Log.i("BLE", "Watch connected: ${device.address}")
                    runOnUiThread {
                        connectionStatus.value = "Watch connected (BLE)"
                    }
                    // Send current state to newly connected watch
                    sendToWatchViaBLE(notesState, projectsState)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.i("BLE", "Watch disconnected: ${device.address}")
                    runOnUiThread {
                        if (connectedDevices.isEmpty()) {
                            connectionStatus.value = "Watch disconnected"
                        }
                    }
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (characteristic.uuid) {
                CHARACTERISTIC_DATA_UUID -> {
                    // Send current state
                    val envelope = NotesEnvelope(notes = notesState.toList(), projects = projectsState.toList())
                    val data = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
                    
                    // Handle offset for large data
                    val response = if (offset >= data.size) {
                        byteArrayOf()
                    } else {
                        data.copyOfRange(offset, data.size)
                    }
                    
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (characteristic.uuid) {
                CHARACTERISTIC_COMMAND_UUID -> {
                    try {
                        val message = String(value, Charsets.UTF_8)
                        val raw = gson.fromJson(message, Map::class.java)
                        
                        when {
                            raw["request_state"] == true -> {
                                sendToWatchViaBLE(notesState, projectsState)
                            }
                            raw["new_note"] != null -> {
                                val note = gson.fromJson(gson.toJson(raw["new_note"]), NotePayload::class.java)
                                runOnUiThread { sendNote(note) }
                            }
                        }
                        
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to process command", e)
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG) {
                // Watch is subscribing to notifications
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                Log.i("BLE", "Watch subscribed to notifications")
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }
    
    private fun sendToWatchViaBLE(notes: List<NotePayload>, projects: List<ProjectPayload>) {
        if (connectedDevices.isEmpty() || dataCharacteristic == null) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        try {
            val envelope = NotesEnvelope(notes = notes.toList(), projects = projects.toList())
            val json = gson.toJson(envelope)
            val data = json.toByteArray(Charsets.UTF_8)
            
            // BLE has a limit of ~512 bytes per characteristic
            // For larger data, we'd need to chunk it or use a different approach
            // For now, we'll send what fits and log if it's too large
            if (data.size > 512) {
                Log.w("BLE", "Data too large for single BLE packet: ${data.size} bytes. Consider chunking.")
                // Send first 512 bytes for now
                dataCharacteristic?.value = data.copyOf(512)
            } else {
                dataCharacteristic?.value = data
            }
            
            // Notify all connected devices
            connectedDevices.forEach { device ->
                gattServer?.notifyCharacteristicChanged(device, dataCharacteristic, false)
            }
            
            Log.i("BLE", "Sent data to ${connectedDevices.size} watch(es)")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to send data to watch", e)
        }
    }
    
    private fun stopBleGattServer() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        Log.i("BLE", "GATT Server stopped")
    }

    private fun projectColorFor(projectName: String): String {
        projectsState.firstOrNull { it.name == projectName }?.let { return it.color }
        val palette = PROJECT_PALETTE
        val index = projectName.hashCode().absoluteValue % palette.size
        return palette[index]
    }

    private fun teardownWebSocket() {
        try {
            webSocket?.close(1000, "Lifecycle stop")
        } catch (_: Exception) {
        } finally {
            webSocket = null
        }
    }

    private fun persistCache(notes: List<NotePayload>, projects: List<ProjectPayload>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val envelope = NotesEnvelope(notes = notes.toList(), projects = projects.toList())
                cacheFile.writeText(gson.toJson(envelope))
            } catch (e: Exception) {
                Log.e("Cache", "Failed to write cache", e)
            }
        }
    }

    private fun loadCachedState() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!cacheFile.exists()) return@launch
                val cached = gson.fromJson(cacheFile.readText(), NotesEnvelope::class.java)
                cached.projects?.let { projects ->
                    withContext(Dispatchers.Main) {
                        projectsState.clear()
                        projectsState.addAll(projects.sortedBy { it.name.lowercase(Locale.getDefault()) })
                    }
                }
                cached.notes?.let { notes ->
                    withContext(Dispatchers.Main) {
                        notesState.clear()
                        notesState.addAll(notes)
                    }
                }
            } catch (e: Exception) {
                Log.e("Cache", "Failed to load cache", e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneUI(
    notes: List<NotePayload>,
    projects: List<ProjectPayload>,
    status: String,
    onSend: (NotePayload) -> Unit,
    onSaveSettings: (Pair<String, Int>) -> Unit,
    currentIp: String,
    currentPort: Int
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf(projects.firstOrNull()?.name ?: "General") }
    var projectColor by remember { mutableStateOf(projects.firstOrNull()?.color ?: PROJECT_PALETTE.first()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(true) }
    var selectedProjectFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedNote by remember { mutableStateOf<NotePayload?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NotePayload?>(null) }

    val projectsByName = remember(projects) { projects.associateBy { it.name } }
    val projectCounts = remember(notes) {
        notes.groupingBy { it.projectName ?: "General" }.eachCount()
    }

    LaunchedEffect(projects.size) {
        if (projects.isNotEmpty()) {
            projectName = projects.first().name
            projectColor = projects.first().color
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentIp = currentIp,
            currentPort = currentPort,
            onDismiss = { showSettingsDialog = false },
            onSave = {
                onSaveSettings(it)
                showSettingsDialog = false
            }
        )
    }

    val filteredNotes = remember(notes, selectedProjectFilter, searchQuery) {
        notes.filter { note ->
            val matchesProject = selectedProjectFilter == null || note.projectName == selectedProjectFilter
            val query = searchQuery.trim().lowercase(Locale.getDefault())
            val matchesQuery = query.isBlank() || note.title.lowercase(Locale.getDefault()).contains(query) ||
                note.content.lowercase(Locale.getDefault()).contains(query)
            matchesProject && matchesQuery
        }
    }

    Scaffold(
        containerColor = Color.Black, // Force scaffold black
        topBar = {
            TopAppBar(
                title = { Text("Live Notes (BLE)", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingNote = null
                    title = ""
                    body = ""
                    showPreview = true
                    projectName = projects.firstOrNull()?.name ?: "General"
                    projectColor = projects.firstOrNull()?.color ?: PROJECT_PALETTE.first()
                    showComposer = true
                },
                containerColor = Color(0xFF90CAF9),
                contentColor = Color.Black
            ) { Icon(Icons.Default.Add, contentDescription = "New note") }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
                .padding(16.dp)
        ) {
            val isWide = maxWidth > 720.dp
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        StatusRow(status = status)
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF333333))
                        ProjectFilterRow(
                            projects = projects,
                            selected = selectedProjectFilter,
                            totalCount = notes.size,
                            counts = projectCounts,
                            onSelect = { selectedProjectFilter = it }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                            placeholder = { Text("Search title or markdown...", color = Color.Gray) },
                            singleLine = true,
                            colors = darkTextFieldColors()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NotesList(notes = filteredNotes, onOpen = { selectedNote = it })
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    StatusRow(status = status)
                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF333333))

                    ProjectFilterRow(
                        projects = projects,
                        selected = selectedProjectFilter,
                        totalCount = notes.size,
                        counts = projectCounts,
                        onSelect = { selectedProjectFilter = it }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                        placeholder = { Text("Search title or markdown...", color = Color.Gray) },
                        singleLine = true,
                        colors = darkTextFieldColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    NotesList(notes = filteredNotes, onOpen = { selectedNote = it })
                }
            }
        }
    }

    selectedNote?.let { note ->
        NoteDetailDialog(
            note = note,
            onDismiss = { selectedNote = null },
            onEdit = {
                editingNote = note
                title = note.title
                body = note.content
                projectName = note.projectName ?: "General"
                projectColor = note.projectColor ?: PROJECT_PALETTE.first()
                showPreview = true
                selectedNote = null
                showComposer = true
            }
        )
    }

    if (showComposer) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showComposer = false
                editingNote = null
            },
            sheetState = sheetState,
            containerColor = Color(0xFF121212) // Dark background for bottom sheet
        ) {
            NoteComposer(
                title = title,
                body = body,
                projectName = projectName,
                projectColor = projectColor,
                availableProjects = availableProjects,
                showPreview = showPreview,
                onTitleChange = { title = it },
                onBodyChange = { body = it },
                onProjectChange = { name ->
                    projectName = name
                    projects.firstOrNull { it.name == name }?.let { projectColor = it.color }
                },
                onColorChange = { projectColor = it },
                onTogglePreview = { showPreview = !showPreview },
                onSubmit = {
                    onSend(
                        NotePayload(
                            title = title,
                            content = body,
                            projectName = projectName,
                            projectColor = projectColor
                        )
                    )
                    showComposer = false
                    editingNote = null
                    title = ""
                    body = ""
                    showPreview = true
                }
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun StatusRow(status: String) {
    val connected = status.contains("Connected", ignoreCase = true)
    val chipColor = if (connected) Color(0xFF1B5E20) else Color(0xFFB71C1C)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Status: $status", style = MaterialTheme.typography.bodySmall, color = Color.White)
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(chipColor, CircleShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilterRow(
    projects: List<ProjectPayload>,
    selected: String?,
    totalCount: Int,
    counts: Map<String, Int>,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All ($totalCount)", color = Color.White) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color(0xFF1A1A1A),
                labelColor = Color.White,
                selectedContainerColor = Color(0xFF333333),
                selectedLabelColor = Color.White
            ),
            border = FilterChipDefaults.filterChipBorder(
                borderColor = Color(0xFF333333)
            )
        )
        projects.forEach { project ->
            FilterChip(
                selected = selected == project.name,
                onClick = { onSelect(project.name) },
                label = {
                    val count = counts[project.name] ?: 0
                    Text("${project.name} ($count)", color = Color.White)
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color.fromHex(project.color), CircleShape)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF1A1A1A),
                    labelColor = Color.White,
                    selectedContainerColor = Color.fromHex(project.color).copy(alpha = 0.25f),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = if (selected == project.name) Color.fromHex(project.color) else Color(0xFF333333)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteComposer(
    title: String,
    body: String,
    projectName: String,
    projectColor: String,
    availableProjects: List<ProjectPayload>,
    showPreview: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onProjectChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        border = BorderStroke(1.25.dp, Color.fromHex(projectColor)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onTogglePreview) {
                    Text(if (showPreview) "Hide preview" else "Show preview", color = Color(0xFF90CAF9))
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                colors = darkTextFieldColors()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("Markdown body") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
                colors = darkTextFieldColors()
            )
            Spacer(modifier = Modifier.height(8.dp))

            QuickMarkdownRow(
                accent = Color.fromHex(projectColor),
                onInsert = { snippet -> onBodyChange(body + snippet) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            ProjectSelector(
                projectName = projectName,
                projectColor = projectColor,
                availableProjects = availableProjects,
                onProjectChange = onProjectChange,
                onColorChange = onColorChange
            )

            Spacer(modifier = Modifier.height(8.dp))
            if (showPreview) {
                MarkdownPreview(body = body, tint = Color.fromHex(projectColor))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSubmit,
                enabled = title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9), contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Save")
                Spacer(Modifier.width(6.dp))
                Text("Save & Sync")
            }
        }
    }
}

@Composable
fun QuickMarkdownRow(accent: Color, onInsert: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            Pair("Bold", " **bold** "),
            Pair("Italic", " _italic_ "),
            Pair("Bullet", "\n- item"),
            Pair("Code", " `code` "),
            Pair("Divider", "\n---\n")
        ).forEach { (label, snippet) ->
            TextButton(
                onClick = { onInsert(snippet) },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            ) {
                Text(label, color = accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSelector(
    projectName: String,
    projectColor: String,
    availableProjects: List<ProjectPayload>,
    onProjectChange: (String) -> Unit,
    onColorChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ColorLens, contentDescription = "Project color", tint = Color.fromHex(projectColor))
            Spacer(Modifier.width(4.dp))
            Text("Project & color", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = projectName,
                onValueChange = onProjectChange,
                label = { Text("Project name") },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = darkTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expanded, 
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1A1A1A))
            ) {
                availableProjects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name, color = Color.White) },
                        onClick = {
                            onProjectChange(project.name)
                            onColorChange(project.color)
                            expanded = false
                        },
                        colors = androidx.compose.material3.MenuDefaults.itemColors(
                            textColor = Color.White,
                            leadingIconColor = Color.White,
                            trailingIconColor = Color.White
                        )
                    )
                }
            }
        }
        ColorStripRow(selected = projectColor, onSelect = onColorChange)
    }
}

@Composable
fun ColorStripRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PROJECT_PALETTE.forEach { colorHex ->
            val color = Color.fromHex(colorHex)
            Card(
                modifier = Modifier
                    .size(32.dp)
                    .border(
                        width = if (selected == colorHex) 3.dp else 1.dp,
                        color = if (selected == colorHex) color else color.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(colorHex) },
                colors = CardDefaults.cardColors(containerColor = color),
                shape = CircleShape
            ) {}
        }
    }
}

@Composable
fun MarkdownPreview(body: String, tint: Color) {
    val text = if (body.isBlank()) "Start writing markdown to see a live preview." else body
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.05f)),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = tint)
            Spacer(Modifier.height(6.dp))
            MarkdownText(text = text, accent = tint, bodyColor = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
fun NotesList(notes: List<NotePayload>, onOpen: (NotePayload) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(notes) { note ->
            NoteCard(note = note, onOpen = onOpen)
        }
    }
}

@Composable
fun NoteCard(note: NotePayload, onOpen: ((NotePayload) -> Unit)?) {
    val accent = Color.fromHex(note.projectColor)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onOpen != null) { onOpen?.invoke(note) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
            Text(
                text = note.title.ifBlank { "(Untitled)" },
                style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        MarkdownText(
            text = note.content.take(200) + if (note.content.length > 200) "..." else "",
            accent = accent,
            bodyColor = Color.White
        )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = note.projectName ?: "General",
                    style = MaterialTheme.typography.labelMedium.copy(color = accent)
                )
                Text(
                    text = formatTimestamp(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFB0BEC5))
                )
            }
        }
    }
}

@Composable
fun NoteDetailDialog(note: NotePayload, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = note.title.ifBlank { "Untitled" }, color = Color.White)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MarkdownText(
                    text = note.content,
                    accent = Color.fromHex(note.projectColor),
                    bodyColor = Color.White
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (onEdit != null) {
                    TextButton(onClick = {
                        onEdit()
                    }) {
                        Text("Edit", color = Color(0xFF90CAF9))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White)
                }
            }
        },
        containerColor = Color(0xFF0D0D0D),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
fun MarkdownText(text: String, accent: Color, bodyColor: Color) {
    val content = remember(text, accent, bodyColor) { markdownToAnnotatedString(text, accent, bodyColor) }
    Text(content)
}

private fun markdownToAnnotatedString(raw: String, accent: Color, bodyColor: Color): AnnotatedString {
    if (raw.isBlank()) return AnnotatedString("No content yet.")
    val lines = raw.trim().lines()
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("### ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)) {
                        append(line.removePrefix("### ").trim())
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                        append(line.removePrefix("## ").trim())
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                        append(line.removePrefix("# ").trim())
                    }
                }
                line.startsWith("- ") -> {
                    append("- ")
                    appendInlineMarkdown(line.removePrefix("- "), accent, bodyColor, this)
                }
                else -> {
                    appendInlineMarkdown(line, accent, bodyColor, this)
                }
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
            else -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = accent.copy(alpha = 0.12f),
                color = accent
            )
        }
        builder.pushStyle(style)
        builder.append(inner)
        builder.pop()
        index = match.range.last + 1
    }
    if (index < text.length) {
        builder.append(text.substring(index))
    }
}

private fun formatTimestamp(ms: Long?): String {
    ms ?: return "Just now"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkTextFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = Color(0xFF121212),
        unfocusedContainerColor = Color(0xFF121212),
        focusedBorderColor = Color(0xFF90CAF9),
        unfocusedBorderColor = Color(0xFF424242),
        cursorColor = Color.White,
        focusedLabelColor = Color(0xFF90CAF9),
        unfocusedLabelColor = Color(0xFFB0BEC5),
        focusedPlaceholderColor = Color(0xFF9CA3AF),
        unfocusedPlaceholderColor = Color(0xFF6B7280)
    )

@Composable
fun SettingsDialog(
    currentIp: String,
    currentPort: Int,
    onDismiss: () -> Unit,
    onSave: (Pair<String, Int>) -> Unit
) {
    var ip by remember { mutableStateOf(currentIp) }
    var port by remember { mutableStateOf(currentPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Server IP Address") },
                    colors = darkTextFieldColors()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    colors = darkTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portInt = port.toIntOrNull() ?: DEFAULT_PORT
                    onSave(Pair(ip, portInt))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9), contentColor = Color.Black)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White)
            ) {
                Text("Cancel")
            }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

private fun Color.Companion.fromHex(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF90CAF9)
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF90CAF9)
    }
}
