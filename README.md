# 📝 Yanta - Live Notes System

A full-stack note-taking system with real-time synchronization across Android phone, Wear OS watch, and cloud backend.

## 🏗️ Architecture

### Three-Tier Sync System
1. **Phone App** (Android) - Main interface with WebSocket cloud sync
2. **Watch App** (Wear OS) - Offline-first sync via Bluetooth RFCOMM
3. **Backend Server** (FastAPI) - WebSocket server with SQLite persistence

### Sync Flow
```
Phone ←──→ WebSocket ←──→ Server (Cloud)
  ↑
  │ Bluetooth RFCOMM
  │ (No Internet Required)
  ↓
Watch
```

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
- ✅ Bluetooth bridge to watch (offline sync)
- ✅ Tablet/landscape two-pane layout

### Watch App (Wear OS)
- ✅ OLED-friendly design (black bg, outlined cards)
- ✅ Connection status indicator
- ✅ Quick compose card with project colors
- ✅ Full notes list with previews
- ✅ Bluetooth RFCOMM sync (no internet needed)
- ✅ Offline-first architecture

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
Edit `phonelivenotes/app/src/main/` to configure server URL:
```kotlin
// Default: ws://10.0.2.2:8000/ws (Android emulator)
// Production: ws://your-server.com/ws
```

### Bluetooth RFCOMM
- **UUID**: `f9a86dbd-0cd6-4a4a-a904-3622fa6b49f4`
- **Protocol**: Classic Bluetooth (not BLE)
- **Messages**:
  - `{"new_note": NotePayload}` - Create note
  - `{"request_state": true}` - Request full state

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

- Legacy Bluetooth adapter API deprecation warnings (Android 12+) - harmless for now
- Release APKs are unsigned by default - sign before distribution
- Watch requires Bluetooth pairing with phone for RFCOMM connection
- Server uses SQLite (single file) - consider PostgreSQL for heavy production use

## 🛠️ Development

### Prerequisites
- Android Studio Hedgehog or newer
- Kotlin 1.9+
- Gradle 8.0+
- Python 3.8+ (for server)

### Testing
```bash
# Run phone app tests
cd phonelivenotes
./gradlew test

# Run watch app tests
cd wearlivenotes
./gradlew :wear:test
```

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
