package com.lomkich.fxus.comfy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    turns: List<Turn>,
    onOpen: (Long) -> Unit,
    onDeleteMany: (Set<Long>) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val done = turns.filter { it.file != null }.asReversed()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    // первый «назад» выходит из режима выбора, второй закрывает галерею
    BackHandler(enabled = selecting) { selected = emptySet() }

    val toggle = { id: Long -> selected = if (id in selected) selected - id else selected + id }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            // экран лежит поверх чата, поэтому нажатия не должны проваливаться вниз
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                IconButton(onClick = { selected = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Отменить выбор", tint = cs.onSurfaceVariant)
                }
                Text(
                    "Выбрано: ${selected.size}",
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onBackground
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selected = done.map { it.id }.toSet() }) { Text("Все") }
                IconButton(onClick = { confirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = cs.error)
                }
            } else {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = cs.onSurfaceVariant)
                }
                Text("Артефакты", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                Spacer(Modifier.weight(1f))
                if (done.isNotEmpty()) Text("${done.size}", color = cs.onSurfaceVariant)
            }
        }

        if (done.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Пока пусто.\nСгенерированные картинки появятся здесь.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalItemSpacing = 14.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(done, key = { it.id }) { t ->
                    GalleryCell(
                        t = t,
                        selected = t.id in selected,
                        onTap = { if (selecting) toggle(t.id) else onOpen(t.id) },
                        onLongPress = { toggle(t.id) }
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить выбранное?") },
            text = {
                Text("Будет удалено: ${selected.size} (картинки и сообщения). Это нельзя отменить.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMany(selected)
                    selected = emptySet()
                    confirm = false
                }) { Text("Удалить", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun GalleryCell(t: Turn, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val bmp = rememberFileBitmap(t.file, 480)
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(t.size.w.toFloat() / t.size.h)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surface)
        ) {
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (selected) 0.6f else 1f },
                    contentScale = ContentScale.Crop
                )
            }
            if (selected) {
                Box(Modifier.fillMaxSize().border(3.dp, cs.primary, RoundedCornerShape(12.dp)))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(cs.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Text(
            t.prompt,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)
        )
    }
}
