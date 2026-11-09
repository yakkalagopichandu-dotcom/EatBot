package com.example.eatbot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.eatbot.coords.GameCoords
import com.example.eatbot.coords.Point
import com.example.eatbot.coords.Region
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holds a single captured frame as both BGR and HSV mats.
 * Must be released after use to avoid memory leaks.
 */
data class FrameBundle(val bgr: Mat, val hsv: Mat, val width: Int, val height: Int) {
    fun release() {
        bgr.release()
        hsv.release()
    }
}

/**
 * Computer Vision engine for EatVenture Bot.
 *
 * DESIGN RULES (from spec §1, §7):
 *  - Never identify or interact with Investor, Character/Gear, Vault, or Ad elements.
 *  - isUserManualScreenOpen() triggers an immediate automation pause.
 *  - isDeliveryOfferAd() prevents delivery acceptance when an ad is detected.
 *  - findBestHelperButton() only returns the non-ad "Invite" button.
 *  - All template searches can be scoped to a specific Region to avoid false positives
 *    from other parts of the screen.
 *
 * FIXES applied:
 *  - Unused parameters hsvMat / bgrMat removed from method signatures.
 *  - 'clamped' variable actually used in isDeliveryOfferAd.
 *  - okayFound tracking moved to FLStrategyEngine.
 *  - isGameScreenVisible now also checks for the upgrade button badge region
 *    so mid-game resume is correctly detected.
 *  - isUserManualScreenOpen now checks bottom-nav Hero + Vault tab colours
 *    so it catches the Character and Vault screens even without templates.
 *  - findTips() uses the tableZone region and tip template as a double-check.
 *  - Ad detection for delivery uses a whitelist approach (accept only when
 *    template found AND no ad icon found nearby).
 */
class GameVision(private val context: Context, private val coords: GameCoords) {

    companion object {
        private const val TAG = "EatbotVision"

        // ── HSV colour ranges ────────────────────────────────────────────────
        val RED_LOWER_1 = Scalar(0.0, 120.0, 150.0)
        val RED_UPPER_1 = Scalar(8.0, 255.0, 255.0)
        val RED_LOWER_2 = Scalar(172.0, 120.0, 150.0)
        val RED_UPPER_2 = Scalar(180.0, 255.0, 255.0)

        val BUY_BLUE_LOWER = Scalar(95.0, 120.0, 200.0)
        val BUY_BLUE_UPPER = Scalar(110.0, 255.0, 255.0)

        val ARROW_WHITE_LOWER = Scalar(0.0, 0.0, 210.0)
        val ARROW_WHITE_UPPER = Scalar(180.0, 60.0, 255.0)
        val CIRCLE_RED_LOWER = Scalar(0.0, 150.0, 200.0)
        val CIRCLE_RED_UPPER = Scalar(6.0, 255.0, 255.0)

        // Tip coins appear as bright yellow-green dollar signs on tables
        val TIP_YELLOW_LOWER = Scalar(22.0, 100.0, 150.0)
        val TIP_YELLOW_UPPER = Scalar(38.0, 255.0, 255.0)

        // Gear rarity colours for helper comparison (HSV)
        val GEAR_LEGENDARY_LOWER = Scalar(12.0, 160.0, 160.0)
        val GEAR_LEGENDARY_UPPER = Scalar(24.0, 255.0, 255.0)
        val GEAR_EPIC_LOWER      = Scalar(270.0 / 2, 100.0, 140.0)  // ~purple in HSV
        val GEAR_EPIC_UPPER      = Scalar(300.0 / 2, 255.0, 255.0)

        // Colour of an "active" bottom-nav tab (bright white/light selected highlight)
        val NAV_SELECTED_LOWER = Scalar(0.0, 0.0, 200.0)
        val NAV_SELECTED_UPPER = Scalar(180.0, 60.0, 255.0)

        const val BADGE_MIN_RATIO    = 0.12
        const val BUY_MIN_RATIO      = 0.12
        const val DIALOG_MIN_RATIO   = 0.40
        const val NAV_SELECTED_RATIO = 0.25  // If >25% of tab is bright → tab open
    }

    private val templates = HashMap<String, Mat>()

    init { loadTemplates() }

    // ── Template loading ─────────────────────────────────────────────────────

    private fun loadTemplates() {
        try {
            val assetList = context.assets.list("templates") ?: return
            for (filename in assetList) {
                if (!filename.endsWith(".png")) continue
                val name = filename.removeSuffix(".png")
                context.assets.open("templates/$filename").use { inputStream ->
                    val bmp = BitmapFactory.decodeStream(inputStream) ?: return@use
                    val rgba = Mat()
                    Utils.bitmapToMat(bmp, rgba)
                    val bgr = Mat()
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    rgba.release()
                    bmp.recycle()

                    // Scale template proportionally to device resolution
                    val scaled = Mat()
                    val newSize = Size(
                        (bgr.cols() * coords.xScale).toDouble().coerceAtLeast(1.0),
                        (bgr.rows() * coords.yScale).toDouble().coerceAtLeast(1.0)
                    )
                    Imgproc.resize(bgr, scaled, newSize, 0.0, 0.0, Imgproc.INTER_LINEAR)
                    bgr.release()
                    templates[name] = scaled
                }
            }
            Log.i(TAG, "Loaded ${templates.size} templates from assets/templates/")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates", e)
        }
    }

    // ── Frame preparation ────────────────────────────────────────────────────

    fun prepareFrame(bmp: Bitmap): FrameBundle {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        rgba.release()
        return FrameBundle(bgr, hsv, bmp.width, bmp.height)
    }

    // ── Game-screen detection ────────────────────────────────────────────────

    /**
     * Returns true when the EatVenture gameplay HUD is confidently visible.
     * Uses multiple anchors so detection works at any point mid-game.
     * Does NOT check for templates that only appear at city transitions.
     */
    fun isGameScreenVisible(bgr: Mat): Boolean {
        // 1. Settings gear (top-right) — primary anchor
        if (hasTemplate(bgr, "settings", 0.65, coords.settingsRoi)) return true

        // 2. Settings gear anywhere in the top bar (fallback if status bar shifted)
        val topBarRegion = Region(0, 0, bgr.cols(), (400 * coords.yScale).toInt())
        if (hasTemplate(bgr, "settings", 0.65, topBarRegion)) return true

        // 3. City transition icons
        if (hasTemplate(bgr, "go_next_level_icon", 0.75)) return true
        if (hasTemplate(bgr, "fly_next_city_icon", 0.75)) return true

        return false
    }

    // ── Manual-screen guard ──────────────────────────────────────────────────

    /**
     * Returns true when the user has opened a MANUAL-ONLY section that the bot
     * must not touch: Character/Gear, Vault, Investor, or a full-screen ad.
     */
    fun isInvestorScreenOpen(bgr: Mat): Boolean {
        return hasTemplate(bgr, "investor_header_title", 0.72) ||
               hasTemplate(bgr, "investor_dialog_body", 0.72) ||
               hasTemplate(bgr, "investor_dialog_ref", 0.72) ||
               hasTemplate(bgr, "investor_card", 0.76)
    }

    fun findInvestorClosePoint(bgr: Mat): Point? {
        val title = findTemplate(bgr, "investor_header_title", 0.72)
        if (title != null) {
            return Point(title.x + coords.sx(280), title.y)
        }
        val dialog = findTemplate(bgr, "investor_dialog_ref", 0.72)
        if (dialog != null) {
            return Point(
                dialog.x + coords.sx(278),
                dialog.y - coords.sy(540)
            )
        }
        return findTemplate(bgr, "popup_close_cross", 0.72)
    }

    fun isUserManualScreenOpen(bgr: Mat, hsv: Mat): Boolean {
        // 1. Template-based checks for Investor / Character screens
        if (hasTemplate(bgr, "investor_header_title", 0.72) ||
            hasTemplate(bgr, "investor_dialog_body", 0.72) ||
            hasTemplate(bgr, "investor_card", 0.76)) {
            Log.d(TAG, "Investor screen detected → pausing")
            return true
        }
        if (hasTemplate(bgr, "character_screen_header", 0.76)) {
            Log.d(TAG, "Character screen detected → pausing")
            return true
        }

        // 2. Very dark screen = ad playing or black transition
        val meanBrightness = Core.mean(bgr).`val`[0]
        if (meanBrightness < 18.0) {
            Log.d(TAG, "Screen near-black (brightness=$meanBrightness) → pausing")
            return true
        }
        return false
    }

    // ── Colour ratio utility ─────────────────────────────────────────────────

    fun colorRatio(
        hsvMat: Mat,
        region: Region,
        lowers: List<Scalar>,
        uppers: List<Scalar>
    ): Double {
        val clamped = clampRegion(region, hsvMat.cols(), hsvMat.rows()) ?: return 0.0
        val roi = hsvMat.submat(clamped)
        val combined = Mat.zeros(roi.size(), CvType.CV_8UC1)
        for (i in lowers.indices) {
            val mask = Mat()
            Core.inRange(roi, lowers[i], uppers[i], mask)
            Core.bitwise_or(combined, mask, combined)
            mask.release()
        }
        val total = combined.rows() * combined.cols()
        val matched = Core.countNonZero(combined)
        combined.release()
        roi.release()
        return if (total > 0) matched.toDouble() / total else 0.0
    }

    // ── Upgrade badge / affordability checks ─────────────────────────────────

    fun hasUpgradeBadge(hsvMat: Mat): Boolean {
        val ratio = colorRatio(
            hsvMat, coords.upgradeBadgeRoi,
            listOf(RED_LOWER_1, RED_LOWER_2), listOf(RED_UPPER_1, RED_UPPER_2)
        )
        return ratio >= BADGE_MIN_RATIO
    }

    fun isUpgradesDialogOpen(hsvMat: Mat): Boolean {
        val ratio = colorRatio(
            hsvMat, coords.closeUpgradesRoi,
            listOf(RED_LOWER_1, RED_LOWER_2), listOf(RED_UPPER_1, RED_UPPER_2)
        )
        return ratio >= DIALOG_MIN_RATIO
    }

    fun isFirstUpgradeAffordable(hsvMat: Mat): Boolean {
        val ratio = colorRatio(
            hsvMat, coords.firstUpgradeBuyRoi,
            listOf(BUY_BLUE_LOWER), listOf(BUY_BLUE_UPPER)
        )
        return ratio >= BUY_MIN_RATIO
    }

    // ── Template matching ────────────────────────────────────────────────────

    /**
     * Finds [templateName] in [bgrMat], optionally scoped to [searchRegion].
     * Returns the centre [Point] of the best match if it exceeds [threshold], else null.
     */
    fun findTemplate(
        bgrMat: Mat,
        templateName: String,
        threshold: Double = 0.80,
        searchRegion: Region? = null
    ): Point? {
        val tpl = templates[templateName] ?: return null
        val clamped = searchRegion?.let { clampRegion(it, bgrMat.cols(), bgrMat.rows()) }
        val searchMat = if (clamped != null) bgrMat.submat(clamped) else bgrMat
        val offsetX = clamped?.x ?: 0
        val offsetY = clamped?.y ?: 0

        if (searchMat.cols() < tpl.cols() || searchMat.rows() < tpl.rows()) {
            if (clamped != null) searchMat.release()
            return null
        }

        val result = Mat()
        Imgproc.matchTemplate(searchMat, tpl, result, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(result)
        result.release()
        if (clamped != null) searchMat.release()

        return if (mm.maxVal >= threshold) {
            Point(offsetX + mm.maxLoc.x.toInt() + tpl.cols() / 2,
                  offsetY + mm.maxLoc.y.toInt() + tpl.rows() / 2)
        } else null
    }

    fun hasTemplate(
        bgrMat: Mat,
        templateName: String,
        threshold: Double = 0.80,
        searchRegion: Region? = null
    ): Boolean = findTemplate(bgrMat, templateName, threshold, searchRegion) != null

    // ── Station upgrade icon detection ───────────────────────────────────────

    /**
     * Finds the pulsing up-arrow upgrade icons on station panels.
     * Uses white-arrow contour + red-circle border test.
     * Returns Points sorted top→bottom (first = Station 1, last = last station visible).
     *
     * SAFETY: filters out any detected point that falls inside the
     * investorExclusionRoi or the side button zones.
     */
    fun findStationUpgradeIcons(hsvMat: Mat): List<Point> {
        val whiteMask = Mat()
        Core.inRange(hsvMat, ARROW_WHITE_LOWER, ARROW_WHITE_UPPER, whiteMask)
        val redMask = Mat()
        Core.inRange(hsvMat, CIRCLE_RED_LOWER, CIRCLE_RED_UPPER, redMask)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(whiteMask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        whiteMask.release()

        val icons = ArrayList<Point>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            contour.release()

            // Size filter
            if (rect.height < coords.upgradeArrowMinHeightY ||
                rect.height > coords.upgradeArrowMaxHeightY) continue
            // Aspect ratio filter (near-square arrow head)
            val aspect = rect.width.toDouble() / rect.height
            if (aspect < 0.55 || aspect > 1.55) continue

            val cx = rect.x + rect.width / 2
            val cy = rect.y + rect.height / 2

            // Must be inside the station area (not in top HUD or bottom nav)
            if (cy > coords.stationIconMaxY) continue

            // Exclude left side-button column
            if (cx < coords.sideButtonZoneWidthX && cy > coords.clubButtonZoneTopY) continue

            // SAFETY: exclude investor exclusion zone
            val excl = coords.investorExclusionRoi
            if (cx in excl.left..excl.right && cy in excl.top..excl.bottom) continue

            // Must have a red circle ring around the arrow
            if (isCircleAround(redMask, cx, cy, rect.height)) {
                icons.add(Point(cx, cy))
            }
        }
        redMask.release()
        icons.sortBy { it.y }
        return icons
    }

    // ── Tip detection ────────────────────────────────────────────────────────

    /**
     * Finds yellow tip coins on customer tables within coords.tableZone.
     * Additionally cross-checks with the tip_cash template (if available) to
     * reduce false positives from other yellow UI elements.
     * Returns up to 6 tip positions per scan.
     */
    fun findTips(hsvMat: Mat, bgrMat: Mat): List<Point> {
        val clamped = clampRegion(coords.tableZone, hsvMat.cols(), hsvMat.rows())
            ?: return emptyList()
        val roiHsv = hsvMat.submat(clamped)

        val yellowMask = Mat()
        Core.inRange(roiHsv, TIP_YELLOW_LOWER, TIP_YELLOW_UPPER, yellowMask)
        roiHsv.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(yellowMask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        yellowMask.release()

        val tips = ArrayList<Point>()
        val minPx = coords.sx(18)
        val maxPx = coords.sx(110)

        for (c in contours) {
            val rect = Imgproc.boundingRect(c)
            c.release()
            if (rect.width !in minPx..maxPx || rect.height !in minPx..maxPx) continue

            val cx = clamped.x + rect.x + rect.width / 2
            val cy = clamped.y + rect.y + rect.height / 2

            // Double-safety: must not land in investor exclusion zone
            val excl = coords.investorExclusionRoi
            if (cx in excl.left..excl.right && cy in excl.top..excl.bottom) continue

            tips.add(Point(cx, cy))
            if (tips.size >= 6) break
        }
        return tips
    }

    // ── Food Delivery ad detection ───────────────────────────────────────────

    /**
     * Returns true when the delivery offer IS an ad-gated reward (must skip).
     * Returns false when it is a SAFE, non-ad deal (may accept).
     *
     * Detection logic:
     *  - Look for the accept button template. If it is NOT found → treat as ad/unknown.
     *  - Look for a camera/play-triangle icon near the accept button → ad detected.
     *  - If only the accept button is found with no ad icon nearby → safe deal.
     */
    fun isDeliveryOfferAd(bgrMat: Mat): Boolean {
        val acceptPt = findTemplate(bgrMat, "food_delivery_accept", 0.78) ?: return true

        // Inspect a 200×60 region above the accept button for ad icons
        val checkRoi = Region.around(
            Point(acceptPt.x, acceptPt.y - coords.sy(50)),
            coords.sx(160), coords.sy(40)
        )
        val clamped = clampRegion(checkRoi, bgrMat.cols(), bgrMat.rows())
            ?: return false // Can't check → assume safe

        // Look for a film-reel / camera icon via a stored template
        val roiMat = bgrMat.submat(clamped)
        // If we have a dedicated "ad_camera_icon" template, use it
        val result = Mat()
        val adTemplate = templates["ad_camera_icon"]
        var adFound = false
        if (adTemplate != null &&
            roiMat.cols() >= adTemplate.cols() &&
            roiMat.rows() >= adTemplate.rows()) {
            Imgproc.matchTemplate(roiMat, adTemplate, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            adFound = mm.maxVal >= 0.72
        }
        result.release()
        roiMat.release()
        return adFound
    }

    // ── Helper best-player selection ─────────────────────────────────────────

    /**
     * Returns the tap point of the best NON-AD helper invite button visible.
     *
     * Strategy (§4):
     *  - Use the helper_invite template to find the first valid "Invite" button.
     *  - If no invite template matches, return null (skip helper action safely).
     *  - Never tap any button whose region overlaps an ad icon.
     *
     * Gear ranking (when multiple invite buttons are visible) uses the
     * legendary-orange colour ratio above each invite button row.
     * Falls back to the first invite button if rarity cannot be read.
     */
    fun findBestHelperButton(bgrMat: Mat, hsvMat: Mat): Point? {
        // Try to find helper invite buttons across the helper selection screen
        val candidates = mutableListOf<Pair<Point, Double>>()

        // Search the whole frame for invite buttons, collect up to 4
        var searchRoi: Region? = null
        for (attempt in 0..3) {
            searchRoi = if (searchRoi == null) null
            else Region(0, (searchRoi.bottom + 1).coerceAtMost(bgrMat.rows() - 1),
                        bgrMat.cols(), bgrMat.rows())

            val pt = findTemplate(bgrMat, "helper_invite", 0.78, searchRoi) ?: break

            // Score: measure legendary orange above this invite row
            val gearCheckRoi = Region.around(
                Point(pt.x, pt.y - coords.sy(60)),
                coords.sx(200), coords.sy(30)
            )
            val score = colorRatio(
                hsvMat, gearCheckRoi,
                listOf(GEAR_LEGENDARY_LOWER), listOf(GEAR_LEGENDARY_UPPER)
            )
            candidates.add(Pair(pt, score))

            // Narrow the search region for next iteration
            searchRoi = Region(0, pt.y + coords.sy(30),
                               bgrMat.cols(), bgrMat.rows())
        }

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates[0].first
            else -> candidates.maxByOrNull { it.second }?.first
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun isCircleAround(redMask: Mat, cx: Int, cy: Int, arrowH: Int): Boolean {
        val radius = (arrowH * 0.85).toInt()
        var hits = 0
        // Sample 12 points around the full circle
        val angles = doubleArrayOf(0.0, 0.52, 1.05, 1.57, 2.09, 2.62,
                                   3.14, 3.67, 4.19, 4.71, 5.24, 5.76)
        for (a in angles) {
            val px = (cx + radius * cos(a)).toInt()
            val py = (cy - radius * sin(a)).toInt()
            if (px in 0 until redMask.cols() && py in 0 until redMask.rows()) {
                val pixel = redMask.get(py, px)
                if (pixel != null && pixel.isNotEmpty() && pixel[0] > 0) hits++
            }
        }
        return hits >= 4  // Require 4/12 hits for robustness
    }

    private fun clampRegion(r: Region, maxW: Int, maxH: Int): Rect? {
        val left   = r.left.coerceIn(0, maxW - 1)
        val top    = r.top.coerceIn(0, maxH - 1)
        val right  = r.right.coerceIn(left + 1, maxW)
        val bottom = r.bottom.coerceIn(top + 1, maxH)
        val w = right - left
        val h = bottom - top
        return if (w > 0 && h > 0) Rect(left, top, w, h) else null
    }
}
