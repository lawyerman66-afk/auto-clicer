package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private fun EditText.int(): Int? = text.toString().trim().toIntOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val etHour = findViewById<EditText>(R.id.etHour)
        val etMin = findViewById<EditText>(R.id.etMin)
        val etSec = findViewById<EditText>(R.id.etSec)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        val etX = findViewById<EditText>(R.id.etX)
        val etY = findViewById<EditText>(R.id.etY)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val svc = ClickService.instance
            if (svc == null) {
                Toast.makeText(this, "اول سرویس دسترسی‌پذیری را فعال کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val h = etHour.int(); val m = etMin.int(); val s = etSec.int()
            val interval = etInterval.int(); val x = etX.int(); val y = etY.int()
            if (h == null || m == null || s == null || interval == null || x == null || y == null
                || h !in 0..23 || m !in 0..59 || s !in 0..59) {
                Toast.makeText(this, "ورودی‌ها را کامل و درست وارد کن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, s); set(Calendar.MILLISECOND, 0)
            }
            // اگر زمان گذشته باشد، برای فردا تنظیم می‌شود
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)

            svc.schedule(cal.timeInMillis, x.toFloat(), y.toFloat(), interval.toLong())
            val t = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(cal.time)
            tvStatus.text = "زمان‌بندی شد: شروع در $t\nفاصله: $interval میلی‌ثانیه"
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ClickService.instance?.stopClicking()
            tvStatus.text = "متوقف شد (تعداد کلیک: ${ClickService.clickCount})"
        }
    }
}
