package com.lomkich.fxus.comfy

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Окно выбора воркфлоу: встроенный, с ПК (сверка по хэшу), импортированные с телефона.
 * «⋯» у строки открывает «Скрыть» / «Удалить»; скрытые лежат в сворачиваемом разделе внизу.
 */
@Composable
fun WorkflowPickerContent(
    items: List<WorkflowItem>,
    hiddenItems: List<WorkflowItem>,
    selectedId: String,
    busy: Boolean,
    status: String?,
    onPick: (WorkflowItem) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onHide: (WorkflowItem) -> Unit,
    onUnhide: (WorkflowItem) -> Unit,
    onDelete: (WorkflowItem) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmId by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Воркфлоу", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (status != null) {
            Text(
                status,
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        items.forEach { item ->
            val sel = item.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (item.enabled) 1f else 0.5f)
                    .clickable(enabled = item.enabled && !busy) { onPick(item) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        fontSize = 15.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        color = if (sel) cs.primary else cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(item.note, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                if (sel) Box(Modifier.size(6.dp).background(cs.primary, CircleShape))
                if (item.canHide || item.canDelete) {
                    Text(
                        "⋯",
                        fontSize = 20.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier
                            .clickable {
                                expandedId = if (expandedId == item.id) null else item.id
                                confirmId = null
                            }
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
            }
            if (expandedId == item.id) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (item.canHide) {
                        TextButton(enabled = !busy, onClick = { expandedId = null; onHide(item) }) { Text("Скрыть") }
                    }
                    if (item.canDelete) {
                        val sure = confirmId == item.id
                        TextButton(enabled = !busy, onClick = {
                            if (sure) {
                                confirmId = null
                                expandedId = null
                                onDelete(item)
                            } else {
                                confirmId = item.id
                            }
                        }) {
                            Text(if (sure) "Точно удалить?" else "Удалить", color = if (sure) cs.error else Color.Unspecified)
                        }
                    }
                }
            }
        }

        if (hiddenItems.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showHidden = !showHidden }
                    .padding(top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Скрытые (${hiddenItems.size})",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(if (showHidden) "▾" else "▸", color = cs.onSurfaceVariant)
            }
            if (showHidden) {
                hiddenItems.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                fontSize = 14.sp,
                                color = cs.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(item.note, fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        TextButton(enabled = !busy, onClick = { onUnhide(item) }) { Text("Вернуть") }
                        if (item.canDelete) {
                            val sure = confirmId == item.id
                            TextButton(enabled = !busy, onClick = {
                                if (sure) {
                                    confirmId = null
                                    onDelete(item)
                                } else {
                                    confirmId = item.id
                                }
                            }) {
                                Text(if (sure) "Точно?" else "Удалить", color = if (sure) cs.error else Color.Unspecified)
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = onRefresh) { Text("С ПК") }
            TextButton(enabled = !busy, onClick = onImport) { Text("Импорт") }
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
    }
}
