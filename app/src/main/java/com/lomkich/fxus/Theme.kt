package com.lomkich.fxus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Графит, не чёрный. Белый почти не используется: текст приглушённо-светлый,
// чистый акцент только на кнопке отправки.
val ColBg = Color(0xFF1A1C20)
val ColSurface = Color(0xFF24272C)
val ColUser = Color(0xFF353A42)
val ColText = Color(0xFFE2E4E7)
val ColMuted = Color(0xFF8B9099)
val ColAccent = Color(0xFFECEDEF)

@Composable
fun FxusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ColBg,
            surface = ColSurface,
            primary = ColAccent,
            onPrimary = ColBg,
            onBackground = ColText,
            onSurface = ColText,
        ),
        content = content,
    )
}
