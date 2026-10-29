package com.example.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private fun EditText.int(): Int? = text.toString().trim().toIntOrNull()
    private val ui = Handler(Looper.getMainLooper())
    private var padArrival = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val cbNow = findViewById<CheckBox>(R.id.cbNow)
        val etHour = findViewById<EditText>(R.id.etHour)
        val etMin = findViewById<EditText>(R.id.etMin)
        val etSec = findViewById<EditText>(R.id.etSec)
        val etMs = findViewById<EditText>(R.id.etMs)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        val spUnit = findViewById<Spinner>(R.id.spUnit)
        val rgStop = findViewById<RadioGroup>(R.id.rgStop)
        val etStopValue = findViewById<EditText>(R.id.etStopValue)
        val cbAnti = findViewById<CheckBox>(R.id.cbAnti)
        val sbSize = findViewById<SeekBar>(R.id.sbSize)
        val tvSize = findViewById<TextView>(R.id.tvSize)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val etHost = findViewById<EditText>(R.id.etHost)
        val cbPingComp = findViewById<CheckBox>(R.id.cbPingComp)
        val etEarly = findViewById<EditText>(R.id.etEarly)
        val tvCalib = findViewById<TextView>(R.id.tvCalib)
        val pad = findViewById<TextView>(R.id.padCalib)

        spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("میلی‌ثانیه", "ثانیه")
        )

        // ---- بازیابی تنظیمات ذخیره‌شده ----
        cbNow.isChecked = prefs.getBoolean("now", false)
        etHour.setText(prefs.getString("h", ""))
        etMin.setText(prefs.getString("m", ""))
        etSec.setText(prefs.getString("s", ""))
        etMs.setText(prefs.getString("ms", ""))
        etInterval.setText(prefs.getString("interval", "300"))
        spUnit.setSelection(prefs.getInt("unit", 0))
        cbAnti.isChecked = prefs.getBoolean("anti", false)
        sbSize.progress = prefs.getInt("size", 34)
        tvSize.text = "اندازه دایره: ${sbSize.progress + 30}"
        etHost.setText(prefs.getString("host", ""))
        cbPingComp.isChecked = prefs.getBoolean("pingComp", false)
        etEarly.setText(prefs.getString("early", ""))
        when (prefs.getInt("stopMode", 0)) {
            1 -> rgStop.check(R.id.rbDuration)
            2 -> rgStop.check(R.id.rbCount)
            else -> rgStop.check(R.id.rbForever)
        }
        etStopValue.setText(prefs.getString("stopValue", ""))

        fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

        fun refreshCalib() {
            val off = prefs.getLong("ntpOffset", 0L)
            val at = prefs.getLong("ntpAt", 0L)
            val lat = prefs.getFloat("latencyMs", -1f)
            val ping = prefs.getFloat("pingMs", -1f)
            val sb = StringBuilder()
            if (at == 0L) sb.append("ساعت: هنوز همگام نشده\n")
            else sb.append("ساعت: اختلاف $off میلی‌ثانیه (${(System.currentTimeMillis() - at) / 60000} دقیقه پیش همگام شد)\n")
            sb.append(if (lat < 0) "تأخیر کلیک: هنوز کالیبره نشده\n" else "تأخیر کلیک: %.1f میلی‌ثانیه\n".format(lat))
            sb.append(if (ping < 0) "پینگ: هنوز اندازه‌گیری نشده" else "پینگ: %.0f میلی‌ثانیه".format(ping))
            tvCalib.text = sb.toString()
        }
        refreshCalib()

        fun stopModeNow() = when (rgStop.checkedRadioButtonId) {
            R.id.rbDuration -> 1
            R.id.rbCount -> 2
            else -> 0
        }

        fun updateStopField() {
            when (stopModeNow()) {
                1 -> { etStopValue.visibility = View.VISIBLE; etStopValue.hint = "مدت به ثانیه" }
                2 -> { etStopValue.visibility = View.VISIBLE; etStopValue.hint = "تعداد دور" }
                else -> etStopValue.visibility = View.GONE
            }
        }
        updateStopField()
        rgStop.setOnCheckedChangeListener { _, _ -> updateStopField() }

        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvSize.text = "اندازه دایره: ${p + 30}"
                ClickService.instance?.setTargetSize(p + 30)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ---- پینگ ----
        findViewById<Button>(R.id.btnPing).setOnClickListener {
            val host = etHost.text.toString().trim()
            if (host.isEmpty()) { toast("آدرس سایت هدف را بنویس"); return@setOnClickListener }
            prefs.edit().putString("host", host).apply()
            tvStatus.text = "در حال اندازه‌گیری پینگ..."
            Thread {
                val r = NetTools.tcpPing(host)
                runOnUiThread {
                    when {
                        r == null -> tvStatus.text = "آدرس نامعتبر است یا اینترنت قطع است"
                        r.lost == r.total -> tvStatus.text = "اتصال به سایت برقرار نشد"
                        else -> {
                            prefs.edit().putFloat("pingMs", r.avgMs.toFloat()).apply()
                            val quality = when {
                                r.lost > 0 || r.jitterMs > 60 -> "ناپایدار"
                                r.avgMs < 100 -> "خوب"
                                r.avgMs < 250 -> "متوسط"
                                else -> "ضعیف"
                            }
                            tvStatus.text = "پینگ: کمترین %.0f / میانگین %.0f / بیشترین %.0f میلی‌ثانیه\nنوسان: %.0f، قطع: ${r.lost} از ${r.total}\nکیفیت: $quality"
                                .format(r.minMs, r.avgMs, r.maxMs, r.jitterMs)
                            refreshCalib()
                        }
                    }
                }
            }.start()
        }

        // ---- همگام‌سازی ساعت ----
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            tvStatus.text = "در حال همگام‌سازی ساعت..."
            Thread {
                val r = NetTools.ntpOffset()
                runOnUiThread {
                    if (r == null) {
                        tvStatus.text = "همگام‌سازی ممکن نشد (اینترنت یا مسدود بودن پورت زمان)"
                    } else {
                        prefs.edit().putLong("ntpOffset", r.offsetMs)
                            .putLong("ntpAt", System.currentTimeMillis()).apply()
                        val dir = if (r.offsetMs >= 0) "عقب" else "جلو"
                        tvStatus.text = "ساعت گوشی ${Math.abs(r.offsetMs)} میلی‌ثانیه $dir است. (منبع: ${r.host}، دقت حدود ±${r.delayMs / 2} میلی‌ثانیه)"
                        refreshCalib()
                    }
                }
            }.start()
        }

        // ---- کالیبراسیون تأخیر کلیک ----
        pad.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) padArrival = System.nanoTime()
            true
        }
        findViewById<Button>(R.id.btnCalib).setOnClickListener {
            val svc = ClickService.instance
            if (svc == null) { toast("اول سرویس دسترسی‌پذیری را فعال کن (مرحله ۱)"); return@setOnClickListener }
            val vis = Rect()
            if (!pad.getGlobalVisibleRect(vis) || vis.height() < pad.height || vis.width() < pad.width) {
                toast("ناحیه تست باید کامل روی صفحه دیده شود")
                return@setOnClickListener
            }
            val loc = IntArray(2); pad.getLocationOnScreen(loc)
            val cx = loc[0] + pad.width / 2f; val cy = loc[1] + pad.height / 2f
            val results = ArrayList<Double>()
            var n = 0
            var dispatchNs = 0L
            val total = 14
            tvStatus.text = "در حال کالیبراسیون... به صفحه دست نزن"

            val step = object : Runnable {
                override fun run() {
                    if (n > 2 && padArrival > dispatchNs) results.add((padArrival - dispatchNs) / 1e6)
                    if (n >= total) {
                        if (results.size < 5) {
                            tvStatus.text = "کالیبراسیون ناموفق بود. دوباره امتحان کن."
                        } else {
                            results.sort()
                            val med = results[results.size / 2]
                            prefs.edit().putFloat("latencyMs", med.toFloat()).apply()
                            tvStatus.text = "تأخیر کلیک: %.1f میلی‌ثانیه. این مقدار خودکار از زمان شروع کم می‌شود.".format(med)
                            refreshCalib()
                        }
                        return
                    }
                    padArrival = 0L
                    dispatchNs = svc.calibrationTap(cx, cy)
                    n++
                    ui.postDelayed(this, 350)
                }
            }
            ui.post(step)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val svc = ClickService.instance
            if (svc == null) { toast("اول سرویس دسترسی‌پذیری را فعال کن (مرحله ۱)"); return@setOnClickListener }
            val now = cbNow.isChecked
            val h = etHour.int(); val m = etMin.int(); val s = etSec.int()
            val intervalNum = etInterval.text.toString().trim().toDoubleOrNull()
            val msStart = etMs.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
            val timeOk = now || (h != null && m != null && s != null && msStart != null &&
                    h in 0..23 && m in 0..59 && s in 0..59 && msStart in 0..999)
            if (!timeOk || intervalNum == null || intervalNum <= 0) {
                toast("ساعت و فاصله را کامل و درست وارد کن")
                return@setOnClickListener
            }
            val mode = stopModeNow()
            if (mode != 0) {
                val v = etStopValue.int()
                if (v == null || v <= 0) { toast("مقدار توقف را وارد کن"); return@setOnClickListener }
            }
            val ms = if (spUnit.selectedItemPosition == 1) intervalNum * 1000 else intervalNum
            if (ms < 40) toast("فاصله کمتر از ۴۰ میلی‌ثانیه ممکن است گوشی را کند کند")

            prefs.edit()
                .putBoolean("now", now)
                .putString("h", etHour.text.toString()).putString("m", etMin.text.toString())
                .putString("s", etSec.text.toString()).putString("ms", etMs.text.toString())
                .putString("interval", etInterval.text.toString())
                .putInt("unit", spUnit.selectedItemPosition)
                .putInt("stopMode", mode).putString("stopValue", etStopValue.text.toString())
                .putBoolean("anti", cbAnti.isChecked).putInt("size", sbSize.progress)
                .putString("host", etHost.text.toString().trim())
                .putBoolean("pingComp", cbPingComp.isChecked)
                .putString("early", etEarly.text.toString())
                .apply()

            svc.setTargetSize(sbSize.progress + 30)
            svc.showPanel()
            tvStatus.text = "ذخیره شد. به سایت برو، در پنل با + هدف بگذار (مرحله ۷) و ▶ را بزن."
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.btnClosePanel).setOnClickListener {
            ClickService.instance?.hidePanel()
            tvStatus.text = "پنل بسته شد"
        }
    }
}
