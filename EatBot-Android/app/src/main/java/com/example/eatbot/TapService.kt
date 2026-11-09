package com.example.eatbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * High-performance AccessibilityService for touch injection.
 *
 * BUG FIXES applied:
 * 1. Touch-freezing fix: tracks TYPE_TOUCH_INTERACTION_START/END from game events,
 *    rather than TYPE_VIEW_CLICKED (which fired for bot taps and caused false "user
 *    interacting" detection, locking the bot out for 600ms after every automated tap).
 * 2. isUserInteracting window reduced to 400ms and only set from TOUCH_INTERACTION_START,
 *    cleared early via TOUCH_INTERACTION_END.
 * 3. Package guard: only events from EATVENTURE_PACKAGE update interaction state,
 *    so system-level touches do not trigger false pauses.
 * 4. isEatventureForeground: empty package is not treated as EatVenture any more —
 *    the bot waits until the game window is actually confirmed foreground.
 */
class TapService : AccessibilityService() {

    companion object {
        private const val TAG = "EatbotTap"
        const val EATVENTURE_PACKAGE = "com.hwqgrhhjfd.idlefastfood"

        /** True when the TapService accessibility connection is alive */
        @Volatile var instance: TapService? = null

        /**
         * Timestamp of the last confirmed *human* touch start inside EatVenture.
         * Updated only on TYPE_TOUCH_INTERACTION_START from the game package.
         */
        @Volatile var lastUserTouchStartTime = 0L

        /**
         * Timestamp of the last confirmed *human* touch end inside EatVenture.
         * Used to clear the interaction window early once the finger lifts.
         */
        @Volatile var lastUserTouchEndTime = 0L

        /** Package name of the currently active (foreground) window */
        @Volatile var currentForegroundPackage = ""

        /** Last time Accessibility confirmed EatVenture as a foreground/window package. */
        @Volatile var lastEatventureSeenMs = 0L

        /** Last time a clearly external app/package became active. */
        @Volatile var lastExternalAppSeenMs = 0L

        val isAvailable: Boolean
            get() = instance != null

        /**
         * True when a real human finger is currently down inside EatVenture.
         * The guard window is 400 ms after TOUCH_INTERACTION_END to absorb
         * any scroll-fling momentum the user may have imparted.
         * Using TOUCH_INTERACTION_START/END means bot taps no longer set this flag.
         */
        val isUserInteracting: Boolean
            get() {
                val endAgo = System.currentTimeMillis() - lastUserTouchEndTime
                val startAgo = System.currentTimeMillis() - lastUserTouchStartTime
                // Consider interacting if touch started within last 3 s but has not
                // yet ended (startAgo < 3000 && endTime < startTime) OR ended < 400 ms ago.
                val touchActive = lastUserTouchStartTime > lastUserTouchEndTime &&
                        startAgo < 3_000L
                val recentEnd = endAgo < 400L
                return touchActive || recentEnd
            }

        /**
         * True only when EatVenture is the confirmed foreground package.
         *
         * Unity games render to a SurfaceView and do NOT expose a standard
         * View-hierarchy to Accessibility, so `rootInActiveWindow` returns
         * null or our own overlay package rather than the game package.
         * Therefore this property uses a multi-layer strategy:
         *
         *  1. Direct window/root match → immediate true (most reliable).
         *  2. Known external app visible → immediate false.
         *  3. Only null / bot-overlay / system roots visible AND EatVenture
         *     was seen before → assume game is STILL in foreground (no timeout).
         *     This is the key fix: we do NOT expire this assumption unless a
         *     real competing app appears.
         *  4. Never seen EatVenture at all (cold start) → false until confirmed.
         *
         *  The external-app guard (step 2) is the safety mechanism that stops
         *  the bot when the user genuinely switches away.
         */
        val isEatventureForeground: Boolean
            get() {
                val svc = instance ?: return false

                val rootNode = try {
                    svc.rootInActiveWindow
                } catch (_: Exception) {
                    null
                }
                val activeRootPackage = rootNode?.packageName?.toString()

                // Scan live Accessibility windows.
                // Unity often does not create an accessible window entry at all,
                // so an empty list is treated as "game might be foreground".
                val liveWindows = try {
                    svc.windows
                } catch (_: Exception) {
                    emptyList<AccessibilityWindowInfo>()
                }

                val knownExternalPackages = mutableSetOf<String>()
                var gameWindowVisible = false
                for (window in liveWindows) {
                    val pkg = try { window.root?.packageName?.toString() } catch (_: Exception) { null }
                    when {
                        pkg == EATVENTURE_PACKAGE -> gameWindowVisible = true
                        pkg == null || pkg == "com.example.eatbot" -> Unit  // our overlay
                        pkg.startsWith("com.android.systemui") ||
                        pkg.startsWith("com.android.launcher") ||
                        pkg.startsWith("android") -> Unit  // system UI
                        else -> knownExternalPackages += pkg
                    }
                }

                val now = SystemClock.elapsedRealtime()

                // ── Layer 1: Positive confirmation ────────────────────────────
                if (gameWindowVisible || activeRootPackage == EATVENTURE_PACKAGE) {
                    currentForegroundPackage = EATVENTURE_PACKAGE
                    lastEatventureSeenMs = now
                    lastExternalAppSeenMs = 0L
                    return true
                }

                // ── Layer 2: Real competing app → bot must stop ───────────────
                val realExternalAppActive = knownExternalPackages.isNotEmpty() ||
                    (activeRootPackage != null &&
                     activeRootPackage != "com.example.eatbot" &&
                     activeRootPackage != EATVENTURE_PACKAGE &&
                     !activeRootPackage.startsWith("com.android.systemui") &&
                     !activeRootPackage.startsWith("com.android.launcher") &&
                     !activeRootPackage.startsWith("android"))

                if (realExternalAppActive) {
                    lastExternalAppSeenMs = now
                    currentForegroundPackage =
                        activeRootPackage ?: knownExternalPackages.firstOrNull() ?: ""
                    Log.d(TAG, "isEatventureForeground=false: external=${knownExternalPackages + listOfNotNull(activeRootPackage)}")
                    return false
                }

                // ── Layer 3: Unity null-root tolerance — NO timeout ───────────
                // Active root is null, our overlay, or an Android system package.
                // No real external app has claimed the foreground.
                // If EatVenture was confirmed at any point, keep returning true
                // so the bot loop is not permanently blocked while Unity renders
                // without exposing an Accessibility window.
                if (lastEatventureSeenMs != 0L) {
                    currentForegroundPackage = EATVENTURE_PACKAGE
                    return true
                }

                // ── Layer 4: Never confirmed yet — stay paused ────────────────
                currentForegroundPackage = activeRootPackage ?: ""
                return false
            }

        private fun isGestureSafe(x: Int, y: Int): Boolean {
            if (!isEatventureForeground) return false
            val svc = instance ?: return false
            val screenHeight = svc.resources.displayMetrics.heightPixels
            val notificationZoneHeight = maxOf(220, (screenHeight * 0.08f).toInt())
            if (y < notificationZoneHeight) return false
            return x >= 0 && y >= 0
        }

        /** Inject a single tap if the user is not actively touching */
        suspend fun tap(x: Int, y: Int): Boolean {
            if (!isGestureSafe(x, y)) return false
            if (isUserInteracting) return false
            val svc = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            return svc.dispatchWithTimeout(gesture, 400L)
        }

        /** Inject a long-press hold gesture */
        suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
            if (!isGestureSafe(x, y)) return false
            if (isUserInteracting) return false
            val svc = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            return svc.dispatchWithTimeout(gesture, durationMs + 350L)
        }

        /** Inject a swipe gesture */
        suspend fun swipe(
            startX: Int, startY: Int,
            endX: Int, endY: Int,
            durationMs: Long = 400L
        ): Boolean {
            if (!isGestureSafe(startX, startY) || !isGestureSafe(endX, endY)) return false
            if (isUserInteracting) return false
            val svc = instance ?: return false
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            return svc.dispatchWithTimeout(gesture, durationMs + 350L)
        }
    }

    // ── Internal gesture dispatcher with coroutine-based callback ──────────────

    private suspend fun dispatchWithTimeout(
        gesture: GestureDescription,
        timeoutMs: Long
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = dispatchGesture(gesture, callback, null)
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
        return result ?: false
    }

    // ── AccessibilityService lifecycle ─────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "EatbotTapService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "EatbotTapService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Any event from EatVenture refreshes the confirmed-foreground timestamp.
        // Unity fires very few accessibility events (usually only touch events),
        // but each one proves the game is in front.
        if (pkg == EATVENTURE_PACKAGE) {
            currentForegroundPackage = EATVENTURE_PACKAGE
            lastEatventureSeenMs = SystemClock.elapsedRealtime()
            lastExternalAppSeenMs = 0L
        }

        // Track foreground package switches from window-state events.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Skip our overlay and Android system shells — they don't reflect user app switches.
            if (pkg != "com.example.eatbot" &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.startsWith("com.android.launcher") &&
                !pkg.startsWith("android")) {
                if (pkg != EATVENTURE_PACKAGE) {
                    // A real external app came to the foreground.
                    lastExternalAppSeenMs = SystemClock.elapsedRealtime()
                    currentForegroundPackage = pkg
                    Log.d(TAG, "Foreground switched to external pkg=$pkg")
                }
            }
        }

        // Track human finger events ONLY from EatVenture to avoid false pauses
        // from system dialogs, notifications, or other apps.
        if (pkg == EATVENTURE_PACKAGE) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    lastUserTouchStartTime = System.currentTimeMillis()
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    lastUserTouchEndTime = System.currentTimeMillis()
                }
                else -> { /* Ignore */ }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "TapService interrupted")
    }
}
