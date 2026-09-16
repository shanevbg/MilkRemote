# MDR Android — Developer Guide

MDR Android is a remote control app for MDropDX12 (MilkDrop visualizer). It communicates over TCP using a length-prefixed UTF-8 protocol on port 9270.

## Platform Setup

Follow the guide for your OS before continuing:

- [Ubuntu 24.04 LTS](Development_ubuntu.md)
- [Fedora 43](Development_fedora.md)
- [Windows 11](Development_windows.md)

All three cover JDK 17, Android SDK, ADB, building, and deploying.

## Opening the Project in VSCodium

Install the **Kotlin** extension (fwcd.kotlin) from Open VSX. For full language support including autocomplete and go-to-definition:

1. Open VSCodium
2. **Extensions** (Ctrl+Shift+X) → search `Kotlin` → install **Kotlin** by fwcd
3. **File → Open Folder** → select the `MDR_Android` root directory
4. Wait for the Kotlin language server to index (first time takes a minute)

Optional but recommended extensions:
- **Gradle for Java** (vscjava.vscode-gradle) — run build tasks from the sidebar
- **XML** (redhat.vscode-xml) — for AndroidManifest and layout resources
- **EditorConfig** — respects `.editorconfig` if present

### Building from VSCodium

Open the integrated terminal (Ctrl+\`) and run:

```bash
./gradlew installDebug      # build + deploy to connected device
```

Or use the Gradle sidebar if the Gradle extension is installed.

## Architecture Overview

```
UI (Compose)  →  ViewModel  →  ConnectionManager  →  TcpClient  →  MDropDX12
                                                   ←  messages  ←
```

The app follows MVVM with Kotlin coroutines and StateFlow for reactive state.

### Package Structure

```
com.sheinsez.mdropdx12.remote/
├── MainActivity.kt              App entry point
├── MdrApp.kt                    Application class, owns ConnectionManager
│
├── data/
│   ├── model/
│   │   ├── VisualizerState.kt   Main UI state (preset, wave, color, audio)
│   │   ├── ButtonConfig.kt      Custom button definition (Room entity)
│   │   ├── DisplayInfo.kt       Monitor info from server
│   │   ├── MirrorState.kt       Mirror/display state
│   │   └── SavedServer.kt       Bookmarked servers (Room entity)
│   ├── db/
│   │   ├── AppDatabase.kt       Room database (buttons + servers)
│   │   ├── ButtonDao.kt         Button CRUD queries
│   │   └── ServerDao.kt         Server CRUD queries
│   └── DataStoreExt.kt          Shared DataStore preferences accessor
│
├── network/
│   ├── TcpClient.kt             Socket connection, AUTH, PING/PONG
│   ├── MilkwaveProtocol.kt      Frame encoding: [4-byte LE length][UTF-8]
│   ├── CommandBuilder.kt        Builds command strings from parameters
│   ├── MessageParser.kt         Parses server responses into typed data
│   └── discovery/
│       └── MdnsDiscovery.kt     mDNS + UDP beacon server discovery
│
├── service/
│   └── ConnectionManager.kt     Connection lifecycle, auto-reconnect
│
├── viewmodel/
│   ├── RemoteViewModel.kt       Visualizer controls + state
│   ├── DisplaysViewModel.kt     Mirror/display controls
│   ├── ButtonsViewModel.kt      Custom button execution + DB
│   └── SettingsViewModel.kt     Preferences, saved servers
│
└── ui/
    ├── navigation/
    │   └── AppNavigation.kt     4-tab bottom nav (Remote, Displays, Buttons, Settings)
    ├── components/
    │   ├── SliderControl.kt     Reusable labeled slider
    │   ├── CollapsibleSection.kt Expandable section
    │   ├── ConnectionHeader.kt  Top bar: connection state + preset name
    │   └── MediaBar.kt          Track info + media playback buttons
    └── screens/
        ├── remote/RemoteScreen.kt     Wave, color, audio, FFT sliders
        ├── displays/DisplaysScreen.kt Monitor opacity, click-through
        ├── buttons/ButtonsScreen.kt   Custom button grid
        └── settings/SettingsScreen.kt Server connection, device prefs
```

### Command Flow

When the user interacts with a control:

1. **Composable** calls a ViewModel method (e.g. `vm.updateColor(hue = 180f)`)
2. **ViewModel** calls `connectionManager.send(CommandBuilder.colorHue(180f))`
3. **CommandBuilder** returns the command string: `"COL_HUE=180.0"`
4. **ConnectionManager** passes it to `tcpClient.send()`
5. **TcpClient** wraps it in a length-prefixed frame via `MilkwaveProtocol.encode()` and writes to the socket

When the server responds:

1. **TcpClient.readLoop()** decodes frames and emits strings to `messages: SharedFlow`
2. **ViewModel** collects messages and calls **MessageParser** methods
3. **MessageParser** returns typed data (String, Float, Triple, etc.)
4. **ViewModel** updates `_state` via `.copy()`
5. **Composable** recomposes automatically via `state.collectAsState()`

### TCP Protocol

All messages use the same binary framing:

```
[4 bytes: payload length, little-endian uint32][N bytes: UTF-8 text]
```

Commands are pipe-delimited text. Examples:

| Command | Format |
|---|---|
| Signal | `SIGNAL\|FULLSCREEN` |
| Set value | `COL_HUE=180.0` |
| Key-value pairs | `WAVE\|MODE=2\|ALPHA=0.8` |
| Media key | `SEND=0xB3` |
| Load preset | `PRESET=C:\Presets\example.milk` |

See `CommandBuilder.kt` for the full command list and `MessageParser.kt` for all response formats.

## How to Add a New Control

This walkthrough adds a hypothetical "Zoom Rotation" slider. The same pattern applies to toggles, buttons, or any new control.

### Step 1: Add state field

In `data/model/VisualizerState.kt`, add the field with a default:

```kotlin
data class VisualizerState(
    // ... existing fields ...
    val zoomRotation: Float = 0f,
)
```

### Step 2: Add command builder

In `network/CommandBuilder.kt`:

```kotlin
fun zoomRotation(value: Float) = "ZOOM_ROTATION=$value"
```

The command string must match what MDropDX12's `LaunchMessage()` expects. Check `engine_messages.cpp` in the MDropDX12 project to confirm the server-side command name.

### Step 3: Add response parser

In `network/MessageParser.kt`, add a parse method if the server sends this value back:

```kotlin
fun parseZoomRotation(msg: String): Float? {
    if (!msg.startsWith("ZOOM_ROTATION=")) return null
    return msg.removePrefix("ZOOM_ROTATION=").toFloatOrNull()
}
```

### Step 4: Wire up the ViewModel

In `viewmodel/RemoteViewModel.kt`:

Add the outbound control method:

```kotlin
fun updateZoomRotation(value: Float) =
    connectionManager.send(CommandBuilder.zoomRotation(value))
```

Add inbound message handling in `processMessage()`:

```kotlin
msg.startsWith("ZOOM_ROTATION=") -> {
    MessageParser.parseZoomRotation(msg)?.let { v ->
        _state.update { it.copy(zoomRotation = v) }
    }
}
```

### Step 5: Add the UI control

In `ui/screens/remote/RemoteScreen.kt`, add inside an appropriate section:

```kotlin
SliderControl(
    label = "Zoom Rotation",
    value = state.zoomRotation,
    range = -360f..360f,
    onValueChange = { vm.updateZoomRotation(it) },
    valueFormat = { "%.0f\u00B0".format(it) },
)
```

### Summary of files changed

| File | Change |
|---|---|
| `data/model/VisualizerState.kt` | Add field |
| `network/CommandBuilder.kt` | Add builder method |
| `network/MessageParser.kt` | Add parser (if server echoes value) |
| `viewmodel/RemoteViewModel.kt` | Add control method + message handler |
| `ui/screens/remote/RemoteScreen.kt` | Add composable control |

## How to Add a New Screen

1. Create `ui/screens/myscreen/MyScreen.kt` with a `@Composable fun MyScreen()`
2. Add a route to `NavRoute` enum in `ui/navigation/AppNavigation.kt`:
   ```kotlin
   MyScreen("My Screen", Icons.Default.Star),
   ```
3. Add the composable to the `NavHost` block:
   ```kotlin
   composable(NavRoute.MyScreen.name) { MyScreen() }
   ```
4. Create a ViewModel in `viewmodel/` if the screen needs its own state

## How to Add a New Button Action Type

The custom button system supports user-defined buttons with different action types.

1. Add the new type to `ButtonActionType` enum in `data/model/ButtonConfig.kt`:
   ```kotlin
   enum class ButtonActionType {
       Signal, SendKey, ScriptCommand, LoadPreset, Message, RunScript,
       MyNewAction,  // new
   }
   ```
2. Handle it in `ButtonsViewModel.executeButton()`:
   ```kotlin
   ButtonActionType.MyNewAction -> send(CommandBuilder.myNewCommand(button.payload))
   ```
3. Add it to the action type dropdown in `ButtonsScreen.kt`'s edit dialog

## How to Change Connection Behaviour

### Auto-reconnect

`ConnectionManager.enableAutoReconnect()` starts a coroutine that retries every 2 seconds on disconnect. After 3 consecutive failures it triggers mDNS re-discovery to find the server on a new IP/port.

To change retry interval or failure threshold, edit `ConnectionManager.startReconnect()`.

### Auto-connect on launch

`RemoteViewModel.tryAutoConnect()` reads the last-used host/port from DataStore preferences on init and connects automatically. The connection is saved by `SettingsViewModel.saveLastConnection()` each time the user connects.

### Server discovery

`MdnsDiscovery` uses two mechanisms:
- **Android NSD** (mDNS-SD) browsing for `_milkwave._tcp` services
- **UDP beacon** listener on port 9271 (format: `MDROP_BEACON|<name>|<tcpPort>|<pid>`)

The UDP beacon is a fallback for networks where mDNS is unreliable.

### Adding a new command to the server

If you add a new command handler in MDropDX12's `engine_messages.cpp` (`LaunchMessage` function), the Android app can send it immediately via `CommandBuilder.raw("MY_NEW_COMMAND=value")` or by adding a proper builder method.

For `SIGNAL|`-prefixed commands, the signal name must match an entry in `pipe_server.cpp`'s `s_signalTable[]`. The TCP server routes `SIGNAL|` commands through `PipeServer::DispatchSignal()`.

## Debugging

### ADB logcat

Filter to the app's tag:

```bash
adb logcat -s "mdropdx12"
```

Or show all logs from the app process:

```bash
adb logcat --pid=$(adb shell pidof com.sheinsez.mdropdx12.remote)
```

### Inspecting TCP traffic

On the device (requires root or debug build):

```bash
adb shell tcpdump -i any -s 0 -w /sdcard/capture.pcap port 9270
adb pull /sdcard/capture.pcap
```

Open in Wireshark to inspect the length-prefixed frames.

### Common issues

| Symptom | Cause | Fix |
|---|---|---|
| Connects but commands do nothing | Signal names don't match server | Check `s_signalTable` in `pipe_server.cpp` |
| State not syncing | Server not sending response for that command | Add broadcast in `LaunchMessage()` |
| Slider resets to default | No parser for the server response | Add handler in `processMessage()` |
| Auto-connect fails | DataStore has no saved host | Connect manually once first |
