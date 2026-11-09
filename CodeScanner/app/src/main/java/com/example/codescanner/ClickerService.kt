package com.example.codescanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class ClickerService : AccessibilityService() {

    companion object {
        var instance: ClickerService? = null
        @Volatile var status = "ожидание"

        const val REL_PATH = "Download/card's/"
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var w = 0; private var h = 0
    private var cfg = Config()
    // true, если последняя находка — окно "У вас нет карты, которую хочет игрок"
    private var lastNoCard = false

    override fun onServiceConnected() { instance = this
        val m = resources.displayMetrics; w = m.widthPixels; h = m.heightPixels }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun start(startCode: Long, count: Int, delayMs: Long) {
        stop()
        cfg = Config.load(applicationContext) // читаем Download/configcards/config.txt заново при каждом старте
        val m = resources.displayMetrics
        w = maxOf(m.widthPixels, m.heightPixels)
        h = minOf(m.widthPixels, m.heightPixels)
        job = scope.launch {
            var code = startCode
            repeat(count) {
                status = "проверяю $code"
                if (tryCode(code)) {
                    saveHit(code, lastNoCard)
                    status = if (lastNoCard) "НАЙДЕН $code (нет карты)" else "НАЙДЕН $code"
                    tap(cfg.dialogCloseX, cfg.dialogCloseY); delay(cfg.delayAfterHitClose)
                }
                code++
                delay(delayMs)
            }
            status = "готово, последний код ${code - 1}"
        }
    }

    fun stop() { job?.cancel(); status = "остановлено" }
    fun isRunning() = job?.isActive == true

    private suspend fun tryCode(code: Long): Boolean {
        val text = code.toString()
        status = "ввод $text"

        // 1) Сфокусировать поле обычным тапом (создаёт/активирует системный EditText).
        tap(cfg.fieldX, cfg.fieldY); delay(cfg.delayAfterFieldTap)

        // 2) Подставить код напрямую через Accessibility — полностью заменяет
        //    содержимое поля, старый текст не остаётся и не склеивается.
        val setOk = setFieldText(text)

        if (!setOk) {
            // Резерв: тапы по клавиатуре, если системное поле не нашлось.
            tap(cfg.clearX, cfg.clearY); delay(200)
            tap(cfg.fieldX, cfg.fieldY); delay(350)
            val digits = cfg.digits()
            for (ch in text) {
                tap(digits[ch - '0'], cfg.keyRowY); delay(cfg.delayDigitTap)
            }
        }

        delay(cfg.delayAfterSetText)

        // 3) Коммит введённого текста — тап по синей кнопке ввода/галочке НА САМОЙ
        //    клавиатуре (IME action), а НЕ системное "Назад". "Назад" в этой игре
        //    отменяет/обнуляет поле вместо того чтобы просто скрыть клавиатуру —
        //    из-за этого реального обмена не происходило вообще.
        tap(cfg.enterX, cfg.enterY)
        delay(cfg.delayAfterEnter)

        tap(cfg.exchX, cfg.exchY); delay(cfg.delayAfterExch)

        val bmp = screenshot() ?: return false
        val yellow = isYellow(bmp, cfg.okX, cfg.okY)
        // Второй вариант находки: код рабочий, но у вас нет нужной карты (серая OK, красная надпись).
        val noCard = !yellow && isNoCard(bmp)
        lastNoCard = noCard
        val hit = yellow || noCard
        if (hit) saveScreenshot(bmp, code, noCard)
        bmp.recycle()
        return hit
    }

    /** Находит системное поле ввода (EditText) в активном окне и заменяет его текст целиком. */
    private fun setFieldText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditText(root, 0) ?: run { root.recycle(); return false }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle(); root.recycle()
        return ok
    }

    private fun findEditText(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child, depth + 1)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun isYellow(b: Bitmap, fx: Float, fy: Float): Boolean {
        val c = b.getPixel((fx * b.width).toInt(), (fy * b.height).toInt())
        return Color.red(c) > cfg.yellowRMin && Color.green(c) in cfg.yellowGMin..cfg.yellowGMax && Color.blue(c) < cfg.yellowBMax
    }

    /** Есть ли в области надписи "У вас нет карты, которую хочет игрок" достаточно красных пикселей. */
    private fun isNoCard(b: Bitmap): Boolean {
        val x1 = (cfg.noCardX1 * b.width).toInt().coerceIn(0, b.width - 1)
        val x2 = (cfg.noCardX2 * b.width).toInt().coerceIn(x1 + 1, b.width)
        val y1 = (cfg.noCardY1 * b.height).toInt().coerceIn(0, b.height - 1)
        val y2 = (cfg.noCardY2 * b.height).toInt().coerceIn(y1 + 1, b.height)
        var red = 0
        val row = IntArray(x2 - x1)
        for (y in y1 until y2) {
            b.getPixels(row, 0, x2 - x1, x1, y, x2 - x1, 1)
            for (c in row) {
                if (Color.red(c) > cfg.noCardRMin && Color.green(c) < cfg.noCardGMax && Color.blue(c) < cfg.noCardBMax) red++
            }
        }
        val total = (x2 - x1) * (y2 - y1)
        return red.toFloat() / total >= cfg.noCardRatio
    }

    // ===== Сохранение в Download/card's через MediaStore (scoped storage) =====

    private fun outputStreamFor(name: String, mime: String, append: Boolean): java.io.OutputStream? {
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(collection, projection, selection, arrayOf(name, REL_PATH), null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                return resolver.openOutputStream(uri, if (append) "wa" else "w")
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return resolver.openOutputStream(uri)
    }

    private fun saveHit(code: Long, noCard: Boolean) {
        outputStreamFor("found_codes.txt", "text/plain", append = true)?.use {
            it.write((if (noCard) "$code (нет карты)\n" else "$code\n").toByteArray())
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", code.toString()))
    }

    private fun saveScreenshot(b: Bitmap, code: Long, noCard: Boolean) {
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val p = Paint().apply { color = Color.RED; textSize = 64f; isFakeBoldText = true }
        canvas.drawText(if (noCard) "CODE: $code (no card)" else "CODE: $code", 40f, 100f, p)
        outputStreamFor("hit_$code.png", "image/png", append = false)?.use {
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
