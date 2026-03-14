# MDR Android — Companion App Design

## Overview

A Kotlin Android companion app for MDropDX12 that connects directly via TCP using the native pipe-format protocol. No dependency on MWR_Web. Features auto-reconnect, display management, user-assignable buttons with script support, and mDNS discovery.

## Connection Architecture

### Transport

Direct TCP socket to MDropDX12. A thin TCP listener (~100-150 lines C++) is added to MDropDX12 that accepts connections and feeds commands into the existing `ProcessIPCMessage()` handler. No WebSocket wrapper, no JSON encoding — raw pipe-format strings over TCP.

```
Android App  —TCP→  MDropDX12 TCP Listener  →  ProcessIPCMessage()
```

Messages use the same format as named pipes:
- `SIGNAL|NEXT_PRESET`
- `SET_MIRROR_OPACITY=50`
- `WAVE|MODE=1|ALPHA=0.5`
- `SHADER_IMPORT=<json>`

### Wire Encoding & Framing

The TCP listener uses **length-prefixed binary framing** to handle multi-line payloads (SHADER_GLSL, SHADER_IMPORT contain embedded newlines):

```
[4-byte little-endian uint32: payload length][UTF-8 payload bytes]
```

The TCP listener converts between UTF-8 (wire format) and UTF-16 (internal `wchar_t` used by `ProcessIPCMessage()`). Conversion is performed at the TCP boundary:
- **Inbound**: UTF-8 → UTF-16 via `MultiByteToWideChar(CP_UTF8, ...)`
- **Outbound**: UTF-16 → UTF-8 via `WideCharToMultiByte(CP_UTF8, ...)`

This preserves full Unicode support for preset paths, track metadata, and script content containing non-ASCII characters.

### Discovery

mDNS/Zeroconf via Android NsdManager. MDropDX12 advertises `_milkwave._tcp` on the LAN with:
- Service name: `MDropDX12-<hostname>`
- Port: TCP listener port
- TXT records: `version=<N>`, `pid=<PID>`

Fallback: manual IP/port entry in Settings with saved server list.

### Authentication

Two-layer auth: PIN verification + device authorization. TCP-only (named pipes are local and secured by Windows SDDL).

**Connection flow:**
1. Client connects, sends `AUTH|<pin>|<device_id>|<device_name>`
   - `device_id`: stable unique identifier (Android `Settings.Secure.ANDROID_ID`)
   - `device_name`: human-readable name (e.g., "Shane's Pixel 8")
2. Server checks PIN hash
3. If PIN wrong → `AUTH_FAIL|BAD_PIN`
4. If PIN correct, check device authorization:
   - **Device already authorized** → `AUTH_OK`
   - **Device unknown** → `AUTH_PENDING` — visualizer shows a toast/dialog on PC: _"New device 'Shane's Pixel 8' wants to connect. Allow?"_ with Allow/Deny buttons
   - User clicks Allow → server sends `AUTH_OK`, saves device_id to authorized list
   - User clicks Deny → server sends `AUTH_FAIL|DENIED`
5. If no PIN configured: skip PIN check, still require device authorization on first connect

**Server-side state:**
- Per-connection: auth state (unauthenticated / pending / authenticated)
- Unauthenticated connections can only send `AUTH|...`; all other commands silently dropped
- Authorized devices persisted in MDropDX12 settings INI:
```ini
[AuthorizedDevices]
count=2
device0_id=a1b2c3d4e5f6
device0_name=Shane's Pixel 8
device0_added=2026-03-14
device1_id=f6e5d4c3b2a1
device1_name=Living Room Tablet
device1_added=2026-03-10
```

**Device management (MDropDX12 UI):**
- Settings or Displays window shows list of authorized devices
- Each entry: device name, date added, "Remove" button
- Removing a device forces disconnect if currently connected

**New IPC commands for device management:**
| Command | Response | Description |
|---------|----------|-------------|
| `AUTH\|<pin>\|<device_id>\|<device_name>` | `AUTH_OK`, `AUTH_PENDING`, or `AUTH_FAIL\|<reason>` | Authenticate and authorize |
| `DEAUTH_DEVICE\|<device_id>` | `DEAUTH_OK` | Revoke device authorization (from local pipe/UI only) |
| `LIST_DEVICES` | `DEVICES\|id=...,name=...,added=...\|id=...` | List authorized devices (from local pipe/UI only) |

**Android-side:**
- App generates device_id on first launch, stores in DataStore
- Device name defaults to `Build.MODEL`, user can customize in Settings
- AUTH_PENDING state shows "Waiting for approval on PC..." with a spinner
- Remembers last successful server (no re-auth needed on reconnect if device still authorized)

### Keepalive

Application-level heartbeat to detect stale TCP connections:
- Client sends `PING` every 15 seconds
- Server responds `PONG`
- If no `PONG` received within 5 seconds, connection is considered dead → trigger reconnect
- Server drops connections that send no data for 60 seconds

### Auto-Reconnect (Smart)

- **Foreground**: Auto-retry every 2 seconds with subtle "Reconnecting..." indicator
- **Background**: Stop retrying, remember last connection
- **App resume**: Reconnect immediately to last known server, send `STATE` to sync UI
- No battery drain from background polling

## Navigation

### Default: Bottom Navigation Bar (4 tabs)

| Tab | Icon | Purpose |
|-----|------|---------|
| Remote | 🎛 | Transport controls, mode buttons, quick buttons, sliders |
| Displays | 🖥 | Mirror/opacity management |
| Buttons | ⚡ | User-assignable button editor |
| Settings | ⚙ | Connection, auth, preferences |

### Alternative: Drawer Mode

Available in Settings. Main screen is Remote; side drawer provides access to Displays, Buttons, Settings.

### Persistent Header (all tabs)

- Connection status dot + server name/IP
- Current preset name
- Media player bar: track title, artist, prev/play-pause/next
  - Media controls use virtual key codes: `SEND=0xB3` (play/pause), `SEND=0xB0` (next), `SEND=0xB1` (prev)

## Tab Details

### Remote Tab

**Transport Controls:**
- Prev/Next Preset (large buttons, top of page)
- Mode button grid (3 columns):

| Label | Signal Name |
|-------|------------|
| Fullscreen | `SIGNAL\|FULLSCREEN` |
| Mirror | `SIGNAL\|MIRROR` |
| Mirror WM | `SIGNAL\|MIRROR_WM` |
| Watermark | `SIGNAL\|WATERMARK` |
| Capture | `SIGNAL\|CAPTURE` |
| Borderless | `SIGNAL\|BORDERLESS_FS` |

**Quick Buttons:**
- User-assignable grid (4 columns)
- Smart defaults populated from usage frequency
- Long-press any button to reassign
- Visually distinct from transport buttons (accent border)
- "+" button to add new

**Collapsible Sections (same as web remote):**
- Wave Controls: Mode (0-8), Alpha, Scale, Zoom, Warp, Rotation, Decay + toggles (Brighten, Darken, Solarize, Invert, Additive, Thick)
- Color Controls: Hue, Saturation, Brightness, Auto Hue toggle
- Variables: Time, Intensity, Quality
- Audio: Amp L/R, FFT Attack/Decay
- Message: text input + send (via `MSG|text=...`)
- Raw Command: text input + send

**Slider Behavior:**
- Throttled to 50ms (same as web remote)
- Wave parameters consolidated into single `WAVE|...` message

### Displays Tab

**Global Controls (always visible):**
- Master opacity slider (0-100, sends `SET_MIRROR_OPACITY=N`)
- Click-through toggle (sends `SET_MIRROR_CLICKTHRU=0|1`)
- Move to display picker (dropdown, sends `MOVE_TO_DISPLAY=N`)

**Advanced Section (expandable):**
- Visual monitor map showing display layout from `DIAG_MIRRORS` response
- Tap a monitor to select it
- Per-display opacity slider (sends `SET_MIRROR_OPACITY=<display>,<value>`)
- Per-display click-through: not currently supported by protocol (global only via `SET_MIRROR_CLICKTHRU`). Display global toggle only; if per-display click-through is added to MDropDX12 later, the UI is ready.
- Displays rendered proportionally based on reported display rects

**State Sync:**
- Queries `DIAG_MIRRORS` on tab open and after changes
- Response format (actual from `engine_messages.cpp:2794`):
```
MIRRORS|active=0|render_on=\\.\DISPLAY1,renderwin=(x,y)-(x,y) WxH,opacity=0.95,fs=0,clickthru=0|mon0=\\.\DISPLAY2,enabled=1,opacity=80,clickthru=0,skipped=0,display=(x,y)-(x,y) WxH,ready=1,hwnd=00000000,swapsize=WxH,winrect=(x,y)-(x,y) WxH,visible=1
```
- Parser must handle comma-separated key=value pairs within pipe-delimited monitor sections, where rect values contain commas and parentheses

### Buttons Tab

**Button Grid:**
- Scrollable grid of user-configured buttons
- Each button shows: label, optional icon/emoji, action type indicator

**Button Editor (long-press or tap edit icon):**
- Action type picker:
  - **Signal** — pick from list of known signals (FULLSCREEN, MIRROR, MIRROR_WM, etc.)
  - **SendKey** — pick from common virtual key codes (Space=0x20, N=0x4E, etc.) or enter custom hex
  - **ScriptCommand** — pipe-chained IPC commands (`|` separated, matching actual `ExecuteScriptLine` behavior)
  - **LoadPreset** — file path on PC (typed manually)
  - **Message** — `MSG|text=...|font=...|size=...` formatted overlay text
  - **RunScript** — push script file from phone to visualizer
- Label text field
- Payload/command field (context-sensitive to action type)
- Test button (execute without saving)

**Script Upload:**
1. User taps "Upload Script" or assigns script to a button
2. Android file picker (filter: .milk3, .eel, .txt)
3. App reads file content from phone storage
4. Sends via TCP: `SHADER_IMPORT=<json>` or `SHADER_GLSL=<code>`
5. Visualizer responds with `SHADER_IMPORT_RESULT=...` or `SHADER_GLSL_RESULT=...`
6. App shows toast/snackbar with success or error from result
7. Can save as a one-tap button for reuse

**Smart Defaults:**
- Track which commands the user sends most frequently
- Auto-suggest popular commands as button candidates
- "Add suggested" prompt when usage patterns emerge

**Import/Export:**
- Button layouts saved as JSON
- Schema:
```json
{
  "version": 1,
  "buttons": [
    {
      "label": "Party Mode",
      "actionType": "ScriptCommand",
      "payload": "SIGNAL|MIRROR_WM|SET_MIRROR_OPACITY=40",
      "icon": "🎬",
      "position": 0
    }
  ]
}
```
- Share layouts between devices
- Import from file picker

### Settings Tab

- **Connection**: Manual IP/port, saved servers list, PIN entry
- **Navigation**: Toggle between bottom nav and drawer mode
- **Theme**: Dark (default), follows system
- **About**: Version, links

## STATE Command Response

On connect (and reconnect), the app sends `STATE` to sync all UI. The server responds with multiple messages in sequence:

1. `OPACITY=<0-100>` — current window opacity
2. `PRESET=<path>` — current preset file path
3. `SETTINGS|ACTIVE=<0|1>|QUALITY=<float>|HUE=<float>|LOCKED=<0|1>|FFTATTACK=<float>|FFTDECAY=<float>|...` — current settings
4. `TRACK|artist=<name>|title=<song>|album=<album>` — current track info
5. `WAVE|COLORR=<0-255>|COLORG=<0-255>|COLORB=<0-255>|MODE=<int>|ALPHA=<float>|SCALE=<float>|ZOOM=<float>|WARP=<float>|ROTATION=<float>|DECAY=<float>|BRIGHTEN=<0|1>|DARKEN=<0|1>|SOLARIZE=<0|1>|INVERT=<0|1>|ADDITIVE=<0|1>|THICK=<0|1>` — current wave parameters

The app should parse all of these to populate slider/toggle state on initial connection.

## Outgoing Messages (Server → Client)

The server broadcasts these asynchronously to all authenticated TCP clients:

| Message | When |
|---------|------|
| `TRACK\|artist=...\|title=...\|album=...\|artwork=...` | Track changes |
| `PRESET=<path>` | Preset changes |
| `PONG` | Response to PING |
| `AUTH_OK` / `AUTH_PENDING` / `AUTH_FAIL\|<reason>` | Response to AUTH |
| `MIRRORS\|...` | Response to DIAG_MIRRORS |
| `MIRROR_OPACITY=<N>` | Response to SET_MIRROR_OPACITY |
| `SHADER_IMPORT_RESULT=...` | Response to SHADER_IMPORT |
| `SHADER_GLSL_RESULT=...` | Response to SHADER_GLSL |

## Protocol Additions (MDropDX12 C++ Changes)

### New TCP Listener

Added to MDropDX12 alongside the existing named pipe server:
- Listens on configurable port (default: 9270)
- Accepts multiple simultaneous clients
- Async I/O (select/poll or IOCP)
- Length-prefixed framing: 4-byte LE uint32 length + UTF-8 payload
- UTF-8 ↔ UTF-16 conversion at TCP boundary
- Per-connection auth state tracking
- Feeds authenticated commands into existing `ProcessIPCMessage()`
- Broadcasts outgoing messages (TRACK|, PRESET=, etc.) to all authenticated TCP clients

### New Commands (TCP only)

| Command | Response | Description |
|---------|----------|-------------|
| `AUTH\|<pin>\|<device_id>\|<device_name>` | `AUTH_OK`, `AUTH_PENDING`, or `AUTH_FAIL\|<reason>` | Authenticate + authorize device |
| `PING` | `PONG` | Keepalive heartbeat |
| `DEAUTH_DEVICE\|<device_id>` | `DEAUTH_OK` | Revoke device (local pipe/UI only) |
| `LIST_DEVICES` | `DEVICES\|id=...,name=...,added=...\|...` | List authorized devices (local pipe/UI only) |

### mDNS Advertisement

- Register `_milkwave._tcp` service via Windows DNS-SD APIs (`DnsServiceRegister`)
- Include TXT records: `version=<N>`, `pid=<PID>`
- Unregister on shutdown

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material 3 |
| Networking | `java.net.Socket` + Coroutines (simple length-prefixed protocol) |
| Discovery | Android NsdManager (built-in mDNS) |
| Preferences | Jetpack DataStore |
| Database | Room (button configs, saved servers) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| Build | Gradle KTS with version catalog |

## Project Structure

```
app/src/main/java/com/milkwave/remote/
  MainActivity.kt
  ui/
    theme/Theme.kt, Color.kt, Type.kt
    navigation/AppNavigation.kt, BottomNavBar.kt, DrawerNav.kt
    screens/
      remote/RemoteScreen.kt, TransportControls.kt, QuickButtons.kt
      remote/WaveControls.kt, ColorControls.kt, AudioControls.kt
      displays/DisplaysScreen.kt, MonitorMap.kt, OpacityControls.kt
      buttons/ButtonsScreen.kt, ButtonEditor.kt, ScriptUploader.kt
      settings/SettingsScreen.kt
    components/
      MediaBar.kt, ConnectionHeader.kt, CollapsibleSection.kt, SliderControl.kt
  data/
    db/AppDatabase.kt, ButtonDao.kt, ServerDao.kt
    model/ButtonConfig.kt, SavedServer.kt, DisplayInfo.kt
    repository/ButtonRepository.kt, ServerRepository.kt
  network/
    TcpClient.kt, MilkwaveProtocol.kt, CommandBuilder.kt
    discovery/MdnsDiscovery.kt
  service/
    ConnectionManager.kt
  viewmodel/
    RemoteViewModel.kt, DisplaysViewModel.kt, ButtonsViewModel.kt, SettingsViewModel.kt
```

## Non-Goals (v1)

- No Android MediaSession integration (media controls use SEND= virtual key codes through visualizer)
- No preset browsing/search (just prev/next)
- No video/Spout controls
- No landscape-specific layouts (portrait-first)
- No capability negotiation (future: server could send `CAPS|version=1|features=...` after AUTH_OK)
