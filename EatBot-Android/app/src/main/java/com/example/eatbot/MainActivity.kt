package com.example.eatbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 101
    private lateinit var overlayStatusText: TextView
    private lateinit var a11yStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12161E"))
            setPadding(48, 64, 48, 64)
        }

        // --- Header Title ---
        val title = TextView(this).apply {
            text = "EatVenture Bot"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        rootLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Pure First & Last Strategy • 100% Offline Capable • Native Android"
            textSize = 13f
            setTextColor(Color.parseColor("#8E9AA8"))
            setPadding(0, 8, 0, 32)
        }
        rootLayout.addView(subtitle)

        // --- Permissions Card ---
        val permCard = createCard("Required Permissions")
        
        val btnOverlay = createStyledButton("1. Enable Floating Window (Overlay)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permCard.addView(btnOverlay)

        overlayStatusText = createStatusLabel("Checking Overlay...")
        permCard.addView(overlayStatusText)

        val btnA11y = createStyledButton("2. Enable Accessibility (Eatbot taps)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permCard.addView(btnA11y)

        a11yStatusText = createStatusLabel("Checking Accessibility...")
        permCard.addView(a11yStatusText)

        rootLayout.addView(permCard)

        // --- Strategy Summary Card ---
        val strategyCard = createCard("Active Strategy: Pure First & Last (F&L)")
        val strategyDesc = TextView(this).apply {
            text = "• Phase 1: Rapidly powers up Station 1 for starting cash.\n" +
                    "• Phase 2: Smoothly scrolls to the bottom to unlock the Last Station.\n" +
                    "• Phase 3: Turbo-levels the Last Station to MAX (huge multipliers).\n" +
                    "• Phase 4: Sweeps and backfills all intermediate stations in seconds.\n" +
                    "• Phase 5: Auto Renovates & Flies to Next City!\n\n" +
                    "Offline Tip: Turn off Wi-Fi/Data or use Airplane Mode to block popup ads."
            textSize = 13f
            setTextColor(Color.parseColor("#CAD4DF"))
            setLineSpacing(6f, 1f)
            setPadding(8, 8, 8, 8)
        }
        strategyCard.addView(strategyDesc)
        rootLayout.addView(strategyCard)

        // --- Launch Control Buttons ---
        val btnStart = Button(this).apply {
            text = "▶ START BOT & LAUNCH OVERLAY"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00C853")) // Emerald green
                cornerRadius = 24f
            }
            background = btnBg
            setPadding(0, 32, 0, 32)
            setOnClickListener {
                startBotProcess()
            }
        }
        rootLayout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹ STOP BOT"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            background = null
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                stopService(Intent(this@MainActivity, BotService::class.java))
                Toast.makeText(this@MainActivity, "EatVenture Bot stopped", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(btnStop)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (hasOverlay) "✔ Floating overlay granted" else "✖ Permission required (tap above)"
        overlayStatusText.setTextColor(if (hasOverlay) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))

        val hasA11y = TapService.isAvailable
        a11yStatusText.text = if (hasA11y) "✔ TapService is ON" else "✖ TapService is OFF (tap above)"
        a11yStatusText.setTextColor(if (hasA11y) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
    }

    private fun startBotProcess() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable Floating Window permission first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!TapService.isAvailable) {
            Toast.makeText(this, "Please turn ON 'Eatbot taps' in Accessibility settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, BotService::class.java)
                    .setAction(BotService.ACTION_START)
                    .putExtra(BotService.EXTRA_CODE, resultCode)
                    .putExtra(BotService.EXTRA_DATA, data)
            )
            Toast.makeText(this, "EatVenture Bot active! Open the game.", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCard(titleText: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2330"))
                cornerRadius = 24f
            }
            background = bg
            setPadding(32, 28, 32, 28)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 36)
            }
            layoutParams = params

            val cardTitle = TextView(this@MainActivity).apply {
                text = titleText
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 16)
            }
            addView(cardTitle)
        }
    }

    private fun createStyledButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#283344"))
                cornerRadius = 16f
            }
            background = btnBg
            setPadding(28, 20, 28, 20)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            layoutParams = params
        }
    }

    private fun createStatusLabel(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            textSize = 12f
            setPadding(8, 4, 8, 16)
        }
    }
}
