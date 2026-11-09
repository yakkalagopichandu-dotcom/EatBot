"""Computer Vision engine using OpenCV for template matching, HSV color checks, and arrow detection."""

import os
import glob
import logging
from typing import List, Optional, Tuple
import cv2
import numpy as np

from core.coords import GameCoords, Point, Region

log = logging.getLogger(__name__)

# HSV Color ranges
RED_RANGES = [
    (np.array([0, 120, 150]), np.array([8, 255, 255])),
    (np.array([172, 120, 150]), np.array([180, 255, 255]))
]
BUY_BLUE_RANGE = (np.array([95, 120, 200]), np.array([110, 255, 255]))

ARROW_WHITE_RANGE = (np.array([0, 0, 210]), np.array([180, 60, 255]))
CIRCLE_RED_RANGE  = (np.array([0, 150, 200]), np.array([6, 255, 255]))

# Tip coins are yellow/gold
TIP_YELLOW_RANGE = (np.array([22, 100, 150]), np.array([38, 255, 255]))

# Nav-tab selected (bright white highlight)
NAV_SELECTED_RANGE = (np.array([0, 0, 200]), np.array([180, 60, 255]))

# Gear rarity (legendary orange)
GEAR_LEGENDARY_RANGE = (np.array([12, 160, 160]), np.array([24, 255, 255]))

BADGE_MIN_RATIO    = 0.12
BUY_MIN_RATIO      = 0.30
DIALOG_MIN_RATIO   = 0.40
NAV_SELECTED_RATIO = 0.25

class VisionEngine:
    def __init__(self, templates_dir: str, coords: GameCoords):
        self.coords = coords
        self.templates = {}
        self._load_templates(templates_dir)

    def _load_templates(self, dir_path: str):
        png_files = glob.glob(os.path.join(dir_path, "*.png"))
        for p in png_files:
            name = os.path.splitext(os.path.basename(p))[0]
            img = cv2.imread(p, cv2.IMREAD_COLOR)
            if img is None:
                continue

            # Scale template to match device resolution
            if self.coords.x_scale != 1.0 or self.coords.y_scale != 1.0:
                new_w = max(1, round(img.shape[1] * self.coords.x_scale))
                new_h = max(1, round(img.shape[0] * self.coords.y_scale))
                scaled = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
                self.templates[name] = scaled
            else:
                self.templates[name] = img
        log.info(f"Loaded {len(self.templates)} templates from {dir_path}")

    def color_ratio(self, hsv_img: np.ndarray, region: Region, ranges: list) -> float:
        clamped = self._clamp_region(region, hsv_img.shape[1], hsv_img.shape[0])
        if clamped is None:
            return 0.0
        x, y, w, h = clamped
        roi = hsv_img[y:y+h, x:x+w]
        mask = np.zeros(roi.shape[:2], dtype=np.uint8)
        for lower, upper in ranges:
            mask |= cv2.inRange(roi, lower, upper)
        return float(np.count_nonzero(mask) / mask.size) if mask.size > 0 else 0.0

    def has_upgrade_badge(self, hsv_img: np.ndarray) -> bool:
        return self.color_ratio(hsv_img, self.coords.upgrade_badge_roi, RED_RANGES) >= BADGE_MIN_RATIO

    def is_upgrades_dialog_open(self, hsv_img: np.ndarray) -> bool:
        return self.color_ratio(hsv_img, self.coords.close_upgrades_roi, RED_RANGES) >= DIALOG_MIN_RATIO

    def is_first_upgrade_affordable(self, hsv_img: np.ndarray) -> bool:
        return self.color_ratio(hsv_img, self.coords.first_upgrade_buy_roi, [BUY_BLUE_RANGE]) >= BUY_MIN_RATIO

    def is_game_screen_visible(self, bgr_img: np.ndarray) -> bool:
        """Returns True when EatVenture gameplay HUD is confidently visible."""
        return (self.has_template(bgr_img, "settings", 0.72, self.coords.settings_roi) or
                self.has_template(bgr_img, "go_next_level_icon", 0.80) or
                self.has_template(bgr_img, "fly_next_city_icon", 0.80))

    def find_investor_close(self, bgr_img: np.ndarray) -> Optional[Point]:
        dialog = self.find_template(bgr_img, "investor_dialog_ref", 0.72)
        if dialog is not None:
            return Point(dialog.x + self.coords.sx(278), dialog.y - self.coords.sy(540))
        return self.find_template(bgr_img, "popup_close_cross", 0.72)

    def is_investor_screen_open(self, bgr_img: np.ndarray) -> bool:
        for tpl in ("investor_dialog_ref", "investor_card"):
            if self.has_template(bgr_img, tpl, 0.72):
                return True
        return False

    def is_user_manual_screen_open(self, bgr_img: np.ndarray, hsv_img) -> bool:
        """Returns True when a manual-only screen (Investor/Gear/Vault/Ad) is open."""
        # Template checks for explicit screens
        for tpl in ("investor_title", "investor_card",
                    "character_screen_header", "character_menu_card"):
            if self.has_template(bgr_img, tpl, 0.76):
                log.debug(f"{tpl} detected → manual screen")
                return True
        # Very dark screen = ad or transition
        if float(np.mean(bgr_img)) < 18.0:
            return True
        return False

    def is_delivery_offer_ad(self, bgr_img: np.ndarray) -> bool:
        """Returns True when delivery offer is ad-gated (unsafe to accept)."""
        accept_pt = self.find_template(bgr_img, "food_delivery_accept", 0.78)
        if accept_pt is None:
            return True  # No accept button → unknown/unsafe
        # Check above accept button for ad icon template
        check_roi = Region(
            max(0, accept_pt.x - self.coords.sx(160)),
            max(0, accept_pt.y - self.coords.sy(90)),
            min(bgr_img.shape[1], accept_pt.x + self.coords.sx(160)),
            max(0, accept_pt.y - self.coords.sy(10))
        )
        if self.has_template(bgr_img, "ad_camera_icon", 0.72, check_roi):
            return True
        return False  # Safe non-ad deal

    def find_best_helper_button(self, bgr_img: np.ndarray,
                                hsv_img: np.ndarray) -> Optional[Point]:
        """Finds the best non-ad helper invite button using gear rarity scoring."""
        candidates = []
        last_y = 0
        for _ in range(4):
            search_roi = Region(0, last_y, bgr_img.shape[1], bgr_img.shape[0])
            pt = self.find_template(bgr_img, "helper_invite", 0.78, search_roi)
            if pt is None:
                break
            gear_roi = Region(
                max(0, pt.x - self.coords.sx(200)),
                max(0, pt.y - self.coords.sy(90)),
                min(bgr_img.shape[1], pt.x + self.coords.sx(200)),
                max(0, pt.y - self.coords.sy(30))
            )
            score = self.color_ratio(hsv_img, gear_roi, [GEAR_LEGENDARY_RANGE])
            candidates.append((pt, score))
            last_y = pt.y + self.coords.sy(30)
        if not candidates:
            return None
        return max(candidates, key=lambda c: c[1])[0]

    def find_template(self, bgr_img: np.ndarray, name: str, threshold: float = 0.80, region: Optional[Region] = None) -> Optional[Point]:
        tpl = self.templates.get(name)
        if tpl is None:
            return None

        if region is not None:
            clamped = self._clamp_region(region, bgr_img.shape[1], bgr_img.shape[0])
            if clamped is None:
                return None
            x, y, w, h = clamped
            search_mat = bgr_img[y:y+h, x:x+w]
            ox, oy = x, y
        else:
            search_mat = bgr_img
            ox, oy = 0, 0

        if search_mat.shape[0] < tpl.shape[0] or search_mat.shape[1] < tpl.shape[1]:
            return None

        res = cv2.matchTemplate(search_mat, tpl, cv2.TM_CCOEFF_NORMED)
        min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(res)

        if max_val >= threshold:
            cx = ox + max_loc[0] + tpl.shape[1] // 2
            cy = oy + max_loc[1] + tpl.shape[0] // 2
            return Point(cx, cy)
        return None

    def has_template(self, bgr_img: np.ndarray, name: str, threshold: float = 0.80, region: Optional[Region] = None) -> bool:
        return self.find_template(bgr_img, name, threshold, region) is not None

    def find_station_upgrade_icons(self, hsv_img: np.ndarray) -> List[Point]:
        """Finds pulsing red circle upgrade arrows on restaurant stations."""
        white_mask = cv2.inRange(hsv_img, *ARROW_WHITE_RANGE)
        red_mask = cv2.inRange(hsv_img, *CIRCLE_RED_RANGE)

        contours, _ = cv2.findContours(white_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        icons = []

        excl = self.coords.investor_exclusion_roi

        for c in contours:
            x, y, w, h = cv2.boundingRect(c)
            if not (self.coords.upgrade_arrow_min_height_y <= h <= self.coords.upgrade_arrow_max_height_y):
                continue
            aspect = w / float(h)
            if not (0.55 <= aspect <= 1.55):
                continue

            cx = x + w // 2
            cy = y + h // 2

            if cy > self.coords.station_icon_max_y:
                continue
            if cx < self.coords.side_button_zone_width_x and cy > self.coords.club_button_zone_top_y:
                continue
            # SAFETY: never return icon inside investor exclusion zone
            if excl.left <= cx <= excl.right and excl.top <= cy <= excl.bottom:
                continue

            if self._is_circle_around(red_mask, cx, cy, h):
                icons.append(Point(cx, cy))

        icons.sort(key=lambda p: p.y)
        return icons

    def find_tips(self, hsv_img: np.ndarray, bgr_img: np.ndarray) -> List[Point]:
        """Finds yellow tip coins on customer tables within the safe table zone."""
        clamped = self._clamp_region(self.coords.table_zone,
                                     hsv_img.shape[1], hsv_img.shape[0])
        if clamped is None:
            return []
        x0, y0, w0, h0 = clamped
        roi = hsv_img[y0:y0+h0, x0:x0+w0]
        yellow_mask = cv2.inRange(roi, *TIP_YELLOW_RANGE)
        contours, _ = cv2.findContours(yellow_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        tips: List[Point] = []
        min_px = self.coords.sx(18)
        max_px = self.coords.sx(110)
        excl = self.coords.investor_exclusion_roi
        for c in contours:
            rx, ry, rw, rh = cv2.boundingRect(c)
            if not (min_px <= rw <= max_px and min_px <= rh <= max_px):
                continue
            cx = x0 + rx + rw // 2
            cy = y0 + ry + rh // 2
            if excl.left <= cx <= excl.right and excl.top <= cy <= excl.bottom:
                continue
            tips.append(Point(cx, cy))
            if len(tips) >= 6:
                break
        return tips

    def _is_circle_around(self, red_mask: np.ndarray, cx: int, cy: int, arrow_h: int) -> bool:
        radius = int(arrow_h * 0.85)
        hits = 0
        angles = np.linspace(0, 2 * np.pi, 12, endpoint=False)
        h, w = red_mask.shape
        for a in angles:
            px = int(cx + radius * np.cos(a))
            py = int(cy - radius * np.sin(a))
            if 0 <= px < w and 0 <= py < h and red_mask[py, px] > 0:
                hits += 1
        return hits >= 4

    def _clamp_region(self, r: Region, max_w: int, max_h: int) -> Optional[Tuple[int, int, int, int]]:
        left = max(0, min(r.left, max_w - 1))
        top = max(0, min(r.top, max_h - 1))
        right = max(left + 1, min(r.right, max_w))
        bottom = max(top + 1, min(r.bottom, max_h))
        w = right - left
        h = bottom - top
        return (left, top, w, h) if w > 0 and h > 0 else None
