package com.example.codescanner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

/**
 * Все координаты (доли экрана 0..1) и задержки (мс) вынесены сюда, чтобы их можно
 * было поправить через файл Download/configcards/config.txt — без пересборки APK.
 * Если файла нет, при первом старте он создаётся с текущими значениями по умолчанию.
 */
class Config {
    // Поле "Введите код обмена"
    var fieldX = 0.465f; var fieldY = 0.833f
    // Крестик очистки поля (резерв, если Accessibility SET_TEXT не сработал)
    var clearX = 0.598f; var clearY = 0.833f
    // Кнопка "Обменять"
    var exchX = 0.666f; var exchY = 0.833f
    // Крестик закрытия окна "Обменять"
    var closeX = 0.807f; var closeY = 0.150f
    // Жёлтая кнопка OK в окне "Обменяться картами"
    var okX = 0.500f; var okY = 0.845f
    // Крестик закрытия окна "Обменяться картами" (после найденного совпадения)
    var dialogCloseX = 0.823f; var dialogCloseY = 0.131f
    // Синяя кнопка ввода/галочка на клавиатуре (коммитит текст поля)
    var enterX = 0.900f; var enterY = 0.952f
    // Ряд цифр клавиатуры (резерв)
    var keyRowY = 0.579f
    var digit0 = 0.917f
    var digit1 = 0.079f; var digit2 = 0.148f; var digit3 = 0.216f
    var digit4 = 0.286f; var digit5 = 0.355f
    var digit6 = 0.641f; var digit7 = 0.710f; var digit8 = 0.780f; var digit9 = 0.848f

    // Задержки, мс
    var delayAfterFieldTap = 350L
    var delayAfterSetText = 250L
    var delayAfterEnter = 550L
    var delayAfterExch = 1000L
    var delayDigitTap = 120L
    var delayAfterHitClose = 600L

    // Порог определения жёлтой кнопки OK (RGB)
    var yellowRMin = 180
    var yellowGMin = 130
    var yellowGMax = 225
    var yellowBMax = 130

    // Окно "Обменяться картами" с красной надписью "У вас нет карты, которую хочет игрок"
    // (код рабочий, но нужной карты нет; кнопка OK серая). Ищем красные пиксели надписи
    // в прямоугольнике (доли экрана) — если их доля >= noCardRatio, считаем находкой.
    var noCardX1 = 0.40f; var noCardX2 = 0.60f
    var noCardY1 = 0.745f; var noCardY2 = 0.800f
    var noCardRatio = 0.02f
    var noCardRMin = 190
    var noCardGMax = 90
    var noCardBMax = 110

    // Определение "открыто окно-диалог поверх списка обменов": точка на месте кнопки "Обменять"
    // (доли реального экрана). В списке там тёмная кнопка/клавиатура, в диалоге — светлая панель.
    var probeX = 0.640f; var probeY = 0.833f
    var probeLumMin = 140
    var closeRetries = 4
    // Сторож: максимум времени на один код (мс). Если шаг завис — пропускаем код и идём дальше.
    var codeTimeoutMs = 30000L

    fun digits() = floatArrayOf(digit0, digit1, digit2, digit3, digit4, digit5, digit6, digit7, digit8, digit9)

    companion object {
        const val REL_PATH = "Download/configcards/"
        const val FILE_NAME = "config.txt"

        fun defaultText(c: Config): String = buildString {
            appendLine("# Конфиг CodeScanner. Числа — доли экрана (0..1) или миллисекунды.")
            appendLine("# Поправьте значение и перезапустите кликер (кнопка Старт) — пересборка APK не нужна.")
            appendLine()
            appendLine("FIELD_X=${c.fieldX}")
            appendLine("FIELD_Y=${c.fieldY}")
            appendLine("CLEAR_X=${c.clearX}")
            appendLine("CLEAR_Y=${c.clearY}")
            appendLine("EXCH_X=${c.exchX}")
            appendLine("EXCH_Y=${c.exchY}")
            appendLine("CLOSE_X=${c.closeX}")
            appendLine("CLOSE_Y=${c.closeY}")
            appendLine("OK_X=${c.okX}")
            appendLine("OK_Y=${c.okY}")
            appendLine("DIALOG_CLOSE_X=${c.dialogCloseX}")
            appendLine("DIALOG_CLOSE_Y=${c.dialogCloseY}")
            appendLine("ENTER_X=${c.enterX}")
            appendLine("ENTER_Y=${c.enterY}")
            appendLine("KEY_ROW_Y=${c.keyRowY}")
            appendLine("DIGIT_0=${c.digit0}")
            appendLine("DIGIT_1=${c.digit1}")
            appendLine("DIGIT_2=${c.digit2}")
            appendLine("DIGIT_3=${c.digit3}")
            appendLine("DIGIT_4=${c.digit4}")
            appendLine("DIGIT_5=${c.digit5}")
            appendLine("DIGIT_6=${c.digit6}")
            appendLine("DIGIT_7=${c.digit7}")
            appendLine("DIGIT_8=${c.digit8}")
            appendLine("DIGIT_9=${c.digit9}")
            appendLine()
            appendLine("DELAY_AFTER_FIELD_TAP=${c.delayAfterFieldTap}")
            appendLine("DELAY_AFTER_SET_TEXT=${c.delayAfterSetText}")
            appendLine("DELAY_AFTER_ENTER=${c.delayAfterEnter}")
            appendLine("DELAY_AFTER_EXCH=${c.delayAfterExch}")
            appendLine("DELAY_DIGIT_TAP=${c.delayDigitTap}")
            appendLine("DELAY_AFTER_HIT_CLOSE=${c.delayAfterHitClose}")
            appendLine()
            appendLine("YELLOW_R_MIN=${c.yellowRMin}")
            appendLine("YELLOW_G_MIN=${c.yellowGMin}")
            appendLine("YELLOW_G_MAX=${c.yellowGMax}")
            appendLine("YELLOW_B_MAX=${c.yellowBMax}")
            appendLine()
            appendLine("# Окно \"нет карты\": красная надпись (область в долях экрана, мин. доля красных пикселей, пороги RGB)")
            appendLine("NOCARD_X1=${c.noCardX1}")
            appendLine("NOCARD_X2=${c.noCardX2}")
            appendLine("NOCARD_Y1=${c.noCardY1}")
            appendLine("NOCARD_Y2=${c.noCardY2}")
            appendLine("NOCARD_RATIO=${c.noCardRatio}")
            appendLine("NOCARD_R_MIN=${c.noCardRMin}")
            appendLine("NOCARD_G_MAX=${c.noCardGMax}")
            appendLine("NOCARD_B_MAX=${c.noCardBMax}")
            appendLine()
            appendLine("# Любое окно-диалог (светлая панель на месте кнопки Обменять) считается находкой и закрывается крестиком")
            appendLine("PROBE_X=${c.probeX}")
            appendLine("PROBE_Y=${c.probeY}")
            appendLine("PROBE_LUM_MIN=${c.probeLumMin}")
            appendLine("CLOSE_RETRIES=${c.closeRetries}")
            appendLine()
            appendLine("# Сторож: максимум времени на один код, мс (при зависании шага код пропускается)")
            appendLine("CODE_TIMEOUT_MS=${c.codeTimeoutMs}")
        }

        /** Читает config.txt из Download/configcards. Если файла нет — создаёт с значениями по умолчанию. */
        fun load(context: Context): Config {
            val cfg = Config()
            val resolver = context.applicationContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            var text: String? = null
            resolver.query(collection, projection, selection, arrayOf(FILE_NAME, REL_PATH), null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    runCatching {
                        resolver.openInputStream(uri)?.use { text = it.readBytes().toString(Charsets.UTF_8) }
                    }
                }
            }
            if (text == null) {
                // Файла ещё нет — создаём с дефолтными значениями, чтобы было что редактировать.
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
                    }
                    val uri = resolver.insert(collection, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(defaultText(cfg).toByteArray()) }
                    }
                }
                return cfg
            }
            apply(cfg, text!!)
            return cfg
        }

        private fun apply(cfg: Config, text: String) {
            val map = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val i = line.indexOf('=')
                if (i <= 0) continue
                map[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
            }
            fun f(key: String, def: Float) = map[key]?.toFloatOrNull() ?: def
            fun l(key: String, def: Long) = map[key]?.toLongOrNull() ?: def
            fun i(key: String, def: Int) = map[key]?.toIntOrNull() ?: def

            cfg.fieldX = f("FIELD_X", cfg.fieldX); cfg.fieldY = f("FIELD_Y", cfg.fieldY)
            cfg.clearX = f("CLEAR_X", cfg.clearX); cfg.clearY = f("CLEAR_Y", cfg.clearY)
            cfg.exchX = f("EXCH_X", cfg.exchX); cfg.exchY = f("EXCH_Y", cfg.exchY)
            cfg.closeX = f("CLOSE_X", cfg.closeX); cfg.closeY = f("CLOSE_Y", cfg.closeY)
            cfg.okX = f("OK_X", cfg.okX); cfg.okY = f("OK_Y", cfg.okY)
            cfg.dialogCloseX = f("DIALOG_CLOSE_X", cfg.dialogCloseX); cfg.dialogCloseY = f("DIALOG_CLOSE_Y", cfg.dialogCloseY)
            cfg.enterX = f("ENTER_X", cfg.enterX); cfg.enterY = f("ENTER_Y", cfg.enterY)
            cfg.keyRowY = f("KEY_ROW_Y", cfg.keyRowY)
            cfg.digit0 = f("DIGIT_0", cfg.digit0); cfg.digit1 = f("DIGIT_1", cfg.digit1)
            cfg.digit2 = f("DIGIT_2", cfg.digit2); cfg.digit3 = f("DIGIT_3", cfg.digit3)
            cfg.digit4 = f("DIGIT_4", cfg.digit4); cfg.digit5 = f("DIGIT_5", cfg.digit5)
            cfg.digit6 = f("DIGIT_6", cfg.digit6); cfg.digit7 = f("DIGIT_7", cfg.digit7)
            cfg.digit8 = f("DIGIT_8", cfg.digit8); cfg.digit9 = f("DIGIT_9", cfg.digit9)

            cfg.delayAfterFieldTap = l("DELAY_AFTER_FIELD_TAP", cfg.delayAfterFieldTap)
            cfg.delayAfterSetText = l("DELAY_AFTER_SET_TEXT", cfg.delayAfterSetText)
            cfg.delayAfterEnter = l("DELAY_AFTER_ENTER", cfg.delayAfterEnter)
            cfg.delayAfterExch = l("DELAY_AFTER_EXCH", cfg.delayAfterExch)
            cfg.delayDigitTap = l("DELAY_DIGIT_TAP", cfg.delayDigitTap)
            cfg.delayAfterHitClose = l("DELAY_AFTER_HIT_CLOSE", cfg.delayAfterHitClose)

            cfg.yellowRMin = i("YELLOW_R_MIN", cfg.yellowRMin)
            cfg.yellowGMin = i("YELLOW_G_MIN", cfg.yellowGMin)
            cfg.yellowGMax = i("YELLOW_G_MAX", cfg.yellowGMax)
            cfg.yellowBMax = i("YELLOW_B_MAX", cfg.yellowBMax)

            cfg.noCardX1 = f("NOCARD_X1", cfg.noCardX1); cfg.noCardX2 = f("NOCARD_X2", cfg.noCardX2)
            cfg.noCardY1 = f("NOCARD_Y1", cfg.noCardY1); cfg.noCardY2 = f("NOCARD_Y2", cfg.noCardY2)
            cfg.noCardRatio = f("NOCARD_RATIO", cfg.noCardRatio)
            cfg.noCardRMin = i("NOCARD_R_MIN", cfg.noCardRMin)
            cfg.noCardGMax = i("NOCARD_G_MAX", cfg.noCardGMax)
            cfg.noCardBMax = i("NOCARD_B_MAX", cfg.noCardBMax)

            cfg.probeX = f("PROBE_X", cfg.probeX); cfg.probeY = f("PROBE_Y", cfg.probeY)
            cfg.probeLumMin = i("PROBE_LUM_MIN", cfg.probeLumMin)
            cfg.closeRetries = i("CLOSE_RETRIES", cfg.closeRetries)
            cfg.codeTimeoutMs = i("CODE_TIMEOUT_MS", cfg.codeTimeoutMs.toInt()).toLong()
        }
    }
}
