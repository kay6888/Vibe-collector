package com.vibecollector.overlay

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vibecollector.MainActivity
import com.vibecollector.R
import com.vibecollector.Vibe
import com.vibecollector.notify.CaptureNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating button.
 *
 * Sits above other apps so the user can hop between a chatbot and the project
 * without hunting for the launcher. Draggable, remembers where it was put, and
 * pulses when a capture is waiting for a decision.
 *
 * Requires SYSTEM_ALERT_WINDOW, which Android only ever grants from Settings —
 * it cannot be requested at runtime, so the app deep-links there instead.
 */
class BubbleService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0
    private var dragging = false
    private var touchSlop = 8

    override fun onCreate() {
        super.onCreate()
        touchSlop = (8 * resources.displayMetrics.density).toInt()
        CaptureNotifier.ensureChannels(this)
        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }
        startForegroundSafely()
        if (bubble == null) showBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        bubble = view

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        lifecycleScope.launch {
            val s = Vibe.get().settings.flow.first()
            val metrics = resources.displayMetrics
            val defaultX = if (s.bubbleX >= 0) s.bubbleX.toInt() else (metrics.widthPixels - size - (8 * metrics.density).toInt())
            val defaultY = if (s.bubbleY >= 0) s.bubbleY.toInt() else ((metrics.heightPixels * 0.62f).toInt())
            params.x = defaultX
            params.y = defaultY
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    initialX = params.x
                    initialY = params.y
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        params.x = initialX + (event.rawX - startX).toInt()
                        params.y = initialY + (event.rawY - startY).toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val moved = abs(event.rawX - startX) > touchSlop || abs(event.rawY - startY) > touchSlop
                        if (moved) {
                            snapToEdge(wm, v, params, size)
                            lifecycleScope.launch {
                                Vibe.get().settings.setBubblePosition(params.x, params.y)
                            }
                        } else {
                            onBubbleTapped()
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(view, params) }
        updateBadge()
    }

    /**
     * Swap: from an AI app, jump to vibe-collector; from vibe-collector, jump back
     * to the AI app the user came from.
     */
    private fun onBubbleTapped() {
        val last = Vibe.lastExternalPackage
        val back = if (Vibe.appVisible && last != null) packageManager.getLaunchIntentForPackage(last) else null
        val intent = back?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) }
            ?: Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        runCatching { startActivity(intent) }
    }

    private fun snapToEdge(wm: WindowManager, v: View, params: WindowManager.LayoutParams, size: Int) {
        val metrics = resources.displayMetrics
        val onLeft = params.x < (metrics.widthPixels - size) / 2
        val target = if (onLeft) 0 else metrics.widthPixels - size
        val from = params.x
        val steps = 12
        for (i in 1..steps) {
            params.x = from + ((target - from) * i / steps)
            runCatching { wm.updateViewLayout(v, params) }
        }
        val edge = if (onLeft) "left" else "right"
        params.x = target
        runCatching { wm.updateViewLayout(v, params) }
        lifecycleScope.launch { Vibe.get().settings.setBubbleEdge(edge) }
    }

    /** Show a count on the bubble when captures are waiting. */
    private fun updateBadge() {
        val view = bubble ?: return
        val badge = view.findViewById<TextView>(R.id.bubbleBadge) ?: return
        val count = Vibe.get().captureCoordinator.count()
        badge.text = if (count > 0) count.toString() else ""
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    fun refresh() {
        handler.post { updateBadge() }
    }

    private fun startForegroundSafely() {
        val text = Vibe.get().captureCoordinator.count().let {
            if (it > 0) "$it capture${if (it == 1) "" else "s"} waiting" else "Watching the clipboard"
        }
        runCatching {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                CaptureNotifier.serviceNotification(this, text),
            )
        }
    }

    override fun onDestroy() {
        bubble?.let { v -> runCatching { windowManager?.removeView(v) } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 9001

        fun canDrawOverlay(context: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)

        fun overlaySettingsIntent(packageName: String): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            )
    }
}
