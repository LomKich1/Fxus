package com.lomkich.fxus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Кнопка «+» слева в поле ввода. Нажимаемая область 44dp, как у кнопки справа. */
@Composable
fun AttachButton(onClick: () -> Unit) {
    val color = ColMuted
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val sw = 2.dp.toPx()
            drawLine(color, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), sw, StrokeCap.Round)
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), sw, StrokeCap.Round)
        }
    }
}

/**
 * Ряд вложений: картинки квадратиками, текстовые файлы плашками с именем и размером.
 * Если передан onRemove, у каждого вложения в углу появляется крестик (так выглядит ещё не отправленное).
 */
@Composable
fun AttachmentRow(
    items: List<Attachment>,
    chipBg: Color,
    modifier: Modifier = Modifier,
    thumb: Dp = 64.dp,
    onRemove: ((Attachment) -> Unit)? = null,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { a ->
            Box {
                if (a.kind == AttachKind.IMAGE) ImageChip(a, thumb) else FileChip(a, chipBg, thumb, onRemove != null)
                if (onRemove != null) RemoveBadge(Modifier.align(Alignment.TopEnd)) { onRemove(a) }
            }
        }
    }
}

@Composable
private fun ImageChip(a: Attachment, size: Dp) {
    val ctx = LocalContext.current
    // декодируем в фоне, чтобы список сообщений не дёргался при прокрутке
    val bmp by produceState<ImageBitmap?>(null, a.file) {
        value = withContext(Dispatchers.IO) { Attachments.thumb(ctx, a)?.asImageBitmap() }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(ColSurface),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = a.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun FileChip(a: Attachment, bg: Color, height: Dp, removable: Boolean) {
    Column(
        Modifier
            .height(height)
            .widthIn(min = 96.dp, max = 190.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(start = 12.dp, end = if (removable) 26.dp else 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(a.name, color = ColText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Attachments.sizeLabel(a.bytes), color = ColMuted, fontSize = 11.sp)
    }
}

/** Крестик: видимый кружок 18dp, а нажимаемая область 28dp, чтобы попадать пальцем. */
@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    val fg = ColText
    val bg = ColBg.copy(alpha = 0.8f)
    Box(modifier.size(28.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(8.dp)) {
                val sw = 1.5.dp.toPx()
                drawLine(fg, Offset(0f, 0f), Offset(size.width, size.height), sw, StrokeCap.Round)
                drawLine(fg, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Round)
            }
        }
    }
}
