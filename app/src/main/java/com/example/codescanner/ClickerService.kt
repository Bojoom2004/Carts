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
import android.os.PowerManager
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
        const val VERSION = "v10"
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var w = 0; private var h = 0
    private var cfg = Config()
    // Пометка последней находки: "" — обычная (жёлтая OK), "нет карты", "окно" — иное диалоговое окно
    private var lastNote = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var errors = 0       // сколько раз шаг завис/упал и был пропущен
    private var gestureTimeouts = 0
    private var needRetry = false    // клавиатура не закрылась — повторить тот же код
    private var kbLeftOpen = false   // клавиатура осталась открытой после прошлого кода — закрыть до ввода

    private fun acquireWake() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodeScanner:run")
            wl.setReferenceCounted(false)
            wl.acquire(12 * 60 * 60 * 1000L)
            wakeLock = wl
        }
    }
    private fun releaseWake() { runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null }

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
        errors = 0; gestureTimeouts = 0
        acquireWake()
        job = scope.launch {
            var code = startCode
            try {
                repeat(count) {
                    status = "[$VERSION] проверяю $code (сбоев: $errors, жестов: $gestureTimeouts)"
                    try {
                        // Сторож: один код не должен занимать дольше cfg.codeTimeoutMs, иначе пропускаем шаг.
                        var hit: Boolean?
                        var attempts = 0
                        do {
                            needRetry = false
                            hit = withTimeoutOrNull(cfg.codeTimeoutMs) { tryCode(code) }
                            attempts++
                        } while (needRetry && hit == false && attempts < 3)
                        if (hit == null) {
                            errors++
                            status = "[$VERSION] таймаут на коде $code, иду дальше (сбоев: $errors, жестов: $gestureTimeouts)"
                            // Возможно, на экране остался диалог — пробуем закрыть крестиком.
                            withTimeoutOrNull(10_000L) { if (isDialogOpenNow()) closeDialog() }
                        } else if (hit) {
                            val note = lastNote
                            runCatching { saveHit(code, note) }
                            status = if (note.isEmpty()) "НАЙДЕН $code" else "НАЙДЕН $code ($note)"
                            withTimeoutOrNull(15_000L) { closeDialog() }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        errors++
                        status = "[$VERSION] ошибка ${e.javaClass.simpleName} на коде $code, иду дальше (сбоев: $errors, жестов: $gestureTimeouts)"
                        delay(1000)
                    }
                    code++
                    delay(delayMs)
                }
                status = "готово, последний код ${code - 1}"
            } finally {
                releaseWake()
            }
        }
    }

    /** Открыт ли сейчас диалог (скриншот + проверка). */
    private suspend fun isDialogOpenNow(): Boolean {
        val b = screenshot() ?: return false
        val r = isDialogOpen(b); b.recycle(); return r
    }

    fun stop() { job?.cancel(); releaseWake(); status = "остановлено" }
    fun isRunning() = job?.isActive == true

    private suspend fun tryCode(code: Long): Boolean {
        val text = code.toString()
        status = "ввод $text"

        // 0) Если прошлый код оставил клавиатуру открытой — сначала закрываем её.
        if (kbLeftOpen) {
            hideKeyboard()
            kbLeftOpen = false
        }

        // 1) Сфокусировать поле обычным тапом (создаёт/активирует системный EditText).
        tap(cfg.fieldX, cfg.fieldY); delay(cfg.delayAfterFieldTap)

        // 2) Подставить код напрямую через Accessibility — полностью заменяет
        //    содержимое поля. Несколько попыток: иногда узел поля на миг недоступен.
        var setOk = false
        for (attempt in 0 until 3) {
            setOk = setFieldText(text)
            if (setOk) break
            delay(200)
        }

        if (!setOk) {
            // Резерв: набор цифр тапами по клавиатуре.
            val shot = screenshot()
            val kbOpen = shot?.let { val r = isKeyboardOpen(it); it.recycle(); r } ?: false
            if (kbOpen) {
                // Клавиатура уже открыта: долгое нажатие Backspace стирает старый текст.
                press(cfg.backspaceX, cfg.backspaceY, 2000); delay(200)
            } else {
                tap(cfg.clearX, cfg.clearY); delay(200)
                tap(cfg.fieldX, cfg.fieldY); delay(350)
            }
            val digits = cfg.digits()
            for (ch in text) {
                tap(digits[ch - '0'], cfg.keyRowY); delay(cfg.delayDigitTap)
            }
        }

        delay(cfg.delayAfterSetText)

        // 3) Коммит введённого текста — тап по синей кнопке ввода НА САМОЙ клавиатуре
        //    (системное "Назад" в этой игре обнуляет поле). Проверяем скриншотом, что клавиатура
        //    закрылась; если нет — повторяем, затем сворачиваем стрелкой клавиатуры.
        if (!commitAndCloseKeyboard()) {
            // Клавиатура не закрылась: "Обменять" не нажимаем (иначе попадём по клавише), код повторим.
            kbLeftOpen = true
            needRetry = true
            status = "[$VERSION] клавиатура не закрылась на коде $code (сбоев: $errors)"
            errors++
            return false
        }

        tap(cfg.exchX, cfg.exchY); delay(cfg.delayAfterExch)

        val bmp = screenshot() ?: run { status = "[$VERSION] скриншот не получен ($code)"; return false }
        if (isKeyboardOpen(bmp)) kbLeftOpen = true
        val yellow = isYellow(bmp, cfg.okX, cfg.okY)
        // Второй вариант находки: код рабочий, но у вас нет нужной карты (серая OK, красная надпись).
        val noCard = !yellow && isNoCard(bmp)
        // Третий (страховка): открылось любое другое окно-диалог — считаем находкой и закроем крестиком.
        val otherDialog = !yellow && !noCard && isDialogOpen(bmp)
        lastNote = if (yellow) "" else if (noCard) "нет карты" else "окно"
        val hit = yellow || noCard || otherDialog
        if (hit) saveScreenshot(bmp, code, lastNote)
        bmp.recycle()
        return hit
    }

    /** Находит системное поле ввода (EditText) в активном окне и заменяет его текст целиком. */
    private fun setFieldText(text: String): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching { for (win in windows) win.root?.let { roots.add(it) } }
        var ok = false
        for (root in roots) {
            if (!ok) {
                val node = findEditText(root, 0)
                if (node != null) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    node.recycle()
                }
            }
            root.recycle()
        }
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

    /** Клавиатура открыта, если на месте цифрового/буквенного ряда тёмный фон (в списке и диалоге там светлая панель). */
    private fun isKeyboardOpen(b: Bitmap): Boolean {
        val x1 = (cfg.kbX1 * b.width).toInt().coerceIn(0, b.width - 2)
        val x2 = (cfg.kbX2 * b.width).toInt().coerceIn(x1 + 1, b.width)
        val y1 = (cfg.kbY1 * b.height).toInt().coerceIn(0, b.height - 2)
        val y2 = (cfg.kbY2 * b.height).toInt().coerceIn(y1 + 1, b.height)
        var sum = 0L; var n = 0
        val row = IntArray(x2 - x1)
        for (y in y1 until y2) {
            b.getPixels(row, 0, x2 - x1, x1, y, x2 - x1, 1)
            for (c in row) { sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3; n++ }
        }
        return n > 0 && sum / n < cfg.kbLumMax
    }

    /** Нажимает Enter на клавиатуре и убеждается, что она закрылась. true — клавиатуры больше нет. */
    private suspend fun commitAndCloseKeyboard(): Boolean {
        for (i in 0 until maxOf(1, cfg.enterRetries)) {
            tap(cfg.enterX, cfg.enterY)
            delay(cfg.delayAfterEnter)
            val b = screenshot() ?: return true   // не удалось проверить — считаем, что закрылась
            val open = isKeyboardOpen(b); b.recycle()
            if (!open) return true
        }
        // Enter не помог — сворачиваем клавиатуру стрелкой.
        return hideKeyboard()
    }

    /** Сворачивает клавиатуру стрелкой, при необходимости пробует ещё Enter. true — закрылась. */
    private suspend fun hideKeyboard(): Boolean {
        for (i in 0 until 2) {
            tap(cfg.kbHideX, cfg.kbHideY); delay(500)
            val b = screenshot() ?: return true
            val open = isKeyboardOpen(b); b.recycle()
            if (!open) return true
            tap(cfg.enterX, cfg.enterY); delay(cfg.delayAfterEnter)
            val b2 = screenshot() ?: return true
            val open2 = isKeyboardOpen(b2); b2.recycle()
            if (!open2) return true
        }
        return false
    }

    private fun isYellow(b: Bitmap, fx: Float, fy: Float): Boolean {
        val c = b.getPixel((fx * b.width).toInt(), (fy * b.height).toInt())
        return Color.red(c) > cfg.yellowRMin && Color.green(c) in cfg.yellowGMin..cfg.yellowGMax && Color.blue(c) < cfg.yellowBMax
    }

    /** Диалог открыт, если на месте кнопки "Обменять" светлая панель (в списке там тёмная кнопка или клавиатура). */
    private fun isDialogOpen(b: Bitmap): Boolean {
        val x = (cfg.probeX * b.width).toInt().coerceIn(3, b.width - 4)
        val y = (cfg.probeY * b.height).toInt().coerceIn(3, b.height - 4)
        var sum = 0; var n = 0
        for (dy in -3..3) for (dx in -3..3) {
            val c = b.getPixel(x + dx, y + dy)
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3; n++
        }
        return sum / n >= cfg.probeLumMin
    }

    /** Закрывает диалог крестиком; проверяет скриншотом и повторяет, пока диалог не исчезнет. */
    private suspend fun closeDialog() {
        repeat(maxOf(1, cfg.closeRetries)) {
            tap(cfg.dialogCloseX, cfg.dialogCloseY)
            delay(cfg.delayAfterHitClose)
            val b = screenshot() ?: return
            val open = isDialogOpen(b)
            b.recycle()
            if (!open) return
        }
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

    private fun saveHit(code: Long, note: String) {
        outputStreamFor("found_codes.txt", "text/plain", append = true)?.use {
            it.write((if (note.isEmpty()) "$code\n" else "$code ($note)\n").toByteArray())
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", code.toString()))
    }

    private fun saveScreenshot(b: Bitmap, code: Long, note: String) {
        val copy = b.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val p = Paint().apply { color = Color.RED; textSize = 64f; isFakeBoldText = true }
        canvas.drawText(if (note.isEmpty()) "CODE: $code" else "CODE: $code ($note)", 40f, 100f, p)
        outputStreamFor("hit_$code.png", "image/png", append = false)?.use {
            copy.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        copy.recycle()
    }

    private suspend fun tap(fx: Float, fy: Float) = press(fx, fy, 60)

    private suspend fun press(fx: Float, fy: Float, durMs: Long) {
        // Если система не вызвала callback жеста (бывает), не ждём вечно.
        val done = withTimeoutOrNull(2500L + durMs) {
            suspendCancellableCoroutine<Unit> { c ->
                val path = Path().apply { moveTo(fx * w, fy * h) }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durMs)).build()
                val ok = dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
                    override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(Unit) }
                }, null)
                if (!ok && c.isActive) c.resume(Unit)
            }
        }
        if (done == null) gestureTimeouts++
    }

    private suspend fun screenshot(): Bitmap? = withTimeoutOrNull(4000L) {
        suspendCancellableCoroutine<Bitmap?> { c ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); r.hardwareBuffer.close()
                    if (c.isActive) c.resume(soft) else soft?.recycle()
                }
                override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
            })
        }
    }
}
