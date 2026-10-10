package com.lomkich.fxus.comfy

import android.graphics.Bitmap
import java.io.File

data class Preset(val ratio: String, val size: Size)

/** Размер кадра. Хранится как «ШxВ»; старые имена (WIDE и т. п.) тоже читаем. */
data class Size(val w: Int, val h: Int) {
    val key: String get() = "${w}x${h}"
    val label: String get() = "$w×$h"

    companion object {
        const val MIN = 64
        const val MAX = 4096
        val DEFAULT = Size(2048, 1024)

        val PRESETS = listOf(
            Preset("1:1", Size(1024, 1024)),
            Preset("2:1", Size(2048, 1024)),
            Preset("2:3", Size(832, 1216)),
            Preset("3:2", Size(1216, 832)),
            Preset("3:4", Size(896, 1152)),
            Preset("4:3", Size(1152, 896)),
            Preset("9:16", Size(768, 1344)),
            Preset("16:9", Size(1344, 768))
        )

        /** Латентное пространство кратно 8, поэтому округляем. */
        fun snap(v: Int): Int = ((v + 4) / 8 * 8).coerceIn(MIN, MAX)

        fun parse(s: String): Size? {
            when (s) {
                "WIDE" -> return Size(2048, 1024)
                "SQUARE" -> return Size(1024, 1024)
                "PORTRAIT" -> return Size(832, 1216)
                "LANDSCAPE" -> return Size(1216, 832)
            }
            val parts = s.split('x')
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return if (w in MIN..MAX && h in MIN..MAX) Size(w, h) else null
        }
    }
}

data class ImageRef(val filename: String, val subfolder: String, val type: String)

sealed interface GenEvent {
    data class Stage(val text: String) : GenEvent
    data class Progress(val value: Int, val max: Int) : GenEvent
    class Preview(val bitmap: Bitmap) : GenEvent
    data class Tags(val text: String) : GenEvent
    data class Done(val images: List<ImageRef>) : GenEvent
    data class Failed(val message: String) : GenEvent
}

data class Turn(
    val id: Long,
    val prompt: String,
    val size: Size,
    val stage: String = "Отправляю…",
    val percent: Int? = null,
    val preview: Bitmap? = null,
    val tags: String? = null,
    val file: File? = null,   // сохранённая картинка (история)
    val error: String? = null,
    val running: Boolean = true
)
