# MDx12 Remote

Android companion app for [MilkDrop DX12 (MDropDX12)](https://github.com/shanevbg/MDropDX12) — control your music visualizer from your phone over Wi-Fi.

## What It Does

MDx12 Remote connects to a MDropDX12 instance on your LAN and lets you:

- **Volume & Mute** — adjust the Windows audio device volume
- **Presets** — cycle through visualizer presets (prev / next)
- **Display Modes** — switch between Fullscreen, Mirror, Watermark, Capture, Borderless
- **Wave Controls** — mode, alpha, scale, zoom, warp, rotation, decay, plus toggles (brighten, darken, solarize, invert, additive, thick)
- **Color** — hue, saturation, brightness, auto-hue
- **Variables** — time, intensity, quality
- **Audio** — amplification (L/R), FFT attack/decay
- **Displays** — opacity, click-through, move to monitor
- **Custom Buttons** — user-defined actions (signals, keys, scripts, presets)
- **Messages** — send text overlay messages to the visualizer
- **Raw Commands** — send arbitrary protocol commands

The app auto-discovers servers via mDNS and UDP beacons, reconnects automatically, and persists your layout and preferences.

## Requirements

- Android 8.0+ (API 26)
- MDropDX12 running on the same LAN with TCP remote enabled (default port 9270)
- Android Studio Ladybug or newer (to build)

## Build

```bash
git clone https://github.com/shanevbg/MDR_Android_dev.git
cd MDR_Android_dev
```

Open in Android Studio, or build from the command line:

```bash
# Debug build
./gradlew assembleDebug

# The APK is at:
# app/build/outputs/apk/debug/app-debug.apk
```

## Install

### Via ADB (USB debugging enabled)

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Via Android Studio

Run > Run 'app' with your device selected.

## Connect

1. **Start MDropDX12** on your PC with TCP remote enabled.
2. **Launch MDx12 Remote** on your phone.
3. Go to **Settings** and either:
   - Tap **Scan for Servers** — the app finds your PC automatically via mDNS/UDP beacon.
   - Enter the IP and port manually under **Manual Connection**.
4. If a PIN is configured on the server, enter it in the PIN field.
5. Tap **Connect**. The header shows connection status and the current preset name.

The app saves your last connection and reconnects automatically on launch. If the server moves to a new IP, the app re-discovers it after a few failed reconnect attempts.

## Use

### Remote Tab

The main control surface. From top to bottom:

- **Volume slider + mute toggle** — controls the Windows audio output device volume
- **Prev / Next** — cycle visualizer presets
- **Mode grid** — tap to switch display modes
- **Collapsible sections** — Wave Controls, Color, Variables, Audio, Message, Raw Command. Each section expands/collapses and the state is remembered.

Sections can be reordered and one can be pinned below the preset buttons — configure this in **Settings > Section Layout**.

### Displays Tab

Control monitor layout, global opacity, and click-through behavior for the visualizer window.

### Buttons Tab

Create custom buttons with different action types:

| Action Type | What It Does |
|---|---|
| Signal | Send a named signal (e.g. `FULLSCREEN`) |
| SendKey | Send a virtual keypress |
| ScriptCommand | Execute a script command |
| LoadPreset | Load a preset by name |
| Message | Display a text message |
| RunScript | Upload and run a GLSL/shader script |

Long-press a button to edit it. Import/export your button layouts as JSON.

### Settings Tab

- **Connection** — auto-discovery, saved servers, manual connection
- **Audio Device** — choose which Windows audio output device to control
- **Section Layout** — reorder sections, pin one below transport controls
- **Device** — set device name and view device ID
- **Navigation** — switch between bottom tabs and drawer

## Architecture

MVVM with Jetpack Compose and Kotlin coroutines.

```
UI (Compose)  →  ViewModel  →  ConnectionManager  →  TcpClient  →  MDropDX12
                     ↑                                    |
               MessageParser  ←────────────────────────────
```

- **Protocol**: Length-prefixed UTF-8 frames over TCP (port 9270)
- **Discovery**: mDNS (`_milkwave._tcp`) + UDP beacon (port 9271)
- **Persistence**: DataStore (preferences), Room (buttons, saved servers)

## License

Private — not for redistribution.
