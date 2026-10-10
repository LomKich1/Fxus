package com.lomkich.fxus.comfy

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Экран «Артефакты»: сетка созданных картинок, просмотр на весь экран и удаление.
 * Сейчас артефакты это только картинки, остальное добавится сюда же.
 */
@Composable
fun GalleryHost(vm: ComfyViewModel, onClose: () -> Unit) {
    var viewingId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = ComfyTypography) {
        val cs = MaterialTheme.colorScheme

        GalleryScreen(
            turns = vm.turns,
            onOpen = { viewingId = it },
            onDeleteMany = vm::deleteMany,
            onClose = onClose
        )

        viewingId?.let { id ->
            vm.turns.firstOrNull { it.id == id }?.let { t ->
                ImageViewer(t, onDismiss = { viewingId = null }, onDelete = { confirmDeleteId = id })
            }
        }

        confirmDeleteId?.let { id ->
            AlertDialog(
                onDismissRequest = { confirmDeleteId = null },
                title = { Text("Удалить?") },
                text = { Text("Сообщение и картинка будут удалены без возможности восстановления.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.delete(id)
                        if (viewingId == id) viewingId = null
                        confirmDeleteId = null
                    }) { Text("Удалить", color = cs.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Отмена") } }
            )
        }
    }
}
