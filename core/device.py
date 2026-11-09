"""ADB Device connection, gesture dispatch, fast screen capture, and network control."""

import io
import logging
import re
import subprocess
import time
from typing import Tuple, Optional
import cv2
import numpy as np
from adbutils import adb

from core.coords import Point, Swipe

log = logging.getLogger(__name__)

GAME_PACKAGE = "com.hwqgrhhjfd.idlefastfood"

class DeviceController:
    def __init__(self, serial: str = ""):
        if serial:
            self._dev = adb.device(serial=serial)
        else:
            devices = adb.device_list()
            if not devices:
                raise RuntimeError("No ADB device connected. Connect your phone via USB with USB Debugging enabled.")
            self._dev = devices[0]

        self.serial = self._dev.serial
        log.info(f"Connected to Android device: {self.serial}")
        self.width, self.height = self.get_resolution()

    def get_resolution(self) -> Tuple[int, int]:
        output = self._dev.shell("wm size")
        match = re.search(r"(\d+)x(\d+)", output)
        if not match:
            raise RuntimeError(f"Unable to parse resolution from 'wm size': {output}")
        w, h = int(match.group(1)), int(match.group(2))
        log.info(f"Device screen resolution: {w}x{h}")
        return w, h

    def capture_screen(self) -> np.ndarray:
        """Fast memory-streamed screencap using adb exec-out into OpenCV BGR array."""
        try:
            cmd = ["adb", "-s", self.serial, "exec-out", "screencap", "-p"]
            png_bytes = subprocess.check_output(cmd, stderr=subprocess.DEVNULL)
            img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                raise ValueError("Decoded image is None")
            return img
        except Exception as e:
            # Fallback
            log.warning(f"Fast screencap fallback due to: {e}")
            raw = self._dev.shell("screencap -p", encoding=None)
            raw = raw.replace(b"\r\n", b"\n")
            return cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)

    def _can_interact(self, action: str, point: Optional[Point] = None) -> bool:
        if not self.is_game_foreground():
            log.warning(f"Blocked {action}: EatVenture is no longer in the foreground")
            return False

        # Android system notifications/status overlays occupy the top edge.
        # Never convert a notification tap into an external-app launch.
        if point is not None and point.y < max(220, round(self.height * 0.08)):
            log.warning(f"Blocked {action} in Android top/status zone: ({point.x}, {point.y})")
            return False
        return True

    def tap(self, point: Point):
        if not self._can_interact("tap", point):
            return
        self._dev.shell(f"input tap {point.x} {point.y}")

    def long_press(self, point: Point, duration_ms: int):
        if not self._can_interact("long press", point):
            return
        self._dev.shell(f"input swipe {point.x} {point.y} {point.x} {point.y} {duration_ms}")

    def swipe(self, s: Swipe):
        if not self._can_interact("swipe", s.end):
            return
        self._dev.shell(f"input swipe {s.start.x} {s.start.y} {s.end.x} {s.end.y} {s.duration_ms}")

    def is_game_foreground(self) -> bool:
        """Returns True only when EatVenture is the top foreground app/window."""
        try:
            activity_out = self._dev.shell("dumpsys activity activities")
            activity_markers = (
                "topResumedActivity=", "mResumedActivity:", "ResumedActivity:",
            )
            for line in activity_out.splitlines():
                if any(marker.lower() in line.lower() for marker in activity_markers):
                    if GAME_PACKAGE in line:
                        return True
                    if re.search(r"ActivityRecord\{[^}]*\s[^ ]*/", line):
                        return False

            window_out = self._dev.shell("dumpsys window windows")
            focus_markers = ("mCurrentFocus=", "mFocusedApp=", "mFocusedWindow=", "mTopFullscreenOpaqueWindowState=")
            for line in window_out.splitlines():
                if any(marker.lower() in line.lower() for marker in focus_markers):
                    if GAME_PACKAGE in line:
                        return True
                    # A populated focus marker for a different app is authoritative.
                    if line.strip().split("=", 1)[-1].strip().lower() != "null":
                        return False

            # Modern Android builds may expose the package through window records
            # even when mCurrentFocus/mFocusedApp are null.
            visible_game = re.search(r"\bpackage=" + re.escape(GAME_PACKAGE) + r"\b", window_out)
            visible_external = re.search(
                r"\bpackage=(?!" + re.escape(GAME_PACKAGE) + r"\b)(?:[^\s]+)",
                window_out,
            )
            return bool(visible_game and not visible_external)
        except Exception as e:
            log.warning(f"Failed to check foreground package: {e}")
            return False

    def set_offline_mode(self, offline: bool):
        """Toggles Wi-Fi and Mobile Data to play 100% offline and block ads."""
        action = "disable" if offline else "enable"
        try:
            self._dev.shell(f"svc wifi {action}")
            self._dev.shell(f"svc data {action}")
            log.info(f"Set phone network: {'OFFLINE (Ad-Block ON)' if offline else 'ONLINE'}")
        except Exception as e:
            log.error(f"Failed to toggle network state: {e}")

    def start_game(self):
        self._dev.shell(f"monkey -p {GAME_PACKAGE} -c android.intent.category.LAUNCHER 1")

    def close_game(self):
        self._dev.shell(f"am force-stop {GAME_PACKAGE}")
