"""Pure First & Last (F&L) Strategy Engine for EatVenture (Python/ADB bot).

Completes cities exponentially faster by:
1. DETECT_STATE: Inspects existing game state on startup to resume mid-session.
2. Station 1 Build: Establishes baseline cash with Station 1.
3. Scroll-to-Last: Smoothly swipes down to reveal the last station.
4. Unlock Last: Taps the Open button on the last station if needed.
5. Max Last Station: Turbo-levels the last station (event-driven, not timer-based).
6. Sweep & Backfill: Uses the massive revenue to max all intermediate stations.
7. City Transition: Auto-renovates or flies to next city, event-driven polling.

SAFETY RULES enforced here:
- Never taps Investor, Character, Vault, or ad elements.
- Only taps within known safe game zones.
- Checks affordability before each kitchen upgrade purchase.
- Respects user interaction (HUD pause).
"""

from enum import Enum
import logging
import time
from typing import Callable

import cv2
import numpy as np

from core.coords import GameCoords, Point
from core.device import DeviceController
from core.vision import VisionEngine

log = logging.getLogger(__name__)


class FLPhase(Enum):
    DETECT_STATE    = "Detecting State..."
    STATION_1_BUILD = "1. St.1 Build"
    SCROLL_TO_LAST  = "2. Seek Last St."
    UNLOCK_LAST     = "2b. Unlock Last St."
    MAX_LAST_STATION= "3. Max Last St."
    SWEEP_BACKFILL  = "4. Sweep All"
    ADVANCE_LEVEL   = "5. City Complete!"


class FLStrategyEngine:
    # Cycle caps
    MAX_ST1_CYCLES    = 5
    MAX_LAST_CYCLES   = 25

    # Scheduled action intervals (seconds)
    TIP_INTERVAL_S      = 25.0
    UPGRADE_INTERVAL_S  =  3.5
    DELIVERY_INTERVAL_S = 60.0
    HELPER_INTERVAL_S   = 90.0

    # Transition timeout
    TRANSITION_TIMEOUT_S = 20.0
    RENO_TIMEOUT_S       =  8.0

    def __init__(
        self,
        device: DeviceController,
        vision: VisionEngine,
        coords: GameCoords,
        on_status: Callable[[str], None],
    ):
        self.dev = device
        self.vision = vision
        self.coords = coords
        self.on_status = on_status

        self.phase = FLPhase.DETECT_STATE
        self.st1_cycles   = 0
        self.last_cycles  = 0

        self._last_tip_time      = 0.0
        self._last_upgrade_time  = 0.0
        self._last_delivery_time = 0.0
        self._last_helper_time   = 0.0

    # ── Public API ────────────────────────────────────────────────────────────

    def reset_phase(self):
        self.phase = FLPhase.DETECT_STATE
        self.st1_cycles  = 0
        self.last_cycles = 0
        self.on_status("Detecting state...")
        log.info("Phase reset to DETECT_STATE")

    def execute_step(self, bgr_img, hsv_img):
        """Called once per bot loop iteration."""

        # ── Guard: EatVenture and no external app may be active ───────────────
        if not self.dev.is_game_foreground():
            self.on_status("Blocked: Game App Not in Foreground")
            log.warning("Bot paused because EatVenture is not the foreground app")
            return

        # ── Guard: game must be visible ───────────────────────────────────────
        if not self.vision.is_game_screen_visible(bgr_img):
            self.on_status("Waiting for game HUD...")
            return

        # ── Investor recovery: close it immediately if a swipe/tap opened it ──
        if self.vision.is_investor_screen_open(bgr_img):
            cross = self.vision.find_investor_close(bgr_img)
            if cross:
                log.info(f"Investor opened accidentally → closing at ({cross.x}, {cross.y})")
                self.on_status("Closing Investor...")
                self.dev.tap(cross)
                time.sleep(0.35)
            else:
                log.warning("Investor detected but close template missing")
                self.on_status("Investor detected – close button not matched")
            return

        # ── Guard: manual screen protection ──────────────────────────────────
        if self.vision.is_user_manual_screen_open(bgr_img, hsv_img):
            self.on_status("Manual Screen – Paused")
            return

        now = time.time()

        # ── Priority 1: City completion ───────────────────────────────────────
        if self._check_city_completion(bgr_img):
            return

        # ── Priority 2: Popup dismissal ───────────────────────────────────────
        if self._dismiss_standard_popups(bgr_img):
            return

        # ── Priority 3: Staff crate ───────────────────────────────────────────
        if self._handle_staff_crate(bgr_img):
            return

        # ── Priority 4: Tips (~20 s) ──────────────────────────────────────────
        if now - self._last_tip_time >= self.TIP_INTERVAL_S:
            self._collect_tips(bgr_img, hsv_img)
            self._last_tip_time = now

        # ── Priority 5: Delivery (~60 s) ──────────────────────────────────────
        if now - self._last_delivery_time >= self.DELIVERY_INTERVAL_S:
            self._handle_food_delivery(bgr_img)
            self._last_delivery_time = now

        # ── Priority 6: Helper (~90 s) ────────────────────────────────────────
        if now - self._last_helper_time >= self.HELPER_INTERVAL_S:
            self._handle_helper_invite(bgr_img, hsv_img)
            self._last_helper_time = now

        # ── Priority 7: Kitchen upgrades (~5 s) ───────────────────────────────
        if now - self._last_upgrade_time >= self.UPGRADE_INTERVAL_S:
            if self.vision.has_upgrade_badge(hsv_img):
                self._buy_kitchen_upgrades(hsv_img)
            self._last_upgrade_time = now

        # ── Priority 8: Food boxes ────────────────────────────────────────────
        self._open_food_boxes(bgr_img)

        # ── Priority 9: Strategy phase ────────────────────────────────────────
        {
            FLPhase.DETECT_STATE:     self._detect_initial_state,
            FLPhase.STATION_1_BUILD:  self._execute_st1_build,
            FLPhase.SCROLL_TO_LAST:   self._execute_scroll_to_last,
            FLPhase.UNLOCK_LAST:      self._execute_unlock_last,
            FLPhase.MAX_LAST_STATION: self._execute_max_last,
            FLPhase.SWEEP_BACKFILL:   self._execute_sweep_backfill,
            FLPhase.ADVANCE_LEVEL:    self._check_city_completion,
        }[self.phase](bgr_img, hsv_img)

    # ═══════════════════════════════════════════════════════════════════════════
    # PHASE IMPLEMENTATIONS
    # ═══════════════════════════════════════════════════════════════════════════

    def _detect_initial_state(self, bgr_img, hsv_img):
        """§5 Resume from existing session state."""
        icons = self.vision.find_station_upgrade_icons(hsv_img)
        log.info(f"DETECT_STATE: {len(icons)} upgrade icons visible")

        if (self.vision.has_template(bgr_img, "fly_next_city_icon", 0.85) or
                self.vision.has_template(bgr_img, "go_next_level_icon", 0.85)):
            self.phase = FLPhase.ADVANCE_LEVEL
            self.on_status("[5/5] City Complete!")
            log.info("Resumed: city complete → ADVANCE_LEVEL")

        elif len(icons) >= 4:
            self.phase = FLPhase.SWEEP_BACKFILL
            self.on_status("Resumed: [4/5] Sweep All")
            log.info("Resumed: 4+ stations → SWEEP_BACKFILL")

        elif len(icons) >= 2 and icons[-1].y > self.coords.station_icon_tooltip_y:
            self.phase = FLPhase.MAX_LAST_STATION
            self.on_status("Resumed: [3/5] Max Last")
            log.info("Resumed: bottom station active → MAX_LAST_STATION")

        else:
            self.phase = FLPhase.STATION_1_BUILD
            self.on_status("[1/5] St.1 Build")
            log.info("Starting: Station 1 Build")

    def _execute_st1_build(self, bgr_img, hsv_img):
        self.on_status(f"[1/5] St.1 Build ({self.st1_cycles}/{self.MAX_ST1_CYCLES})")

        open_pt = self.vision.find_template(bgr_img, "open_button", 0.82)
        if open_pt:
            log.info("Station 1 Open button found → tapping")
            self.dev.tap(open_pt)
            time.sleep(0.7)
            return

        icons = self.vision.find_station_upgrade_icons(hsv_img)
        if not icons:
            self.dev.tap(self.coords.null_spot)
            time.sleep(0.25)
            return

        self._upgrade_station(icons[0])
        self.st1_cycles += 1

        if self.st1_cycles >= self.MAX_ST1_CYCLES:
            log.info(f"Station 1 built ({self.st1_cycles} cycles) → SCROLL_TO_LAST")
            self.phase = FLPhase.SCROLL_TO_LAST
            self.on_status("[2/5] Seek Last St.")

    def _execute_scroll_to_last(self, bgr_img, hsv_img):
        self.on_status("[2/5] Scrolling to Last St.")
        log.info("Scrolling down to reveal last station...")
        for _ in range(4):
            self.dev.swipe(self.coords.swipe_down_to_bottom)
            time.sleep(0.35)

        # Check if last station needs unlocking
        open_pt = self.vision.find_template(bgr_img, "open_button", 0.82)
        if open_pt:
            self.phase = FLPhase.UNLOCK_LAST
            self.on_status("[2b/5] Unlock Last St.")
            log.info("Last station needs unlocking → UNLOCK_LAST")
        else:
            self.phase = FLPhase.MAX_LAST_STATION
            self.on_status("[3/5] Max Last St.")
            log.info("Last station visible → MAX_LAST_STATION")

    def _execute_unlock_last(self, bgr_img, hsv_img):
        self.on_status("[2b/5] Unlocking Last St.")
        open_pt = self.vision.find_template(bgr_img, "open_button", 0.82)
        if open_pt:
            log.info(f"Tapping Open at ({open_pt.x}, {open_pt.y})")
            self.dev.tap(open_pt)
            time.sleep(0.8)
        else:
            log.info("Open button gone → MAX_LAST_STATION")
            self.phase = FLPhase.MAX_LAST_STATION
            self.on_status("[3/5] Max Last St.")

    def _execute_max_last(self, bgr_img, hsv_img):
        self.on_status(f"[3/5] Max Last ({self.last_cycles}/{self.MAX_LAST_CYCLES})")
        icons = self.vision.find_station_upgrade_icons(hsv_img)
        if not icons:
            self.dev.swipe(self.coords.swipe_down_to_bottom)
            time.sleep(0.4)
            return

        self._upgrade_station(icons[-1])
        self.last_cycles += 1

        if self.last_cycles >= self.MAX_LAST_CYCLES:
            log.info(f"Safety cap reached → SWEEP_BACKFILL")
            self.phase = FLPhase.SWEEP_BACKFILL
            self.on_status("[4/5] Sweep All")

    def _execute_sweep_backfill(self, bgr_img, hsv_img):
        self.on_status("[4/5] Sweep All")
        icons = self.vision.find_station_upgrade_icons(hsv_img)
        for icon in icons:
            self._upgrade_station(icon)
            time.sleep(0.12)

        self.dev.swipe(self.coords.swipe_up_to_top)
        time.sleep(0.45)

    # ═══════════════════════════════════════════════════════════════════════════
    # CITY COMPLETION
    # ═══════════════════════════════════════════════════════════════════════════

    def _check_city_completion(self, bgr_img, hsv_img=None) -> bool:
        if self.vision.has_template(bgr_img, "fly_next_city_icon", 0.87):
            self.on_status("[5/5] Flying to Next City!")
            log.info("Flying to next city...")
            self.dev.tap(self.coords.next_level_button)
            time.sleep(0.5)
            self.dev.tap(self.coords.fly_next_city_button)

            # Poll for welcome OK button (up to 20 s)
            deadline = time.time() + self.TRANSITION_TIMEOUT_S
            while time.time() < deadline:
                time.sleep(0.5)
                self.dev.tap(self.coords.welcome_okay_button)
                time.sleep(0.35)

            self.dev.tap(self.coords.first_stand_open_button)
            time.sleep(0.7)
            self._transition_to_new_city()
            return True

        if self.vision.has_template(bgr_img, "go_next_level_icon", 0.87):
            self.on_status("[5/5] Renovating!")
            log.info("Renovating restaurant...")
            self.dev.tap(self.coords.next_level_button)
            time.sleep(0.5)
            self.dev.tap(self.coords.renovate_button)

            deadline = time.time() + self.RENO_TIMEOUT_S
            while time.time() < deadline:
                time.sleep(0.4)
                self.dev.tap(self.coords.first_stand_open_button)

            time.sleep(0.5)
            self._transition_to_new_city()
            return True

        return False

    def _transition_to_new_city(self):
        self.phase = FLPhase.DETECT_STATE
        self.st1_cycles  = 0
        self.last_cycles = 0
        self.on_status("Detecting new city state...")
        log.info("City transition complete → DETECT_STATE")

    # ═══════════════════════════════════════════════════════════════════════════
    # TIP / DELIVERY / HELPER / UPGRADES / CRATES
    # ═══════════════════════════════════════════════════════════════════════════

    def _collect_tips(self, bgr_img, hsv_img):
        tips = self.vision.find_tips(hsv_img, bgr_img)
        if not tips:
            tp = self.vision.find_template(bgr_img, "tip_cash", 0.78, self.coords.table_zone)
            if tp:
                self.on_status("Collecting Tip...")
                self.dev.tap(tp)
                time.sleep(0.1)
            return
        self.on_status(f"Collecting {len(tips)} tips...")
        log.info(f"Collecting {len(tips)} tips")
        for tip in tips:
            self.dev.tap(tip)
            time.sleep(0.09)

    def _handle_food_delivery(self, bgr_img):
        if not self.vision.has_template(bgr_img, "food_delivery_available", 0.76,
                                        self.coords.food_delivery_roi):
            return
        log.info("Delivery truck detected → inspecting")
        self.on_status("Checking Delivery...")
        self.dev.tap(self.coords.food_delivery_button)
        time.sleep(0.65)

        if self.vision.is_delivery_offer_ad(bgr_img):
            log.info("Delivery is ad-gated → skipping")
            self.dev.tap(self.coords.null_spot)
            time.sleep(0.3)
            return

        accept_pt = self.vision.find_template(bgr_img, "food_delivery_accept", 0.78)
        if accept_pt:
            log.info(f"Accepting delivery deal at ({accept_pt.x}, {accept_pt.y})")
            self.dev.tap(accept_pt)
            time.sleep(0.5)
        else:
            log.info("No valid accept button → closing")
            self.dev.tap(self.coords.null_spot)
            time.sleep(0.3)

    def _handle_helper_invite(self, bgr_img, hsv_img):
        if not self.vision.has_template(bgr_img, "helper_available", 0.76,
                                        self.coords.helper_roi):
            return
        log.info("Helper available → opening selection")
        self.on_status("Checking Helper...")
        self.dev.tap(self.coords.helper_button)
        time.sleep(0.75)

        best = self.vision.find_best_helper_button(bgr_img, hsv_img)
        if best:
            log.info(f"Inviting best helper at ({best.x}, {best.y})")
            self.dev.tap(best)
            time.sleep(0.5)
        else:
            log.info("No valid helper button → closing")
            self.dev.tap(self.coords.null_spot)
            time.sleep(0.3)

    def _buy_kitchen_upgrades(self, hsv_img):
        self.on_status("Buying Upgrades...")
        log.info("Upgrade badge detected → opening kitchen menu")
        self.dev.tap(self.coords.null_spot)
        time.sleep(0.12)
        self.dev.tap(self.coords.upgrade_button)
        time.sleep(0.3)

        for i in range(12):
            fresh_bgr = self.dev.capture_screen()
            fresh_hsv = cv2.cvtColor(fresh_bgr, cv2.COLOR_BGR2HSV)
            if not self.vision.is_first_upgrade_affordable(fresh_hsv):
                log.debug(f"Upgrade not affordable at round {i} → stopping")
                break
            self.dev.tap(self.coords.first_upgrade_buy)
            time.sleep(0.08)

        self.dev.tap(self.coords.close_upgrades)
        time.sleep(0.2)

    def _handle_staff_crate(self, bgr_img) -> bool:
        chest_pt = self.vision.find_template(bgr_img, "chest_icon", 0.84)
        if not chest_pt:
            return False
        log.info(f"Staff crate at ({chest_pt.x}, {chest_pt.y}) → opening")
        self.on_status("Opening Crate...")
        self.dev.tap(chest_pt)
        time.sleep(0.5)
        close_pt = self.vision.find_template(bgr_img, "popup_close_cross", 0.80)
        if close_pt:
            self.dev.tap(close_pt)
            time.sleep(0.25)
        return True

    def _dismiss_standard_popups(self, bgr_img) -> bool:
        if self.vision.is_user_manual_screen_open(bgr_img, None):
            return False
        cross = self.vision.find_template(bgr_img, "popup_close_cross", 0.80)
        if not cross:
            return False
        log.debug(f"Dismissing popup at ({cross.x}, {cross.y})")
        self.dev.tap(cross)
        time.sleep(0.22)
        return True

    def _open_food_boxes(self, bgr_img):
        box_pt = (self.vision.find_template(bgr_img, "box", 0.80) or
                  self.vision.find_template(bgr_img, "box2", 0.80))
        if box_pt:
            self.dev.tap(box_pt)
            time.sleep(0.13)

    # ═══════════════════════════════════════════════════════════════════════════
    # STATION UPGRADE HELPER
    # ═══════════════════════════════════════════════════════════════════════════

    def _upgrade_station(self, icon: Point):
        tap_pt = Point(
            icon.x + self.coords.station_tap_shift_x,
            icon.y + self.coords.station_tap_offset_y + self.coords.station_tap_shift_y
        )
        self.dev.tap(tap_pt)
        time.sleep(0.08)

        hold_pt = Point(
            icon.x + self.coords.better_food_hold_offset_x,
            icon.y - self.coords.better_food_hold_offset_y
        )
        self.dev.long_press(hold_pt, 2500)
        time.sleep(0.08)

        self.dev.tap(self.coords.null_spot)
        time.sleep(0.08)
