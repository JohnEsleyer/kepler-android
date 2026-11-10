# Esper Android - Headless CLI Build & Run Guide (Linux)

Esper is a **pure offline, folder-backed workspace for books and interactive
notes**. There is no account system, cloud sync, or network requirement. Books
are ordinary folders containing Markdown chapters, JSON metadata, and HTML
visuals; the same vault can be opened in Obsidian, VS Code, Git, or Pandoc.

- Architecture overview: [`docs/esper-main/overview.md`](docs/esper-main/overview.md)
- Vault format and interoperability: [`docs/vault-spec.md`](docs/vault-spec.md)

The rest of this guide walks you through installing prerequisites, building,
and running the `esper-android` project on Linux completely from the command
line without opening Android Studio.

---

## 1. Prerequisites

### 1.1. Java Development Kit (JDK 17)
The project is configured for JDK 17 / JVM Target 17.

On Ubuntu / Debian:
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk
```

On Fedora / RHEL:
```bash
sudo dnf install -y java-17-openjdk-devel
```

Verify your Java version:
```bash
java -version
```

---

## 2. Install Android Command-Line Tools & SDK

If you don't already have the Android SDK installed:

### 2.1. Set SDK Environment Variables
Add these to `~/.bashrc` (or `~/.zshrc`):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
```

Reload environment:
```bash
source ~/.bashrc
```

### 2.2. Download & Extract Android Command-Line Tools
```bash
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip cmdline-tools.zip
mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
mv cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
rm -rf cmdline-tools cmdline-tools.zip
```

### 2.3. Accept Licenses & Install Required SDK Packages
The app targets SDK `35` and minSdk `26`:

```bash
# Accept all licenses
yes | sdkmanager --licenses

# Install platforms, build-tools, platform-tools, emulator, and system image
sdkmanager "platform-tools" \
           "platforms;android-35" \
           "build-tools;35.0.0" \
           "emulator" \
           "system-images;android-34;google_apis;x86_64"
```

---

## 3. Initialize Gradle Wrapper in the Project

If `gradlew` is not yet generated in `esper-android/`:

1. Install Gradle temporarily:
   ```bash
   sudo apt install -y gradle  # or use sdkman: sdk install gradle 8.11
   ```

2. Generate the wrapper inside `esper-android`:
   ```bash
   cd esper-android
   gradle wrapper --gradle-version 8.11.1
   chmod +x gradlew
   ```

---

## 4. Build the APK via CLI

From the `esper-android` directory:

### 4.1. Clean & Assemble Debug Build
```bash
cd esper-android
./gradlew assembleDebug
```

The compiled APK will be output at:
```
esper-android/app/build/outputs/apk/debug/app-debug.apk
```

### 4.2. Clean & Assemble Optimized Production Release Build
```bash
cd esper-android
./gradlew assembleRelease
```

The optimized, R8-minified production APK will be output at:
```
esper-android/app/build/outputs/apk/release/app-release.apk
```

> **Signing:** the `release` build type is signed with the keystore pointed at by
> `KEYSTORE_PATH` (or the `RELEASE_STORE_FILE` Gradle property). When no
> production keystore is configured it falls back to the local debug keystore, so
> `assembleRelease` always yields an APK that `adb install` can put on a device.
> Supply real credentials for distribution:
> ```bash
> export KEYSTORE_PATH=/path/to/release.jks
> export KEYSTORE_PASSWORD=…
> export KEY_ALIAS=…
> export KEY_PASSWORD=…
> ./gradlew assembleRelease
> ```

---

## 5. Running & Deploying the App via CLI

### Option A: Optimized Production Release Deployment (Recommended)

1. **Build + Install + Launch Release via Wireless / USB Deploy**:
   ```bash
   bun run scripts/wireless-deploy.js --release
   # or with Node.js:
   node scripts/wireless-deploy.js --release
   # or as a package script:
   bun run deploy:release
   ```

2. **Skip Build (Fast Re-deploy of existing Release APK)**:
   ```bash
   bun run scripts/wireless-deploy.js --release --skip-build
   # or
   node scripts/wireless-deploy.js -r -s
   # or as a package script:
   bun run install:device:release
   ```

3. **Direct ADB Command (USB or Connected Wireless ADB)**:
   ```bash
   ./gradlew assembleRelease
   adb install -r -d app/build/outputs/apk/release/app-release.apk
   adb shell am start - com.johnesleyer.esper/com.esper.android.MainActivity
   ```

   For the debug build instead:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start - com.johnesleyer.esper/com.esper.android.MainActivity
   ```

### Option B: Debug Build Deployment

```bash
bun run scripts/wireless-deploy.js
# or direct manual adb:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start - com.johnesleyer.esper/com.esper.android.MainActivity
```

### Option C: Using the Automated Wireless Deploy Script

The repository provides an interactive deployment script (`scripts/wireless-deploy.js`) that supports wireless pairing, device discovery via mDNS, automatic Gradle compilation, installing, and launching the app on your Android device over Wi-Fi (or USB). It works with `bun` or `node`.

#### Requirements:
- Either [Bun](https://bun.sh) (`bun`) or [Node.js](https://nodejs.org) (`node >= 18`) installed.
- Android phone with **Developer Options** and **Wireless Debugging** (Android 11+) or **USB Debugging** enabled on the same Wi-Fi network.

#### Usage:

1. **Standard Run (Build + Connect/Pair + Install + Launch)**:
   ```bash
   # Using Bun script:
   bun run deploy
   # (explicit debug alias)
   bun run deploy:debug

   # Or direct script execution:
   bun run scripts/wireless-deploy.js
   node scripts/wireless-deploy.js
   ```

2. **Optimized Release Run (Build + Install + Launch the production APK)**:
   ```bash
   # Using Bun script:
   bun run deploy:release

   # Or direct script execution:
   bun run scripts/wireless-deploy.js --release
   node scripts/wireless-deploy.js -r
   ```

3. **Skip Build (Fast Re-deploy of existing APK)**:
   ```bash
   # Using Bun script:
   bun run install:device
   # (explicit debug alias)
   bun run install:device:debug
   # (release APK, no rebuild)
   bun run install:device:release

   # Or direct flags:
   bun run scripts/wireless-deploy.js --skip-build
   node scripts/wireless-deploy.js -s
   bun run scripts/wireless-deploy.js --release --skip-build
   node scripts/wireless-deploy.js -r -s
   ```

4. **Force Re-pairing Step**:
   ```bash
   # Using Bun script:
   bun run pair

   # Or direct flags:
   bun run scripts/wireless-deploy.js --pair
   node scripts/wireless-deploy.js -p
   ```

The script will guide you step-by-step through pairing code entry (if not already paired), connect to the device, install `app-debug.apk` (or `app-release.apk` with `--release`), and immediately start `MainActivity`.

#### Script reference

| Bun script | What it does |
|---|---|
| `bun run deploy` / `deploy:debug` | Build the debug APK, then pair/connect, install and launch |
| `bun run deploy:release` | Build the optimized release APK, then pair/connect, install and launch |
| `bun run install:device` / `install:device:debug` | Re-install the existing debug APK (no rebuild) |
| `bun run install:device:release` | Re-install the existing release APK (no rebuild) |
| `bun run pair` | Re-pair the device with this computer |

From the repository root (`022-esper/`), the same actions are available as
`bun run android:build`, `bun run android:build:release`,
`bun run android:deploy`, `bun run android:deploy:release` and
`bun run android:install:release`.

---

### Option D: Using a Physical Android Device via Manual ADB (USB)

1. Enable **Developer Options** and **USB Debugging** on your phone.
2. Connect your phone via USB.
3. Verify connection:
   ```bash
   adb devices
   ```
4. Install and start the application:
   ```bash
   adb install -r -d app/build/outputs/apk/release/app-release.apk
   adb shell am start -n com.johnesleyer.esper/com.esper.android.MainActivity
   ```

---

### Option E: Using a Headless / GUI Android Emulator via CLI

1. **Create an Android Virtual Device (AVD)**:
   ```bash
   avdmanager create avd \
     -n esper_emulator \
     -k "system-images;android-34;google_apis;x86_64" \
     -d "pixel_6"
   ```

2. **Launch the Emulator**:
   - With GUI window:
     ```bash
     emulator -avd esper_emulator &
     ```
   - Headless mode (no window, e.g. on a remote Linux server):
     ```bash
     emulator -avd esper_emulator -no-window -no-audio -gpu swiftshader_indirect &
     ```

3. **Wait for boot to finish**:
   ```bash
   adb wait-for-device shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 1; done;'
   ```

4. **Install & Launch Esper**:
   ```bash
   ./gradlew installDebug
   adb shell am start -n com.johnesleyer.esper/com.esper.android.MainActivity
   ```

---

## 6. Viewing Logs & Debugging

Stream real-time logcat output filtered by the application:
```bash
adb logcat -v time | grep -E "com.johnesleyer.esper|com.esper.android|EsperBridge"
```

To stop the app:
```bash
adb shell am force-stop com.johnesleyer.esper
```
