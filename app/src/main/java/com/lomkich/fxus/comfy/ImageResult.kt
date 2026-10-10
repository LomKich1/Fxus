package com.lomkich.fxus.comfy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Картинка из истории по id, чтобы показать её в обычном чате: прогресс и превью во время генерации,
 * потом готовый кадр. Тап открывает на весь экран, оттуда же можно удалить.
 */
@Composable
fun ImageResult(vm: ComfyViewModel, turnId: Long, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = vm.turns.firstOrNull { it.id == turnId }
    var viewing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (t == null) {
        if (vm.historyLoaded) {
            Text(
                "Картинка удалена или недоступна",
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = modifier.padding(4.dp)
            )
        } else {
            // история ещё грузится: держим место, чтобы список не прыгал
            Spacer(modifier.height(48.dp))
        }
    } else {
        Column(modifier.widthIn(max = 340.dp)) {
            TurnResult(t, onOpen = { viewing = true }, onRetry = { vm.retry(it) })
        }
        if (viewing) {
            ImageViewer(t, onDismiss = { viewing = false }, onDelete = { confirmDelete = true })
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Удалить?") },
                text = { Text("Картинка будет удалена из чата и из артефактов без возможности восстановления.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.delete(t.id)
                        viewing = false
                        confirmDelete = false
                    }) { Text("Удалить", color = cs.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
            )
        }
    }
}
