package com.lomkich.fxus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Графит, не чёрный. Белый почти не используется: текст приглушённо-светлый,
// чистый акцент только на кнопках действия.
val ColBg = Color(0xFF1A1C20)
val ColDrawer = Color(0xFF141619)
val ColSurface = Color(0xFF24272C)
val ColUser = Color(0xFF353A42)
val ColText = Color(0xFFE2E4E7)
val ColMuted = Color(0xFF8B9099)
val ColAccent = Color(0xFFECEDEF)
val ColThink = Color(0xFFA0A5AD)   // текст рассуждений
val ColCode = Color(0xFF2D3036)    // окно кода: серое, не белое
val ColCodeBg = Color(0xFF383C43)  // плашка инлайн-кода
val ColLink = Color(0xFF9DB4D6)
val ColGlass = Color(0xCC24272C)   // «стекло» пузырей: полупрозрачный графит
val ColGlassBlur = Color(0x8C24272C) // заливка поверх размытия (API 31+): прозрачнее, чтобы блюр был виден
val ColGlassEdge = Color(0x14FFFFFF) // тонкая кромка, чтобы пузырь читался на тёмном

@Composable
fun FxusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ColBg,
            surface = ColSurface,
            surfaceContainer = ColSurface, // фон выпадающего списка моделей
            primary = ColAccent,
            onPrimary = ColBg,
            onBackground = ColText,
            onSurface = ColText,
        ),
        content = content,
    )
}
