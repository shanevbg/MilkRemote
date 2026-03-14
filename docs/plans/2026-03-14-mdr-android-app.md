# MDR Android App Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin Android companion app for MDropDX12 with direct TCP connection, mDNS discovery, display management, and user-assignable buttons with script upload.

**Architecture:** MVVM with Jetpack Compose UI. Single-activity app with bottom navigation (4 tabs). Network layer uses raw TCP socket with length-prefixed framing and coroutines. Room database for button configs and saved servers. DataStore for preferences. NsdManager for mDNS discovery.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Room, DataStore, Coroutines, NsdManager, Gradle KTS.

**License:** Same as MDropDX12 (proprietary/closed-source — all rights reserved).

**Spec:** `docs/design/2026-03-14-mdr-android-design.md`

**Dependency:** MDropDX12 TCP server must be implemented first (see `docs/plans/2026-03-14-mdropdx12-tcp-server.md`).

---

## Chunk 1: Project Scaffold & Network Layer

### Task 1: Android Project Scaffold

**Files:**
- Create: `build.gradle.kts` (project root)
- Create: `settings.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/milkwave/remote/MainActivity.kt`
- Create: `app/src/main/java/com/milkwave/remote/MdrApp.kt`

- [ ] **Step 1: Create project-level build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 2: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MDR_Android"
include(":app")
```

- [ ] **Step 3: Create gradle/libs.versions.toml**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
compose-bom = "2025.01.01"
lifecycle = "2.8.7"
navigation = "2.8.5"
room = "2.6.1"
datastore = "1.1.1"
ksp = "2.1.0-1.0.29"
coroutines = "1.9.0"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 4: Create app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.milkwave.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.milkwave.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
}
```

- [ ] **Step 5: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <application
        android:name=".MdrApp"
        android:allowBackup="true"
        android:label="MDR Android"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Create MdrApp.kt (Application class)**

```kotlin
package com.milkwave.remote

import android.app.Application
import com.milkwave.remote.service.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MdrApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
    val connectionManager by lazy { ConnectionManager(applicationScope) }
}
```

This provides a single `ConnectionManager` instance shared across all ViewModels via `(application as MdrApp).connectionManager`.

- [ ] **Step 7: Create minimal MainActivity.kt**

```kotlin
package com.milkwave.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.milkwave.remote.ui.theme.MdrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MdrTheme {
                Text("MDR Android")
            }
        }
    }
}
```

- [ ] **Step 8: Create .gitignore**

```
*.iml
.gradle
/local.properties
/.idea
/build
/app/build
/captures
.externalNativeBuild
.cxx
*.apk
*.ap_
*.aab
.superpowers/
```

- [ ] **Step 9: Verify project builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: scaffold Android project with Compose, Room, DataStore dependencies"
```

### Task 2: Theme

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/theme/Color.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/theme/Type.kt`

- [ ] **Step 1: Create Color.kt**

```kotlin
package com.milkwave.remote.ui.theme

import androidx.compose.ui.graphics.Color

val Purple500 = Color(0xFF7C3AED)
val Purple700 = Color(0xFF6D28D9)
val Blue500 = Color(0xFF2563EB)
val Blue700 = Color(0xFF1D4ED8)
val Green500 = Color(0xFF059669)
val Red500 = Color(0xFFDC2626)
val DarkBg = Color(0xFF111111)
val DarkSurface = Color(0xFF1A1A2E)
val DarkCard = Color(0xFF16162A)
val TextPrimary = Color(0xFFE2E8F0)
val TextSecondary = Color(0xFF94A3B8)
```

- [ ] **Step 2: Create Type.kt**

```kotlin
package com.milkwave.remote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MdrTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)
```

- [ ] **Step 3: Create Theme.kt**

```kotlin
package com.milkwave.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    secondary = Purple500,
    tertiary = Green500,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Red500,
)

@Composable
fun MdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MdrTypography,
        content = content,
    )
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/theme/
git commit -m "feat: add dark Material 3 theme"
```

### Task 3: TCP Client — Protocol & Framing

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/network/MilkwaveProtocol.kt`
- Create: `app/src/main/java/com/milkwave/remote/network/TcpClient.kt`

- [ ] **Step 1: Create MilkwaveProtocol.kt**

```kotlin
package com.milkwave.remote.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MilkwaveProtocol {
    /**
     * Encode a command string into a length-prefixed frame.
     * Format: [4-byte LE uint32: payload length][UTF-8 payload bytes]
     */
    fun encode(command: String): ByteArray {
        val payload = command.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + payload.size)
        ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    /**
     * Try to decode one frame from the buffer.
     * Returns the decoded string and bytes consumed, or null if incomplete.
     */
    fun decode(buffer: ByteArray, offset: Int, length: Int): DecodeResult? {
        if (length < 4) return null
        val payloadLen = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (payloadLen < 0 || payloadLen > 4 * 1024 * 1024) return DecodeResult("", 4) // skip bad frame
        if (length < 4 + payloadLen) return null // incomplete
        val message = String(buffer, offset + 4, payloadLen, Charsets.UTF_8)
        return DecodeResult(message, 4 + payloadLen)
    }

    data class DecodeResult(val message: String, val bytesConsumed: Int)
}
```

- [ ] **Step 2: Create TcpClient.kt**

```kotlin
package com.milkwave.remote.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

enum class ConnectionState { Disconnected, Connecting, AuthPending, Connected }

class TcpClient(private val scope: CoroutineScope) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var lastPongReceived: Long = 0L

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    fun connect(host: String, port: Int, pin: String, deviceId: String, deviceName: String) {
        scope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.Connecting
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5000)
                sock.soTimeout = 0 // Non-blocking reads handled via coroutine
                socket = sock
                outputStream = sock.getOutputStream()

                // Start reading
                readJob = scope.launch(Dispatchers.IO) { readLoop(sock.getInputStream()) }

                // Send auth
                send("AUTH|$pin|$deviceId|$deviceName")
                _connectionState.value = ConnectionState.AuthPending
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    fun send(command: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.let {
                    val frame = MilkwaveProtocol.encode(command)
                    synchronized(it) {
                        it.write(frame)
                        it.flush()
                    }
                }
            } catch (_: IOException) {
                disconnect()
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        pingJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        outputStream = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun readLoop(input: InputStream) {
        val buffer = ByteArray(65536)
        val accumulated = java.io.ByteArrayOutputStream(8192)

        try {
            while (isActive) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                accumulated.write(buffer, 0, bytesRead)

                // Decode frames from accumulated bytes
                val bytes = accumulated.toByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    val result = MilkwaveProtocol.decode(bytes, offset, bytes.size - offset) ?: break
                    if (result.message.isNotEmpty()) {
                        handleMessage(result.message)
                    }
                    offset += result.bytesConsumed
                }

                // Compact: keep only unconsumed bytes
                accumulated.reset()
                if (offset < bytes.size) {
                    accumulated.write(bytes, offset, bytes.size - offset)
                }
            }
        } catch (_: IOException) {
            // Connection lost
        }
        disconnect()
    }

    private suspend fun handleMessage(message: String) {
        when {
            message == "AUTH_OK" -> {
                _connectionState.value = ConnectionState.Connected
                startPing()
                send("STATE") // Sync UI state
            }
            message.startsWith("AUTH_FAIL") -> {
                _connectionState.value = ConnectionState.Disconnected
            }
            message == "AUTH_PENDING" -> {
                _connectionState.value = ConnectionState.AuthPending
            }
            message == "PONG" -> { lastPongReceived = System.currentTimeMillis() }
            else -> _messages.emit(message)
        }
    }

    private fun startPing() {
        lastPongReceived = System.currentTimeMillis()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                // Check if last PONG was received within timeout
                if (System.currentTimeMillis() - lastPongReceived > 20_000) {
                    // No PONG for 20s (missed at least one cycle) — connection dead
                    disconnect()
                    break
                }
                send("PING")
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/network/
git commit -m "feat: implement TCP client with length-prefixed framing, auth, and keepalive"
```

### Task 4: Command Builder

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/network/CommandBuilder.kt`

- [ ] **Step 1: Create CommandBuilder.kt**

```kotlin
package com.milkwave.remote.network

object CommandBuilder {
    fun signal(name: String) = "SIGNAL|$name"
    fun sendKey(hexCode: String) = "SEND=$hexCode"
    fun raw(command: String) = command
    fun message(text: String) = "MSG|text=$text"

    fun wave(params: Map<String, String>): String {
        val parts = params.entries.joinToString("|") { "${it.key}=${it.value}" }
        return "WAVE|$parts"
    }

    fun setMirrorOpacity(value: Int) = "SET_MIRROR_OPACITY=$value"
    fun setMirrorOpacity(display: Int, value: Int) = "SET_MIRROR_OPACITY=$display,$value"
    fun setMirrorClickThru(enabled: Boolean) = "SET_MIRROR_CLICKTHRU=${if (enabled) 1 else 0}"
    fun moveToDisplay(n: Int) = "MOVE_TO_DISPLAY=$n"
    fun diagMirrors() = "DIAG_MIRRORS"
    fun state() = "STATE"

    fun colorHue(value: Float) = "COL_HUE=$value"
    fun colorSaturation(value: Float) = "COL_SATURATION=$value"
    fun colorBrightness(value: Float) = "COL_BRIGHTNESS=$value"
    fun hueAuto(enabled: Boolean) = "HUE_AUTO=${if (enabled) 1 else 0}"

    fun varTime(value: Float) = "VAR_TIME=$value"
    fun varIntensity(value: Float) = "VAR_INTENSITY=$value"
    fun varQuality(value: Float) = "VAR_QUALITY=$value"

    fun amp(left: Float, right: Float) = "AMP|l=$left|r=$right"
    fun fftAttack(value: Float) = "FFT_ATTACK=$value"
    fun fftDecay(value: Float) = "FFT_DECAY=$value"

    fun shaderImport(json: String) = "SHADER_IMPORT=$json"
    fun shaderGlsl(code: String) = "SHADER_GLSL=$code"

    // Media via virtual key codes
    fun mediaPlayPause() = sendKey("0xB3")
    fun mediaNext() = sendKey("0xB0")
    fun mediaPrev() = sendKey("0xB1")
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/network/CommandBuilder.kt
git commit -m "feat: add command builder for all pipe protocol commands"
```

### Task 5: mDNS Discovery

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/network/discovery/MdnsDiscovery.kt`

- [ ] **Step 1: Create MdnsDiscovery.kt**

```kotlin
package com.milkwave.remote.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val version: String = "",
    val pid: String = "",
)

class MdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    fun startDiscovery() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val server = DiscoveredServer(
                            name = info.serviceName,
                            host = info.host.hostAddress ?: return,
                            port = info.port,
                            version = info.attributes["version"]?.decodeToString() ?: "",
                            pid = info.attributes["pid"]?.decodeToString() ?: "",
                        )
                        _servers.value = _servers.value + server
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _servers.value = _servers.value.filter { it.name != serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices("_milkwave._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/network/discovery/
git commit -m "feat: implement mDNS discovery via NsdManager"
```

### Task 6: Connection Manager

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/service/ConnectionManager.kt`

- [ ] **Step 1: Create ConnectionManager.kt**

Manages connection lifecycle, auto-reconnect logic, and state sync:

```kotlin
package com.milkwave.remote.service

import com.milkwave.remote.network.ConnectionState
import com.milkwave.remote.network.TcpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConnectionManager(private val scope: CoroutineScope) {
    val tcpClient = TcpClient(scope)
    private var reconnectJob: Job? = null
    private var lastHost: String? = null
    private var lastPort: Int = 9270
    private var lastPin: String = ""
    private var lastDeviceId: String = ""
    private var lastDeviceName: String = ""

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    val connectionState: StateFlow<ConnectionState> = tcpClient.connectionState
    val messages: SharedFlow<String> = tcpClient.messages

    fun connect(host: String, port: Int, pin: String, deviceId: String, deviceName: String) {
        lastHost = host
        lastPort = port
        lastPin = pin
        lastDeviceId = deviceId
        lastDeviceName = deviceName
        stopReconnect()
        tcpClient.connect(host, port, pin, deviceId, deviceName)
    }

    fun disconnect() {
        stopReconnect()
        tcpClient.disconnect()
        lastHost = null
    }

    fun send(command: String) = tcpClient.send(command)

    fun enableAutoReconnect() {
        scope.launch {
            tcpClient.connectionState.collect { state ->
                if (state == ConnectionState.Disconnected && lastHost != null) {
                    startReconnect()
                } else {
                    stopReconnect()
                }
            }
        }
    }

    fun onResume() {
        if (tcpClient.connectionState.value == ConnectionState.Disconnected && lastHost != null) {
            connect(lastHost!!, lastPort, lastPin, lastDeviceId, lastDeviceName)
        }
    }

    fun onPause() {
        stopReconnect()
    }

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        _isReconnecting.value = true
        reconnectJob = scope.launch {
            while (isActive && tcpClient.connectionState.value == ConnectionState.Disconnected) {
                delay(2000)
                lastHost?.let { host ->
                    tcpClient.connect(host, lastPort, lastPin, lastDeviceId, lastDeviceName)
                    delay(3000) // Wait for connection attempt
                }
            }
            _isReconnecting.value = false
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/service/
git commit -m "feat: add ConnectionManager with auto-reconnect logic"
```

## Chunk 2: Data Layer

### Task 7: Data Models

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/data/model/ButtonConfig.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/model/SavedServer.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/model/DisplayInfo.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/model/VisualizerState.kt`

- [ ] **Step 1: Create ButtonConfig.kt**

```kotlin
package com.milkwave.remote.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ButtonActionType {
    Signal, SendKey, ScriptCommand, LoadPreset, Message, RunScript
}

@Entity(tableName = "buttons")
data class ButtonConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val actionType: ButtonActionType,
    val payload: String,
    val icon: String = "",
    val position: Int = 0,
    val usageCount: Int = 0,
)
```

- [ ] **Step 2: Create SavedServer.kt**

```kotlin
package com.milkwave.remote.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class SavedServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val host: String,
    val port: Int = 9270,
    val pin: String = "",
    val lastConnected: Long = 0,
)
```

- [ ] **Step 3: Create DisplayInfo.kt**

```kotlin
package com.milkwave.remote.data.model

data class DisplayInfo(
    val index: Int,
    val deviceName: String,
    val enabled: Boolean,
    val opacity: Int,
    val clickThrough: Boolean,
    val displayRect: Rect,
    val visible: Boolean,
) {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
}

data class MirrorState(
    val active: Boolean,
    val renderDisplay: String,
    val renderOpacity: Float,
    val renderFullscreen: Boolean,
    val renderClickThrough: Boolean,
    val monitors: List<DisplayInfo>,
)
```

- [ ] **Step 4: Create VisualizerState.kt**

```kotlin
package com.milkwave.remote.data.model

data class VisualizerState(
    val presetName: String = "",
    val opacity: Int = 100,
    val trackArtist: String = "",
    val trackTitle: String = "",
    val trackAlbum: String = "",
    val waveMode: Int = 0,
    val waveAlpha: Float = 1f,
    val waveScale: Float = 1f,
    val waveZoom: Float = 1f,
    val waveWarp: Float = 1f,
    val waveRotation: Float = 0f,
    val waveDecay: Float = 0.98f,
    val waveBrighten: Boolean = false,
    val waveDarken: Boolean = false,
    val waveSolarize: Boolean = false,
    val waveInvert: Boolean = false,
    val waveAdditive: Boolean = false,
    val waveThick: Boolean = false,
    val colorHue: Float = 0f,
    val colorSaturation: Float = 1f,
    val colorBrightness: Float = 1f,
    val hueAuto: Boolean = false,
    val varTime: Float = 1f,
    val varIntensity: Float = 1f,
    val varQuality: Float = 1f,
    val ampLeft: Float = 1f,
    val ampRight: Float = 1f,
    val fftAttack: Float = 0.5f,
    val fftDecay: Float = 0.5f,
)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/data/model/
git commit -m "feat: add data models for buttons, servers, displays, and visualizer state"
```

### Task 8: Room Database & DAOs

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/data/db/ButtonDao.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/db/ServerDao.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/milkwave/remote/data/db/Converters.kt`

- [ ] **Step 1: Create ButtonDao.kt**

```kotlin
package com.milkwave.remote.data.db

import androidx.room.*
import com.milkwave.remote.data.model.ButtonConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ButtonDao {
    @Query("SELECT * FROM buttons ORDER BY position ASC")
    fun getAll(): Flow<List<ButtonConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(button: ButtonConfig): Long

    @Delete
    suspend fun delete(button: ButtonConfig)

    @Query("UPDATE buttons SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Int)

    @Query("SELECT * FROM buttons ORDER BY usageCount DESC LIMIT :limit")
    suspend fun getMostUsed(limit: Int = 10): List<ButtonConfig>
}
```

- [ ] **Step 2: Create ServerDao.kt**

```kotlin
package com.milkwave.remote.data.db

import androidx.room.*
import com.milkwave.remote.data.model.SavedServer
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY lastConnected DESC")
    fun getAll(): Flow<List<SavedServer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: SavedServer): Long

    @Delete
    suspend fun delete(server: SavedServer)

    @Query("UPDATE servers SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Int, timestamp: Long)
}
```

- [ ] **Step 3: Create Converters.kt**

```kotlin
package com.milkwave.remote.data.db

import androidx.room.TypeConverter
import com.milkwave.remote.data.model.ButtonActionType

class Converters {
    @TypeConverter
    fun fromActionType(value: ButtonActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ButtonActionType = ButtonActionType.valueOf(value)
}
```

- [ ] **Step 4: Create AppDatabase.kt**

```kotlin
package com.milkwave.remote.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.milkwave.remote.data.model.ButtonConfig
import com.milkwave.remote.data.model.SavedServer

@Database(entities = [ButtonConfig::class, SavedServer::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun buttonDao(): ButtonDao
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mdr_android.db"
                ).build().also { instance = it }
            }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/data/db/
git commit -m "feat: add Room database with button and server DAOs"
```

### Task 9: Message Parser

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/network/MessageParser.kt`

- [ ] **Step 1: Create MessageParser.kt**

Parses incoming pipe-format messages into VisualizerState updates and MirrorState:

```kotlin
package com.milkwave.remote.network

import com.milkwave.remote.data.model.DisplayInfo
import com.milkwave.remote.data.model.MirrorState
import com.milkwave.remote.data.model.VisualizerState

object MessageParser {
    fun parsePreset(message: String): String? {
        if (!message.startsWith("PRESET=")) return null
        val path = message.removePrefix("PRESET=")
        // Extract filename without path and extension
        return path.substringAfterLast("\\").substringAfterLast("/").substringBeforeLast(".")
    }

    fun parseTrack(message: String): Triple<String, String, String>? {
        if (!message.startsWith("TRACK|")) return null
        val params = parseKeyValue(message.removePrefix("TRACK|"))
        return Triple(
            params["artist"] ?: "",
            params["title"] ?: "",
            params["album"] ?: "",
        )
    }

    fun parseOpacity(message: String): Int? {
        if (!message.startsWith("OPACITY=")) return null
        return message.removePrefix("OPACITY=").toIntOrNull()
    }

    fun parseWave(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("WAVE|")) return null
        val params = parseKeyValue(message.removePrefix("WAVE|"))
        return current.copy(
            waveMode = params["MODE"]?.toIntOrNull() ?: current.waveMode,
            waveAlpha = params["ALPHA"]?.toFloatOrNull() ?: current.waveAlpha,
            waveScale = params["SCALE"]?.toFloatOrNull() ?: current.waveScale,
            waveZoom = params["ZOOM"]?.toFloatOrNull() ?: current.waveZoom,
            waveWarp = params["WARP"]?.toFloatOrNull() ?: current.waveWarp,
            waveRotation = params["ROTATION"]?.toFloatOrNull() ?: current.waveRotation,
            waveDecay = params["DECAY"]?.toFloatOrNull() ?: current.waveDecay,
            waveBrighten = params["BRIGHTEN"]?.let { it == "1" } ?: current.waveBrighten,
            waveDarken = params["DARKEN"]?.let { it == "1" } ?: current.waveDarken,
            waveSolarize = params["SOLARIZE"]?.let { it == "1" } ?: current.waveSolarize,
            waveInvert = params["INVERT"]?.let { it == "1" } ?: current.waveInvert,
            waveAdditive = params["ADDITIVE"]?.let { it == "1" } ?: current.waveAdditive,
            waveThick = params["THICK"]?.let { it == "1" } ?: current.waveThick,
        )
    }

    fun parseSettings(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("SETTINGS|")) return null
        val params = parseKeyValue(message.removePrefix("SETTINGS|"))
        return current.copy(
            colorHue = params["HUE"]?.toFloatOrNull() ?: current.colorHue,
            fftAttack = params["FFTATTACK"]?.toFloatOrNull() ?: current.fftAttack,
            fftDecay = params["FFTDECAY"]?.toFloatOrNull() ?: current.fftDecay,
            varQuality = params["QUALITY"]?.toFloatOrNull() ?: current.varQuality,
        )
    }

    fun parseMirrors(message: String): MirrorState? {
        if (!message.startsWith("MIRRORS|")) return null
        // Split on '|' but handle complex nested values
        val sections = message.removePrefix("MIRRORS|").split("|")
        var active = false
        var renderDisplay = ""
        var renderOpacity = 1f
        var renderFs = false
        var renderClickThru = false
        val monitors = mutableListOf<DisplayInfo>()

        for (section in sections) {
            when {
                section.startsWith("active=") -> {
                    active = section.removePrefix("active=") != "0"
                }
                section.startsWith("render_on=") -> {
                    val parts = parseCommaSeparated(section.removePrefix("render_on="))
                    renderDisplay = parts.firstOrNull() ?: ""
                    renderOpacity = parts.findValue("opacity")?.toFloatOrNull() ?: 1f
                    renderFs = parts.findValue("fs") == "1"
                    renderClickThru = parts.findValue("clickthru") == "1"
                }
                section.startsWith("mon") -> {
                    val idx = section.substringBefore("=").removePrefix("mon").toIntOrNull() ?: continue
                    val parts = parseCommaSeparated(section.substringAfter("="))
                    val deviceName = parts.firstOrNull() ?: ""
                    monitors.add(DisplayInfo(
                        index = idx,
                        deviceName = deviceName,
                        enabled = parts.findValue("enabled") == "1",
                        opacity = parts.findValue("opacity")?.toIntOrNull() ?: 100,
                        clickThrough = parts.findValue("clickthru") == "1",
                        displayRect = parseRect(parts.findValue("display") ?: ""),
                        visible = parts.findValue("visible") == "1",
                    ))
                }
            }
        }

        return MirrorState(active, renderDisplay, renderOpacity, renderFs, renderClickThru, monitors)
    }

    private fun parseKeyValue(s: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in s.split("|")) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                map[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        return map
    }

    /**
     * Smart comma splitter that respects parenthesized groups.
     * e.g., "\\.\DISPLAY1,renderwin=(0,0)-(1920,1080) 1920x1080,opacity=0.95"
     * splits to: ["\\.\DISPLAY1", "renderwin=(0,0)-(1920,1080) 1920x1080", "opacity=0.95"]
     */
    private fun parseCommaSeparated(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(s.substring(start))
        return result
    }

    private fun List<String>.findValue(key: String): String? {
        return firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
    }

    private fun parseRect(s: String): DisplayInfo.Rect {
        // Format: (x,y)-(x2,y2) WxH
        val match = Regex("""\((-?\d+),(-?\d+)\)-\((-?\d+),(-?\d+)\)\s+(\d+)x(\d+)""").find(s)
        return if (match != null) {
            val (x, y, _, _, w, h) = match.destructured
            DisplayInfo.Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        } else {
            DisplayInfo.Rect(0, 0, 0, 0)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/network/MessageParser.kt
git commit -m "feat: add message parser for all pipe protocol response formats"
```

## Chunk 3: ViewModels

### Task 10: Remote ViewModel

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/viewmodel/RemoteViewModel.kt`

- [ ] **Step 1: Create RemoteViewModel.kt**

Manages visualizer state, processes incoming messages, sends commands with throttling:

```kotlin
package com.milkwave.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milkwave.remote.data.model.VisualizerState
import com.milkwave.remote.network.CommandBuilder
import com.milkwave.remote.network.MessageParser
import com.milkwave.remote.service.ConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    val connectionManager = (application as MdrApp).connectionManager

    private val _state = MutableStateFlow(VisualizerState())
    val state: StateFlow<VisualizerState> = _state

    private var waveThrottleJob: Job? = null
    private val pendingWaveParams = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                processMessage(msg)
            }
        }
        connectionManager.enableAutoReconnect()
    }

    private fun processMessage(msg: String) {
        when {
            msg.startsWith("PRESET=") -> {
                MessageParser.parsePreset(msg)?.let { name ->
                    _state.update { it.copy(presetName = name) }
                }
            }
            msg.startsWith("TRACK|") -> {
                MessageParser.parseTrack(msg)?.let { (artist, title, album) ->
                    _state.update { it.copy(trackArtist = artist, trackTitle = title, trackAlbum = album) }
                }
            }
            msg.startsWith("OPACITY=") -> {
                MessageParser.parseOpacity(msg)?.let { opacity ->
                    _state.update { it.copy(opacity = opacity) }
                }
            }
            msg.startsWith("WAVE|") -> {
                MessageParser.parseWave(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("SETTINGS|") -> {
                MessageParser.parseSettings(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            // Pass through MIRRORS, MIRROR_OPACITY, SHADER results to other flows
            msg.startsWith("MIRRORS|") ||
            msg.startsWith("MIRROR_OPACITY=") ||
            msg.startsWith("SHADER_IMPORT_RESULT=") ||
            msg.startsWith("SHADER_GLSL_RESULT=") -> {
                // Already emitted to messages flow — ViewModels collect directly
            }
        }
    }

    fun sendSignal(name: String) = connectionManager.send(CommandBuilder.signal(name))
    fun sendKey(hex: String) = connectionManager.send(CommandBuilder.sendKey(hex))
    fun sendMessage(text: String) = connectionManager.send(CommandBuilder.message(text))
    fun sendRaw(command: String) = connectionManager.send(CommandBuilder.raw(command))
    fun mediaPlayPause() = connectionManager.send(CommandBuilder.mediaPlayPause())
    fun mediaNext() = connectionManager.send(CommandBuilder.mediaNext())
    fun mediaPrev() = connectionManager.send(CommandBuilder.mediaPrev())

    // Throttled wave parameter update
    fun updateWaveParam(key: String, value: String) {
        synchronized(pendingWaveParams) {
            pendingWaveParams[key] = value
        }
        if (waveThrottleJob?.isActive != true) {
            waveThrottleJob = viewModelScope.launch {
                delay(50)
                val params: Map<String, String>
                synchronized(pendingWaveParams) {
                    params = pendingWaveParams.toMap()
                    pendingWaveParams.clear()
                }
                if (params.isNotEmpty()) {
                    connectionManager.send(CommandBuilder.wave(params))
                }
            }
        }
    }

    fun updateColor(hue: Float? = null, saturation: Float? = null, brightness: Float? = null) {
        hue?.let { connectionManager.send(CommandBuilder.colorHue(it)) }
        saturation?.let { connectionManager.send(CommandBuilder.colorSaturation(it)) }
        brightness?.let { connectionManager.send(CommandBuilder.colorBrightness(it)) }
    }

    fun updateVariable(time: Float? = null, intensity: Float? = null, quality: Float? = null) {
        time?.let { connectionManager.send(CommandBuilder.varTime(it)) }
        intensity?.let { connectionManager.send(CommandBuilder.varIntensity(it)) }
        quality?.let { connectionManager.send(CommandBuilder.varQuality(it)) }
    }

    fun updateAmp(left: Float, right: Float) = connectionManager.send(CommandBuilder.amp(left, right))
    fun updateFftAttack(value: Float) = connectionManager.send(CommandBuilder.fftAttack(value))
    fun updateFftDecay(value: Float) = connectionManager.send(CommandBuilder.fftDecay(value))
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/viewmodel/RemoteViewModel.kt
git commit -m "feat: add RemoteViewModel with state sync, throttled wave params, media controls"
```

### Task 11: Displays ViewModel

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/viewmodel/DisplaysViewModel.kt`

- [ ] **Step 1: Create DisplaysViewModel.kt**

```kotlin
package com.milkwave.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milkwave.remote.MdrApp
import com.milkwave.remote.data.model.MirrorState
import com.milkwave.remote.network.CommandBuilder
import com.milkwave.remote.network.MessageParser
import com.milkwave.remote.service.ConnectionManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DisplaysViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager
    private val _mirrorState = MutableStateFlow<MirrorState?>(null)
    val mirrorState: StateFlow<MirrorState?> = _mirrorState

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                when {
                    msg.startsWith("MIRRORS|") -> {
                        _mirrorState.value = MessageParser.parseMirrors(msg)
                    }
                    msg.startsWith("MIRROR_OPACITY=") -> {
                        // Refresh full state after opacity change confirmation
                        refresh()
                    }
                }
            }
        }
    }

    fun refresh() = connectionManager.send(CommandBuilder.diagMirrors())

    fun setGlobalOpacity(value: Int) {
        connectionManager.send(CommandBuilder.setMirrorOpacity(value))
    }

    fun setDisplayOpacity(display: Int, value: Int) {
        connectionManager.send(CommandBuilder.setMirrorOpacity(display, value))
    }

    fun setClickThrough(enabled: Boolean) {
        connectionManager.send(CommandBuilder.setMirrorClickThru(enabled))
    }

    fun moveToDisplay(n: Int) {
        connectionManager.send(CommandBuilder.moveToDisplay(n))
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/viewmodel/DisplaysViewModel.kt
git commit -m "feat: add DisplaysViewModel with mirror state management"
```

### Task 12: Buttons ViewModel

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/viewmodel/ButtonsViewModel.kt`

- [ ] **Step 1: Create ButtonsViewModel.kt**

```kotlin
package com.milkwave.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milkwave.remote.data.db.AppDatabase
import com.milkwave.remote.data.model.ButtonActionType
import com.milkwave.remote.data.model.ButtonConfig
import com.milkwave.remote.MdrApp
import com.milkwave.remote.network.CommandBuilder
import com.milkwave.remote.service.ConnectionManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ButtonsViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager
    private val dao = AppDatabase.getInstance(application).buttonDao()

    // Shader upload results — UI observes this for toast/snackbar
    private val _shaderResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shaderResult: SharedFlow<String> = _shaderResult

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg ->
                when {
                    msg.startsWith("SHADER_IMPORT_RESULT=") ||
                    msg.startsWith("SHADER_GLSL_RESULT=") -> {
                        _shaderResult.emit(msg)
                    }
                }
            }
        }
    }

    val buttons: StateFlow<List<ButtonConfig>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun executeButton(button: ButtonConfig) {
        viewModelScope.launch { dao.incrementUsage(button.id) }
        when (button.actionType) {
            ButtonActionType.Signal -> connectionManager.send(CommandBuilder.signal(button.payload))
            ButtonActionType.SendKey -> connectionManager.send(CommandBuilder.sendKey(button.payload))
            ButtonActionType.ScriptCommand -> {
                // Send as a single pipe-delimited message (matches ExecuteScriptLine behavior)
                connectionManager.send(button.payload)
            }
            ButtonActionType.LoadPreset -> connectionManager.send("PRESET=${button.payload}")
            ButtonActionType.Message -> connectionManager.send(button.payload) // Already MSG|text=...
            ButtonActionType.RunScript -> {
                // Payload is the script content (stored from file upload)
                connectionManager.send(CommandBuilder.shaderGlsl(button.payload))
            }
        }
    }

    fun saveButton(button: ButtonConfig) {
        viewModelScope.launch { dao.upsert(button) }
    }

    fun deleteButton(button: ButtonConfig) {
        viewModelScope.launch { dao.delete(button) }
    }

    fun exportToJson(buttons: List<ButtonConfig>): String {
        val json = JSONObject()
        json.put("version", 1)
        val arr = JSONArray()
        buttons.forEach { b ->
            arr.put(JSONObject().apply {
                put("label", b.label)
                put("actionType", b.actionType.name)
                put("payload", b.payload)
                put("icon", b.icon)
                put("position", b.position)
            })
        }
        json.put("buttons", arr)
        return json.toString(2)
    }

    fun importFromJson(json: String) {
        viewModelScope.launch {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("buttons")
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                dao.upsert(ButtonConfig(
                    label = b.getString("label"),
                    actionType = ButtonActionType.valueOf(b.getString("actionType")),
                    payload = b.getString("payload"),
                    icon = b.optString("icon", ""),
                    position = b.optInt("position", i),
                ))
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/viewmodel/ButtonsViewModel.kt
git commit -m "feat: add ButtonsViewModel with execute, CRUD, import/export"
```

### Task 13: Settings ViewModel

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/viewmodel/SettingsViewModel.kt`

- [ ] **Step 1: Create SettingsViewModel.kt**

```kotlin
package com.milkwave.remote.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.milkwave.remote.data.db.AppDatabase
import com.milkwave.remote.data.model.SavedServer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private val android.content.Context.dataStore by preferencesDataStore("settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.dataStore
    private val serverDao = AppDatabase.getInstance(application).serverDao()

    val savedServers: StateFlow<List<SavedServer>> = serverDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_PIN = stringPreferencesKey("pin")
        val KEY_NAV_MODE = stringPreferencesKey("nav_mode") // "tabs" or "drawer"
        val KEY_LAST_HOST = stringPreferencesKey("last_host")
        val KEY_LAST_PORT = intPreferencesKey("last_port")
    }

    val deviceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: run {
            val id = try {
                Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                UUID.randomUUID().toString().replace("-", "").take(16)
            }
            // Save it
            viewModelScope.launch {
                dataStore.edit { it[KEY_DEVICE_ID] = id }
            }
            id
        }
    }

    val deviceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: Build.MODEL
    }

    val pin: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PIN] ?: ""
    }

    val navMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_NAV_MODE] ?: "tabs"
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_DEVICE_NAME] = name } }
    }

    fun setPin(pin: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_PIN] = pin } }
    }

    fun setNavMode(mode: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_NAV_MODE] = mode } }
    }

    fun saveServer(server: SavedServer) {
        viewModelScope.launch { serverDao.upsert(server) }
    }

    fun deleteServer(server: SavedServer) {
        viewModelScope.launch { serverDao.delete(server) }
    }

    fun saveLastConnection(host: String, port: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[KEY_LAST_HOST] = host
                it[KEY_LAST_PORT] = port
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/viewmodel/SettingsViewModel.kt
git commit -m "feat: add SettingsViewModel with DataStore prefs and server management"
```

## Chunk 4: UI Screens

### Task 14: Shared UI Components

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/components/ConnectionHeader.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/components/MediaBar.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/components/CollapsibleSection.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/components/SliderControl.kt`

- [ ] **Step 1: Create ConnectionHeader.kt**

Composable showing connection dot, server name, and current preset. Shows "Reconnecting..." when auto-reconnecting.

- [ ] **Step 2: Create MediaBar.kt**

Composable with track title, artist, and prev/play-pause/next buttons.

- [ ] **Step 3: Create CollapsibleSection.kt**

Reusable composable for expandable/collapsible sections with header tap-to-toggle.

- [ ] **Step 4: Create SliderControl.kt**

Reusable slider with label, current value display, and throttled onChange callback.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/components/
git commit -m "feat: add shared UI components — header, media bar, collapsible section, slider"
```

### Task 15: Remote Screen

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/RemoteScreen.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/TransportControls.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/QuickButtons.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/WaveControls.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/ColorControls.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/remote/AudioControls.kt`

- [ ] **Step 1: Create TransportControls.kt**

Grid with Prev/Next Preset (large) and mode buttons (Fullscreen, Mirror, Mirror WM, Watermark, Capture, Borderless).

- [ ] **Step 2: Create QuickButtons.kt**

4-column grid of user-assignable buttons. Long-press opens edit dialog. "+" button at end.

- [ ] **Step 3: Create WaveControls.kt**

Collapsible section with Mode, Alpha, Scale, Zoom, Warp, Rotation, Decay sliders and toggle checkboxes.

- [ ] **Step 4: Create ColorControls.kt**

Collapsible section with Hue, Saturation, Brightness sliders and Auto Hue toggle.

- [ ] **Step 5: Create AudioControls.kt**

Collapsible section with Amp L/R, FFT Attack/Decay sliders. Variables (Time, Intensity, Quality) in same or separate section.

- [ ] **Step 6: Create RemoteScreen.kt**

Composes all sub-components in a scrollable column. Includes message and raw command inputs at bottom.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/screens/remote/
git commit -m "feat: add Remote screen with transport, quick buttons, wave/color/audio controls"
```

### Task 16: Displays Screen

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/displays/DisplaysScreen.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/displays/MonitorMap.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/displays/OpacityControls.kt`

- [ ] **Step 1: Create OpacityControls.kt**

Global opacity slider, click-through toggle, move-to-display dropdown.

- [ ] **Step 2: Create MonitorMap.kt**

Canvas-based composable that renders proportional monitor layout from DIAG_MIRRORS rects. Tap to select a monitor. Selected monitor shows per-display opacity slider.

- [ ] **Step 3: Create DisplaysScreen.kt**

Composes global controls at top, advanced expandable section with MonitorMap below. Queries DIAG_MIRRORS on appear.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/screens/displays/
git commit -m "feat: add Displays screen with global controls and visual monitor map"
```

### Task 17: Buttons Screen

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/buttons/ButtonsScreen.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/buttons/ButtonEditor.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/buttons/ScriptUploader.kt`

- [ ] **Step 1: Create ButtonEditor.kt**

Bottom sheet or dialog with: action type dropdown, label field, payload field (context-sensitive), test button, save/cancel.

- [ ] **Step 2: Create ScriptUploader.kt**

Uses `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` to pick files. Reads content, sends via SHADER_IMPORT or SHADER_GLSL. Shows result toast.

- [ ] **Step 3: Create ButtonsScreen.kt**

Grid of configured buttons. Long-press opens ButtonEditor. "+" to add. Import/export via share sheet. Upload script button.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/screens/buttons/
git commit -m "feat: add Buttons screen with editor, script upload, import/export"
```

### Task 18: Settings Screen

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Create SettingsScreen.kt**

Sections:
- Connection: discovered servers list (from mDNS), saved servers, manual IP/port entry, PIN field
- Device: device name (editable), device ID (read-only)
- Navigation: tabs vs drawer toggle
- About: version, license info

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/screens/settings/
git commit -m "feat: add Settings screen with connection, device, and nav mode options"
```

## Chunk 5: Navigation & Integration

### Task 19: Navigation Setup

**Files:**
- Create: `app/src/main/java/com/milkwave/remote/ui/navigation/AppNavigation.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/navigation/BottomNavBar.kt`
- Create: `app/src/main/java/com/milkwave/remote/ui/navigation/DrawerNav.kt`

- [ ] **Step 1: Create navigation route enum and BottomNavBar.kt**

```kotlin
enum class NavRoute(val label: String, val icon: String) {
    Remote("Remote", "tune"),
    Displays("Displays", "monitor"),
    Buttons("Buttons", "bolt"),
    Settings("Settings", "settings"),
}
```

Bottom nav bar with 4 items using Material 3 NavigationBar.

- [ ] **Step 2: Create DrawerNav.kt**

Modal navigation drawer with same 4 destinations.

- [ ] **Step 3: Create AppNavigation.kt**

NavHost with 4 routes. Reads nav mode preference to switch between BottomNavBar and DrawerNav. Persistent header (ConnectionHeader + MediaBar) above NavHost.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/ui/navigation/
git commit -m "feat: add navigation with bottom tabs and drawer mode switching"
```

### Task 20: Wire Up MainActivity

**Files:**
- Modify: `app/src/main/java/com/milkwave/remote/MainActivity.kt`

- [ ] **Step 1: Update MainActivity with full app composition**

```kotlin
package com.milkwave.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.milkwave.remote.ui.navigation.AppNavigation
import com.milkwave.remote.ui.theme.MdrTheme
import com.milkwave.remote.viewmodel.RemoteViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var remoteViewModel: RemoteViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MdrTheme {
                AppNavigation()
            }
        }
    }
}
```

- [ ] **Step 2: Handle lifecycle for auto-reconnect**

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        remoteViewModel.connectionManager.onResume()
    }
}
// In onStop or via lifecycle observer:
// remoteViewModel.connectionManager.onPause()
```

- [ ] **Step 3: Verify full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/milkwave/remote/MainActivity.kt
git commit -m "feat: wire up MainActivity with navigation, theme, and lifecycle management"
```

### Task 21: Proguard Rules

**Files:**
- Create: `app/proguard-rules.pro`

- [ ] **Step 1: Add proguard rules for Room and DataStore**

```
# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# DataStore
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
```

- [ ] **Step 2: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "feat: add proguard rules for Room and DataStore"
```

### Task 22: Gradle Wrapper & Build Verification

- [ ] **Step 1: Initialize Gradle wrapper if missing**

Run: `gradle wrapper --gradle-version 8.11`

- [ ] **Step 2: Full clean build**

Run: `./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any remaining files**

```bash
git add -A
git commit -m "chore: finalize build config and verify clean build"
```
