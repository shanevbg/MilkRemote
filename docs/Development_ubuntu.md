# Development Setup — Ubuntu 24.04 LTS

Step-by-step guide to build and deploy MDR Android on a clean Ubuntu 24.04 machine.

## Prerequisites

- Ubuntu 24.04 LTS (Desktop or Server with GUI not required)
- USB cable and an Android device with Developer Options / USB Debugging enabled
- Internet connection

## 1. Install JDK 17

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk
```

Verify:

```bash
java -version   # should show openjdk 17.x
```

## 2. Install Android Command-Line Tools

```bash
sudo apt install -y wget unzip

mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip
```

Add to your shell profile (`~/.bashrc` or `~/.zshrc`):

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

## 4. Set Up USB Debugging (udev rules)

```bash
sudo apt install -y android-tools-adb
```

Create a udev rule so your user can access USB devices without root:

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

You should see your device listed. Accept the RSA key prompt on the phone if shown.

## 5. Clone and Build

```bash
git clone <repo-url> MDR_Android
cd MDR_Android
```

Create `local.properties` pointing to your SDK:

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

Build the debug APK:

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
| `adb devices` shows nothing | Re-plug USB, check Developer Options is on, accept RSA prompt |
| `JAVA_HOME` not set | `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` |
| `sdkmanager` license errors | Run `yes \| sdkmanager --licenses` again |
| Build fails with OOM | Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` |
