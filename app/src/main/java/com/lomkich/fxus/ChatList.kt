package com.lomkich.fxus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Строка чата. Тап открывает, долгий тап даёт меню «Переименовать / Удалить».
 * Используется и в меню (Недавние), и на экране «Чаты».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    meta: ChatMeta,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Box {
        Text(
            meta.title,
            color = ColText,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) ColSurface else Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Переименовать", color = ColText, fontSize = 15.sp) },
                onClick = {
                    menu = false
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text("Удалить", color = ColText, fontSize = 15.sp) },
                onClick = {
                    menu = false
                    deleting = true
                },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            initial = meta.title,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                onRename(it)
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = ColSurface,
            title = { Text("Удалить чат?", color = ColText) },
            text = { Text(meta.title, color = ColMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("Удалить", color = ColText) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text("Отмена", color = ColMuted) }
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ColSurface,
        title = { Text("Название чата", color = ColText) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColUser)
                    .padding(14.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                    cursorBrush = SolidColor(ColText),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text("Сохранить", color = ColText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = ColMuted) }
        },
    )
}

/** Экран «Чаты»: все чаты и поиск по названиям и тексту сообщений. */
@Composable
fun ChatsScreen(vm: ChatViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Set<String>?>(null) }

    // небольшая пауза, чтобы не перебирать файлы на каждую набранную букву
    LaunchedEffect(query) {
        found = if (query.isBlank()) {
            null
        } else {
            delay(250)
            vm.searchChats(query)
        }
    }
    val ids = found
    val shown = vm.chats.filter { ids == null || it.id in ids }

    Column(Modifier.fillMaxSize()) {
        TopBar("Чаты", onBack)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(ColSurface)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            if (query.isEmpty()) Text("Поиск по чатам", color = ColMuted, fontSize = 16.sp)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                cursorBrush = SolidColor(ColText),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.chats.isEmpty()) "Чатов пока нет" else "Ничего не найдено",
                    color = ColMuted,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(shown, key = { it.id }) { meta ->
                    ChatRow(
                        meta = meta,
                        selected = meta.id == vm.currentId,
                        onOpen = { onOpen(meta.id) },
                        onRename = { vm.renameChat(meta.id, it) },
                        onDelete = { vm.deleteChat(meta.id) },
                    )
                }
            }
        }
    }
}
