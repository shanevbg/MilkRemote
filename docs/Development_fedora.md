# Development Setup — Fedora 43

Step-by-step guide to build and deploy MDR Android on a clean Fedora 43 machine.

## Prerequisites

- Fedora 43 Workstation or Server
- USB cable and an Android device with Developer Options / USB Debugging enabled
- Internet connection

## 1. Install JDK 17

```bash
sudo dnf install -y java-17-openjdk-devel
```

Verify:

```bash
java -version   # should show openjdk 17.x
```

If multiple JDKs are installed, select 17:

```bash
sudo alternatives --config java
```

## 2. Install Android Command-Line Tools

```bash
sudo dnf install -y wget unzip

mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip
```

Add to `~/.bashrc`:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Reload:

```bash
source ~/.bashrc
```

## 3. Install SDK Packages

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 4. Set Up USB Debugging

Install ADB:

```bash
sudo dnf install -y android-tools
```

Fedora uses `systemd-udevd`. Create a udev rule:

```bash
sudo tee /etc/udev/rules.d/51-android.rules << 'EOF'
SUBSYSTEM=="usb", ATTR{idVendor}=="*", MODE="0666", GROUP="plugdev"
EOF
sudo udevadm control --reload-rules
sudo udevadm trigger
```

Plug in your device and verify:

```bash
adb devices
```

Accept the RSA key prompt on the phone if shown.

**SELinux note:** If `adb` cannot see the device, check for denials:

```bash
sudo ausearch -m avc -ts recent
```

If needed, create a local policy or temporarily set permissive mode for debugging:

```bash
sudo setenforce 0   # temporary, resets on reboot
```

## 5. Clone and Build

```bash
git clone <repo-url> MDR_Android
cd MDR_Android
```

Create `local.properties`:

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

Build:

```bash
./gradlew assembleDebug
```

The first build downloads Gradle 8.11.1 and all dependencies automatically.

## 6. Deploy via ADB

```bash
./gradlew installDebug
```

Or manually:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 7. Launch

```bash
adb shell am start -n com.sheinsez.mdropdx12.remote/.MainActivity
```

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb devices` shows nothing | Re-plug USB, check Developer Options, accept RSA prompt |
| SELinux blocks ADB | `sudo ausearch -m avc -ts recent` then create policy or `setenforce 0` |
| `JAVA_HOME` not set | `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` |
| `sdkmanager` license errors | Run `yes \| sdkmanager --licenses` again |
| Build fails with OOM | Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` |
