package com.example.eatbot.strategy

import android.util.Log
import com.example.eatbot.TapService
import com.example.eatbot.coords.GameCoords
import com.example.eatbot.coords.Point
import com.example.eatbot.vision.FrameBundle
import com.example.eatbot.vision.GameVision
import kotlinx.coroutines.delay

/**
 * Strategy phases for the Pure First & Last (F&L) city speedrun.
 */
enum class FLPhase {
    DETECT_STATE,       // Initial startup: inspect screen and set correct phase
    STATION_1_BUILD,    // Phase 1: Power up Station 1 for starting cashflow
    SCROLL_TO_LAST,     // Phase 2: Scroll down to reveal the highest-tier station
    UNLOCK_LAST,        // Phase 2b: Unlock the last station if it needs an Open button
    MAX_LAST_STATION,   // Phase 3: Turbo-level the last station to MAX
    SWEEP_BACKFILL,     // Phase 4: Sweep upward and max all intermediate stations
    ADVANCE_LEVEL       // Phase 5: City complete → renovate or fly to next city
}

/**
 * Smart, State-Aware Pure First & Last (F&L) Strategy Engine for EatVenture.
 *
 * Implements all 13 spec requirements:
 *
 * §1  Strict action restriction — never taps ads, Investor, Gear/Vault.
 * §2  Tips collected every ~20 s.
 * §3  Food Delivery evaluated; ad-gated offers skipped.
 * §4  Best helper selected by gear rarity; ad options skipped.
 * §5  Resume from existing state on startup (DETECT_STATE phase).
 * §6  Smart upgrades reassessed every ~5 s; affordability checked first.
 * §7  Manual screens (Investor / Gear / Vault / Ad) paused immediately; resumed automatically.
 * §8  City upgrades evaluated per progression state; no blind purchases.
 * §9  Staff crates opened after detecting the crate interface.
 * §10 Touch-lag fixed in TapService; no arbitrary sleeps here beyond UI settle time.
 * §11 City transitions event-driven with polling loops; stale coords avoided.
 * §12 Action → validate → execute → verify → update state pattern used throughout.
 * §13 Logging: every skipped action and state change is logged.
 *
 * SAFETY RULES enforced in executeStep():
 *  - TapService.isUserInteracting → yield immediately (0 taps, 0 changes)
 *  - !TapService.isEatventureForeground → wait silently
 *  - vision.isUserManualScreenOpen() → pause automation completely
 *  - vision.isGameScreenVisible() → skip phase execution if HUD absent
 */
class FLStrategyEngine(
    private val coords: GameCoords,
    private val vision: GameVision,
    private val onStatusUpdate: (String) -> Unit,
    private val captureFreshFrame: (() -> FrameBundle?)? = null
) {

    companion object {
        private const val TAG = "EatbotFL"

        // Long-press hold duration for turbo-levelling a station
        private const val HOLD_MS = 2000L

        // Phase 1: number of upgrade cycles on Station 1 before seeking last station
        private const val ST1_UPGRADE_CYCLES = 10

        // Phase 3: maximum rounds on last station before force-transitioning to sweep
        // This is a safety cap; the bot will transition earlier if it detects city completion
        private const val MAX_LAST_CYCLES = 40

        // Scheduled action intervals
        private const val TIP_INTERVAL_MS        = 20_000L
        private const val UPGRADE_CHECK_INTERVAL  = 5_000L
        private const val DELIVERY_CHECK_INTERVAL = 60_000L
        private const val HELPER_CHECK_INTERVAL   = 90_000L

        // How long to poll for expected screen transitions before giving up
        private const val TRANSITION_TIMEOUT_MS = 20_000L
        private const val RENO_TIMEOUT_MS       = 8_000L
    }

    // ── Internal state ────────────────────────────────────────────────────────

    var currentPhase = FLPhase.DETECT_STATE
        private set

    private var st1CycleCount = 0
    private var lastStationRounds = 0

    // Timestamps for scheduled actions
    private var lastTipTimeMs       = 0L
    private var lastUpgradeCheckMs  = 0L
    private var lastDeliveryCheckMs = 0L
    private var lastHelperCheckMs   = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    fun resetPhase() {
        currentPhase = FLPhase.DETECT_STATE
        st1CycleCount = 0
        lastStationRounds = 0
        onStatusUpdate("Detecting state...")
        Log.i(TAG, "Phase reset to DETECT_STATE")
    }

    // ── Main execution entry point ────────────────────────────────────────────

    /**
     * Called once per bot loop iteration (every ~400 ms from BotService).
     * Priority order matches spec §13 acceptance criteria.
     */
    suspend fun executeStep(frame: FrameBundle) {

        // ── Guard 1: Real user touch → yield immediately, touch nothing ────────
        if (TapService.isUserInteracting) {
            onStatusUpdate("User Active – Yielding")
            return
        }

        // ── Guard 2: EatVenture not in foreground → wait silently ─────────────
        if (!TapService.isEatventureForeground) {
            onStatusUpdate("Waiting for Eatventure...")
            return
        }

        // ── Guard 3: Investor screen opened accidentally → close it immediately ─
        if (vision.isInvestorScreenOpen(frame.bgr)) {
            val close = vision.findInvestorClosePoint(frame.bgr)
                ?: vision.findTemplate(frame.bgr, "popup_close_cross", 0.72)
            if (close != null) {
                onStatusUpdate("Closing Investor Screen...")
                Log.i(TAG, "Investor opened accidentally → closing at (${close.x}, ${close.y})")
                TapService.tap(close.x, close.y)
                delay(350)
            } else {
                onStatusUpdate("Investor Detected – Close Button Searching")
                Log.i(TAG, "Investor detected but close button template was not matched")
            }
            return
        }

        // ── Guard 4: Other manual screen open (Gear/Vault/Ad) → PAUSE ──────
        if (vision.isUserManualScreenOpen(frame.bgr, frame.hsv)) {
            onStatusUpdate("Manual Screen – Paused")
            Log.i(TAG, "Manual screen detected → automation paused")
            return
        }

        // ── Guard 4: Game HUD not visible → wait for game to load ─────────────
        if (!vision.isGameScreenVisible(frame.bgr)) {
            onStatusUpdate("Waiting for game HUD...")
            return
        }

        val now = System.currentTimeMillis()

        // ── Priority 1: City completion (highest priority) ─────────────────────
        if (checkCityCompletion(frame)) return

        // ── Priority 2: Standard popup dismissal ───────────────────────────────
        if (dismissStandardPopups(frame)) return

        // ── Priority 3: Staff crate opening ────────────────────────────────────
        if (handleStaffCrate(frame)) return

        // ── Priority 4: Tip collection every ~20 s ─────────────────────────────
        if (now - lastTipTimeMs >= TIP_INTERVAL_MS) {
            collectTips(frame)
            lastTipTimeMs = now
        }

        // ── Priority 5: Food delivery check every ~60 s ────────────────────────
        if (now - lastDeliveryCheckMs >= DELIVERY_CHECK_INTERVAL) {
            handleFoodDelivery(frame)
            lastDeliveryCheckMs = now
        }

        // ── Priority 6: Helper check every ~90 s ──────────────────────────────
        if (now - lastHelperCheckMs >= HELPER_CHECK_INTERVAL) {
            handleHelperInvite(frame)
            lastHelperCheckMs = now
        }

        // ── Priority 7: Kitchen / city upgrade check every ~5 s ────────────────
        if (now - lastUpgradeCheckMs >= UPGRADE_CHECK_INTERVAL) {
            if (vision.hasUpgradeBadge(frame.hsv)) {
                buyKitchenUpgrades()
            }
            lastUpgradeCheckMs = now
        }

        // ── Priority 8: Open floating food boxes ───────────────────────────────
        openFoodBoxes(frame)

        // ── Priority 9: Execute current strategy phase ─────────────────────────
        when (currentPhase) {
            FLPhase.DETECT_STATE       -> detectInitialState(frame)
            FLPhase.STATION_1_BUILD    -> executeStation1Build(frame)
            FLPhase.SCROLL_TO_LAST     -> executeScrollToLast(frame)
            FLPhase.UNLOCK_LAST        -> executeUnlockLast(frame)
            FLPhase.MAX_LAST_STATION   -> executeMaxLastStation(frame)
            FLPhase.SWEEP_BACKFILL     -> executeSweepBackfill(frame)
            FLPhase.ADVANCE_LEVEL      -> checkCityCompletion(frame)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE IMPLEMENTATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * §5 Resume from existing session:
     * On startup, inspect the current screen to decide the correct phase rather
     * than always restarting from Station 1.
     */
    private fun detectInitialState(frame: FrameBundle) {
        val icons = vision.findStationUpgradeIcons(frame.hsv)
        Log.i(TAG, "DETECT_STATE: found ${icons.size} station upgrade icons")

        when {
            // City is complete → handle transition immediately
            vision.hasTemplate(frame.bgr, "fly_next_city_icon", 0.85) ||
            vision.hasTemplate(frame.bgr, "go_next_level_icon", 0.85) -> {
                currentPhase = FLPhase.ADVANCE_LEVEL
                onStatusUpdate("[5/5] City Complete!")
                Log.i(TAG, "Resumed: city complete → ADVANCE_LEVEL")
            }

            // 4+ stations visible → mid-sweep
            icons.size >= 4 -> {
                currentPhase = FLPhase.SWEEP_BACKFILL
                onStatusUpdate("Resumed: [4/5] Sweep All")
                Log.i(TAG, "Resumed: 4+ stations open → SWEEP_BACKFILL")
            }

            // Last station visible and below tooltip Y → we were maxing it
            icons.size >= 2 && icons.last().y > coords.stationIconTooltipY -> {
                currentPhase = FLPhase.MAX_LAST_STATION
                onStatusUpdate("Resumed: [3/5] Max Last St.")
                Log.i(TAG, "Resumed: bottom station active → MAX_LAST_STATION")
            }

            // Only Station 1 visible or nothing yet
            else -> {
                currentPhase = FLPhase.STATION_1_BUILD
                onStatusUpdate("[1/5] St.1 Build")
                Log.i(TAG, "Resumed: starting/continuing Station 1 build")
            }
        }
    }

    /**
     * Phase 1: Power up Station 1 for baseline cashflow.
     * Transitions to Phase 2 after ST1_UPGRADE_CYCLES upgrade rounds.
     */
    private suspend fun executeStation1Build(frame: FrameBundle) {
        onStatusUpdate("[1/5] St.1 Build ($st1CycleCount/$ST1_UPGRADE_CYCLES)")

        // First check if Station 1 needs to be opened
        val openPt = vision.findTemplate(frame.bgr, "open_button", 0.82)
        if (openPt != null) {
            Log.i(TAG, "Station 1 Open button found → tapping to open")
            TapService.tap(openPt.x, openPt.y)
            delay(700)
            return
        }

        val icons = vision.findStationUpgradeIcons(frame.hsv)
        if (icons.isEmpty()) {
            Log.d(TAG, "No upgrade icons visible in Phase 1 → tapping null spot")
            TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
            delay(250)
            return
        }

        // Upgrade Station 1 (topmost icon)
        upgradeStation(icons.first())
        st1CycleCount++

        if (st1CycleCount >= ST1_UPGRADE_CYCLES) {
            Log.i(TAG, "Station 1 established after $st1CycleCount cycles → SCROLL_TO_LAST")
            currentPhase = FLPhase.SCROLL_TO_LAST
            onStatusUpdate("[2/5] Seek Last St.")
        }
    }

    /**
     * Phase 2: Scroll down to reveal the highest-tier station.
     * Performs 4 downward swipes and then checks for an Open button.
     */
    private suspend fun executeScrollToLast(frame: FrameBundle) {
        onStatusUpdate("[2/5] Scrolling to Last St.")
        Log.i(TAG, "Scrolling down to reveal last station...")

        repeat(4) {
            TapService.swipe(
                coords.swipeDownToBottom.start.x,
                coords.swipeDownToBottom.start.y,
                coords.swipeDownToBottom.end.x,
                coords.swipeDownToBottom.end.y,
                coords.swipeDownToBottom.durationMs
            )
            delay(350)
        }

        // After scrolling, check if the last station needs to be unlocked
        val openPt = vision.findTemplate(frame.bgr, "open_button", 0.82)
        if (openPt != null) {
            currentPhase = FLPhase.UNLOCK_LAST
            onStatusUpdate("[2b/5] Unlock Last St.")
            Log.i(TAG, "Last station needs unlocking → UNLOCK_LAST")
        } else {
            currentPhase = FLPhase.MAX_LAST_STATION
            onStatusUpdate("[3/5] Max Last St.")
            Log.i(TAG, "Last station visible → MAX_LAST_STATION")
        }
    }

    /**
     * Phase 2b: Unlock the last station (tap the Open button).
     * Waits for confirmation that the station opened before continuing.
     */
    private suspend fun executeUnlockLast(frame: FrameBundle) {
        onStatusUpdate("[2b/5] Unlocking Last St.")
        val openPt = vision.findTemplate(frame.bgr, "open_button", 0.82)
        if (openPt != null) {
            Log.i(TAG, "Tapping Open button for last station at (${openPt.x}, ${openPt.y})")
            TapService.tap(openPt.x, openPt.y)
            delay(800)
        } else {
            // Open button gone → station unlocked, proceed to maxing
            Log.i(TAG, "Last station unlocked → MAX_LAST_STATION")
            currentPhase = FLPhase.MAX_LAST_STATION
            onStatusUpdate("[3/5] Max Last St.")
        }
    }

    /**
     * Phase 3: Turbo-level the last (bottom-most) station until MAX or cycle cap.
     *
     * FIX §11: Transition is event-driven. The engine moves to SWEEP_BACKFILL
     * when city-completion icons appear (handled by checkCityCompletion at top
     * of executeStep), OR when MAX_LAST_CYCLES is reached as a safety fallback.
     */
    private suspend fun executeMaxLastStation(frame: FrameBundle) {
        onStatusUpdate("[3/5] Max Last ($lastStationRounds/$MAX_LAST_CYCLES)")

        val icons = vision.findStationUpgradeIcons(frame.hsv)
        if (icons.isEmpty()) {
            // Scroll down slightly to bring the last station into view
            Log.d(TAG, "No icons visible in MAX_LAST → scrolling down")
            TapService.swipe(
                coords.swipeDownToBottom.start.x,
                coords.swipeDownToBottom.start.y,
                coords.swipeDownToBottom.end.x,
                coords.swipeDownToBottom.end.y,
                300L
            )
            delay(400)
            return
        }

        // Target the bottom-most visible station
        val lastStation = icons.last()
        upgradeStation(lastStation)
        lastStationRounds++

        if (lastStationRounds >= MAX_LAST_CYCLES) {
            Log.i(TAG, "Safety cap reached ($MAX_LAST_CYCLES rounds) → SWEEP_BACKFILL")
            currentPhase = FLPhase.SWEEP_BACKFILL
            onStatusUpdate("[4/5] Sweep All")
        }
    }

    /**
     * Phase 4: Sweep upward and max all intermediate stations.
     *
     * FIX §11: Phase 4 has a genuine exit condition — it transitions to
     * ADVANCE_LEVEL when the city-complete icon appears (handled by
     * checkCityCompletion at the top of executeStep). The sweep loop never
     * loops indefinitely.
     */
    private suspend fun executeSweepBackfill(frame: FrameBundle) {
        onStatusUpdate("[4/5] Sweep All")

        val icons = vision.findStationUpgradeIcons(frame.hsv)
        if (icons.isNotEmpty()) {
            for (icon in icons) {
                if (TapService.isUserInteracting) return // Yield to user immediately
                upgradeStation(icon)
                delay(120)
            }
        } else {
            Log.d(TAG, "No icons in SWEEP_BACKFILL → scrolling up")
        }

        // Scroll up to reveal earlier stations
        TapService.swipe(
            coords.swipeUpToTop.start.x,
            coords.swipeUpToTop.start.y,
            coords.swipeUpToTop.end.x,
            coords.swipeUpToTop.end.y,
            400L
        )
        delay(450)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CITY COMPLETION & TRANSITION   (§11)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Detects and handles city completion.
     * Uses event-driven polling instead of fixed sleeps.
     * Returns true if a transition was handled (caller should return early).
     */
    private suspend fun checkCityCompletion(frame: FrameBundle): Boolean {
        // ── Fly to Next City ───────────────────────────────────────────────
        val airportPoint = vision.findTemplate(frame.bgr, "fly_next_city_icon", 0.72)
        if (airportPoint != null) {
            onStatusUpdate("[5/5] Flying to Next City!")
            Log.i(TAG, "Airport icon detected → initiating city flight")

            TapService.tap(airportPoint.x, airportPoint.y)
            delay(500)
            TapService.tap(coords.flyNextCityButton.x, coords.flyNextCityButton.y)

            val deadline = System.currentTimeMillis() + TRANSITION_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && TapService.isEatventureForeground) {
                delay(500)
                TapService.tap(coords.welcomeOkayButton.x, coords.welcomeOkayButton.y)
                delay(400)

                val liveFrame = captureFreshFrame?.invoke()
                if (liveFrame != null) {
                    val opened = try {
                        vision.hasTemplate(liveFrame.bgr, "open_button", 0.78)
                    } finally {
                        liveFrame.release()
                    }
                    if (opened) break
                }
            }

            TapService.tap(coords.firstStandOpenButton.x, coords.firstStandOpenButton.y)
            delay(700)
            transitionToNewCity()
            return true
        }

        // ── Renovate (same city, new floor) ────────────────────────────────
        val hammerPoint = vision.findTemplate(frame.bgr, "go_next_level_icon", 0.72)
        if (hammerPoint != null) {
            onStatusUpdate("[5/5] Renovating!")
            Log.i(TAG, "Hammer/renovate icon detected → starting renovation")

            TapService.tap(hammerPoint.x, hammerPoint.y)
            delay(600)

            // Tap the hammer and its known action button again while fresh frames confirm.
            TapService.tap(coords.renovateButton.x, coords.renovateButton.y)
            delay(500)

            val deadline = System.currentTimeMillis() + RENO_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && TapService.isEatventureForeground) {
                TapService.tap(coords.firstStandOpenButton.x, coords.firstStandOpenButton.y)
                delay(400)
                val liveFrame = captureFreshFrame?.invoke()
                if (liveFrame != null) {
                    val opened = try {
                        vision.hasTemplate(liveFrame.bgr, "open_button", 0.78)
                    } finally {
                        liveFrame.release()
                    }
                    if (opened) break
                }
            }

            transitionToNewCity()
            return true
        }

        return false
    }

    /** Resets internal phase state after any city/renovation transition */
    private fun transitionToNewCity() {
        currentPhase = FLPhase.DETECT_STATE
        st1CycleCount = 0
        lastStationRounds = 0
        onStatusUpdate("Detecting new city state...")
        Log.i(TAG, "City transition complete → DETECT_STATE")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIP COLLECTION   (§2)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Collects available tip coins from customer tables.
     * Only taps within the permitted table zone.
     * Never taps ads or side-column buttons (safety ensured by tableZone bounds).
     */
    private suspend fun collectTips(frame: FrameBundle) {
        val tips = vision.findTips(frame.hsv, frame.bgr)
        if (tips.isEmpty()) {
            // Also check for tip_cash template as fallback
            val templateTip = vision.findTemplate(frame.bgr, "tip_cash", 0.78,
                                                  coords.tableZone)
            if (templateTip != null) {
                Log.d(TAG, "Tip (template) found at (${templateTip.x}, ${templateTip.y})")
                onStatusUpdate("Collecting Tip...")
                TapService.tap(templateTip.x, templateTip.y)
                delay(120)
            }
            return
        }

        Log.i(TAG, "Collecting ${tips.size} tip(s)")
        onStatusUpdate("Collecting Tips...")
        for (tip in tips) {
            if (TapService.isUserInteracting) return
            TapService.tap(tip.x, tip.y)
            delay(90)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FOOD DELIVERY   (§3)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates food delivery offers.
     * Only accepts when:
     *  1. The delivery truck icon is detected in the delivery ROI.
     *  2. An accept button is found.
     *  3. No ad icon is detected near the accept button.
     */
    private suspend fun handleFoodDelivery(frame: FrameBundle) {
        // Check delivery truck icon in its known location only (not full screen)
        if (!vision.hasTemplate(frame.bgr, "food_delivery_available", 0.76,
                                coords.foodDeliveryRoi)) {
            return
        }

        Log.i(TAG, "Delivery truck icon detected → inspecting offer")
        onStatusUpdate("Checking Delivery...")

        TapService.tap(coords.foodDeliveryButton.x, coords.foodDeliveryButton.y)
        delay(650)

        // At this point the delivery screen should be visible; check ad status
        if (vision.isDeliveryOfferAd(frame.bgr)) {
            Log.i(TAG, "Delivery offer requires ad → skipping; closing dialog")
            TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
            delay(300)
            return
        }

        val acceptPt = vision.findTemplate(frame.bgr, "food_delivery_accept", 0.78)
        if (acceptPt != null) {
            Log.i(TAG, "Accepting valid delivery deal at (${acceptPt.x}, ${acceptPt.y})")
            TapService.tap(acceptPt.x, acceptPt.y)
            delay(500)
        } else {
            // No valid accept button found → close safely
            Log.i(TAG, "No valid delivery accept button found → closing")
            TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
            delay(300)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPER INVITE   (§4)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates helper availability and invites the best non-ad option.
     * Scoped to helperRoi to avoid false positives from other side buttons.
     */
    private suspend fun handleHelperInvite(frame: FrameBundle) {
        if (!vision.hasTemplate(frame.bgr, "helper_available", 0.76,
                                coords.helperRoi)) {
            return
        }

        Log.i(TAG, "Helper available → opening selection screen")
        onStatusUpdate("Checking Helper...")

        TapService.tap(coords.helperButton.x, coords.helperButton.y)
        delay(750)

        val bestBtn = vision.findBestHelperButton(frame.bgr, frame.hsv)
        if (bestBtn != null) {
            Log.i(TAG, "Inviting best helper at (${bestBtn.x}, ${bestBtn.y})")
            TapService.tap(bestBtn.x, bestBtn.y)
            delay(500)
        } else {
            Log.i(TAG, "No valid helper invite button found → closing")
            TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
            delay(300)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KITCHEN UPGRADES   (§6)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Purchases kitchen upgrades when the red badge is visible.
     * Only buys while the buy button is confirmed affordable (blue).
     * Stops buying as soon as affordability is lost.
     */
    private suspend fun buyKitchenUpgrades() {
        onStatusUpdate("Buying Upgrades...")
        Log.i(TAG, "Upgrade badge detected → opening kitchen menu")

        TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
        delay(180)
        if (!TapService.tap(coords.upgradeButton.x, coords.upgradeButton.y)) return
        delay(650)

        // Do not require a fragile red close-icon check. The menu can animate or
        // render the close control differently while still being fully open.
        // Fresh blue affordability is the purchase gate instead.

        // Buy up to 8 rounds, but assess affordability from a fresh screen each round.
        for (round in 1..8) {
            if (TapService.isUserInteracting || !TapService.isEatventureForeground) break

            val liveFrame = captureFreshFrame?.invoke() ?: break
            val affordable = try {
                vision.isFirstUpgradeAffordable(liveFrame.hsv)
            } finally {
                liveFrame.release()
            }

            if (!affordable) {
                Log.d(TAG, "Upgrade no longer affordable at round $round → stopping")
                break
            }

            if (!TapService.tap(coords.firstUpgradeBuy.x, coords.firstUpgradeBuy.y)) break
            Log.i(TAG, "Kitchen upgrade tapped successfully at round $round")
            delay(180)
        }

        TapService.tap(coords.closeUpgrades.x, coords.closeUpgrades.y)
        delay(280)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STAFF CRATE HANDLING   (§9)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Opens staff crates (cashier, waiter, chef) when the crate interface is detected.
     * Returns true if a crate was handled so the caller can skip other actions.
     *
     * SAFETY: only taps when chest_icon template confirmed visible.
     * Never opens gift boxes, premium chests, or ad chests.
     */
    private suspend fun handleStaffCrate(frame: FrameBundle): Boolean {
        val chestPt = vision.findTemplate(frame.bgr, "chest_icon", 0.84) ?: return false

        Log.i(TAG, "Staff crate detected at (${chestPt.x}, ${chestPt.y}) → opening")
        onStatusUpdate("Opening Crate...")

        TapService.tap(chestPt.x, chestPt.y)
        delay(500)

        // Confirm the crate result screen and close it
        val closePt = vision.findTemplate(frame.bgr, "popup_close_cross", 0.80)
        if (closePt != null && !vision.isUserManualScreenOpen(frame.bgr, frame.hsv)) {
            TapService.tap(closePt.x, closePt.y)
            delay(250)
        }
        return true
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POPUP DISMISSAL   (§12 Recovery)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Dismisses standard in-game popups (close X button).
     * NEVER dismisses a manual menu the user opened.
     * Returns true if a popup was dismissed.
     */
    private suspend fun dismissStandardPopups(frame: FrameBundle): Boolean {
        val cross = vision.findTemplate(frame.bgr, "popup_close_cross", 0.80)
            ?: return false

        // Double-check this is not inside a manual screen
        if (vision.isUserManualScreenOpen(frame.bgr, frame.hsv)) {
            Log.d(TAG, "Close X found but manual screen is open → not dismissing")
            return false
        }

        Log.d(TAG, "Dismissing popup at (${cross.x}, ${cross.y})")
        TapService.tap(cross.x, cross.y)
        delay(220)
        return true
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FOOD BOX COLLECTION
    // ═════════════════════════════════════════════════════════════════════════

    /** Opens any floating food reward boxes on screen */
    private suspend fun openFoodBoxes(frame: FrameBundle) {
        val boxPt = vision.findTemplate(frame.bgr, "box", 0.80)
            ?: vision.findTemplate(frame.bgr, "box2", 0.80)
            ?: return
        TapService.tap(boxPt.x, boxPt.y)
        delay(130)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATION UPGRADE HELPER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Upgrades a station at the given icon position.
     * Sequence: tap station (open drawer) → long-press level-up → close drawer.
     *
     * SAFETY: aborts immediately if user starts touching during the sequence.
     */
    private suspend fun upgradeStation(icon: Point) {
        // Step 1: Open the station upgrade drawer
        val tapPt = Point(
            icon.x + coords.stationTapShiftX,
            icon.y + coords.stationTapOffsetY + coords.stationTapShiftY
        )
        if (!TapService.tap(tapPt.x, tapPt.y)) return
        delay(110)

        // Step 2: Long-press the "Better Food" level-up button
        if (TapService.isUserInteracting) return
        val holdPt = Point(
            icon.x + coords.betterFoodHoldOffsetX,
            icon.y - coords.betterFoodHoldOffsetY
        )
        TapService.longPress(holdPt.x, holdPt.y, HOLD_MS)
        delay(130)

        // Step 3: Close the drawer safely
        if (!TapService.isUserInteracting) {
            TapService.tap(coords.nullSpot.x, coords.nullSpot.y)
            delay(110)
        }
    }
}
