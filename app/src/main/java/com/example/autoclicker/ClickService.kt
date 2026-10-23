package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

class ClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickService? = null
        @Volatile var clickCount = 0
        private const val GAP = 60L          // فاصله بین کلیک روی هدف‌های یک دور
        private const val MAX_TARGETS = 10
    }

    private class Cfg(
        val now: Boolean, val h: Int, val m: Int, val s: Int,
        val intervalMs: Long, val stopMode: Int, val stopValue: Long,
        val anti: Boolean, val sizeDp: Int, val earlyMs: Long, val offsetMs: Long, val startMs: Int
    )

    private class Target(val view: TargetView, val params: WindowManager.LayoutParams)

    private class TargetView(c: Context) : View(c) {
        var number = 1
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 116, 218); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 109, 0); typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, cx - 6f, fill)
            canvas.drawCircle(cx, cy, cx - 6f, ring)
            txt.textSize = width * 0.45f
            canvas.drawText(number.toString(), cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        }
    }

    private val handler = Handler(Looper.getMainLooper())     // زمان‌بندی کلیک
    private val uiHandler = Handler(Looper.getMainLooper())   // به‌روزرسانی نوشته پنل
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val targets = mutableListOf<Target>()
    private var panel: View? = null
    private var tvStatus: TextView? = null
    private var playBtn: TextView? = null

    private var running = false
    private var centers: List<Pair<Float, Float>> = emptyList()
    private var interval = 100L
    private var stopMode = 0
    private var stopValue = 0L
    private var anti = false
    private var sizeDp = 64
    private var nextAt = 0L
    private var startAtWall = 0L
    private var targetTrue = 0L     // زمان هدف بر اساس ساعت واقعی
    private var earlyMs = 0L

    private val durationStop = Runnable { finish() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            performCycle()
            clickCount++
            if (stopMode == 2 && clickCount >= stopValue) {
                // صبر می‌کنیم تا کلیک‌های همین دور هم انجام شوند
                handler.postDelayed({ finish() }, centers.size * GAP + 100)
                return
            }
            val step = if (anti)
                (interval * (0.9 + Random.nextDouble() * 0.2)).toLong().coerceAtLeast(10)
            else interval
            nextAt += step
            handler.postAtTime(this, nextAt)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (panel == null) return
            tvStatus?.text = when {
                !running -> ""
                startAtWall - System.currentTimeMillis() > 0 ->
                    fmt((startAtWall - System.currentTimeMillis()) / 1000)
                else -> clickCount.toString()
            }
            playBtn?.text = if (running) "■" else "▶"
            uiHandler.postDelayed(this, 250)
        }
    }

    // ---------- ابزارهای کمکی ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun fmt(sec: Long) = "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** touchView را لمس می‌کنی، moveView جابه‌جا می‌شود */
    private fun attachDrag(
        touchView: View, moveView: View, p: WindowManager.LayoutParams, onTap: (() -> Unit)?
    ) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        touchView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    p.x = ox + dx.toInt(); p.y = oy + dy.toInt()
                    wm.updateViewLayout(moveView, p)
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    private fun loadConfig(): Cfg? {
        val p = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val now = p.getBoolean("now", false)
        val h = p.getString("h", "")?.toIntOrNull()
        val m = p.getString("m", "")?.toIntOrNull()
        val s = p.getString("s", "")?.toIntOrNull()
        if (!now && (h == null || m == null || s == null)) return null
        val iv = p.getString("interval", "300")?.replace(',', '.')?.toDoubleOrNull() ?: 300.0
        val ms = (if (p.getInt("unit", 0) == 1) iv * 1000 else iv).toLong().coerceAtLeast(10)
        var mode = p.getInt("stopMode", 0)
        val sv = p.getString("stopValue", "")?.toLongOrNull() ?: 0L
        if (sv <= 0) mode = 0
        val stopV = if (mode == 1) sv * 1000 else sv
        val latency = p.getFloat("latencyMs", 0f)
        val manual = p.getString("early", "")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val pingHalf = if (p.getBoolean("pingComp", false)) p.getFloat("pingMs", 0f) / 2 else 0f
        val early = (latency + manual + pingHalf).toLong().coerceAtLeast(0)
        return Cfg(
            now, h ?: 0, m ?: 0, s ?: 0, ms, mode, stopV,
            p.getBoolean("anti", false), p.getInt("size", 34) + 30,
            early, p.getLong("ntpOffset", 0L),
            (p.getString("ms", "")?.toIntOrNull() ?: 0).coerceIn(0, 999)
        )
    }

    // ---------- پنل کنترل ----------

    fun panelVisible() = panel != null

    private fun iconBtn(symbol: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = symbol; textSize = 24f; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 48, 48, 48)); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val st = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            minWidth = dp(58); typeface = Typeface.MONOSPACE
        }
        val play = iconBtn("▶", 0xFF2979FF.toInt()) {
            if (running) stopClicking() else startFromPrefs()
        }
        val add = iconBtn("+", 0xFF43A047.toInt()) { addTarget() }
        val minus = iconBtn("−", 0xFFE64A19.toInt()) { removeLastTarget() }
        val gear = iconBtn("⚙", 0xFF90A4AE.toInt()) { openSettings() }
        val close = iconBtn("✕", 0xFFB0B0B0.toInt()) { hidePanel() }
        val handle = iconBtn("✥", 0xFFB0B0B0.toInt()) {}
        listOf(st, play, add, minus, gear, close, handle).forEach { root.addView(it) }

        val pp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { x = dp(8); y = dp(120) }
        attachDrag(handle, root, pp, null)
        wm.addView(root, pp)
        panel = root; tvStatus = st; playBtn = play
        uiHandler.post(statusTick)
    }

    fun hidePanel() {
        stopClicking()
        uiHandler.removeCallbacksAndMessages(null)
        targets.forEach { try { wm.removeView(it.view) } catch (_: Exception) {} }
        targets.clear()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; tvStatus = null; playBtn = null
    }

    private fun openSettings() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("برنامه «اتو کلیکر» را از لیست برنامه‌ها باز کن")
        }
    }

    // ---------- هدف‌ها ----------

    private fun addTarget() {
        if (running) { toast("اول توقف بزن"); return }
        if (targets.size >= MAX_TARGETS) { toast("حداکثر $MAX_TARGETS هدف"); return }
        sizeDp = loadConfig()?.sizeDp ?: sizeDp
        val d = resources.displayMetrics
        val size = dp(sizeDp)
        val v = TargetView(this).apply { number = targets.size + 1 }
        val p = overlayParams(size, size).apply {
            x = d.widthPixels / 2 - size / 2 + targets.size * dp(28)
            y = d.heightPixels / 2 - size / 2 + targets.size * dp(28)
        }
        attachDrag(v, v, p, null)
        wm.addView(v, p)
        targets.add(Target(v, p))
    }

    private fun removeLastTarget() {
        if (running) { toast("اول توقف بزن"); return }
        if (targets.isEmpty()) return
        val t = targets.removeAt(targets.lastIndex)
        try { wm.removeView(t.view) } catch (_: Exception) {}
    }

    fun setTargetSize(dpVal: Int) {
        sizeDp = dpVal
        val newPx = dp(dpVal)
        targets.forEach { t ->
            val diff = (newPx - t.params.width) / 2
            t.params.x -= diff; t.params.y -= diff
            t.params.width = newPx; t.params.height = newPx
            wm.updateViewLayout(t.view, t.params)
        }
    }

    private fun setTargetsTouchable(touchable: Boolean) {
        targets.forEach { t ->
            t.params.flags = if (touchable)
                t.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else t.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm.updateViewLayout(t.view, t.params) } catch (_: Exception) {}
        }
    }

    // ---------- شروع و توقف ----------

    private fun startFromPrefs() {
        if (running) return
        if (targets.isEmpty()) { toast("اول با دکمه + یک هدف اضافه کن"); return }
        val cfg = loadConfig()
        if (cfg == null) { toast("ساعت شروع را در برنامه تنظیم کن (دکمه ⚙)"); return }

        // زمان واقعی = ساعت گوشی + اختلاف ساعت (همگام‌سازی با اینترنت)
        val nowTrue = System.currentTimeMillis() + cfg.offsetMs
        val tTrue = if (cfg.now) nowTrue else {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowTrue
                set(Calendar.HOUR_OF_DAY, cfg.h); set(Calendar.MINUTE, cfg.m)
                set(Calendar.SECOND, cfg.s); set(Calendar.MILLISECOND, cfg.startMs)
            }
            if (cal.timeInMillis <= nowTrue) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis
        }
        targetTrue = if (cfg.now) 0L else tTrue
        earlyMs = cfg.earlyMs
        // کلیک به اندازه تأخیر کلیک (و پینگ) زودتر شروع می‌شود
        val startAt = tTrue - cfg.offsetMs - cfg.earlyMs

        centers = targets.map { t ->
            val loc = IntArray(2)
            t.view.getLocationOnScreen(loc)
            Pair(loc[0] + t.view.width / 2f, loc[1] + t.view.height / 2f)
        }
        interval = cfg.intervalMs
        stopMode = cfg.stopMode
        stopValue = cfg.stopValue
        anti = cfg.anti
        clickCount = 0
        startAtWall = startAt
        running = true
        setTargetsTouchable(false)

        val delay = (startAt - System.currentTimeMillis()).coerceAtLeast(0)
        nextAt = SystemClock.uptimeMillis() + delay
        handler.postAtTime(tick, nextAt)
        if (stopMode == 1) handler.postAtTime(durationStop, nextAt + stopValue)
        toast(if (delay < 1500) "شروع شد" else "شروع در ${fmt(delay / 1000)} دیگر")
        if (!cfg.now && delay > 4000) backgroundSync()
        playBtn?.text = "■"
    }

    fun stopClicking() {
        running = false
        handler.removeCallbacksAndMessages(null)
        setTargetsTouchable(true)
        playBtn?.text = "▶"
    }

    private fun finish() {
        val n = clickCount
        stopClicking()
        Toast.makeText(this, "پایان: $n دور انجام شد", Toast.LENGTH_LONG).show()
    }

    private fun performCycle() {
        centers.forEachIndexed { i, c ->
            if (i == 0) tapAt(c.first, c.second)
            else handler.postDelayed({ if (running) tapAt(c.first, c.second) }, i * GAP)
        }
    }

    private fun tapAt(x: Float, y: Float) {
        var px = x; var py = y
        if (anti) {
            val r = dp(sizeDp) / 4
            px += Random.nextInt(-r, r + 1)
            py += Random.nextInt(-r, r + 1)
        }
        tapRaw(px, py)
    }

    private fun tapRaw(px: Float, py: Float) {
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** برای کالیبراسیون: زمان (nanoTime) لحظه ارسال کلیک را برمی‌گرداند */
    fun calibrationTap(x: Float, y: Float): Long {
        val ns = System.nanoTime()
        tapRaw(x, y)
        return ns
    }

    /** وقتی هنوز تا شروع زمان هست، ساعت را با اینترنت همگام می‌کند و زمان شروع را اصلاح می‌کند */
    private fun backgroundSync() {
        Thread {
            val r = NetTools.ntpOffset()
            uiHandler.post {
                if (r == null) {
                    toast("همگام‌سازی ساعت ممکن نشد؛ از اختلاف ذخیره‌شده استفاده شد")
                    return@post
                }
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putLong("ntpOffset", r.offsetMs)
                    .putLong("ntpAt", System.currentTimeMillis()).apply()
                if (running && clickCount == 0 && targetTrue != 0L) {
                    val deviceTarget = targetTrue - r.offsetMs - earlyMs
                    val delay = deviceTarget - System.currentTimeMillis()
                    if (delay > 300) {
                        handler.removeCallbacksAndMessages(null)
                        startAtWall = deviceTarget
                        nextAt = SystemClock.uptimeMillis() + delay
                        handler.postAtTime(tick, nextAt)
                        if (stopMode == 1) handler.postAtTime(durationStop, nextAt + stopValue)
                        toast("ساعت با اینترنت همگام شد")
                    }
                }
            }
        }.start()
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean {
        hidePanel(); instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }
}
