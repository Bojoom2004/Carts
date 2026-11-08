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

        // ===== Координаты измерены по видео пользователя (экран 1280x580, landscape) =====
        const val FIELD_X = 0.465f;  const val FIELD_Y = 0.833f   // поле "Введите код обмена"
        const val CLEAR_X = 0.598f;  const val CLEAR_Y = 0.833f   // крестик очистки поля (резерв)
        const val EXCH_X = 0.666f;   const val EXCH_Y = 0.833f    // кнопка "Обменять"
        const val CLOSE_X = 0.807f;  const val CLOSE_Y = 0.150f   // крестик окна "Обменять"

        const val OK_X = 0.500f;     const val OK_Y = 0.845f      // жёлтая кнопка OK в "Обменяться картами"
        const val DIALOG_CLOSE_X = 0.823f; const val DIALOG_CLOSE_Y = 0.131f

        // Нейтральная точка (обычный текст подсказки) — тап туда убирает клавиатуру,
        // не задевая ни одной кнопки и не закрывая окно.
        const val DISMISS_X = 0.300f; const val DISMISS_Y = 0.235f

        // Клавиатура (резервный способ ввода, если Accessibility SET_TEXT не сработал)
        const val KEY_ROW_Y = 0.579f
        val DIGITS = arrayOf(
            0.917f, // 0
            0.079f, 0.148f, 0.216f, 0.286f, 0.355f, // 1 2 3 4 5
            0.641f, 0.710f, 0.780f, 0.848f          // 6 7 8 9
        )
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
        val m = resources.displayMetrics
        w = maxOf(m.widthPixels, m.heightPixels)
        h = minOf(m.widthPixels, m.heightPixels)
        job = scope.launch {
            var code = startCode
            repeat(count) {
                status = "проверяю $code"
                if (tryCode(code)) {
                    saveHit(code)
                    status = "НАЙДЕН $code"
                    tap(DIALOG_CLOSE_X, DIALOG_CLOSE_Y); delay(600)
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
        tap(FIELD_X, FIELD_Y); delay(350)

        // 2) Подставить код напрямую через Accessibility — ПОЛНОСТЬЮ заменяет
        //    содержимое поля (старый текст не остаётся и не склеивается), и сразу
        //    программно снимаем фокус с поля (ACTION_CLEAR_FOCUS) — это надёжнее,
        //    чем тап "в пустое место", и должно закрыть клавиатуру без риска
        //    попасть по чему-то на ней или рядом с кнопкой "Обменять".
        val setOk = setFieldText(text)

        if (!setOk) {
            // Резерв: тапы по клавиатуре, если системное поле не нашлось.
            tap(CLEAR_X, CLEAR_Y); delay(200)
            tap(FIELD_X, FIELD_Y); delay(350)
            for (ch in text) tap(DIGITS[ch - '0'], KEY_ROW_Y).also { delay(110) }
        }

        // Закрыть клавиатуру системным действием "Назад". Это НЕ зависит от того,
        // как конкретная игра обрабатывает фокус поля (ACTION_CLEAR_FOCUS на
        // Unity-поле не сработал — клавиатура оставалась открытой всё время).
        // Первое "назад" при открытой клавиатуре всегда закрывает именно её,
        // а не окно/диалог позади неё.
        delay(150)
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Пауза побольше — дать клавиатуре полностью закрыться и макету "осесть"
        // перед тапом по "Обменять", чтобы не промахнуться мимо кнопки.
        delay(600)
        tap(EXCH_X, EXCH_Y); delay(1000)

        val bmp = screenshot() ?: return false
        val hit = isYellow(bmp, OK_X, OK_Y)
        if (hit) saveScreenshot(bmp, code)
        bmp.recycle()
        return hit
    }

    /** Находит системное поле ввода (EditText), заменяет текст целиком и снимает фокус. */
    private fun setFieldText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditText(root, 0) ?: run { root.recycle(); return false }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
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
        return Color.red(c) > 180 && Color.green(c) in 130..225 && Color.blue(c) < 130
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

    private fun saveHit(code: Long) {
        outputStreamFor("found_codes.txt", "text/plain", append = true)?.use {
            it.write("$code\n".toByteArray())
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", code.toString()))
    }

    private fun saveScreenshot(b: Bitmap, code: Long) {
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val p = Paint().apply { color = Color.RED; textSize = 64f; isFakeBoldText = true }
        canvas.drawText("CODE: $code", 40f, 100f, p)
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
