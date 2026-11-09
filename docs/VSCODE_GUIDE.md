# EatVenture Bot (VS Code Edition)

A high-performance offline gamebot for **Eatventure** (`com.hwqgrhhjfd.idlefastfood`) running directly in **VS Code** over ADB.

No Android Studio required! Everything runs with Python and ADB.

---

## What We Built for You in VS Code

1. **Floating Desktop HUD Widget ([ui/floating_hud.py](file:///c:/YGC/AG_GameBot/ui/floating_hud.py))**:
   - An always-on-top mini control panel that floats over your desktop/VS Code.
   - **`[ ▶ BOT RUNNING ]` (Green) / `[ ⏸ BOT PAUSED ]` (Red)**: Single click (or press **`F9`** / **`Space`**) to pause or resume instantly.
   - **`[ ✈ Ad-Block: ON/OFF ]`**: Toggles your phone's Wi-Fi and Mobile Data via ADB so Eatventure runs 100% offline with zero popup ads.
   - **Live Strategy Tracker**: Shows the current phase in real-time (`[1/4] St.1 Build`, `[2/4] Seek Last`, `[3/4] Max Last`, `[4/4] Sweep All`, `Renovating...`).

2. **Pure First & Last (F&L) Speedrun Strategy ([core/fl_strategy.py](file:///c:/YGC/AG_GameBot/core/fl_strategy.py))**:
   - **Phase 1 (Station 1 Build)**: Upgrades Station 1 to establish baseline income.
   - **Phase 2 (Seek Last Station)**: Swipes down the restaurant floor to reveal and unlock the highest-tier station.
   - **Phase 3 (Max the Last Station)**: Directs 100% of income into the last station using continuous long-press leveling (`2200ms`) and auto-drains kitchen multipliers until the last station reaches **MAX**.
   - **Phase 4 (Sweep & Backfill)**: Sweeps upward and maxes all intermediate stations in seconds using the astronomical revenue from the last station.
   - **Phase 5 (Auto Renovate / Next City)**: Detects the Renovate icon or Airport icon, confirms the flight, clicks "Okay" on the Welcome dialog, opens the first stand, opens reward chests, and loops!

3. **Auto-Scaled Resolution ([core/coords.py](file:///c:/YGC/AG_GameBot/core/coords.py))**:
   - Auto-detects your connected phone resolution (`1260x2800` on your device `10BD9G1NBM0002E`) and dynamically maps all coordinates.

---

## How to Run in VS Code

### Option 1: One-Click Launch
Double-click [start.bat](file:///c:/YGC/AG_GameBot/start.bat) from VS Code or File Explorer.

### Option 2: Run in VS Code Terminal
Open your terminal in `c:\YGC\AG_GameBot` and run:
```powershell
python run_bot.py
```

---

## Hotkeys
- **`F9`** or **`Space`**: Pause / Resume bot
- **Close Button (`✕`)**: Stop bot cleanly
