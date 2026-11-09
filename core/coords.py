"""Resolution-independent coordinate definitions with dynamic screen scaling."""

from dataclasses import dataclass
from typing import Tuple, List

REF_WIDTH  = 1220
REF_HEIGHT = 2712

@dataclass(frozen=True)
class Point:
    x: int
    y: int

@dataclass(frozen=True)
class Region:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    @classmethod
    def around(cls, p: Point, hw: int, hh: int) -> 'Region':
        return cls(p.x - hw, p.y - hh, p.x + hw, p.y + hh)

@dataclass(frozen=True)
class Swipe:
    start: Point
    end: Point
    duration_ms: int = 400

class GameCoords:
    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.x_scale = width / REF_WIDTH
        self.y_scale = height / REF_HEIGHT

    def sx(self, x: int) -> int:
        return round(x * self.x_scale)

    def sy(self, y: int) -> int:
        return round(y * self.y_scale)

    def sp(self, p: Point) -> Point:
        return Point(self.sx(p.x), self.sy(p.y))

    def sr(self, r: Region) -> Region:
        return Region(self.sx(r.left), self.sy(r.top), self.sx(r.right), self.sy(r.bottom))

    def ss(self, s: Swipe) -> Swipe:
        return Swipe(self.sp(s.start), self.sp(s.end), s.duration_ms)

    @property
    def null_spot(self) -> Point:
        # Safe spot on floor just below top bar right side (x~886) - NEVER hits the Active Skills circle (x~610)
        return self.sp(Point(886, 278))

    @property
    def settings_roi(self) -> Region:
        # Settings cog icon is in the top-right header area
        return Region(
            max(0, self.width - int(260 * self.x_scale)),
            int(30 * self.y_scale),
            self.width,
            int(360 * self.y_scale)
        )

    @property
    def upgrade_button(self) -> Point:
        return self.sp(Point(1093, 2524))

    @property
    def upgrade_badge_roi(self) -> Region:
        return self.sr(Region(971, 2407, 1107, 2542))

    @property
    def first_upgrade_buy(self) -> Point:
        return self.sp(Point(968, 1115))

    @property
    def first_upgrade_buy_roi(self) -> Region:
        return self.sr(Region.around(Point(968, 1115), 100, 34))

    @property
    def close_upgrades(self) -> Point:
        return self.sp(Point(1035, 908))

    @property
    def close_upgrades_roi(self) -> Region:
        return self.sr(Region.around(Point(1035, 908), 40, 40))

    @property
    def next_level_button(self) -> Point:
        return self.sp(Point(127, 2521))

    @property
    def renovate_button(self) -> Point:
        return self.sp(Point(593, 1912))

    @property
    def fly_next_city_button(self) -> Point:
        return self.sp(Point(593, 1892))

    @property
    def welcome_okay_button(self) -> Point:
        return self.sp(Point(618, 1695))

    @property
    def first_stand_open_button(self) -> Point:
        return self.sp(Point(604, 1604))

    @property
    def swipe_down_to_bottom(self) -> Swipe:
        return self.ss(Swipe(Point(646, 1900), Point(646, 900), 450))

    @property
    def swipe_up_to_top(self) -> Swipe:
        return self.ss(Swipe(Point(646, 900), Point(646, 1900), 450))

    # Station icon calibration thresholds
    @property
    def station_icon_min_y(self) -> int:
        return self.sy(1100)

    @property
    def station_icon_max_y(self) -> int:
        return self.sy(2200)

    @property
    def station_icon_tooltip_y(self) -> int:
        return self.sy(1240)

    @property
    def station_tap_offset_y(self) -> int:
        return self.sy(22)

    @property
    def station_tap_shift_x(self) -> int:
        return self.sx(2)

    @property
    def station_tap_shift_y(self) -> int:
        return self.sy(2)

    @property
    def better_food_hold_offset_x(self) -> int:
        return self.sx(20)

    @property
    def better_food_hold_offset_y(self) -> int:
        return self.sy(130)

    @property
    def upgrade_arrow_min_height_y(self) -> int:
        return self.sy(22)

    @property
    def upgrade_arrow_max_height_y(self) -> int:
        return self.sy(48)

    @property
    def side_button_zone_width_x(self) -> int:
        return self.sx(180)

    @property
    def club_button_zone_top_y(self) -> int:
        return self.sy(2029)

    # ── Customer table zone (tip collection area) ──────────────────────────────
    @property
    def table_zone(self) -> Region:
        # Excludes notification banners, floating icons, and investor zone
        return self.sr(Region(220, 700, 1080, 1720))

    # ── Left side-button column ────────────────────────────────────────────────
    @property
    def helper_button(self) -> Point:
        return self.sp(Point(120, 434))

    @property
    def helper_roi(self) -> Region:
        return self.sr(Region(0, 370, 200, 510))

    @property
    def food_delivery_button(self) -> Point:
        return self.sp(Point(120, 600))

    @property
    def food_delivery_roi(self) -> Region:
        return self.sr(Region(0, 540, 200, 680))

    # SAFETY: Investor exclusion zone – NEVER tap here (covers counter + button column)
    @property
    def investor_exclusion_roi(self) -> Region:
        return self.sr(Region(0, 650, 450, 1250))

    # ── Manual-only bottom nav zones ───────────────────────────────────────────
    @property
    def bottom_nav_hero_roi(self) -> Region:
        return self.sr(Region(0, 2400, 250, 2712))

    @property
    def bottom_nav_vault_roi(self) -> Region:
        return self.sr(Region(350, 2400, 600, 2712))
