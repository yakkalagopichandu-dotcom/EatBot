# EatVenture Bot 🍔🤖

An automated bot for the mobile game **EatVenture** (Idle Fast Food), available in two modes:

| Mode | Description |
|------|-------------|
| **Python / ADB** | Runs on PC, controls the game via USB ADB — no root required |
| **Android App** | Native Android overlay app — runs directly on-device via MediaProjection + Accessibility |

---

## Project Structure

```
AG_GameBot/
├── run_bot.py              # Python ADB bot entry point
├── start.bat               # One-click Windows launcher (ADB mode)
├── requirements.txt        # Python dependencies
│
├── core/                   # Python bot engine
│   ├── coords.py           # Screen coordinate system (1260×2800)
│   ├── device.py           # ADB device interface
│   ├── vision.py           # OpenCV template matching & HUD detection
│   └── fl_strategy.py      # FL Strategy: build stations, buy upgrades, collect tips
│
├── ui/
│   └── floating_hud.py     # On-screen overlay HUD (Python mode)
│
├── templates/              # OpenCV template images for game element detection
│
├── EatBot-Android/         # Native Android app (Kotlin)
│   ├── app/src/main/java/com/example/eatbot/
│   │   ├── BotService.kt           # MediaProjection capture + bot loop
│   │   ├── TapService.kt           # Accessibility gesture injection
│   │   ├── OverlayManager.kt       # Floating overlay UI
│   │   ├── MainActivity.kt         # App entry & permission flows
│   │   ├── vision/GameVision.kt    # OpenCV vision (Android)
│   │   ├── strategy/FLStrategyEngine.kt  # Bot strategy (Android)
│   │   └── coords/GameCoords.kt    # Coordinate system (Android)
│   └── app/src/main/assets/templates/   # Template images (Android)
│
└── docs/
    └── VSCODE_GUIDE.md     # VS Code + ADB development guide
```

---

## Python / ADB Setup

### Prerequisites
- Python 3.10+
- [ADB](https://developer.android.com/tools/releases/platform-tools) on PATH
- Android device with **USB Debugging** enabled
- EatVenture installed on device

### Install
```bash
python -m venv .venv
.venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

### Run
```bash
# With virtual env active:
python run_bot.py

# Or double-click:
start.bat
```

---

## Android App Setup

### Prerequisites
- Android Studio (Hedgehog or later)
- ADB on PATH
- Device with Android 11+

### Build & Install
```bash
cd EatBot-Android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### First Run
1. Open **EatVenture Bot** app
2. Enable the **Accessibility Service** when prompted (`Settings → Accessibility → EatVenture Bot Taps`)
3. Grant **Screen Capture** permission
4. Open **EatVenture**, then press ▶ in the overlay to start

---

## Bot Strategy

The bot runs a **5-phase station build loop** (FL Strategy):

1. Detect current station build state
2. Buy all available station upgrades (cheapest → most expensive)
3. Max out the last station unlock cost
4. Collect tips every ~20 s
5. Dismiss popups (races, rewards, cities) automatically

**Safety rules:**
- Never taps notification banners (top 8% of screen)
- Never taps floating notification bubbles
- Never taps the Investor option or its suitcase logo
- Pauses immediately when user touches the screen

---

## Device Tested
- **Device:** Vivo (1260×2800 resolution)
- **Game:** EatVenture `com.hwqgrhhjfd.idlefastfood`

---

## License
MIT
