package com.example.codescanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {

    private lateinit var codeEdit: EditText
    private lateinit var countEdit: EditText
    private lateinit var delayEdit: EditText
    private lateinit var statusView: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }

        fun edit(hint: String, text: String) = EditText(this).apply {
            this.hint = hint; setText(text); inputType = InputType.TYPE_CLASS_NUMBER; root.addView(this)
        }
        codeEdit = edit("Стартовый код", prefs.getLong("code", 331075708L).toString())
        countEdit = edit("Сколько кодов проверить", prefs.getInt("count", 100).toString())
        delayEdit = edit("Пауза между попытками, мс", prefs.getLong("delay", 1500L).toString())
        statusView = TextView(this)

        fun saveSettings() {
            prefs.edit()
                .putLong("code", codeEdit.text.toString().toLongOrNull() ?: 331075708L)
                .putInt("count", countEdit.text.toString().toIntOrNull() ?: 100)
                .putLong("delay", maxOf(800L, delayEdit.text.toString().toLongOrNull() ?: 1500L))
                .apply()
        }

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() }; root.addView(this) }

        btn("1. Включить спец. возможности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btn("2. Разрешить окно поверх других приложений") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Разрешение уже есть", Toast.LENGTH_SHORT).show()
            }
        }

        btn("3. Показать кнопку Старт/Стоп (плавающая)") {
            saveSettings()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала дайте разрешение в пункте 2", Toast.LENGTH_LONG).show()
                return@btn
            }
            if (ClickerService.instance == null) {
                Toast.makeText(this, "Сначала включите службу в спец. возможностях", Toast.LENGTH_LONG).show()
                return@btn
            }
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Кнопка появится поверх экрана. Перейдите в игру и откройте окно обмена.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        btn("Скрыть плавающую кнопку") {
            stopService(Intent(this, OverlayService::class.java))
        }

        btn("Старт прямо отсюда (без переключения)") {
            saveSettings()
            val svc = ClickerService.instance
            if (svc == null) Toast.makeText(this, "Включите службу в спец. возможностях", Toast.LENGTH_LONG).show()
            else {
                svc.start(codeEdit.text.toString().toLong(), countEdit.text.toString().toInt(),
                    maxOf(800L, delayEdit.text.toString().toLong()))
                moveTaskToBack(true)
            }
        }

        btn("Стоп") { ClickerService.instance?.stop() }
        btn("Обновить статус") { statusView.text = ClickerService.status }
        root.addView(statusView)

        val note = TextView(this).apply {
            text = "Найденные коды и скриншоты сохраняются в Download/card's.\n" +
                "Координаты и задержки — в Download/configcards/config.txt " +
                "(создаётся автоматически при первом старте). Поправьте файл и " +
                "нажмите Старт заново — пересборка приложения не нужна."
            setPadding(0, 30, 0, 0)
        }
        root.addView(note)

        setContentView(root)
    }
}
