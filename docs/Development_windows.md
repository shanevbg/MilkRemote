# Development Setup — Windows 11

Step-by-step guide to build and deploy MDR Android on a clean Windows virtual machine.

## Prerequisites

- Windows 10 or 11 (any edition)
- USB cable and an Android device with Developer Options / USB Debugging enabled
- Internet connection
- If running in a VM: USB passthrough configured for the Android device

## 1. Install JDK 17

Download and install the Microsoft OpenJDK 17 build (recommended for Windows):

1. Go to https://learn.microsoft.com/en-us/java/openjdk/download#openjdk-17
2. Download the `.msi` installer for Windows x64
3. Run the installer — it sets `JAVA_HOME` automatically

Verify in a new terminal:

```powershell
java -version
```

Alternative: install via `winget`:

```powershell
winget install Microsoft.OpenJDK.17
```

## 2. Install Android Studio Command-Line Tools

Option A — Android Studio (includes GUI, emulator, SDK Manager):

1. Download from https://developer.android.com/studio
2. Install and launch, complete the Setup Wizard (installs SDK to `%LOCALAPPDATA%\Android\Sdk`)
3. In SDK Manager, install **Android SDK Platform 35** and **Build-Tools 35.0.0**

Option B — Command-line only:

```powershell
mkdir "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools" -Force
cd "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools"
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile cmdline-tools.zip
Expand-Archive cmdline-tools.zip -DestinationPath .
Rename-Item cmdline-tools latest
Remove-Item cmdline-tools.zip
```

Add to your PATH (System Environment Variables):

```
%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
%LOCALAPPDATA%\Android\Sdk\platform-tools
```

Set `ANDROID_HOME`:

```
%LOCALAPPDATA%\Android\Sdk
```

Open a new terminal and install SDK packages:

```powershell
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 3. Install USB Driver

For most devices, the Google USB Driver is sufficient:

```powershell
sdkmanager "extras;google;usb_driver"
```

Then in Device Manager:
1. Plug in the Android device
2. Find it under **Portable Devices** or **Other Devices**
3. Right-click > **Update Driver** > **Browse my computer** > point to:
   `%LOCALAPPDATA%\Android\Sdk\extras\google\usb_driver`

For Samsung devices, install Samsung USB Drivers from https://developer.samsung.com/android-usb-driver instead.

Verify:

```powershell
adb devices
```

Accept the RSA key prompt on the phone if shown.

## 4. Clone and Build

Using Git for Windows (https://git-scm.com/download/win) or `winget install Git.Git`:

```powershell
git clone <repo-url> MDR_Android
cd MDR_Android
```

Create `local.properties`:

```powershell
"sdk.dir=$($env:LOCALAPPDATA -replace '\\','/')/Android/Sdk" | Out-File -Encoding utf8 local.properties
```

Build with the Gradle wrapper (uses Git Bash shell bundled with the project):

```powershell
.\gradlew.bat assembleDebug
```

The first build downloads Gradle 8.11.1 and all dependencies automatically.

## 5. Deploy via ADB

```powershell
.\gradlew.bat installDebug
```

Or manually:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 6. Launch

```powershell
adb shell am start -n com.sheinsez.mdropdx12.remote/.MainActivity
```

## VM-Specific Notes

### USB Passthrough

| VM Platform | How to pass USB |
|---|---|
| **VirtualBox** | Devices > USB > select the Android device (install Extension Pack first) |
| **VMware** | VM > Removable Devices > select Android device > Connect |
| **Hyper-V** | Not natively supported — use Enhanced Session or USB/IP |

Make sure the device shows as **connected** in the guest before running `adb devices`.

### Performance

- Allocate at least **8 GB RAM** and **4 CPU cores** to the VM
- Use an SSD-backed virtual disk — Gradle builds are I/O heavy
- Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` if builds are slow or OOM

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb devices` shows nothing | Install correct USB driver, re-plug, accept RSA prompt |
| `gradlew.bat` not recognized | Run from the project root directory |
| `JAVA_HOME` not set | Set it to the JDK 17 install path (e.g. `C:\Program Files\Microsoft\jdk-17.x.x`) |
| `sdk.dir` wrong in `local.properties` | Use forward slashes: `C:/Users/you/AppData/Local/Android/Sdk` |
| USB device not visible in VM | Configure USB passthrough in VM settings, re-attach device |
| Build fails with OOM | Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` |
