package com.lomkich.fxus

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** Состояние Haze экрана чата. Без него (null) пузыри рисуются как раньше, просто полупрозрачными. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Фон плавающих пузырей (шапка, поле ввода): размытие того, что лежит под ними (Haze),
 * сверху полупрозрачная заливка и тонкая кромка. Весь вид пузырей живёт здесь.
 * Блюр работает с Android 12 (API 31). Ниже него заливка плотнее, как до Haze.
 * strong = true: то же размытие, но плотная заливка (для списков поверх текста).
 */
@Composable
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier {
    val haze = LocalHazeState.current
    // strong: плотнее заливка (выпадающий список поверх текста должен читаться)
    val blurred = haze != null && Build.VERSION.SDK_INT >= 31 && !strong
    var m = this.clip(shape)
    if (haze != null) {
        m = m.hazeEffect(state = haze) {
            blurRadius = 18.dp
            noiseFactor = 0f
            backgroundColor = ColBg
        }
    }
    return m.background(if (blurred) ColGlassBlur else ColGlass).border(0.5.dp, ColGlassEdge, shape)
}
