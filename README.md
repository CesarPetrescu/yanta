# 📝 Yanta - Live Notes System

A full-stack note-taking system with real-time synchronization across Android phone, Wear OS watch, and cloud backend.

## 🏗️ Architecture

### Three-Tier Sync System
1. **Phone App** (Android) - Main interface with WebSocket cloud sync + BLE GATT Server
2. **Watch App** (Wear OS) - Offline-first sync via BLE GATT Client
3. **Backend Server** (FastAPI) - WebSocket server with SQLite persistence

### Sync Flow
```
Phone ←──────────→ WebSocket ←──────────→ Server (Cloud)
  ↑                                           ↓
  │ BLE GATT                          (SQLite Database)
  │ (Notifications + Commands)
  │ (No Internet Required)
  ↓
Watch
```

### BLE Architecture Details

**Phone (GATT Server):**
- Advertises BLE service with custom UUID
- Hosts two characteristics:
  - **Data Characteristic** (READ + NOTIFY): Pushes note/project updates to watch
  - **Command Characteristic** (WRITE): Receives commands from watch
- Automatically notifies watch on any data change
- Maintains persistent connection

**Watch (GATT Client):**
- Scans for phone's advertised service
- Automatically connects to first matching device
- Subscribes to data characteristic notifications
- Writes commands to command characteristic
- Auto-reconnects on disconnect

**Data Flow:**
1. **Phone → Watch**: Notes created on phone trigger BLE notification to watch
2. **Watch → Phone**: Watch writes note command, phone processes and broadcasts to server
3. **Server → Phone → Watch**: Server updates propagate through WebSocket to phone, then BLE to watch

## 📁 Project Structure

```
yanta/
├── phonelivenotes/          # Android phone app (Jetpack Compose)
│   ├── app/
│   │   ├── src/main/        # Kotlin source files
│   │   ├── build.gradle.kts # App build configuration
│   │   └── build/outputs/   # Generated APKs
│   ├── build.gradle.kts     # Project build configuration
│   └── gradle/              # Gradle wrapper and dependencies
│
├── wearlivenotes/           # Wear OS ecosystem
│   ├── wear/                # Main Wear OS app
│   │   ├── src/main/        # Kotlin source for watch
│   │   └── build/outputs/   # Generated watch APKs
│   └── app/                 # Companion stub (optional)
│
├── server/                  # FastAPI WebSocket backend
│   ├── main.py              # Server implementation
│   ├── requirements.txt     # Python dependencies
│   └── notes.db             # SQLite database (auto-generated)
│
├── .gitignore              # Git ignore rules
└── README.md               # This file
```

## 🚀 Quick Start

### Prerequisites
- **Android Development**: Android Studio, JDK 17+
- **Server**: Python 3.8+
- **Deployment**: Android phone + Wear OS watch (for full testing)

### 1️⃣ Build Android Apps

#### Phone App
```bash
cd phonelivenotes
./gradlew assembleDebug        # Debug build
./gradlew assembleRelease      # Release build (unsigned)
```

**Output**: `phonelivenotes/app/build/outputs/apk/debug/app-debug.apk`

#### Watch App
```bash
cd wearlivenotes
./gradlew :wear:assembleDebug  # Debug build
./gradlew :wear:assembleRelease # Release build (unsigned)
```

**Output**: `wearlivenotes/wear/build/outputs/apk/debug/wear-debug.apk`

### 2️⃣ Install APKs

#### Phone
```bash
adb install -r phonelivenotes/app/build/outputs/apk/debug/app-debug.apk
```

#### Watch
```bash
# List connected devices to find watch ID
adb devices

# Install to specific watch
adb -s <WATCH_DEVICE_ID> install -r wearlivenotes/wear/build/outputs/apk/debug/wear-debug.apk
```

### 3️⃣ Run Backend Server

```bash
cd server

# Install dependencies (first time only)
pip install -r requirements.txt

# Run server
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

**Server Endpoints**:
- WebSocket: `ws://localhost:8000/ws`
- REST API: 
  - `GET /state` - Full app state
  - `GET /notes` - All notes
  - `GET /projects` - All projects

## 💡 Features

### Phone App (Jetpack Compose)
- ✅ OLED-optimized UI (pure black background, white text)
- ✅ Floating Action Button (FAB) for quick note creation
- ✅ Modal composer with markdown support
- ✅ Project/color organization with visual tags
- ✅ Search and filter by project
- ✅ Real-time WebSocket sync with server
- ✅ Local markdown backups
- ✅ **BLE GATT Server** for watch communication (modern Bluetooth)
- ✅ Tablet/landscape two-pane layout

### Watch App (Wear OS)
- ✅ OLED-friendly design (black bg, outlined cards)
- ✅ Connection status indicator
- ✅ Quick compose card with project colors
- ✅ Full notes list with previews
- ✅ **BLE GATT Client** sync (no internet needed, modern Bluetooth)
- ✅ Offline-first architecture
- ✅ Automatic reconnection on disconnect

### Backend Server (FastAPI)
- ✅ SQLite persistence (projects + notes)
- ✅ WebSocket real-time broadcast
- ✅ REST API for debugging
- ✅ Automatic project color management
- ✅ Timestamp tracking
- ✅ Connection manager for multiple clients

## 📊 Data Model

### Note Payload
```json
{
  "title": "string",
  "content": "markdown string",
  "projectName": "string",
  "projectColor": "#HEX",
  "updatedAt": 1234567890000
}
```

### Project Payload
```json
{
  "name": "string",
  "color": "#HEX"
}
```

### WebSocket Envelope
```json
{
  "notes": [/* array of notes */],
  "projects": [/* array of projects */]
}
```

## 🔧 Configuration

### Phone WebSocket Connection
Configure server IP/port in the app settings (tap the gear icon):
- **Default IP**: `192.168.10.161`
- **Default Port**: `8000`
- For Android emulator use: `10.0.2.2`

### BLE Configuration
Service and characteristic UUIDs are hardcoded in both apps:
- **Service UUID**: `a9e86dbd-0cd6-4a4a-a904-3622fa6b49f4`
- **Data Characteristic**: `b9e86dbd-0cd6-4a4a-a904-3622fa6b49f4`
- **Command Characteristic**: `c9e86dbd-0cd6-4a4a-a904-3622fa6b49f4`

To change these, edit the UUIDs at the top of both `MainActivity.kt` files.

### Bluetooth Low Energy (BLE)
- **Service UUID**: `a9e86dbd-0cd6-4a4a-a904-3622fa6b49f4`
- **Data Characteristic UUID**: `b9e86dbd-0cd6-4a4a-a904-3622fa6b49f4` (READ + NOTIFY)
- **Command Characteristic UUID**: `c9e86dbd-0cd6-4a4a-a904-3622fa6b49f4` (WRITE)
- **Protocol**: Bluetooth Low Energy (BLE) with GATT
- **Commands**:
  - `{"new_note": NotePayload}` - Create note
  - `{"request_state": true}` - Request full state
- **Features**:
  - Modern BLE API (no deprecation warnings)
  - Low power consumption
  - Automatic notifications from phone to watch
  - Automatic reconnection on disconnect

## 🏭 Production Deployment

### Server
```bash
# Using uvicorn with production settings
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4

# Or with gunicorn
pip install gunicorn
gunicorn main:app -w 4 -k uvicorn.workers.UvicornWorker --bind 0.0.0.0:8000
```

### Android APK Signing
```bash
# Generate keystore (first time only)
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release-key

# Sign release APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore release-key.jks app-release-unsigned.apk release-key

# Align APK (requires Android build tools)
zipalign -v 4 app-release-unsigned.apk app-release.apk
```

⚠️ **Security Note**: Never commit `release-key.jks` to version control!

## 🐛 Known Issues & Limitations

- Release APKs are unsigned by default - sign before distribution
- Watch requires Bluetooth pairing with phone for initial BLE connection
- BLE characteristic data limited to ~512 bytes per packet - large note lists may be truncated (consider chunking for production)
- Server uses SQLite (single file) - consider PostgreSQL for heavy production use
- Watch automatically scans and connects to first phone advertising the service UUID

## 🛠️ Development

### Prerequisites
- Android Studio Hedgehog or newer
- Kotlin 1.9+
- Gradle 8.0+
- Python 3.8+ (for server)

### Testing

#### Unit Tests
```bash
# Run phone app tests
cd phonelivenotes
./gradlew test

# Run watch app tests
cd wearlivenotes
./gradlew :wear:test
```

#### BLE Integration Testing

**Prerequisites:**
- Physical Android phone (emulator doesn't support BLE properly)
- Physical Wear OS watch or emulator
- Both devices must support Bluetooth Low Energy (BLE 4.0+)

**Testing Steps:**

1. **Pair Devices via Bluetooth Settings**
   - On phone: Settings → Bluetooth → Pair new device
   - On watch: Settings → Connectivity → Bluetooth
   - Pair the devices (standard Bluetooth pairing)

2. **Start the Server**
   ```bash
   cd server
   uvicorn main:app --host 0.0.0.0 --port 8000
   ```

3. **Install and Launch Phone App**
   ```bash
   adb install -r phonelivenotes/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.example.phone_livenotes/.MainActivity
   ```
   - Check top bar shows "Live Notes (BLE)"
   - Grant Bluetooth permissions when prompted
   - Verify status shows "Connected to server"
   - In Settings (gear icon), verify/update server IP

4. **Install and Launch Watch App**
   ```bash
   adb -s <WATCH_ID> install -r wearlivenotes/wear/build/outputs/apk/debug/wear-debug.apk
   adb -s <WATCH_ID> shell am start -n com.example.phone_livenotes/.MainActivity
   ```
   - Grant Bluetooth permissions when prompted
   - Watch should show "Scanning for phone..." then "Connecting..."
   - Once connected: "Connected to phone (BLE)"

5. **Test Note Creation from Phone**
   - Tap FAB (+) button on phone
   - Create a note with title and content
   - Tap "Save & Sync"
   - Verify note appears on phone immediately
   - Verify note appears on watch within 1-2 seconds

6. **Test Note Creation from Watch**
   - On watch, scroll to "Quick Compose" card
   - Tap project badge to cycle projects (if multiple exist)
   - Enter text in the quick note field
   - Tap "Send"
   - Verify note appears on watch
   - Verify note appears on phone within 1-2 seconds

7. **Test Disconnection/Reconnection**
   - Disable Bluetooth on phone or watch
   - Verify status updates to "Disconnected" / "Reconnecting..."
   - Re-enable Bluetooth
   - Verify automatic reconnection within 3-5 seconds
   - Verify notes sync after reconnection

8. **Check Logs**
   ```bash
   # Phone logs
   adb logcat | grep -E "BLE|GATT"
   
   # Watch logs
   adb -s <WATCH_ID> logcat | grep -E "BLE|GATT"
   ```

**Expected Behavior:**
- ✅ Phone advertises BLE service automatically
- ✅ Watch scans and connects automatically
- ✅ Bidirectional communication (phone ↔ watch)
- ✅ Automatic reconnection on disconnect
- ✅ Notes created on either device sync to both
- ✅ Server receives all notes from phone
- ✅ Low latency (< 2 seconds for sync)

**Common Issues:**
- **Watch can't find phone**: Ensure devices are paired in Bluetooth settings first
- **"Service not found"**: Verify UUIDs match in both MainActivity.kt files
- **Permissions denied**: Check AndroidManifest.xml and grant all BLE permissions
- **Data truncated**: Large note lists (>512 bytes JSON) may be cut off - this is a known limitation

### Debug Server
```bash
# Server with auto-reload
uvicorn main:app --reload --log-level debug

# Test WebSocket connection
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" http://localhost:8000/ws
```

## 📝 Contributing

1. Follow existing code style (Kotlin conventions, Python PEP 8)
2. Test on both phone and watch before submitting
3. Update README for significant changes
4. Ensure no linter errors in Android Studio

## 📄 License

This project is provided as-is for educational and personal use.

## 🙏 Acknowledgments

- Built with Jetpack Compose, Wear OS Compose, FastAPI
- OLED-friendly design inspired by modern note-taking apps
- Bluetooth RFCOMM implementation based on Android Classic Bluetooth APIs

---

**Need Help?** Check the inline documentation in `server/main.py` and Kotlin source files.
