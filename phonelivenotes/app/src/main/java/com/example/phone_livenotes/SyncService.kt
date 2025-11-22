package com.example.phone_livenotes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.absoluteValue

class SyncService : Service() {
    companion object {
        private const val CHANNEL_ID = "livenotes_sync_channel"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_TITLE = "Live Notes"
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val gson = com.google.gson.Gson()
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var serverConnected = false
    private var lastWatchSyncedAt: Long? = null
    private var classicServer: BluetoothServerSocket? = null
    private val classicClients = mutableSetOf<BluetoothSocket>()

    private lateinit var prefs: SharedPreferences
    private val cacheFile: File by lazy { File(filesDir, "cached_state.json") }

    // BLE server components
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

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

    inner class LocalBinder : android.os.Binder() {
        fun getService(): SyncService = this@SyncService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("livenotes_prefs", Context.MODE_PRIVATE)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        createNotificationChannel()
        if (!canPostNotifications()) {
            SyncState.connectionStatus.value = "Allow notifications to keep sync running"
            Log.w("SyncService", "Notification permission missing; stopping service until granted")
            stopSelf()
            return
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (se: SecurityException) {
            SyncState.connectionStatus.value = "Allow notifications to keep sync running"
            Log.e("SyncService", "Failed to start foreground service; missing POST_NOTIFICATIONS", se)
            stopSelf()
            return
        }
        loadCachedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServices()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownWebSocket()
        stopBleGattServer()
    }

    fun sendNote(note: NotePayload) {
        if (note.title.isBlank() || note.content.isBlank()) return
        val cleanedProject = note.projectName?.ifBlank { "General" } ?: "General"
        val projectColor = note.projectColor ?: projectColorFor(cleanedProject)
        val updatedAt = note.updatedAt ?: System.currentTimeMillis()
        val payload = note.copy(
            title = note.title.trim(),
            content = note.content.trim(),
            projectName = cleanedProject,
            projectColor = projectColor,
            updatedAt = updatedAt
        )
        webSocket?.send(gson.toJson(mapOf("new_note" to payload)))
        // Replace existing by id if present, otherwise prepend
        payload.id?.let { id ->
            val idx = SyncState.notes.indexOfFirst { it.id == id }
            if (idx >= 0) {
                SyncState.notes[idx] = payload
            } else {
                SyncState.notes.add(0, payload)
            }
        } ?: SyncState.notes.add(0, payload)
        sendToWatchViaBLE(SyncState.notes, SyncState.projects)
        broadcastClassic(SyncState.notes, SyncState.projects)
        persistCache(SyncState.notes, SyncState.projects)
        saveLocally(payload)
    }

    fun applyServerSettings(ip: String, port: Int) {
        with(prefs.edit()) {
            putString("server_ip", ip)
            putInt("server_port", port)
            apply()
        }
        teardownWebSocket()
        connectWebSocket()
    }

    fun requestRefresh() {
        connectWebSocket()
        startBleGattServer()
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = SyncState.connectionStatus.value

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Notes Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationStatus() {
        if (!canPostNotifications()) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startServices() {
        if (!hasAllPermissions()) {
            mainScope.launch { SyncState.connectionStatus.value = "Permissions required" }
            return
        }
        mainScope.launch { SyncState.connectionStatus.value = mergedStatus(advertising = true) }
        log("Starting services: advertising + websocket connect")
        connectWebSocket()
        startBleGattServer()
        startClassicServer()
    }

    private fun getCurrentIp(): String =
        prefs.getString("server_ip", DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP

    private fun getCurrentPort(): Int = prefs.getInt("server_port", DEFAULT_PORT)

    private fun connectWebSocket() {
        if (webSocket != null) return
        val url = "ws://${getCurrentIp()}:${getCurrentPort()}/ws"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverConnected = true
                log("WebSocket connected")
                mainScope.launch { SyncState.connectionStatus.value = mergedStatus() }
                mainScope.launch { updateNotificationStatus() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ioScope.launch {
                    try {
                        val envelope = gson.fromJson(text, NotesEnvelope::class.java)
                        envelope.projects?.let { projects ->
                            withContext(Dispatchers.Main) {
                                SyncState.projects.clear()
                                SyncState.projects.addAll(projects.sortedBy { it.name.lowercase(Locale.getDefault()) })
                            }
                            persistCache(SyncState.notes, SyncState.projects)
                        }
                        envelope.notes?.let { notes ->
                            withContext(Dispatchers.Main) {
                                SyncState.notes.clear()
                                SyncState.notes.addAll(notes)
                                sendToWatchViaBLE(SyncState.notes, SyncState.projects)
                            }
                            persistCache(notes, SyncState.projects)
                        }
                    } catch (e: Exception) {
                        Log.e("WS", "Parse error", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                serverConnected = false
                log("WebSocket error: ${t.message}")
                mainScope.launch { SyncState.connectionStatus.value = "WS Error: ${t.message}" }
                teardownWebSocket()
                mainScope.launch { updateNotificationStatus() }
            }
        })
    }

    private fun teardownWebSocket() {
        try {
            webSocket?.close(1000, "Lifecycle stop")
        } catch (_: Exception) {
        } finally {
            webSocket = null
        }
    }

    private fun startBleGattServer() {
        if (!hasAllPermissions()) {
            mainScope.launch { SyncState.connectionStatus.value = "BLE permission missing" }
            return
        }

        try {
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            dataCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            val configDescriptor = BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            dataCharacteristic?.addDescriptor(configDescriptor)

            commandCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_COMMAND_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            service.addCharacteristic(dataCharacteristic)
            service.addCharacteristic(commandCharacteristic)

            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
            gattServer?.addService(service)

            startBleAdvertising()

            Log.i("BLE", "GATT Server started successfully")
            log("GATT server started, advertising service UUID")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to start GATT server", e)
            mainScope.launch { SyncState.connectionStatus.value = "BLE Error: ${e.message}" }
            log("GATT server error: ${e.message}")
        }
    }

    private fun startBleAdvertising() {
        if (!hasAllPermissions()) return

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
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BLE", "Advertising started")
            log("Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising failed with code: $errorCode")
            log("Advertising failed code $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    Log.i("BLE", "Watch connected: ${device.address}")
                    log("Watch connected ${device.address}")
                    mainScope.launch {
                        SyncState.connectionStatus.value = mergedStatus(watchConnected = true, syncing = true)
                        updateNotificationStatus()
                    }
                    sendToWatchViaBLE(SyncState.notes, SyncState.projects)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.i("BLE", "Watch disconnected: ${device.address}")
                    log("Watch disconnected ${device.address}")
                    mainScope.launch {
                        if (connectedDevices.isEmpty()) {
                            SyncState.connectionStatus.value = mergedStatus(watchConnected = false)
                            updateNotificationStatus()
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
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return

            when (characteristic.uuid) {
                CHARACTERISTIC_DATA_UUID -> {
                    val envelope = NotesEnvelope(notes = SyncState.notes.toList(), projects = SyncState.projects.toList())
                    val data = gson.toJson(envelope).toByteArray(Charsets.UTF_8)

                    val response = if (offset >= data.size) byteArrayOf() else data.copyOfRange(offset, data.size)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
                }
                else -> gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
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
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return

            when (characteristic.uuid) {
                CHARACTERISTIC_COMMAND_UUID -> {
                    try {
                        val message = String(value, Charsets.UTF_8)
                        val raw = gson.fromJson(message, Map::class.java)

                        when {
                            raw["request_state"] == true -> {
                                log("Watch requested state")
                                mainScope.launch { SyncState.connectionStatus.value = mergedStatus(watchConnected = true, syncing = true) }
                                sendToWatchViaBLE(SyncState.notes, SyncState.projects)
                                mainScope.launch { updateNotificationStatus() }
                            }
                            raw["new_note"] != null -> {
                                val note = gson.fromJson(gson.toJson(raw["new_note"]), NotePayload::class.java)
                                mainScope.launch {
                                    SyncState.connectionStatus.value = mergedStatus(watchConnected = true, syncing = true).replace("Synced", "Received note @ ${nowTime()}")
                                    sendNote(note)
                                    log("Note received from watch: ${note.title}")
                                    updateNotificationStatus()
                                }
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
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return

            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG) {
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
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return

        try {
            val envelope = NotesEnvelope(notes = notes.toList(), projects = projects.toList())
            val json = gson.toJson(envelope)
            val data = json.toByteArray(Charsets.UTF_8)

            if (data.size > 512) {
                Log.w("BLE", "Data too large for single BLE packet: ${data.size} bytes. Consider chunking.")
                dataCharacteristic?.value = data.copyOf(512)
            } else {
                dataCharacteristic?.value = data
            }

            connectedDevices.forEach { device ->
                gattServer?.notifyCharacteristicChanged(device, dataCharacteristic, false)
            }

            Log.i("BLE", "Sent data to ${connectedDevices.size} watch(es)")
            mainScope.launch {
                lastWatchSyncedAt = System.currentTimeMillis()
                log("Sent ${notes.size} notes / ${projects.size} projects to watch")
                SyncState.connectionStatus.value = mergedStatus(watchConnected = connectedDevices.isNotEmpty(), syncing = false)
                updateNotificationStatus()
            }
            broadcastClassic(notes, projects)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to send data to watch", e)
            log("Send to watch failed: ${e.message}")
        }
    }

    private fun stopBleGattServer() {
        if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }

        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        Log.i("BLE", "GATT Server stopped")
        log("BLE GATT server stopped")
    }

    private fun startClassicServer() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            classicServer = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("LiveNotesClassic", SERVICE_UUID)
            ioScope.launch {
                while (true) {
                    val socket = classicServer?.accept() ?: break
                    classicClients.add(socket)
                    log("Classic client connected ${socket.remoteDevice.address}")
                    handleClassicClient(socket)
                    sendEnvelopeOverClassic(socket, NotesEnvelope(SyncState.notes.toList(), SyncState.projects.toList()))
                }
            }
        } catch (e: Exception) {
            log("Classic server error: ${e.message}")
        }
    }

    private fun handleClassicClient(socket: BluetoothSocket) {
        ioScope.launch {
            try {
                val input = socket.inputStream
                val reader = input.bufferedReader()
                mainScope.launch {
                    SyncState.connectionStatus.value = mergedStatus(watchConnected = true)
                    updateNotificationStatus()
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    val raw = gson.fromJson(line, Map::class.java)
                    when {
                        raw["request_state"] == true -> {
                            mainScope.launch { SyncState.connectionStatus.value = mergedStatus(watchConnected = true, syncing = true) }
                            sendEnvelopeOverClassic(socket, NotesEnvelope(SyncState.notes.toList(), SyncState.projects.toList()))
                            mainScope.launch { updateNotificationStatus() }
                        }
                        raw["new_note"] != null -> {
                            val note = gson.fromJson(gson.toJson(raw["new_note"]), NotePayload::class.java)
                            mainScope.launch {
                                SyncState.connectionStatus.value = mergedStatus(watchConnected = true, syncing = true).replace("Synced", "Received note @ ${nowTime()}")
                                sendNote(note)
                                updateNotificationStatus()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("Classic client error: ${e.message}")
            } finally {
                classicClients.remove(socket)
                try { socket.close() } catch (_: Exception) {}
                mainScope.launch {
                    if (connectedDevices.isEmpty() && classicClients.isEmpty()) {
                        SyncState.connectionStatus.value = mergedStatus(watchConnected = false)
                        updateNotificationStatus()
                    }
                }
            }
        }
    }

    private fun sendEnvelopeOverClassic(socket: BluetoothSocket, envelope: NotesEnvelope) {
        try {
            val out = socket.outputStream
            val json = gson.toJson(envelope) + "\n"
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            log("Classic send failed: ${e.message}")
            classicClients.remove(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastClassic(notes: List<NotePayload>, projects: List<ProjectPayload>) {
        if (classicClients.isEmpty()) return
        val env = NotesEnvelope(notes.toList(), projects.toList())
        classicClients.toList().forEach { sendEnvelopeOverClassic(it, env) }
    }

    private fun persistCache(notes: List<NotePayload>, projects: List<ProjectPayload>) {
        ioScope.launch {
            try {
                val envelope = NotesEnvelope(notes = notes.toList(), projects = projects.toList())
                cacheFile.writeText(gson.toJson(envelope))
            } catch (e: Exception) {
                Log.e("Cache", "Failed to write cache", e)
            }
        }
    }

    private fun loadCachedState() {
        ioScope.launch {
            try {
                if (!cacheFile.exists()) return@launch
                val cached = gson.fromJson(cacheFile.readText(), NotesEnvelope::class.java)
                cached.projects?.let { projects ->
                    withContext(Dispatchers.Main) {
                        SyncState.projects.clear()
                        SyncState.projects.addAll(projects.sortedBy { it.name.lowercase(Locale.getDefault()) })
                    }
                }
                cached.notes?.let { notes ->
                    withContext(Dispatchers.Main) {
                        SyncState.notes.clear()
                        SyncState.notes.addAll(notes)
                    }
                }
            } catch (e: Exception) {
                Log.e("Cache", "Failed to load cache", e)
            }
        }
    }

    private fun saveLocally(note: NotePayload) {
        val file = File(filesDir, "QuickNotes.md")
        val header = "\n## ${note.title}\nProject: ${note.projectName ?: "General"}\n\n"
        file.appendText(header + note.content.trim() + "\n")
    }

    private fun projectColorFor(projectName: String): String {
        SyncState.projects.firstOrNull { it.name == projectName }?.let { return it.color }
        val palette = PROJECT_PALETTE
        val index = projectName.hashCode().absoluteValue % palette.size
        return palette[index]
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { hasPermission(it) }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun mergedStatus(
        watchConnected: Boolean = connectedDevices.isNotEmpty() || classicClients.isNotEmpty(),
        syncing: Boolean = false,
        advertising: Boolean = false
    ): String {
        val syncedStamp = lastWatchSyncedAt?.let { "Synced @ ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}" }
        return when {
            advertising && !watchConnected -> "Advertising for watch"
            serverConnected && watchConnected && syncing -> "Connected to server + watch - syncing"
            serverConnected && watchConnected -> "Connected to server + watch - ${syncedStamp ?: "Synced"}"
            serverConnected && !watchConnected -> "Connected to server - watch disconnected"
            !serverConnected && watchConnected -> "Watch connected - server offline"
            else -> "Disconnected"
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun log(message: String) {
        val entry = "${nowTime()} $message"
        SyncState.debugLog.add(0, entry)
        if (SyncState.debugLog.size > 50) {
            SyncState.debugLog.removeLast()
        }
    }
}
