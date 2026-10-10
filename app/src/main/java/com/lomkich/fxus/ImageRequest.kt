package com.lomkich.fxus

import com.lomkich.fxus.comfy.Size

/**
 * Запрос картинки из обычного чата: «сделай фото кота на крыше 768x1344».
 * Триггер ищется словами, а не решается моделью: маленькие модели нестабильно вызывают функции.
 * Размер берётся из текста («1024x1024», «1024×768», «1024 на 768»), по умолчанию 1024x1024.
 */
data class ImageRequest(val prompt: String, val width: Int, val height: Int) {
    companion object {
        const val DEFAULT_SIDE = 1024

        // «сделай фото», «сделай мне фотку», «сделай фотографию»; после слова не должно идти продолжение
        private val TRIGGER = Regex("""сделай\s+(?:мне\s+)?(?:фото|фотку|фотографию)(?!\p{L})""", RegexOption.IGNORE_CASE)
        // латинская x, русская х, ×, *
        private val SIZE_X = Regex("""(?<!\d)(\d{3,4})\s*[xXхХ×*]\s*(\d{3,4})(?!\d)""")
        private val SIZE_BY = Regex("""(?<!\d)(\d{3,4})\s+на\s+(\d{3,4})(?!\d)""", RegexOption.IGNORE_CASE)

        /**
         * null, если в тексте нет триггера. Описание берём из текста после триггера (приветствие до него
         * не нужно), а если после пусто, то из текста до. Пустой prompt значит «сделай фото» без описания.
         */
        fun parse(text: String): ImageRequest? {
            var w = DEFAULT_SIDE
            var h = DEFAULT_SIDE
            var cleaned = text
            val size = SIZE_X.find(text) ?: SIZE_BY.find(text)
            if (size != null) {
                w = Size.snap(size.groupValues[1].toInt())
                h = Size.snap(size.groupValues[2].toInt())
                cleaned = text.removeRange(size.range)
            }
            val trigger = TRIGGER.find(cleaned) ?: return null
            val after = tidy(cleaned.substring(trigger.range.last + 1))
            val before = tidy(cleaned.substring(0, trigger.range.first))
            return ImageRequest(after.ifBlank { before }, w, h)
        }

        private fun tidy(s: String): String =
            s.replace(Regex("""\s+"""), " ").trim().trim(',', ':', ';', '-', '—', '.', ' ')
    }
}
