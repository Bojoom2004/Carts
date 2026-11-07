package com.example.codescanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class ClickerService : AccessibilityService() {

    companion object {
        var instance: ClickerService? = null
        @Volatile var status = "idle"

        // ВСЕ координаты в долях экрана (0..1), ландшафт. Подгоните под своё устройство!
        // Подсказка: Параметры разработчика -> "Показывать касания / Расположение указателя".
        const val FIELD_X = 0.46f;  const val FIELD_Y = 0.83f   // поле ввода кода
        const val CLEAR_X = 0.60f;  const val CLEAR_Y = 0.83f   // крестик очистки в поле
        const val EXCH_X = 0.67f;   const val EXCH_Y = 0.835f   // кнопка "Обменять"
        const val OK_X = 0.50f;     const val OK_Y = 0.845f      // жёлтая кнопка OK в окне обмена
        const val CLOSE_X = 0.82f;  const val CLOSE_Y = 0.13f   // крестик закрытия окна обмена

        // Клавиатура: центр каждой цифры 0..9 (x, y в долях экрана). Заполните по своей клавиатуре.
        val DIGITS = arrayOf(
            0.50f to 0.90f, // 0
            0.10f to 0.55f, 0.20f to 0.55f, 0.30f to 0.55f, // 1 2 3
            0.40f to 0.55f, 0.50f to 0.55f, 0.60f to 0.55f, // 4 5 6
            0.70f to 0.55f, 0.80f to 0.55f, 0.90f to 0.55f  // 7 8 9
        )
        const val KEYBOARD_DONE_X = 0.95f; const val KEYBOARD_DONE_Y = 0.90f
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var w = 0; private var h = 0

    override fun onServiceConnected() { instance = this
        val m = resources.displayMetrics; w = m.widthPixels; h = m.heightPixels }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun start(startCode: Long, count: Int, delayMs: Long) {
        stop()
        val m = resources.displayMetrics; w = maxOf(m.widthPixels, m.heightPixels); h = minOf(m.widthPixels, m.heightPixels)
        job = scope.launch {
            var code = startCode
            repeat(count) {
                status = "проверяю $code"
                if (tryCode(code)) {
                    saveHit(code)
                    status = "НАЙДЕН $code"
                    tap(CLOSE_X, CLOSE_Y); delay(600)
                }
                code++
                delay(delayMs)   // пауза между попытками, не ставьте слишком маленькую
            }
            status = "готово, последний код ${code - 1}"
        }
    }

    fun stop() { job?.cancel(); status = "остановлено" }

    private suspend fun tryCode(code: Long): Boolean {
        tap(CLEAR_X, CLEAR_Y); delay(250)
        tap(FIELD_X, FIELD_Y); delay(500)         // открыть клавиатуру
        for (ch in code.toString()) {
            val (x, y) = DIGITS[ch - '0']; tap(x, y); delay(120)
        }
        tap(KEYBOARD_DONE_X, KEYBOARD_DONE_Y); delay(300)
        tap(EXCH_X, EXCH_Y); delay(1000)           // ждём ответ сервера
        val bmp = screenshot() ?: return false
        val hit = isYellow(bmp, OK_X, OK_Y)        // жёлтая кнопка OK => окно обмена открылось
        if (hit) saveScreenshot(bmp, code)
        bmp.recycle()
        return hit
    }

    private fun isYellow(b: Bitmap, fx: Float, fy: Float): Boolean {
        val c = b.getPixel((fx * b.width).toInt(), (fy * b.height).toInt())
        return Color.red(c) > 220 && Color.green(c) in 160..230 && Color.blue(c) < 90
    }

    private fun saveHit(code: Long) {
        File(getExternalFilesDir(null), "found_codes.txt").appendText("$code\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", code.toString()))
    }

    private fun saveScreenshot(b: Bitmap, code: Long) {
        // Код в имени файла + подписан на самой картинке
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(copy)
        val p = android.graphics.Paint().apply { color = Color.RED; textSize = 64f; isFakeBoldText = true }
        canvas.drawText("CODE: $code", 40f, 100f, p)
        FileOutputStream(File(getExternalFilesDir(null), "hit_$code.png")).use {
            copy.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        copy.recycle()
    }

    private suspend fun tap(fx: Float, fy: Float) = suspendCancellableCoroutine<Unit> { c ->
        val path = Path().apply { moveTo(fx * w, fy * h) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
        }, null)
        if (!ok && c.isActive) c.resume(Unit)
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle(); r.hardwareBuffer.close()
                if (c.isActive) c.resume(soft)
            }
            override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
        })
    }
}
