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
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ),
            1
        )

        startBleScanning()

        setContent {
            OledTheme {
                WatchUI(
                    notes = notesState,
                    projects = projectsState,
                    status = statusState.value,
                    onSend = ::sendToPhone,
                    onRefresh = ::requestState
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnectGatt()
    }
    
    private fun startBleScanning() {
        if (isScanning) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            statusState.value = "BLE permission required"
            return
        }
        
        // Scan specifically for our service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        isScanning = true
        statusState.value = "Scanning..."
        Log.i("BLE", "Started BLE scan")
    }
    
    private fun stopBleScanning() {
        if (!isScanning) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.i("BLE", "Stopped BLE scan")
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        statusState.value = "Connecting..."
        bluetoothGatt = device.connectGatt(this, true, gattCallback)
        Log.i("BLE", "Connecting to device: ${device.address}")
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("BLE", "Connected to phone, discovering services...")
                    runOnUiThread {
                        statusState.value = "Connected"
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("BLE", "Disconnected from phone")
                    runOnUiThread {
                        statusState.value = "Disconnected"
                    }
                    dataCharacteristic = null
                    commandCharacteristic = null
                    
                    // Retry connection after delay
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000)
                        startBleScanning()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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
                        statusState.value = "Connected"
                    }
                    
                    // Request initial state
                    requestState()
                } else {
                    Log.e("BLE", "Service not found")
                    runOnUiThread {
                        statusState.value = "Service missing"
                    }
                }
            } else {
                Log.e("BLE", "Service discovery failed: $status")
                runOnUiThread {
                    statusState.value = "Discovery failed"
                }
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
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to parse read data", e)
                    }
                }
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Write successful")
            } else {
                Log.e("BLE", "Write failed: $status")
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Descriptor write successful - notifications enabled")
                // Now request initial state
                requestState()
            } else {
                Log.e("BLE", "Descriptor write failed: $status")
            }
        }
    }
    
    private fun requestState() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        commandCharacteristic?.let { char ->
            val request = gson.toJson(mapOf("request_state" to true))
            char.value = request.toByteArray(Charsets.UTF_8)
            bluetoothGatt?.writeCharacteristic(char)
            Log.i("BLE", "Requested state from phone")
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
                    
                    // Request updated state after a brief delay
                    delay(500)
                    requestState()
                }
            } catch (e: Exception) {
                Log.e("BLE", "Failed to send note", e)
                runOnUiThread {
                    statusState.value = "Send failed"
                }
            }
        }
    }
    
    private fun disconnectGatt() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        stopBleScanning()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
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
    onSend: (String, String?) -> Unit,
    onRefresh: () -> Unit
) {
    val listState: ScalingLazyListState = rememberScalingLazyListState()
    var quickNote by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf(projects.firstOrNull()?.name ?: DEFAULT_PROJECT_NAME) }
    var projectColor by remember { mutableStateOf(projects.firstOrNull()?.color ?: DEFAULT_PROJECT_COLOR) }

    androidx.wear.compose.material.Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            anchorType = ScalingLazyListAnchorType.ItemStart
        ) {
            item { 
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    StatusChipCompact(status = status)
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.iconButtonColors(contentColor = Color.White, backgroundColor = Color(0xFF222222)),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(14.dp))
                    }
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
            items(notes.size) { index ->
                NoteCardWatch(note = notes[index])
            }
        }
    }
}

@Composable
fun StatusChipCompact(status: String) {
    val connected = status.contains("Connected", true)
    val color = if (connected) Color(0xFF4CAF50) else Color(0xFFFFC107)
    
    CompactChip(
        onClick = {},
        label = { 
             Row(verticalAlignment = Alignment.CenterVertically) {
                 Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
                 Spacer(Modifier.width(4.dp))
                 Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption2)
             }
        },
        colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(
            backgroundColor = Color(0xFF111111),
            contentColor = Color.White
        ),
        border = androidx.wear.compose.material.ChipDefaults.chipBorder(BorderStroke(1.dp, color.copy(alpha = 0.5f)))
    )
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
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color.Black),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                CompactChip(
                    onClick = onProjectTapped,
                    label = { Text(projectName, style = MaterialTheme.typography.caption2, color = Color.Black) },
                    colors = androidx.wear.compose.material.ChipDefaults.chipColors(backgroundColor = accent),
                    modifier = Modifier.height(24.dp)
                )
                
                Button(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.iconButtonColors(
                        backgroundColor = if (text.isNotBlank()) accent else Color.DarkGray,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (text.isBlank()) {
                    Text("Quick note...", color = Color.Gray, style = MaterialTheme.typography.caption2)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.caption2.copy(color = Color.White),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
            .padding(bottom = 4.dp)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color(0xFF0A0A0A)),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(
                text = note.title.ifBlank { "Untitled" },
                color = Color.White,
                style = MaterialTheme.typography.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            MarkdownTextWatch(
                text = note.content,
                accent = accent,
                bodyColor = Color.White.copy(alpha = 0.8f)
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
