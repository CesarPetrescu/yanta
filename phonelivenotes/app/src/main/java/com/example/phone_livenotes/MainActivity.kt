package com.example.phone_livenotes

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.content.ContextCompat
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

val PROJECT_PALETTE = listOf(
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
    private val notesState = SyncState.notes
    private val projectsState = SyncState.projects
    private val connectionStatus = SyncState.connectionStatus

    private lateinit var prefs: android.content.SharedPreferences

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private var syncService: SyncService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
            val localBinder = binder as? SyncService.LocalBinder
            syncService = localBinder?.getService()
            serviceBound = syncService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            serviceBound = false
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, SyncService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindToService()
    }

    private fun bindToService() {
        if (serviceBound) return
        val intent = Intent(this, SyncService::class.java)
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("livenotes_prefs", Context.MODE_PRIVATE)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            if (granted.values.all { it }) {
                ensureBluetoothOn()
                startAndBindService()
            } else {
                connectionStatus.value = "Permissions required"
            }
        }
        if (hasAllPermissions()) {
            ensureBluetoothOn()
            startAndBindService()
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
        if (hasAllPermissions()) bindToService()
    }
    
    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensureBluetoothOn() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter != null && !adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun getCurrentIp(): String = prefs.getString("server_ip", DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    private fun getCurrentPort(): Int = prefs.getInt("server_port", DEFAULT_PORT)

    private fun saveSettings(ip: String, port: Int) {
        with(prefs.edit()) {
            putString("server_ip", ip)
            putInt("server_port", port)
            apply()
        }
        syncService?.applyServerSettings(ip, port)
    }

    private fun sendNote(note: NotePayload) {
        if (!hasAllPermissions()) {
            connectionStatus.value = "Permissions required"
            return
        }
        syncService?.sendNote(note)
    }
    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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
    val availableProjects = if (projects.isNotEmpty()) {
        projects
    } else {
        listOf(ProjectPayload(name = "General", color = PROJECT_PALETTE.first()))
    }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf(availableProjects.first().name) }
    var projectColor by remember { mutableStateOf(availableProjects.first().color) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(true) }
    var selectedProjectFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedNote by remember { mutableStateOf<NotePayload?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NotePayload?>(null) }

    val projectsByName = projects.associateBy { it.name }
    val projectCounts = notes.groupingBy { it.projectName ?: "General" }.eachCount()

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

    val filteredNotes = notes.filter { note ->
        val matchesProject = selectedProjectFilter == null || (note.projectName ?: "General") == selectedProjectFilter
        val query = searchQuery.trim().lowercase(Locale.getDefault())
        val matchesQuery = query.isBlank() ||
            note.title.lowercase(Locale.getDefault()).contains(query) ||
            note.content.lowercase(Locale.getDefault()).contains(query)
        matchesProject && matchesQuery
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
                    projectName = availableProjects.first().name
                    projectColor = availableProjects.first().color
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
                    val updatedAt = System.currentTimeMillis()
                    val existingId = editingNote?.id
                    onSend(
                        NotePayload(
                            id = existingId,
                            title = title,
                            content = body,
                            projectName = projectName,
                            projectColor = projectColor,
                            updatedAt = updatedAt
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
    val chipColor = if (connected) Color(0xFF4CAF50) else Color(0xFFFFC107)
    val serverUp = status.contains("server", true) && !status.contains("Error", true)
    val watchUp = status.contains("watch", true) || status.contains("Synced", true)
    var showDebug by remember { mutableStateOf(false) }
    val detail = when {
        status.contains("Synced", true) -> "Phone ↔ Watch BLE is synced; websocket live."
        status.contains("watch", true) && status.contains("Connected", true) -> "BLE link to watch is up. Notes stream automatically."
        status.contains("Service missing", true) -> "Watch can’t find the phone advertiser. Open phone app to restart sync."
        status.contains("BLE permission", true) -> "Grant Bluetooth + Location + Notifications to keep sync alive."
        status.contains("Scanning", true) -> "Phone is advertising; watch is scanning. Keep Bluetooth on."
        else -> "Live Notes sync service status (server + watch)."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black, RoundedCornerShape(12.dp))
            .clickable { showDebug = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(chipColor, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Status: $status", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(label = "Server", ok = serverUp)
                StatusDot(label = "Watch", ok = watchUp)
            }
        }
    }
    if (showDebug) {
        AlertDialog(
            onDismissRequest = { showDebug = false },
            confirmButton = {
                TextButton(onClick = { showDebug = false }) { Text("Close") }
            },
            title = { Text("Sync Debug") },
            text = {
                Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text("Current status: $status")
                    Spacer(Modifier.height(8.dp))
                    Text("Recent events:", fontWeight = FontWeight.Bold)
                    if (SyncState.debugLog.isEmpty()) {
                        Text("No events yet.")
                    } else {
                        SyncState.debugLog.take(10).forEach {
                            Text(it, fontSize = 12.sp)
                        }
                    }
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
private fun StatusDot(label: String, ok: Boolean) {
    val color = if (ok) Color(0xFF4CAF50) else Color(0xFFFFC107)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White)
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
        colors = CardDefaults.cardColors(containerColor = Color.Black),
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
        colors = CardDefaults.cardColors(containerColor = Color.Black),
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
        colors = CardDefaults.cardColors(containerColor = Color.Black),
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
