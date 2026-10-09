package com.lomkich.fxus

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * Палитра приложения. Базовые цвета те же, что в ComfyChat (тёплый графит и кремовый светлый),
 * остальные роли (код, стекло, ссылки) подобраны к ним.
 */
@Immutable
class FxusColors(
    val dark: Boolean,
    val bg: Color,
    val drawer: Color,
    val surface: Color,
    val user: Color,        // пузырь пользователя
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,    // текст и иконки на акцентной заливке
    val think: Color,       // текст рассуждений
    val code: Color,        // окно кода
    val codeBg: Color,      // плашка инлайн-кода
    val codeBgUser: Color,  // инлайн-код внутри пузыря пользователя: заметнее пузыря, иначе сливается
    val link: Color,
    val glass: Color,       // «стекло» пузырей: полупрозрачная заливка
    val glassBlur: Color,   // то же поверх размытия (API 31+): прозрачнее, чтобы блюр был виден
    val glassEdge: Color,   // тонкая кромка, чтобы пузырь читался на фоне
)

private val DarkFxus = FxusColors(
    dark = true,
    bg = Color(0xFF262624),
    drawer = Color(0xFF1F1E1D),
    surface = Color(0xFF30302E),
    user = Color(0xFF3A3A37),
    text = Color(0xFFFAF9F5),
    muted = Color(0xFF8A877F),
    accent = Color(0xFFD97757),
    onAccent = Color(0xFF1F1E1D),
    think = Color(0xFFA6A39A),
    code = Color(0xFF30302E),
    codeBg = Color(0xFF3A3A37),
    codeBgUser = Color(0xFF4A4945),
    link = Color(0xFF9DB4D6),
    glass = Color(0xCC30302E),
    glassBlur = Color(0x8C30302E),
    glassEdge = Color(0x14FFFFFF),
)

private val LightFxus = FxusColors(
    dark = false,
    bg = Color(0xFFFAF9F5),
    drawer = Color(0xFFF0EEE6),
    surface = Color(0xFFF0EEE6),
    user = Color(0xFFE8E6DC),
    text = Color(0xFF141413),
    muted = Color(0xFF7A7873),
    accent = Color(0xFFC6613F),
    onAccent = Color(0xFFFFFFFF),
    think = Color(0xFF6B6A68),
    code = Color(0xFFF0EEE6),
    codeBg = Color(0xFFE8E6DC),
    codeBgUser = Color(0xFFDAD9D4),
    link = Color(0xFF2B5FAE),
    glass = Color(0xCCF0EEE6),
    glassBlur = Color(0x8CF0EEE6),
    glassEdge = Color(0x14000000),
)

val LocalFxusColors = staticCompositionLocalOf { DarkFxus }

// Прежние имена остались, чтобы не переписывать весь код, но теперь это геттеры темы:
// читать их можно только внутри @Composable. В Canvas, drawBehind и remember {} сначала
// кладём цвет в локальную переменную, а уже её используем внутри.
val ColBg: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.bg
val ColDrawer: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.drawer
val ColSurface: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.surface
val ColUser: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.user
val ColText: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.text
val ColMuted: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.muted
val ColAccent: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.accent
val ColOnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.onAccent
val ColThink: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.think
val ColCode: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.code
val ColCodeBg: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.codeBg
val ColCodeBgUser: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.codeBgUser
val ColLink: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.link
val ColGlass: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.glass
val ColGlassBlur: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.glassBlur
val ColGlassEdge: Color
    @Composable @ReadOnlyComposable get() = LocalFxusColors.current.glassEdge

@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.AUTO -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FxusTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.resolveDark()
    val c = if (dark) DarkFxus else LightFxus
    val scheme = if (dark) {
        darkColorScheme(
            background = c.bg,
            surface = c.surface,
            surfaceContainer = c.surface, // фон выпадающего списка моделей
            surfaceVariant = c.user,
            surfaceTint = Color.Transparent, // без цветного налёта на диалогах и меню
            primary = c.accent,
            onPrimary = c.onAccent,
            onBackground = c.text,
            onSurface = c.text,
            onSurfaceVariant = c.think,
            outline = Color(0xFF4A4945),
        )
    } else {
        lightColorScheme(
            background = c.bg,
            surface = c.surface,
            surfaceContainer = c.surface,
            surfaceVariant = c.user,
            surfaceTint = Color.Transparent, // без цветного налёта на диалогах и меню
            primary = c.accent,
            onPrimary = c.onAccent,
            onBackground = c.text,
            onSurface = c.text,
            onSurfaceVariant = c.think,
            outline = Color(0xFFDAD9D4),
        )
    }
    CompositionLocalProvider(LocalFxusColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
