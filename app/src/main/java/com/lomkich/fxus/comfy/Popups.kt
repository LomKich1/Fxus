package com.lomkich.fxus.comfy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** Держит границы элемента-источника анимации, не вызывая перекомпозиций. */
class RectHolder {
    var r: Rect = Rect.Zero
}

enum class MorphPlacement {
    /** Растёт от элемента до центра экрана (диалог). */
    CENTER,

    /** Растёт вверх от элемента, ширина равна ширине элемента (выпадающий список). */
    ABOVE
}

/**
 * Окно, которое «вырастает» из элемента: стартует с его границ и формы, плавно меняет размер,
 * позицию и скругление. Содержимое всегда лежит в целевом размере и просто открывается вместе
 * с рамкой. anchor == null: закрыто (идёт обратная анимация и окно пропадает).
 */
@Composable
fun MorphPopup(
    anchor: Rect?,
    placement: MorphPlacement,
    color: Color,
    startRadius: Dp,
    endRadius: Dp,
    scrimAlpha: Float,
    onDismiss: () -> Unit,
    maxWidth: Dp = 420.dp,
    content: @Composable () -> Unit
) {
    val open = anchor != null
    val held = remember { arrayOfNulls<Rect>(1) }
    if (anchor != null) held[0] = anchor
    val p = animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (open) spring(dampingRatio = 0.85f, stiffness = 380f) else tween(200, easing = FastOutSlowInEasing),
        label = "morph"
    )
    val visible by remember(open) { derivedStateOf { open || p.value > 0.001f } }

    BackHandler(enabled = open, onBack = onDismiss)

    val rect = held[0]
    if (rect != null && visible) {
        val density = LocalDensity.current
        val topInset = WindowInsets.statusBars.getTop(density)
        val bottomInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
        val startR = with(density) { startRadius.toPx() }
        val endR = with(density) { endRadius.toPx() }
        val shadowPx = with(density) { 12.dp.toPx() }
        val maxWPx = with(density) { maxWidth.roundToPx() }
        val marginPx = with(density) { 16.dp.roundToPx() }
        val anchorW = rect.width.roundToInt()
        val anchorH = rect.height.roundToInt()
        val matchWidth = placement == MorphPlacement.ABOVE

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .pointerInput(open) { if (open) detectTapGestures { onDismiss() } }
            )
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        Modifier
                            .graphicsLayer {
                                val f = p.value.coerceIn(0f, 1f)
                                shape = RoundedCornerShape(CornerSize(lerp(startR, endR, f)))
                                clip = true
                                shadowElevation = shadowPx * f
                            }
                            .background(color)
                            .layout { measurable, constraints ->
                                val f = p.value
                                val tw = if (matchWidth) anchorW else minOf(constraints.maxWidth - 2 * marginPx, maxWPx)
                                val maxH = when (placement) {
                                    MorphPlacement.CENTER -> constraints.maxHeight - topInset - bottomInset - 2 * marginPx
                                    MorphPlacement.ABOVE -> minOf((rect.bottom - topInset - marginPx).toInt(), constraints.maxHeight / 2)
                                }.coerceAtLeast(0)
                                val pl = measurable.measure(Constraints(minWidth = tw, maxWidth = tw, minHeight = 0, maxHeight = maxH))
                                val w = lerp(anchorW, tw, f).coerceAtLeast(0)
                                val h = lerp(anchorH, pl.height, f).coerceAtLeast(0)
                                layout(w, h) { pl.place(0, 0) }
                            }
                            .pointerInput(Unit) { detectTapGestures { } }
                    ) {
                        Box(Modifier.graphicsLayer { alpha = ((p.value - 0.2f) / 0.6f).coerceIn(0f, 1f) }) {
                            content()
                        }
                    }
                }
            ) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val card = measurables[0].measure(Constraints(maxWidth = w, maxHeight = h))
                layout(w, h) {
                    val f = p.value
                    val pos = when (placement) {
                        MorphPlacement.CENTER -> {
                            val cx = lerp(rect.center.x, w / 2f, f)
                            val cy = lerp(rect.center.y, (topInset + (h - bottomInset)) / 2f, f)
                            IntOffset((cx - card.width / 2f).roundToInt(), (cy - card.height / 2f).roundToInt())
                        }
                        MorphPlacement.ABOVE -> IntOffset(rect.left.roundToInt(), (rect.bottom - card.height).roundToInt())
                    }
                    card.place(pos)
                }
            }
        }
    }
}

/** Мини-прямоугольник с пропорциями кадра. */
@Composable
fun RatioIcon(w: Int, h: Int, color: Color = LocalContentColor.current, box: Dp = 18.dp) {
    val kw = if (w >= h) 1f else w.toFloat() / h
    val kh = if (w >= h) h.toFloat() / w else 1f
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = box * kw, height = box * kh)
                .border(1.5.dp, color, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun SizeTile(size: Size, ratio: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (selected) cs.primary else cs.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) cs.primary else cs.outline)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RatioIcon(size.w, size.h, tint)
            Column {
                Text("${size.w}×${size.h}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                if (ratio != null) Text(ratio, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TileGrid(items: List<Pair<Size, String?>>, current: Size, onPick: (Size) -> Unit) {
    items.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { (s, ratio) ->
                SizeTile(s, ratio, s == current, Modifier.weight(1f)) { onPick(s) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Содержимое окна разрешения: готовые варианты, недавние свои и ввод своего. */
@Composable
fun SizePickerContent(
    current: Size,
    recent: List<Size>,
    onPick: (Size) -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var wText by remember { mutableStateOf(current.w.toString()) }
    var hText by remember { mutableStateOf(current.h.toString()) }
    val w = wText.toIntOrNull()
    val h = hText.toIntOrNull()
    val custom = if (w != null && h != null && w in Size.MIN..Size.MAX && h in Size.MIN..Size.MAX) {
        Size(Size.snap(w), Size.snap(h))
    } else null
    val typed = wText.isNotEmpty() && hText.isNotEmpty()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Разрешение", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Spacer(Modifier.height(16.dp))

        TileGrid(Size.PRESETS.map { it.size to it.ratio }, current, onPick)

        val extra = recent.filter { r -> Size.PRESETS.none { it.size == r } }
        if (extra.isNotEmpty()) {
            Text(
                "Свои",
                fontSize = 14.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            TileGrid(extra.map { it to null }, current, onPick)
        }

        Text(
            "Своё разрешение",
            fontSize = 14.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = wText,
                onValueChange = { wText = it.filter(Char::isDigit).take(4) },
                label = { Text("Ширина") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text("×", color = cs.onSurfaceVariant)
            OutlinedTextField(
                value = hText,
                onValueChange = { hText = it.filter(Char::isDigit).take(4) },
                label = { Text("Высота") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        val rounded = custom != null && (custom.w != w || custom.h != h)
        Text(
            when {
                custom == null && typed -> "От ${Size.MIN} до ${Size.MAX} по каждой стороне"
                rounded -> "Округлю до кратного 8: ${custom!!.w}×${custom.h}"
                else -> "От ${Size.MIN} до ${Size.MAX}, кратно 8"
            },
            fontSize = 12.sp,
            color = if (custom == null && typed) cs.error else cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            TextButton(enabled = custom != null, onClick = { custom?.let(onPick) }) { Text("Применить") }
        }
    }
}

/** Имя модели без расширения, чтобы влезало в кнопку и список. */
fun modelTitle(name: String): String = name.removeSuffix(".safetensors").removeSuffix(".ckpt")

/** Содержимое выпадающего списка чекпоинтов. */
@Composable
fun CheckpointListContent(
    models: List<String>?,
    scanning: Boolean,
    error: String?,
    selected: String,
    onPick: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
    ) {
        when {
            scanning -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Сканирую…", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
            error != null -> Text(
                error,
                fontSize = 14.sp,
                color = cs.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
            else -> models.orEmpty().forEach { name ->
                val sel = name == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(name) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        modelTitle(name),
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        color = if (sel) cs.primary else cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (sel) Box(Modifier.size(6.dp).background(cs.primary, CircleShape))
                }
            }
        }
    }
}
