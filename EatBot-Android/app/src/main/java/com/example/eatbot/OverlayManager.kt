package com.example.eatbot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating On-Screen Overlay Switch for EatVenture.
 * Renders a glassmorphic floating pill widget directly over the game.
 * Features:
 * - Direct ON/OFF toggle switch with instant pause/resume
 * - Live strategy status display (e.g. "[1/4] St.1 Build", "[3/4] Max Last")
 * - Freeform touch dragging across the entire screen
 * - Quick action drawer (Reset Phase, Force Upgrades, Exit)
 */
class OverlayManager(
    private val context: Context,
    private val onToggleBot: (Boolean) -> Unit,
    private val onResetPhase: () -> Unit,
    private val onCloseBot: () -> Unit
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var toggleBtn: TextView? = null
    private var statusText: TextView? = null
    private var extraPanel: LinearLayout? = null

    private var isBotActive = true
    private var isExpanded = false

    fun show() {
        if (rootView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 260
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(220, 20, 24, 30))
                cornerRadius = 28f
                setStroke(2, Color.argb(100, 255, 255, 255))
            }
            background = bg
            setPadding(16, 12, 16, 12)
            elevation = 16f
        }

        // --- Main Floating Pill Header ---
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Drag handle
        val dragHandle = TextView(context).apply {
            text = "⋮⋮"
            setTextColor(Color.LTGRAY)
            textSize = 18f
            setPadding(8, 0, 12, 0)
        }
        header.addView(dragHandle)

        // ON/OFF Toggle Button
        toggleBtn = TextView(context).apply {
            text = "▶ ON"
            textSize = 15f
            setTextColor(Color.WHITE)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676")) // Neon green
                cornerRadius = 20f
            }
            background = btnBg
            setPadding(24, 10, 24, 10)
            setOnClickListener {
                isBotActive = !isBotActive
                updateToggleUI()
                onToggleBot(isBotActive)
            }
        }
        header.addView(toggleBtn)

        // Status text pill
        statusText = TextView(context).apply {
            text = "[1/4] St.1 Build"
            textSize = 13f
            setTextColor(Color.parseColor("#EEEEEE"))
            setPadding(16, 0, 16, 0)
        }
        header.addView(statusText)

        // Expand / Menu button
        val menuBtn = TextView(context).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(10, 0, 10, 0)
            setOnClickListener {
                isExpanded = !isExpanded
                extraPanel?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
        }
        header.addView(menuBtn)

        // Quick Close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#FF5252"))
            setPadding(10, 0, 8, 0)
            setOnClickListener {
                onCloseBot()
            }
        }
        header.addView(closeBtn)

        container.addView(header)

        // --- Expandable Quick Action Drawer ---
        extraPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 12, 0, 4)

            val resetBtn = Button(context).apply {
                text = "↺ Reset F&L Strategy Phase"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 50, 60, 80))
                setOnClickListener {
                    onResetPhase()
                }
            }
            addView(resetBtn)
        }
        container.addView(extraPanel)

        // Touch listener for smooth dragging
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    wm.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        rootView = container
        wm.addView(container, params)
    }

    fun updateStatus(status: String) {
        uiHandler.post {
            statusText?.text = status
        }
    }

    private fun updateToggleUI() {
        val btn = toggleBtn ?: return
        if (isBotActive) {
            btn.text = "▶ ON"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#00E676"))
        } else {
            btn.text = "⏸ OFF"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#FF5252"))
        }
    }

    fun hide() {
        rootView?.let {
            wm.removeView(it)
            rootView = null
        }
    }
}
