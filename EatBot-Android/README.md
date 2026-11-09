# EatVenture Bot 🤖🍔

A native Android automation assistant for **Eatventure** that runs directly on your phone. It uses screen capture, OpenCV vision, and Android accessibility gestures to upgrade restaurants, collect rewards, renovate floors, and progress through cities.

> Use the bot only with the game account and device you own.

## 🎬 Demo Videos

- **[Demo 1 — Live gameplay](demo-videos/eatventure-bot-demo.mp4)**
- **[Demo 2 — Bot automation](demo-videos/eatventure-bot-demo-2.mp4)**

## ✨ Features

- **First & Last strategy** — builds the first station, progresses to the final station, upgrades it, then sweeps remaining stations.
- **Automatic kitchen upgrades** — detects upgrade opportunities and checks affordability before tapping.
- **City and floor progression** — detects renovate, airport, welcome, and first-station screens.
- **Reward automation** — collects tips, deliveries, helpers, boxes, and available chests.
- **Floating controls** — pause, resume, move, reset, or close the bot while EatVenture remains visible.
- **Safety checks** — pauses for user interaction, manual menus, notifications, or when EatVenture is not foregrounded.
- **Resolution scaling** — adapts configured game coordinates for supported Android screen sizes.

## 📱 Install on Android

### APK installation

1. Build or download `app-debug.apk`.
2. Transfer the APK to your phone.
3. Open it from **Files** and allow installation from that app or file manager.
4. Launch **EatVenture Bot**.

### Required permissions

Enable these permissions in the setup screen:

1. **Overlay / Floating Window** — displays the on-screen bot controls.
2. **Accessibility — Eatbot taps** — enables taps, holds, and swipes.
3. **Screen capture** — select **Start now** when Android requests it.

If Accessibility is restricted on Android 13 or newer:

1. Open **Settings → Apps → EatVenture Bot**.
2. Open the three-dot menu.
3. Select **Allow restricted settings**, then enable Accessibility.

## 🚀 How to Use

1. Install EatVenture on the same phone.
2. Open **EatVenture Bot** and grant the required permissions.
3. Tap **START BOT & LAUNCH OVERLAY**.
4. Allow the screen capture prompt.
5. Open Eatventure and keep it in the foreground.
6. Use the floating controls:
   - **ON / OFF** — pause or resume automation.
   - **Drag handle** — move the overlay.
   - **X** — stop the bot.

Automation pauses when EatVenture is not active, notifications or another app are foregrounded, or a manual screen is detected.

## 🛠 Run from Terminal

### Requirements

- Android phone or emulator
- Android SDK Platform 34
- Java 17
- USB debugging enabled

### Build the APK

Windows:

```powershell
cd C:\YGC\AG_GameBot\EatBot-Android
.\gradlew.bat assembleDebug
```

Linux/macOS:

```bash
cd EatBot-Android
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Install and run

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.eatbot/.MainActivity
```

### Launch EatVenture

```powershell
adb shell monkey -p com.hwqgrhhjfd.idlefastfood 1
```

### Record a demo

```powershell
adb shell screenrecord --size 1080x2400 --bit-rate 8000000 --time-limit 45 /sdcard/eatventure-bot-demo.mp4
adb pull /sdcard/eatventure-bot-demo.mp4 demo-videos/eatventure-bot-demo.mp4
adb shell rm -f /sdcard/eatventure-bot-demo.mp4
```

## 🧱 Project Structure

```text
EatBot-Android/
├── app/src/main/
│   ├── assets/                 Vision templates and game references
│   ├── java/com/example/eatbot/
│   │   ├── MainActivity.kt     Setup and permissions
│   │   ├── BotService.kt       Screen capture and bot lifecycle
│   │   ├── TapService.kt       Accessibility gestures and foreground checks
│   │   ├── OverlayManager.kt   Floating bot controls
│   │   ├── coords/             Resolution-scaled tap coordinates
│   │   ├── strategy/           First & Last state machine
│   │   └── vision/             OpenCV and HSV detection
│   └── res/                    Icons and Accessibility configuration
├── demo-videos/                Demo recordings
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew.bat
```

## 🔧 Technology

Kotlin · Android Accessibility · MediaProjection · OpenCV · Gradle 8.2
