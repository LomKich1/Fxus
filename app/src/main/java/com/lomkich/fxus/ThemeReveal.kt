package com.lomkich.fxus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * Запрос на смену темы с круговой анимацией. Аргумент: центр круга в координатах окна (px),
 * обычно центр нажатой кнопки. Без хоста ThemeReveal ничего не делает.
 */
val LocalThemeToggle = compositionLocalOf<(Offset) -> Unit> { { } }

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Как View Transition с круговой маской в вебе: снимаем кадр окна со старой темой, включаем
 * новую, а сверху рисуем старый кадр с дырой. Дыра растёт от кнопки и открывает новую тему.
 * Если снимок не получился, тема просто переключается без анимации.
 */
@Composable
fun ThemeReveal(
    isDark: Boolean,
    onSetTheme: (ThemeMode) -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dark by rememberUpdatedState(isDark)
    val setTheme by rememberUpdatedState(onSetTheme)
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var running by remember { mutableStateOf(false) }
    val radius = remember { Animatable(0f) }

    val toggle: (Offset) -> Unit = remember {
        { origin ->
            if (!running) {
                running = true
                val target = if (dark) ThemeMode.LIGHT else ThemeMode.DARK
                val w = view.width
                val h = view.height
                val window = view.context.findActivity()?.window
                if (window == null || w <= 0 || h <= 0) {
                    setTheme(target)
                    running = false
                } else {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(window, bmp, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            snapshot = bmp.asImageBitmap()
                            center = origin
                            setTheme(target)
                            val reach = hypot(max(origin.x, w - origin.x), max(origin.y, h - origin.y))
                            scope.launch {
                                radius.snapTo(0f)
                                radius.animateTo(reach, tween(550, easing = FastOutSlowInEasing))
                                snapshot = null
                                running = false
                            }
                        } else {
                            setTheme(target)
                            running = false
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            }
        }
    }

    CompositionLocalProvider(LocalThemeToggle provides toggle) {
        Box(Modifier.fillMaxSize()) {
            content()
            val snap = snapshot
            if (snap != null) {
                // Offscreen нужен, чтобы BlendMode.Clear вырезал дыру только в этом слое
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    drawImage(snap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    drawCircle(Color.Black, radius = radius.value, center = center, blendMode = BlendMode.Clear)
                }
            }
        }
    }
}
