package com.lomkich.fxus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Отступы под системные панели и клавиатуру делает FxusApp, здесь их нет. */
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val listState = rememberLazyListState()

    // «внизу» = последний элемент списка виден; пока читаешь выше, не дёргаем скролл
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // новое сообщение: всегда вниз
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            listState.scrollToItem(vm.messages.lastIndex)
            listState.scrollBy(100_000f)
        }
    }
    // новый токен: вниз, только если пользователь и так внизу
    LaunchedEffect(vm.tick) {
        if (atBottom) listState.scrollBy(100_000f)
    }

    Column(Modifier.fillMaxSize()) {
        Header(vm, onMenu)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty()) {
                Text(
                    "Напиши сообщение или /help",
                    color = ColMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vm.messages, key = { it.id }) { MessageItem(it) }
            }
        }
        InputBar(busy = vm.busy, onSend = vm::send, onStop = vm::stop)
    }
}

// ---------- шапка ----------

@Composable
private fun Header(vm: ChatViewModel, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(onMenu)
        Spacer(Modifier.weight(1f))
        ModelPill(vm)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                drawLine(
                    color = ColText,
                    start = Offset(0f, size.height * y),
                    end = Offset(size.width, size.height * y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ModelPill(vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(ColSurface)
                .clickable {
                    open = true
                    vm.refreshModels()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (vm.model.isBlank()) "нет модели" else vm.model,
                color = ColText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("▾", color = ColMuted, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (vm.models.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            vm.serverError ?: "Моделей нет. В Termux: ollama pull <имя>",
                            color = ColMuted,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = { vm.refreshModels() },
                )
            } else {
                vm.models.forEach { name ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (name == vm.model) "✓  $name" else name,
                                color = if (name == vm.model) ColText else ColMuted,
                                fontSize = 15.sp,
                            )
                        },
                        onClick = {
                            vm.selectModel(name)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

// ---------- сообщения ----------

@Composable
private fun MessageItem(m: Msg) {
    when (m.role) {
        Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(ColUser)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                SelectionContainer { Text(m.content, color = ColText, fontSize = 16.sp) }
            }
        }
        Role.ASSISTANT -> AssistantMessage(m)
        Role.SYSTEM -> Text(
            m.content,
            color = ColMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

/** Текст с точками, которые вырастают по одной до трёх и сбрасываются. */
@Composable
private fun Dots(label: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            n = (n + 1) % 4
        }
    }
    Text(label + ".".repeat(n), color = color, fontSize = fontSize, modifier = modifier)
}

@Composable
private fun AssistantMessage(m: Msg) {
    var open by remember { mutableStateOf(false) }
    val thinkingNow = m.streaming && m.content.isEmpty() && m.thinking.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (m.thinking.isNotEmpty()) {
            val toggle = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp)
            if (thinkingNow) {
                Dots("Думает", ColMuted, 13.sp, toggle)
            } else {
                Text("Рассуждения", color = ColMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = toggle)
            }
            if (open) {
                SelectionContainer {
                    Text(
                        m.thinking,
                        color = ColMuted,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
        if (m.content.isNotEmpty()) {
            SelectionContainer { Text(m.content, color = ColText, fontSize = 16.sp, lineHeight = 24.sp) }
        } else if (m.streaming && m.thinking.isEmpty()) {
            // ещё нет ни одного токена: модель грузится или собирается с мыслями
            Dots("", ColMuted, 16.sp)
        }
    }
}

// ---------- ввод ----------

private enum class Action { MIC, SEND, STOP }

/** Поле и кнопка в одном контейнере. Кнопка: микрофон / отправить / стоп. */
@Composable
private fun InputBar(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val action = when {
        busy -> Action.STOP
        text.isBlank() -> Action.MIC
        else -> Action.SEND
    }

    // Диктовка через системный распознаватель речи: своего аудио-кода у нас нет,
    // разрешение на микрофон не нужно. Распознанный текст дописывается в поле.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) text = if (text.isBlank()) heard else "$text $heard"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(ColSurface)
            .padding(start = 18.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Сообщение или /help", color = ColMuted, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = ColText, fontSize = 16.sp),
                cursorBrush = SolidColor(ColText),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ActionButton(action) {
            when (action) {
                Action.STOP -> onStop()
                Action.SEND -> {
                    onSend(text)
                    text = ""
                }
                Action.MIC -> {
                    try {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        )
                    } catch (e: ActivityNotFoundException) {
                        // на телефоне нет распознавателя речи, молча ничего не делаем
                    }
                }
            }
        }
    }
}

/**
 * Смена иконки: кнопка сжимается в ноль, меняется содержимое, растёт обратно.
 * Нажимаемая область фиксированные 44dp, сам круг 36dp.
 */
@Composable
private fun ActionButton(action: Action, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(action) }
    LaunchedEffect(action) {
        if (shown != action) {
            scale.animateTo(0f, tween(110))
            shown = action
        }
        scale.animateTo(1f, tween(110))
    }
    val isMic = shown == Action.MIC
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
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(CircleShape)
                .background(if (isMic) ColUser else ColAccent),
            contentAlignment = Alignment.Center,
        ) {
            ActionIcon(shown, if (isMic) ColText else ColBg)
        }
    }
}

@Composable
private fun ActionIcon(action: Action, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        when (action) {
            Action.SEND -> {
                // стрелка вверх: ствол и два крыла
                drawLine(color, Offset(w / 2f, h * 0.92f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.14f, h * 0.44f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.86f, h * 0.44f), Offset(w / 2f, h * 0.08f), sw, StrokeCap.Round)
            }
            Action.STOP -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.12f),
                )
            }
            Action.MIC -> {
                // капсула, дуга-подставка снизу и ножка
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.35f, 0f),
                    size = Size(w * 0.30f, h * 0.58f),
                    cornerRadius = CornerRadius(w * 0.15f),
                )
                drawArc(
                    color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.15f, h * 0.18f),
                    size = Size(w * 0.70f, h * 0.55f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(w / 2f, h * 0.73f), Offset(w / 2f, h * 0.95f), sw, StrokeCap.Round)
            }
        }
    }
}
