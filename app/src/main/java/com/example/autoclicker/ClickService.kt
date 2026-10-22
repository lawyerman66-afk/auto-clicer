package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.random.Random
import android.widget.TextView
import kotlin.math.abs

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
    private var stopMode = 0        // 0 = بی‌پایان، 1 = مدت زمان، 2 = تعداد کلیک
    private var stopValue = 0L
    private var anti = false
    private var sizeDp = 64

    private var wm: WindowManager? = null
    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var stopView: View? = null

    private val durationStop = Runnable { finish() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (stopMode == 2 && clickCount >= stopValue) { finish(); return }
            performTap()
            clickCount++
            if (stopMode == 2 && clickCount >= stopValue) { finish(); return }
            val step = if (anti)
                (interval * (0.9 + Random.nextDouble() * 0.2)).toLong().coerceAtLeast(10)
            else interval
            nextAt += step
            handler.postAtTime(this, nextAt)
        }
    }

    private fun finish() {
        val n = clickCount
        stopClicking()
        Toast.makeText(this, "پایان: $n کلیک انجام شد", Toast.LENGTH_LONG).show()
    }

    // ---------- دایره هدف و دکمه توقف ----------

    private class CircleView(c: android.content.Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 0, 0) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, cx - 4f, fill)
            canvas.drawCircle(cx, cy, cx - 4f, ring)
            canvas.drawCircle(cx, cy, 6f, dot)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun attachDrag(v: View, p: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    p.x = ox + dx.toInt(); p.y = oy + dy.toInt()
                    wm?.updateViewLayout(v, p)
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    fun overlaysVisible() = targetView != null

    fun showOverlays() {
        if (targetView != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val d = resources.displayMetrics
        val size = dp(sizeDp)

        val circle = CircleView(this)
        val cp = overlayParams(size, size).apply {
            x = d.widthPixels / 2 - size / 2
            y = d.heightPixels / 2 - size / 2
        }
        attachDrag(circle, cp, null)
        manager.addView(circle, cp)
        targetView = circle; targetParams = cp

        val stop = TextView(this).apply {
            text = "■ توقف"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 200, 0, 0)); cornerRadius = dp(20).toFloat()
            }
        }
        val sp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { x = dp(16); y = dp(90) }
        attachDrag(stop, sp) { stopClicking() }
        manager.addView(stop, sp)
        stopView = stop
    }

    fun hideOverlays() {
        stopClicking()
        targetView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        stopView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        targetView = null; targetParams = null; stopView = null
    }

    fun setTargetSize(dpVal: Int) {
        sizeDp = dpVal
        val v = targetView ?: return
        val p = targetParams ?: return
        val newPx = dp(dpVal)
        val diff = (newPx - p.width) / 2
        p.x -= diff; p.y -= diff
        p.width = newPx; p.height = newPx
        wm?.updateViewLayout(v, p)
    }

    private fun setTargetTouchable(touchable: Boolean) {
        val v = targetView ?: return
        val p = targetParams ?: return
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm?.updateViewLayout(v, p)
    }

    private fun targetCenter(): Pair<Float, Float>? {
        val v = targetView ?: return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Pair(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    // ---------- زمان‌بندی کلیک ----------

    /** false یعنی دایره روی صفحه نیست */
    fun schedule(
        startAtMillis: Long, intervalMs: Long,
        stopMode: Int, stopValue: Long, anti: Boolean
    ): Boolean {
        stopClicking()
        val c = targetCenter() ?: return false
        x = c.first; y = c.second
        interval = intervalMs.coerceAtLeast(10)
        this.stopMode = stopMode
        this.stopValue = stopValue
        this.anti = anti
        clickCount = 0
        running = true
        setTargetTouchable(false)   // تا کلیک‌ها از دایره رد شوند
        val delay = (startAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        nextAt = SystemClock.uptimeMillis() + delay
        handler.postAtTime(tick, nextAt)
        if (stopMode == 1) handler.postAtTime(durationStop, nextAt + stopValue)
        return true
    }

    fun stopClicking() {
        running = false
        handler.removeCallbacksAndMessages(null)
        setTargetTouchable(true)
    }

    private fun performTap() {
        var px = x; var py = y
        if (anti) {
            val r = dp(sizeDp) / 4
            px += Random.nextInt(-r, r + 1)
            py += Random.nextInt(-r, r + 1)
        }
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideOverlays(); instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }
}
