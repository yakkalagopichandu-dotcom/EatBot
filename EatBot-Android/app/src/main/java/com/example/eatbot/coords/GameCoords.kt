package com.example.eatbot.coords

import kotlin.math.roundToInt

data class Point(val x: Int, val y: Int)

data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun around(center: Point, halfWidth: Int, halfHeight: Int): Region {
            return Region(
                center.x - halfWidth,
                center.y - halfHeight,
                center.x + halfWidth,
                center.y + halfHeight
            )
        }
    }
}

data class Swipe(val start: Point, val end: Point, val durationMs: Long = 450L)

/**
 * Resolution-independent coordinate definitions for EatVenture Bot.
 *
 * Reference resolution: 1220 × 2712 (calibrated from gameplay screenshots).
 * All coordinates auto-scale to the actual device screen at runtime.
 *
 * SAFETY ZONES documented:
 *  - Investor button:  left side column ~y 710-830 (NEVER tapped by bot)
 *  - Character/Gear:   bottom nav left zone (NEVER tapped by bot)
 *  - Vault:            bottom nav second zone (NEVER tapped by bot)
 *  - Ad buttons:       identified by vision detection, never tapped
 */
class GameCoords(val screenWidth: Int, val screenHeight: Int) {
    companion object {
        const val REF_WIDTH = 1220f
        const val REF_HEIGHT = 2712f
    }

    val xScale = screenWidth / REF_WIDTH
    val yScale = screenHeight / REF_HEIGHT

    fun sx(x: Int): Int = (x * xScale).roundToInt()
    fun sy(y: Int): Int = (y * yScale).roundToInt()
    fun sp(p: Point): Point = Point(sx(p.x), sy(p.y))
    fun sr(r: Region): Region = Region(sx(r.left), sy(r.top), sx(r.right), sy(r.bottom))
    fun ss(s: Swipe): Swipe = Swipe(sp(s.start), sp(s.end), s.durationMs)

    // ── Settings / HUD anchors ────────────────────────────────────────────────
    val settings = sp(Point(1134, 161))
    val settingsRoi = Region(
        maxOf(0, screenWidth - (260 * xScale).roundToInt()),
        (30 * yScale).roundToInt(),
        screenWidth,
        (360 * yScale).roundToInt()
    )

    /**
     * Safe "null spot" — a zone in the upper restaurant floor (x=886, y=278) that has no
     * interactive elements. Tapping it closes station drawers / tooltips safely.
     * Verified: Safe from Active Skills circle (x=610), Investor column, and tables.
     */
    val nullSpot = sp(Point(886, 278))

    // ── Upgrade menu ──────────────────────────────────────────────────────────
    val upgradeButton = sp(Point(1093, 2524))
    val upgradeBadgeRoi = sr(Region(971, 2407, 1107, 2542))
    val firstUpgradeBuy = sp(Point(968, 1115))
    val firstUpgradeBuyRoi = sr(Region.around(Point(968, 1115), 100, 34))
    val closeUpgrades = sp(Point(1035, 908))
    val closeUpgradesRoi = sr(Region.around(Point(1035, 908), 40, 40))

    // ── City progression (Renovate / Fly-to-next-city) ───────────────────────
    val nextLevelButton = sp(Point(127, 2521))
    val renovateButton = sp(Point(593, 1912))
    val flyNextCityButton = sp(Point(593, 1892))
    val welcomeOkayButton = sp(Point(618, 1695))
    val firstStandOpenButton = sp(Point(604, 1604))

    // ── Chests / Crates ───────────────────────────────────────────────────────
    // Only staff-related crates (cashier, waiter, chef) — opened after purchase.
    val chest = sp(Point(110, 734))
    val closeChest = sp(Point(1127, 200))
    val crateCenterTap = sp(Point(610, 1400))

    // ── Station Upgrade Geometry ──────────────────────────────────────────────
    val stationIconMinY = sy(1100)
    val stationIconMaxY = sy(2200)
    val stationIconTooltipY = sy(1240)
    val clubButtonZoneTopY = sy(2029)
    val sideButtonZoneWidthX = sx(180)

    val stationTapOffsetY = sy(22)
    val stationTapShiftX = sx(2)
    val stationTapShiftY = sy(2)
    val betterFoodHoldOffsetX = sx(20)
    val betterFoodHoldOffsetY = sy(130)
    val betterFoodTapOffsetX = sx(110)

    val upgradeArrowMinHeightY = sy(22)
    val upgradeArrowMaxHeightY = sy(48)

    // ── Swipe gestures ────────────────────────────────────────────────────────
    // Scroll DOWN: finger moves up → reveals lower (later) stations
    val swipeDownToBottom = ss(Swipe(Point(646, 1900), Point(646, 900), 450L))
    // Scroll UP: finger moves down → reveals upper (earlier) stations
    val swipeUpToTop = ss(Swipe(Point(646, 900), Point(646, 1900), 450L))
    val swipeLittleUp = ss(Swipe(Point(646, 604), Point(646, 1133), 300L))

    // ── Popular Dish / Skills ─────────────────────────────────────────────────
    val skillsCircle = sp(Point(610, 325))
    val popularDishButton = sp(Point(942, 1385))
    val popularDishButtonRoi = sr(Region.around(Point(942, 1385), 175, 90))
    val firstDishCandidates = listOf(
        sp(Point(360, 1304)),
        sp(Point(486, 1304)),
        sp(Point(610, 1304))
    )

    // ── Customer Table Zone for Tip Collection ────────────────────────────────
    // Covers the floor area where tip coins appear on customer tables.
    // Excludes side-button column, top HUD/notification area, and bottom nav bars.
    // Starts below y=700 so floating top/middle notification bubbles or investor heads are never tapped.
    val tableZone = sr(Region(220, 700, 1080, 1720))

    // ── Left Button Column ────────────────────────────────────────────────────
    /**
     * SAFETY: The left side column contains several buttons. We only tap:
     *   helperButton (~y=434):  "Invite helper" when NOT an ad
     *   foodDeliveryButton (~y=600): "Food delivery truck" when available
     *
     * We NEVER tap the Investor button (~y=755) or any button below it in that column.
     */
    val helperButton = sp(Point(120, 434))
    val helperRoi = sr(Region(0, 370, 200, 510))
    val helperInvite = sp(Point(804, 1019))
    val helperInviteRoi = sr(Region.around(Point(804, 1019), 147, 79))

    val foodDeliveryButton = sp(Point(120, 600))
    val foodDeliveryRoi = sr(Region(0, 540, 200, 680))
    val foodDeliveryAccept = sp(Point(610, 1166))
    val foodDeliveryAcceptRoi = sr(Region.around(Point(610, 1166), 147, 79))

    /**
     * INVESTOR EXCLUSION ZONE — bot NEVER taps anywhere in this region.
     * Encompasses the entire investor spawn zone, counter area, and button column (x: 0..450, y: 650..1250).
     */
    val investorExclusionRoi = sr(Region(0, 650, 450, 1250))

    // ── Manual-Only Bottom Nav Zones ──────────────────────────────────────────
    // Bot will detect if these are open and pause immediately.
    val bottomNavHeroRoi = sr(Region(0, 2400, 250, 2712))
    val bottomNavVaultRoi = sr(Region(350, 2400, 600, 2712))
}
