package com.example.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val cbNow = findViewById<CheckBox>(R.id.cbNow)
        val etHour = findViewById<EditText>(R.id.etHour)
        val etMin = findViewById<EditText>(R.id.etMin)
        val etSec = findViewById<EditText>(R.id.etSec)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        val spUnit = findViewById<Spinner>(R.id.spUnit)
        val rgStop = findViewById<RadioGroup>(R.id.rgStop)
        val etStopValue = findViewById<EditText>(R.id.etStopValue)
        val cbAnti = findViewById<CheckBox>(R.id.cbAnti)
        val sbSize = findViewById<SeekBar>(R.id.sbSize)
        val tvSize = findViewById<TextView>(R.id.tvSize)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("میلی‌ثانیه", "ثانیه")
        )

        // ---- بازیابی تنظیمات ذخیره‌شده ----
        cbNow.isChecked = prefs.getBoolean("now", false)
        etHour.setText(prefs.getString("h", ""))
        etMin.setText(prefs.getString("m", ""))
        etSec.setText(prefs.getString("s", ""))
        etInterval.setText(prefs.getString("interval", "300"))
        spUnit.setSelection(prefs.getInt("unit", 0))
        cbAnti.isChecked = prefs.getBoolean("anti", false)
        sbSize.progress = prefs.getInt("size", 34)
        tvSize.text = "اندازه دایره: ${sbSize.progress + 30}"
        when (prefs.getInt("stopMode", 0)) {
            1 -> rgStop.check(R.id.rbDuration)
            2 -> rgStop.check(R.id.rbCount)
            else -> rgStop.check(R.id.rbForever)
        }
        etStopValue.setText(prefs.getString("stopValue", ""))

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

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val svc = ClickService.instance
            if (svc == null) {
                Toast.makeText(this, "اول سرویس دسترسی‌پذیری را فعال کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val now = cbNow.isChecked
            val h = etHour.int(); val m = etMin.int(); val s = etSec.int()
            val intervalNum = etInterval.text.toString().trim().toDoubleOrNull()
            val timeOk = now || (h != null && m != null && s != null && h in 0..23 && m in 0..59 && s in 0..59)
            if (!timeOk || intervalNum == null || intervalNum <= 0) {
                Toast.makeText(this, "ساعت و فاصله را کامل و درست وارد کن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mode = stopModeNow()
            if (mode != 0) {
                val v = etStopValue.int()
                if (v == null || v <= 0) {
                    Toast.makeText(this, "مقدار توقف را وارد کن", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            val ms = if (spUnit.selectedItemPosition == 1) intervalNum * 1000 else intervalNum
            if (ms < 40) {
                Toast.makeText(this, "فاصله کمتر از ۴۰ میلی‌ثانیه ممکن است گوشی را کند کند", Toast.LENGTH_LONG).show()
            }

            prefs.edit()
                .putBoolean("now", now)
                .putString("h", etHour.text.toString()).putString("m", etMin.text.toString())
                .putString("s", etSec.text.toString())
                .putString("interval", etInterval.text.toString())
                .putInt("unit", spUnit.selectedItemPosition)
                .putInt("stopMode", mode).putString("stopValue", etStopValue.text.toString())
                .putBoolean("anti", cbAnti.isChecked).putInt("size", sbSize.progress)
                .apply()

            svc.setTargetSize(sbSize.progress + 30)
            svc.showPanel()
            tvStatus.text = "ذخیره شد. به صفحه مورد نظر برو، با + هدف بگذار و ▶ را بزن."
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.btnClosePanel).setOnClickListener {
            ClickService.instance?.hidePanel()
            tvStatus.text = "پنل بسته شد"
        }
    }
}
