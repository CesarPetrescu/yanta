package com.example.wear_livenotes

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Colors
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

private const val BT_UUID_STR_WATCH = "f9a86dbd-0cd6-4a4a-a904-3622fa6b49f4"
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
    private val statusState = mutableStateOf("Connecting to phone...")
    private val gson = Gson()
    @Volatile private var watchSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ),
            1
        )

        connectToPhone()

        setContent {
            OledTheme {
                WatchUI(
                    notes = notesState,
                    projects = projectsState,
                    status = statusState.value,
                    onSend = ::sendToPhone
                )
            }
        }
    }

    private fun connectToPhone() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        if (adapter == null) {
                            statusState.value = "No Bluetooth adapter"
                        } else {
                            adapter.cancelDiscovery()
                            val device = adapter.bondedDevices.firstOrNull()

                            if (device != null) {
                                val uuid = UUID.fromString(BT_UUID_STR_WATCH)
                                val socket = device.createRfcommSocketToServiceRecord(uuid)
                                socket.connect() // blocking
                                watchSocket?.close()
                                watchSocket = socket
                                statusState.value = "Connected to phone"

                                // Request latest state from phone and keep pulling while connected
                                val requestJson = gson.toJson(mapOf("request_state" to true))
                                try {
                                    statusState.value = "Syncing..."
                                    socket.outputStream.write(requestJson.toByteArray())
                                    statusState.value = "Connected to phone"
                                } catch (_: Exception) {}

                                val syncJob = launch {
                                    while (isActive && socket.isConnected) {
                                        delay(12000)
                                        try {
                                            statusState.value = "Syncing..."
                                            socket.outputStream.write(requestJson.toByteArray())
                                            statusState.value = "Connected to phone"
                                        } catch (_: Exception) {
                                            break
                                        }
                                    }
                                }

                                val buffer = ByteArray(4096)
                                while (true) {
                                    val bytes = socket.inputStream.read(buffer)
                                    if (bytes <= 0) break
                                    val message = String(buffer, 0, bytes)
                                    val envelope = gson.fromJson(message, NotesEnvelope::class.java)
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
                                }
                                syncJob.cancel()
                                statusState.value = "Disconnected"
                                try { socket.close() } catch (_: Exception) {}
                            } else {
                                statusState.value = "Pair phone via Bluetooth"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Watch", "Connection failed, retrying...", e)
                        statusState.value = "Reconnecting..."
                        watchSocket = null
                        try { watchSocket?.close() } catch (_: Exception) {}
                    }
                }
                delay(2500)
            }
        }
    }

    private fun sendToPhone(text: String, projectName: String?) {
        val payload = NotePayload(
            title = if (text.length > 30) text.take(30) + "..." else text,
            content = text,
            projectName = projectName?.ifBlank { DEFAULT_PROJECT_NAME } ?: DEFAULT_PROJECT_NAME,
            projectColor = projectsState.firstOrNull { it.name == projectName }?.color ?: DEFAULT_PROJECT_COLOR
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = gson.toJson(mapOf("new_note" to payload))
                val socket = watchSocket
                if (socket != null && socket.isConnected) {
                    socket.outputStream.write(json.toByteArray())
                    try {
                        val request = gson.toJson(mapOf("request_state" to true))
                        socket.outputStream.write(request.toByteArray())
                    } catch (_: Exception) {}
                } else {
                    statusState.value = "Reconnecting..."
                }
            } catch (e: Exception) {
                Log.e("Watch", "Send failed", e)
                statusState.value = "Reconnecting..."
            }
        }
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
    onSend: (String, String?) -> Unit
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
            item { StatusChip(status = status) }
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
fun StatusChip(status: String) {
    val outline = if (status.contains("Connected", true)) Color(0xFF4CAF50) else Color(0xFFFFC107)
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .border(1.dp, outline.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color.Black),
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Status", color = Color.White, style = MaterialTheme.typography.caption2)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(outline, CircleShape)
            )
            Text(status, color = outline, style = MaterialTheme.typography.caption2)
        }
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
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color.Black),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                ChipProjectBadge(projectName, projectColor, onProjectTapped)
                Button(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                ) {
                    Text("Send", color = Color.Black)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                if (text.isBlank()) {
                    Text("Quick note", color = Color.Gray, style = MaterialTheme.typography.caption2)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.caption1.copy(color = Color.White, fontWeight = FontWeight.Medium),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ChipProjectBadge(name: String, colorHex: String, onClick: () -> Unit) {
    val color = Color.fromHex(colorHex)
    androidx.wear.compose.material.Chip(
        modifier = Modifier.height(28.dp),
        colors = androidx.wear.compose.material.ChipDefaults.chipColors(
            backgroundColor = Color.Black,
            contentColor = Color.White
        ),
        border = androidx.wear.compose.material.ChipDefaults.chipBorder(BorderStroke(1.dp, color)),
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
                Text(name, color = Color.White, style = MaterialTheme.typography.caption2)
            }
        }
    )
}

@Composable
fun NoteCardWatch(note: NotePayload) {
    val accent = Color.fromHex(note.projectColor)
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(Color.Black),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    color = Color.White,
                    style = MaterialTheme.typography.body2,
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = note.projectName ?: DEFAULT_PROJECT_NAME,
                color = accent,
                style = MaterialTheme.typography.caption2
            )
        }
    }
}

@Composable
fun MarkdownTextWatch(text: String, accent: Color, bodyColor: Color) {
    val content = remember(text, accent, bodyColor) { markdownToAnnotatedString(text, accent, bodyColor) }
    Text(content, color = bodyColor, style = MaterialTheme.typography.caption1)
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
