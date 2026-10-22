package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class ClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickService? = null
        @Volatile var clickCount = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var x = 0f
    private var y = 0f
    private var interval = 100L
    private var nextAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            performTap()
            clickCount++
            nextAt += interval              // بدون drift: بر اساس زمان هدف حساب می‌شود
            handler.postAtTime(this, nextAt)
        }
    }

    /** startAtMillis: زمان شروع به میلی‌ثانیه (System.currentTimeMillis) */
    fun schedule(startAtMillis: Long, x: Float, y: Float, intervalMs: Long) {
        stopClicking()
        this.x = x
        this.y = y
        this.interval = intervalMs.coerceAtLeast(10)
        clickCount = 0
        running = true
        val delay = (startAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        nextAt = SystemClock.uptimeMillis() + delay
        handler.postAtTime(tick, nextAt)
    }

    fun stopClicking() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun performTap() {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopClicking(); instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }
}
