package com.lomkich.fxus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Экран «Диалог ИИ»: два собеседника с разными промптами переписываются друг с другом.
 * Настройки собеседников живут в сворачиваемой карточке сверху, переписка под ней, ввод внизу.
 * Сервер и ключ берём из обычного чата, поэтому здесь адрес не вводится.
 */
@Composable
fun DialogScreen(chat: ChatViewModel, dlg: DialogViewModel, onMenu: () -> Unit) {
    val listState = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(true) }
    val conn = Conn(chat.host, chat.backend, chat.apiKey, chat.model)

    // follow = «едем за низом», как в обычном чате: выключается, когда палец двинул список,
    // и включается обратно, если отпустили внизу.
    var follow by remember { mutableStateOf(true) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                follow = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                follow = !listState.canScrollForward
                return Offset.Zero
            }
        }
    }

    suspend fun toEnd() {
        if (dlg.messages.isEmpty()) return   // пока пусто, не уезжаем от карточки настроек
        listState.scrollToItem(dlg.messages.size)   // индекс 0 занят карточкой, сообщения идут с 1
        listState.scrollBy(100_000f)
    }

    // новая реплика или новый токен в последней: если едем за низом, догоняем
    LaunchedEffect(Unit) {
        snapshotFlow { dlg.messages.size to (dlg.messages.lastOrNull()?.text?.length ?: 0) }
            .collect { if (follow) toEnd() }
    }
    // разговор пошёл: настройки сворачиваем, они больше не нужны на глазах
    LaunchedEffect(dlg.running) { if (dlg.running) expanded = false }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        DialogTopBar(
            running = dlg.running,
            canClear = dlg.messages.isNotEmpty(),
            onMenu = onMenu,
            onClear = {
                dlg.clear()
                expanded = true
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(userScroll),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "setup") {
                Participants(chat, dlg, expanded, onToggle = { expanded = !expanded })
            }
            if (dlg.messages.isEmpty()) {
                item(key = "hint") {
                    Text(
                        "Напиши тему внизу: первым ответит собеседник 1, дальше они сами. " +
                            "Пока идёт разговор, можно вклиниваться: твою реплику увидят оба.",
                        color = ColMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }
            items(dlg.messages, key = { it.id }) { Bubble(it, dlg.nameOf(Speaker.A), dlg.nameOf(Speaker.B)) }
        }
        DialogInput(
            hasMessages = dlg.messages.isNotEmpty(),
            running = dlg.running,
            onSend = {
                follow = true
                dlg.say(it, conn)
            },
            onStop = dlg::stop,
            onResume = {
                follow = true
                dlg.resume(conn)
            },
        )
    }
}

// ---------- шапка ----------

@Composable
private fun DialogTopBar(running: Boolean, canClear: Boolean, onMenu: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // та же кнопка меню, что и в чате (там она private, поэтому рисуем свою)
        Box(
            Modifier.size(44.dp).glass(CircleShape).clickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            val bar = ColText
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.dp.toPx()
                listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                    drawLine(bar, Offset(0f, size.height * y), Offset(size.width, size.height * y), stroke, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).height(44.dp).glass(RoundedCornerShape(22.dp)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Диалог ИИ",
                color = ColText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (running) Text("идёт", color = ColMuted, fontSize = 13.sp)
        }
        if (canClear) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.height(44.dp).glass(RoundedCornerShape(22.dp)).clickable(onClick = onClear).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Сброс", color = ColText, fontSize = 15.sp)
            }
        }
    }
}

// ---------- настройки собеседников ----------

@Composable
private fun Participants(chat: ChatViewModel, dlg: DialogViewModel, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ColSurface)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${dlg.nameOf(Speaker.A)}  ⇄  ${dlg.nameOf(Speaker.B)}",
                color = ColText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChevronIcon(up = expanded)
        }
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            PersonaEditor("Собеседник 1 (слева)", dlg.a, dlg::editA, chat)
            Spacer(Modifier.height(18.dp))
            PersonaEditor("Собеседник 2 (справа)", dlg.b, dlg::editB, chat)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PersonaEditor(title: String, p: Persona, onChange: (Persona) -> Unit, chat: ChatViewModel) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            color = ColText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        DialogField("Имя", p.name, { onChange(p.copy(name = it)) })
        Spacer(Modifier.height(10.dp))
        DialogField(
            "Системный промпт",
            p.prompt,
            { onChange(p.copy(prompt = it)) },
            singleLine = false,
            hint = "Кто он, как говорит, чего хочет. Чем сильнее роли отличаются, тем интереснее спор.",
        )
        Spacer(Modifier.height(10.dp))
        Text("Модель", color = ColMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ColSurface)
                .clickable {
                    if (!picking) chat.refreshModels()
                    picking = !picking
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                p.model.ifBlank { "Как в чате (${chat.model.ifBlank { "не выбрана" }})" },
                color = ColText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChevronIcon(up = picking)
        }
        if (picking) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ColSurface)
            ) {
                PickRow("Как в чате", p.model.isBlank()) {
                    onChange(p.copy(model = ""))
                    picking = false
                }
                chat.models.forEach { name ->
                    PickRow(name, p.model == name) {
                        onChange(p.copy(model = name))
                        picking = false
                    }
                }
                if (chat.models.isEmpty()) {
                    Text(
                        "Список моделей пуст: проверь сервер в настройках",
                        color = ColMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        name,
        color = if (selected) ColAccent else ColText,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Такое же поле, как в настройках приложения. */
@Composable
private fun DialogField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    hint: String? = null,
) {
    Column {
        Text(label, color = ColMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ColSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                maxLines = if (singleLine) 1 else 10,
                textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                cursorBrush = SolidColor(ColText),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!hint.isNullOrBlank()) {
            Text(hint, color = ColMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

// ---------- переписка ----------

@Composable
private fun Bubble(m: DialogMsg, aName: String, bName: String) {
    when (m.who) {
        Speaker.A, Speaker.B -> {
            val left = m.who == Speaker.A
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (left) Alignment.Start else Alignment.End) {
                Text(
                    if (left) aName else bName,
                    color = ColMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(if (left) RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp) else RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                        .background(if (left) ColSurface else ColUser)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (m.text.isBlank()) {
                        Text("печатает…", color = ColMuted, fontSize = 14.sp)
                    } else {
                        SelectionContainer { Markdown(m.text, codeBg = if (left) ColCodeBg else ColCodeBgUser) }
                    }
                }
            }
        }
        Speaker.HOST -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ведущий", color = ColMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ColSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                SelectionContainer { Markdown(m.text) }
            }
        }
        Speaker.SYSTEM -> Text(
            m.text,
            color = ColMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

// ---------- ввод ----------

private enum class DAction { SEND, STOP, PLAY, NONE }

@Composable
private fun DialogInput(
    hasMessages: Boolean,
    running: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Есть текст: отправить. Иначе: идёт разговор, значит стоп; стоит, но есть что продолжать, значит play.
    val action = when {
        text.isNotBlank() -> DAction.SEND
        running -> DAction.STOP
        hasMessages -> DAction.PLAY
        else -> DAction.NONE
    }
    val placeholder = when {
        !hasMessages -> "Тема или первая реплика…"
        running -> "Вклиниться в разговор…"
        else -> "Сказать от себя…"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .glass(RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(placeholder, color = ColMuted, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                cursorBrush = SolidColor(ColText),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        DialogActionButton(action) {
            when (action) {
                DAction.SEND -> {
                    onSend(text)
                    text = ""
                }
                DAction.STOP -> onStop()
                DAction.PLAY -> onResume()
                DAction.NONE -> Unit
            }
        }
    }
}

@Composable
private fun DialogActionButton(action: DAction, onClick: () -> Unit) {
    val active = action != DAction.NONE
    val bg = if (active) ColAccent else ColSurface
    val fg = if (active) ColOnAccent else ColMuted
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg).clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            when (action) {
                DAction.SEND -> {   // стрелка вверх
                    val sw = 2.2.dp.toPx()
                    drawLine(fg, Offset(w / 2, h), Offset(w / 2, 0f), sw, StrokeCap.Round)
                    drawLine(fg, Offset(w / 2, 0f), Offset(0f, h * 0.45f), sw, StrokeCap.Round)
                    drawLine(fg, Offset(w / 2, 0f), Offset(w, h * 0.45f), sw, StrokeCap.Round)
                }
                DAction.STOP -> drawRoundRect(
                    fg,
                    topLeft = Offset(w * 0.1f, h * 0.1f),
                    size = Size(w * 0.8f, h * 0.8f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                DAction.PLAY, DAction.NONE -> {   // треугольник «играть»
                    val tri = Path().apply {
                        moveTo(w * 0.2f, 0f)
                        lineTo(w, h / 2)
                        lineTo(w * 0.2f, h)
                        close()
                    }
                    drawPath(tri, fg)
                }
            }
        }
    }
}
