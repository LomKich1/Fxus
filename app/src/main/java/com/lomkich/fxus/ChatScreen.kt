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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Список сообщений занимает весь экран и уходит под шапку, поле ввода и системные панели.
 * Шапка и ввод плавают поверх (пузыри), а их высоту список получает как contentPadding,
 * чтобы первое и последнее сообщения не оставались под ними.
 */
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, imageContent: @Composable (Long) -> Unit) {
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    val density = LocalDensity.current
    // Затемнение под системными панелями и вокруг пузырей: плотное у края экрана, к контенту сходит в ноль.
    val bgColor = ColBg
    val scrimTop = remember(bgColor) { Brush.verticalGradient(listOf(bgColor.copy(alpha = 0.95f), Color.Transparent)) }
    val scrimBottom = remember(bgColor) { Brush.verticalGradient(listOf(Color.Transparent, bgColor.copy(alpha = 0.95f))) }
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var modelOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = modelOpen) { modelOpen = false }

    // Запуск/остановка сервера идут через Termux и требуют его разрешения RUN_COMMAND.
    // Если оно ещё не выдано, показываем системный диалог и после согласия выполняем отложенное действие.
    val ctx = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askTermux = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            vm.reportLaunch("Разрешение не выдано. Настройки Android → Приложения → Fxus → Разрешения")
        }
    }
    fun withTermux(action: () -> Unit) {
        if (Termux.hasPermission(ctx)) {
            action()
        } else {
            pendingAction = action
            askTermux.launch(Termux.PERMISSION)
        }
    }

    // follow = «едем за низом». Включён, пока чат стоит полностью внизу, выключается,
    // как только палец начал двигать список (и включается обратно, если отпустили внизу).
    // Программный скролл (dispatchRawDelta) сюда не попадает: NestedScroll видит только жесты.
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

    suspend fun jumpToEnd() {
        if (vm.messages.isEmpty()) return
        follow = true
        listState.scrollToItem(vm.messages.lastIndex)
        listState.scrollBy(100_000f)
    }

    // сколько пикселей осталось до низа последнего сообщения (с учётом нижнего отступа)
    fun distanceToEnd(): Float {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index < info.totalItemsCount - 1) return info.viewportSize.height.toFloat()
        val end = info.viewportEndOffset - info.afterContentPadding
        return (last.offset + last.size - end).toFloat().coerceAtLeast(0f)
    }

    // открыли чат из истории (и первый показ экрана): всегда в конец
    LaunchedEffect(vm.jumpSignal) { jumpToEnd() }
    // новое сообщение: своё всегда вниз, остальные (ответ, заметки) только если едем за низом
    LaunchedEffect(vm.messages.size) {
        val last = vm.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role == Role.USER || follow) jumpToEnd()
    }

    // Плавный автоскролл: каждый кадр проезжаем долю оставшегося пути, а не прыгаем на каждый токен.
    // Крутится только пока идёт стрим (+ до 45 кадров доехать после конца).
    val minStepPx = with(density) { 1.5.dp.toPx() }
    LaunchedEffect(Unit) {
        var wasBusy = false
        snapshotFlow { vm.busy }.collectLatest { busy ->
            if (!busy && !wasBusy) return@collectLatest
            wasBusy = true
            var spare = 45
            while (busy || spare-- > 0) {
                withFrameNanos { }
                if (!follow) continue
                if (!listState.canScrollForward) {
                    if (!busy) break
                    continue
                }
                listState.dispatchRawDelta((distanceToEnd() * 0.2f).coerceAtLeast(minStepPx))
            }
        }
    }

    // imePadding: при клавиатуре весь экран чата сжимается над ней
    CompositionLocalProvider(LocalHazeState provides hazeState) {
    Box(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            // hazeSource: всё, что рисуется в списке (вместе с фоном), пузыри шапки и ввода размывают под собой.
            // Пузыри лежат рядом со списком, а не внутри него, иначе Haze не работает.
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll).hazeSource(hazeState).background(ColBg),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = with(density) { topPx.toDp() } + 8.dp,
                bottom = with(density) { bottomPx.toDp() } + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.messages, key = { it.id }) { MessageItem(it, imageContent) }
        }

        if (vm.messages.isEmpty()) {
            EmptyChat(Modifier.align(Alignment.Center))
        }

        // верх: затемнение + статус-бар + пузыри шапки. onSizeChanged стоит раньше паддингов,
        // поэтому высота включает и статус-бар.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topPx = it.height }
                .background(scrimTop)
                .statusBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Header(
                vm = vm,
                open = modelOpen,
                onMenu = {
                    modelOpen = false
                    onMenu()
                },
                onToggle = {
                    if (!modelOpen) vm.refreshModels()
                    modelOpen = !modelOpen
                },
            )
        }

        // низ: затемнение + пузырь ввода + панель навигации (при клавиатуре её отступ уже съеден)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomPx = it.height }
                .background(scrimBottom)
                .padding(top = 12.dp)
                .navigationBarsPadding()
        ) {
            InputBar(busy = vm.busy, onSend = vm::send, onStop = vm::stop)
        }

        // Список моделей. Невидимый слой ловит тап мимо и закрывает список.
        if (modelOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { modelOpen = false } })
        }
        // Панель плавно «разворачивается» вниз из-под пилюли. Слева 64dp = отступ + кнопка меню + зазор,
        // сверху чуть выше нижнего края пилюли (topPx включает нижний отступ шапки 12dp и 4dp строки).
        AnimatedVisibility(
            visible = modelOpen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = 64.dp,
                    end = 12.dp,
                    top = maxOf(0.dp, with(density) { topPx.toDp() } - 10.dp),
                ),
            enter = fadeIn(tween(160)) + expandVertically(tween(260), expandFrom = Alignment.Top),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
        ) {
            ModelList(
                vm = vm,
                onServer = { withTermux { if (vm.serverError != null) vm.startOllama() else vm.stopOllama() } },
                onPicked = { modelOpen = false },
            )
        }
    }
    }
}

// ---------- пустой чат ----------

private val GREETINGS = listOf(
    "О чём подумаем?",
    "С чего начнём?",
    "Что обсудим сегодня?",
    "Чем займёмся?",
    "Что у тебя на уме?",
    "Над чем поработаем?",
    "Какой вопрос сегодня?",
)

/** Приветствие вместо подсказки. Фраза выбирается заново каждый раз, когда чат становится пустым. */
@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    val greeting = remember { GREETINGS.random() }
    Row(
        modifier.padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sparkle()
        Spacer(Modifier.width(14.dp))
        Text(
            greeting,
            color = ColText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 32.sp,
        )
    }
}

/** Восьмилучевая звёздочка, приглушённая. */
@Composable
private fun Sparkle() {
    val ray = ColMuted
    Canvas(Modifier.size(28.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val sw = 2.5.dp.toPx()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(
                ray,
                Offset(c.x + dx * size.width * 0.18f, c.y + dy * size.height * 0.18f),
                Offset(c.x + dx * size.width * 0.5f, c.y + dy * size.height * 0.5f),
                sw,
                StrokeCap.Round,
            )
        }
    }
}

// ---------- шапка: кнопка меню и пилюля выбора модели ----------

@Composable
private fun Header(vm: ChatViewModel, open: Boolean, onMenu: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(onMenu)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .glass(RoundedCornerShape(22.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (vm.model.isBlank()) "Выбрать модель" else vm.model,
                color = ColText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Chevron(open)
        }
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).glass(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bar = ColText
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                drawLine(
                    color = bar,
                    start = Offset(0f, size.height * y),
                    end = Offset(size.width, size.height * y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Панель со списком моделей: стекло, строки со скруглённой подсветкой выбранной.
 * Внизу управление сервером: нет связи, значит «Запустить», есть, значит «Остановить».
 * Для удалённого сервера (адрес не 127.0.0.1) кнопки нет, мы им не управляем.
 */
@Composable
private fun ModelList(vm: ChatViewModel, onServer: () -> Unit, onPicked: () -> Unit) {
    val offline = vm.serverError != null
    val working = vm.launching || vm.stopping
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), strong = true)
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (vm.models.isEmpty()) {
            Text(
                vm.serverError ?: "Моделей нет. В Termux: ollama pull <имя>",
                color = ColMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        } else {
            vm.models.forEach { name ->
                ModelRow(name, selected = name == vm.model) {
                    vm.selectModel(name)
                    onPicked()
                }
            }
        }
        if (vm.isLocalHost) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ColUser.copy(alpha = 0.7f))
                    .clickable(enabled = !working, onClick = onServer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        vm.launching -> "Запускаю…"
                        vm.stopping -> "Останавливаю…"
                        offline -> "Запустить Ollama"
                        else -> "Остановить Ollama"
                    },
                    color = if (working) ColMuted else ColText,
                    fontSize = 15.sp,
                )
            }
            vm.launchNote?.let {
                Text(it, color = ColMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ColUser.copy(alpha = 0.7f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = if (selected) ColText else ColThink,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            val tick = ColText
            Canvas(Modifier.size(16.dp)) {
                val sw = 2.dp.toPx()
                drawLine(tick, Offset(size.width * 0.1f, size.height * 0.55f), Offset(size.width * 0.4f, size.height * 0.85f), sw, StrokeCap.Round)
                drawLine(tick, Offset(size.width * 0.4f, size.height * 0.85f), Offset(size.width * 0.9f, size.height * 0.2f), sw, StrokeCap.Round)
            }
        }
    }
}

// ---------- сообщения ----------

@Composable
private fun MessageItem(m: Msg, imageContent: @Composable (Long) -> Unit) {
    when (m.role) {
        Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(ColUser)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                SelectionContainer { Markdown(m.content, codeBg = ColCodeBgUser) }
            }
        }
        Role.ASSISTANT -> if (m.turnId != 0L) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) { imageContent(m.turnId) }
        } else {
            AssistantMessage(m)
        }
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

/** Текст с точками: вырастают по одной до трёх и сбрасываются. Под точки зарезервирована ширина, чтобы соседи не прыгали. */
@Composable
private fun Dots(label: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            n = (n + 1) % 4
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Text(label, color = color, fontSize = fontSize)
        Box(Modifier.width((fontSize.value * 1.2f).dp)) {
            Text(".".repeat(n), color = color, fontSize = fontSize)
        }
    }
}

@Composable
private fun Chevron(open: Boolean) {
    val rot by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chevron")
    val arrow = ColMuted
    Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = rot }) {
        val sw = 1.5.dp.toPx()
        drawLine(arrow, Offset(size.width * 0.15f, size.height * 0.35f), Offset(size.width / 2f, size.height * 0.7f), sw, StrokeCap.Round)
        drawLine(arrow, Offset(size.width * 0.85f, size.height * 0.35f), Offset(size.width / 2f, size.height * 0.7f), sw, StrokeCap.Round)
    }
}

/** «Думает… 35с ⌄» пока идут рассуждения, потом «Думал 35с ⌄». */
@Composable
private fun ThinkingHeader(thinkingNow: Boolean, seconds: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thinkingNow) {
            Dots("Думает", ColThink, 13.sp)
        } else {
            Text("Думал", color = ColThink, fontSize = 13.sp)
        }
        if (seconds > 0) {
            Spacer(Modifier.width(8.dp))
            Text("${seconds}с", color = ColMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(6.dp))
        Chevron(open)
    }
}

/**
 * Бегущие строки: хвост потока рассуждений, максимум 3 строки.
 * Текст прижат к низу, новые строки выталкивают старые вверх, верхний край плавно гаснет.
 */
@Composable
private fun ThinkingTicker(text: String) {
    var lines by remember { mutableIntStateOf(0) }
    val fadePx = with(LocalDensity.current) { 18.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
            .animateContentSize()
            .heightIn(max = 60.dp)
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (lines > 3) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black),
                            startY = 0f,
                            endY = fadePx,
                        ),
                        size = Size(size.width, fadePx),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
    ) {
        Text(
            text,
            color = ColThink,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            onTextLayout = { lines = it.lineCount },
            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
        )
    }
}

/** Развёрнутые рассуждения: рамка без заливки, при стриме прокручивается за текстом. */
@Composable
private fun ThinkingBox(text: String, follow: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .border(1.dp, ColMuted.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .heightIn(max = 280.dp)
            .verticalScroll(scroll)
            .padding(12.dp)
    ) {
        SelectionContainer {
            Text(text, color = ColThink, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun AssistantMessage(m: Msg) {
    var open by remember { mutableStateOf(false) }
    val thinkingNow = m.streaming && m.content.isEmpty() && m.thinking.isNotEmpty()

    // секундомер тикает только пока идут рассуждения
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(thinkingNow) {
        while (thinkingNow) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val seconds = if (thinkingNow) {
        ((now - m.thinkStart) / 1000).toInt().coerceAtLeast(0)
    } else {
        (m.thinkMs / 1000).toInt()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (m.thinking.isNotEmpty()) {
            ThinkingHeader(thinkingNow, seconds, open) { open = !open }
            when {
                open -> ThinkingBox(m.thinking, follow = thinkingNow)
                thinkingNow -> ThinkingTicker(m.thinking)
                else -> Spacer(Modifier.height(6.dp))
            }
        }
        if (m.content.isNotEmpty()) {
            SelectionContainer { Markdown(m.content, Modifier.fillMaxWidth()) }
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
            .glass(RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Как я могу вам помочь сегодня?", color = ColMuted, fontSize = 16.sp)
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
            ActionIcon(shown, if (isMic) ColText else ColOnAccent)
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
