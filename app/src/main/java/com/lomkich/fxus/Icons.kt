package com.lomkich.fxus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Иконки в одном тонком контурном стиле, нарисованные руками (без библиотеки иконок).
 * Цвет передаётся параметром: внутри Canvas цвета темы читать нельзя.
 */

private fun DrawScope.rays(color: Color, count: Int, from: Float, to: Float, width: Float) {
    val c = center
    val m = size.minDimension
    for (i in 0 until count) {
        val a = Math.PI * 2 * i / count
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            color,
            Offset(c.x + dx * m * from, c.y + dy * m * from),
            Offset(c.x + dx * m * to, c.y + dy * m * to),
            width,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawSun(color: Color) {
    val sw = 1.8.dp.toPx()
    drawCircle(color, radius = size.minDimension * 0.2f, center = center, style = Stroke(sw))
    rays(color, 8, 0.34f, 0.47f, sw)
}

private fun DrawScope.drawMoon(color: Color) {
    val r = size.minDimension * 0.42f
    val c = center
    val disc = Path().apply { addOval(Rect(center = c, radius = r)) }
    val bite = Path().apply { addOval(Rect(center = Offset(c.x + r * 0.55f, c.y - r * 0.45f), radius = r * 0.85f)) }
    val moon = Path.combine(PathOperation.Difference, disc, bite)
    drawPath(moon, color, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
}

private fun DrawScope.drawGear(color: Color) {
    val sw = 1.7.dp.toPx()
    val m = size.minDimension
    drawCircle(color, radius = m * 0.3f, center = center, style = Stroke(sw))
    drawCircle(color, radius = m * 0.12f, center = center, style = Stroke(sw))
    rays(color, 8, 0.3f, 0.45f, 2.6.dp.toPx())
}

private fun DrawScope.drawGlobe(color: Color) {
    val sw = 1.7.dp.toPx()
    val c = center
    val r = size.minDimension * 0.42f
    drawCircle(color, radius = r, center = c, style = Stroke(sw))
    drawOval(color, topLeft = Offset(c.x - r * 0.42f, c.y - r), size = Size(r * 0.84f, r * 2f), style = Stroke(sw))
    drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), sw)
}

private fun DrawScope.drawChevron(color: Color, up: Boolean) {
    val w = size.width
    val h = size.height
    val sw = 1.8.dp.toPx()
    val tip = if (up) 0.3f else 0.7f
    val base = if (up) 0.65f else 0.35f
    drawLine(color, Offset(w * 0.15f, h * base), Offset(w * 0.5f, h * tip), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.85f, h * base), Offset(w * 0.5f, h * tip), sw, StrokeCap.Round)
}

@Composable
fun SunIcon(color: Color = ColText, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) { drawSun(color) }
}

@Composable
fun MoonIcon(color: Color = ColText, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) { drawMoon(color) }
}

@Composable
fun GearIcon(color: Color = ColText, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) { drawGear(color) }
}

@Composable
fun GlobeIcon(color: Color = ColText, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) { drawGlobe(color) }
}

@Composable
fun ChevronIcon(color: Color = ColMuted, up: Boolean = false, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawChevron(color, up) }
}

@Composable
fun HelpIcon(color: Color = ColText, size: Dp = 20.dp) {
    Box(Modifier.size(size).border(1.7.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text("?", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
