"""Main entry point for EatVenture Bot (VS Code / Python execution).

Connects to your Android phone over ADB, starts the Pure First & Last (F&L)
strategy worker, and opens the floating desktop controller HUD.
"""

import logging
import os
import sys
import threading
import time
import cv2

from core.coords import GameCoords
from core.device import DeviceController
from core.fl_strategy import FLStrategyEngine
from core.vision import VisionEngine
from ui.floating_hud import FloatingHUD

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S"
)
log = logging.getLogger("EatVentureBot")

def main():
    print("=" * 60)
    print("  EATVENTURE BOT - PURE FIRST & LAST (F&L) STRATEGY")
    print("  Runs directly from VS Code / Terminal with ADB")
    print("=" * 60)

    # 1. Connect to Android device
    try:
        dev = DeviceController()
    except Exception as e:
        log.error(f"Failed to connect to phone via ADB: {e}")
        print("\n[!] Please ensure your phone is connected via USB and 'USB Debugging' is enabled.")
        sys.exit(1)

    # 2. Setup coordinates & vision
    coords = GameCoords(dev.width, dev.height)
    templates_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates")
    vision = VisionEngine(templates_dir, coords)

    bot_running = threading.Event()
    bot_running.set()  # Starts active
    app_closing = threading.Event()

    hud = None

    def on_status_update(status_text: str):
        if hud:
            hud.update_status(status_text)
        log.info(f"Phase Update -> {status_text}")

    # 3. Strategy Engine
    strategy = FLStrategyEngine(dev, vision, coords, on_status_update)

    def worker_loop():
        log.info("Strategy worker thread started.")
        while not app_closing.is_set():
            if bot_running.is_set():
                # Rule 1: Do not start/continue automation unless Eatventure is foreground
                if not dev.is_game_foreground():
                    on_status_update("Waiting for Eatventure...")
                    time.sleep(0.3)
                    continue

                try:
                    bgr_img = dev.capture_screen()
                    if bgr_img is not None:
                        hsv_img = cv2.cvtColor(bgr_img, cv2.COLOR_BGR2HSV)
                        strategy.execute_step(bgr_img, hsv_img)
                except Exception as e:
                    log.error(f"Error in strategy cycle: {e}")
            time.sleep(0.1)
        log.info("Worker thread exited.")

    worker_thread = threading.Thread(target=worker_loop, daemon=True)
    worker_thread.start()

    # 4. HUD Callbacks
    def handle_toggle_bot(is_active: bool):
        if is_active:
            bot_running.set()
            log.info("Bot RESUMED")
        else:
            bot_running.clear()
            log.info("Bot PAUSED")

    def handle_toggle_offline(is_offline: bool):
        dev.set_offline_mode(is_offline)

    def handle_reset_phase():
        strategy.reset_phase()

    hud = FloatingHUD(
        on_toggle_bot=handle_toggle_bot,
        on_toggle_offline=handle_toggle_offline,
        on_reset_phase=handle_reset_phase
    )

    try:
        hud.start()
    except KeyboardInterrupt:
        pass
    finally:
        app_closing.set()
        log.info("Shutting down EatVenture Bot.")

if __name__ == "__main__":
    main()
