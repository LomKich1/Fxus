package com.lomkich.fxus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Фон плавающих пузырей (шапка, поле ввода).
 * Сейчас это просто полупрозрачная заливка. Блюр (Haze) подключим отдельным шагом,
 * и менять придётся только эту функцию.
 */
fun Modifier.glass(shape: Shape): Modifier =
    this.clip(shape).background(ColGlass).border(0.5.dp, ColGlassEdge, shape)
