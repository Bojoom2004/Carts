package com.example.codescanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun edit(hint: String, text: String) = EditText(this).apply {
            this.hint = hint; setText(text); inputType = InputType.TYPE_CLASS_NUMBER; root.addView(this)
        }
        val code = edit("Стартовый код", "331075708")
        val count = edit("Сколько кодов проверить", "100")
        val delay = edit("Пауза между попытками, мс", "1500")
        val status = TextView(this)

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() }; root.addView(this) }
        btn("1. Включить спец. возможности") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("2. Старт (откройте окно обмена в игре!)") {
            val svc = ClickerService.instance
            if (svc == null) Toast.makeText(this, "Включите службу в спец. возможностях", Toast.LENGTH_LONG).show()
            else {
                svc.start(code.text.toString().toLong(), count.text.toString().toInt(),
                    maxOf(800L, delay.text.toString().toLong()))
                moveTaskToBack(true)
            }
        }
        btn("Стоп") { ClickerService.instance?.stop() }
        btn("Обновить статус") { status.text = ClickerService.status }
        root.addView(status)
        setContentView(root)
    }
}
