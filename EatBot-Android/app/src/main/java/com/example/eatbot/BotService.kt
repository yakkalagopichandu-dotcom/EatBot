package com.example.eatbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.eatbot.coords.GameCoords
import com.example.eatbot.strategy.FLStrategyEngine
import com.example.eatbot.vision.GameVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * Foreground Service running the EatVenture Bot core engine.
 *
 * FIXES applied:
 *  - Deprecated NotificationCompat.Builder(Int, CharSequence, PendingIntent)
 *    replaced with Notification.Builder(context, channelId) — no third-party libs.
 *  - Bot loop checks TapService.isEatventureForeground before capturing a frame,
 *    avoiding wasted MediaProjection calls when EatVenture is not foregrounded.
 *  - Frame grabbing moved off the main thread (already on Dispatchers.Default).
 *  - LOOP_DELAY_MS reduced to 350 ms for more responsive state detection.
 *  - Proper cleanup order in shutdown(): coroutines first, then overlay, then capture.
 *
 * SAFETY RULES (§1):
 *  - Never switches to or launches another application.
 *  - If TapService.isAvailable is false (service not connected), the loop
 *    waits 1 s and retries — it does not try to open Accessibility settings.
 *  - If EatVenture is not in the foreground, the loop yields with a status message.
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.example.eatbot.START"
        const val ACTION_STOP  = "com.example.eatbot.STOP"
        const val EXTRA_CODE   = "code"
        const val EXTRA_DATA   = "data"
        private const val TAG           = "EatbotService"
        private const val CHANNEL_ID    = "eatbot_channel"
        private const val NOTIF_ID      = 1
        private const val LOOP_DELAY_MS = 350L
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private var coords: GameCoords? = null
    private var vision: GameVision? = null
    private var strategy: FLStrategyEngine? = null
    private var overlayManager: OverlayManager? = null

    private var screenWidth  = 0
    private var screenHeight = 0
    private var isStarted    = false

    @Volatile private var isBotEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialisation failed")
        } else {
            Log.i(TAG, "OpenCV initialised")
        }
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> { shutdown(); return START_NOT_STICKY }
            ACTION_START -> {
                if (isStarted) return START_NOT_STICKY

                startForegroundNotification()

                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                if (data == null || !setupCapture(code, data)) {
                    Log.e(TAG, "Screen capture setup failed; shutting down")
                    shutdown()
                    return START_NOT_STICKY
                }

                initComponents()
                isStarted = true
                startBotLoop()
            }
        }
        return START_NOT_STICKY
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private fun initComponents() {
        val gc = GameCoords(screenWidth, screenHeight)
        coords = gc
        val gv = GameVision(this, gc)
        vision = gv

        overlayManager = OverlayManager(
            context       = this,
            onToggleBot   = { active ->
                isBotEnabled = active
                Log.i(TAG, "Bot toggled: $isBotEnabled")
            },
            onResetPhase  = { strategy?.resetPhase() },
            onCloseBot    = { shutdown() }
        ).also { it.show() }

        strategy = FLStrategyEngine(
            coords         = gc,
            vision         = gv,
            onStatusUpdate = { status -> overlayManager?.updateStatus(status) },
            captureFreshFrame = {
                val bmp = grabScreenBitmap()
                if (bmp != null) {
                    val frame = gv.prepareFrame(bmp)
                    bmp.recycle()
                    frame
                } else {
                    null
                }
            }
        )
    }

    // ── Bot loop ──────────────────────────────────────────────────────────────

    private fun startBotLoop() {
        scope.launch {
            Log.i(TAG, "Bot loop started")
            while (isActive) {
                if (isBotEnabled) {
                    // Guard: TapService must be connected
                    if (!TapService.isAvailable) {
                        overlayManager?.updateStatus("TapService not ready...")
                        delay(1_000L)
                        continue
                    }

                    // Guard: EatVenture must be in the foreground.
                    // Unity games often don't expose accessibility windows, so we
                    // also do a visual fallback: grab a frame and check if the game
                    // HUD is visible. If it is, we update lastEatventureSeenMs so
                    // TapService's Layer 3 tolerance keeps isEatventureForeground=true.
                    if (!TapService.isEatventureForeground) {
                        val bmpCheck = grabScreenBitmap()
                        if (bmpCheck != null) {
                            val frameCheck = vision?.prepareFrame(bmpCheck)
                            bmpCheck.recycle()
                            if (frameCheck != null) {
                                var gameVisible = false
                                try {
                                    gameVisible = vision?.isGameScreenVisible(frameCheck.bgr) == true
                                } catch (_: Exception) {
                                    // ignore; gameVisible stays false
                                } finally {
                                    frameCheck.release()
                                }
                                if (gameVisible) {
                                    // Visual proof the game is on screen — update the timestamp
                                    // so TapService.isEatventureForeground stays true.
                                    TapService.lastEatventureSeenMs = android.os.SystemClock.elapsedRealtime()
                                    Log.d(TAG, "Visual HUD confirmation: game is on screen, unblocking bot")
                                    // Fall through — next loop iteration will pass the guard.
                                    delay(LOOP_DELAY_MS)
                                    continue
                                }
                            }
                        }
                        overlayManager?.updateStatus("Waiting for Eatventure...")
                        delay(800L)
                        continue
                    }

                    // Capture and process a frame
                    val bmp = grabScreenBitmap()
                    if (bmp != null) {
                        val frame = vision?.prepareFrame(bmp)
                        bmp.recycle()
                        if (frame != null) {
                            try {
                                strategy?.executeStep(frame)
                            } catch (e: Exception) {
                                Log.e(TAG, "Strategy step error", e)
                            } finally {
                                frame.release()
                            }
                        }
                    }
                }
                delay(LOOP_DELAY_MS)
            }
            Log.i(TAG, "Bot loop exited")
        }
    }

    // ── Screen capture ────────────────────────────────────────────────────────

    private fun grabScreenBitmap(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        return try {
            val plane      = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * screenWidth
            val rawBmp = Bitmap.createBitmap(
                screenWidth + rowPadding / plane.pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBmp.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) {
                rawBmp
            } else {
                val cropped = Bitmap.createBitmap(rawBmp, 0, 0, screenWidth, screenHeight)
                rawBmp.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame grab error", e)
            null
        } finally {
            img.close()
        }
    }

    private fun setupCapture(code: Int, data: Intent): Boolean {
        return try {
            val mpm  = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { shutdown() }
            }, uiHandler)

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth  = dm.widthPixels
            screenHeight = dm.heightPixels

            val ir = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            display = proj.createVirtualDisplay(
                "eatbot_vd", screenWidth, screenHeight, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, null
            )
            reader     = ir
            projection = proj
            Log.i(TAG, "Capture initialised: ${screenWidth}x${screenHeight}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Capture setup failed", e)
            false
        }
    }

    // ── Notification (FIXED: no more deprecated Builder overload) ─────────────

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EatVenture Bot", NotificationManager.IMPORTANCE_LOW)
        )

        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Use Notification.Builder(context, channelId) — non-deprecated
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EatVenture Bot Running")
            .setContentText("Smart F&L Automation active. Tap overlay pill to pause.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop",
                    stopPi
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private fun shutdown() {
        Log.i(TAG, "Shutting down BotService")
        scope.coroutineContext.cancelChildren()   // Stop coroutines first
        uiHandler.post {
            overlayManager?.hide()               // Then remove overlay on UI thread
        }
        display?.release()
        reader?.close()
        projection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
