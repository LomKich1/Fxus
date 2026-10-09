package com.lomkich.fxus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Screen { CHAT, CHATS, ARTIFACTS, SETTINGS, LANGUAGE, HELP }

/**
 * Два слоя: меню лежит сзади, экран (чат/настройки/заглушки) сверху.
 * Открытие меню = слой с экраном плавно уезжает вправо.
 * Закрыть меню: свайп влево по меню или по сдвинутому экрану.
 * Открыть меню: свайп вправо по чату (только быстрый: если палец сначала зажали дольше
 * long-press, например чтобы выделить текст, свайпа не будет; жесты, которые забрали
 * вложенные скроллы, тоже не трогаем). У самого левого края срабатывает системное «назад».
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FxusApp(vm: ChatViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // 0 = меню закрыто, 1 = открыто. Animatable, потому что палец тоже двигает значение.
    val progress = remember { Animatable(if (drawerOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()

    // Скорость, с которой отпустили палец (в долях ширины меню в секунду): пружина её подхватывает,
    // поэтому меню доезжает так, как его бросили, а не с места.
    val release = remember { FloatArray(1) }
    val settleSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f) }
    // быстрее этого (dp в секунду) жест считается броском, и решает его направление
    val flingPx = with(LocalDensity.current) { 500.dp.toPx() }

    LaunchedEffect(drawerOpen) {
        val v = release[0]
        release[0] = 0f
        progress.animateTo(if (drawerOpen) 1f else 0f, settleSpec, v)
    }

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // любое открытие меню прячет клавиатуру и снимает фокус с поля (иначе курсор остаётся под меню)
    fun openDrawer() {
        focus.clearFocus()
        keyboard?.hide()
        drawerOpen = true
    }

    // из настроек, языка и помощи выходим в меню (оттуда пришли), из остальных экранов в чат
    fun leaveSettings() {
        screen = Screen.CHAT
        openDrawer()
    }

    BackHandler(enabled = drawerOpen || screen != Screen.CHAT) {
        when {
            drawerOpen -> drawerOpen = false
            screen == Screen.SETTINGS || screen == Screen.LANGUAGE || screen == Screen.HELP -> leaveSettings()
            else -> screen = Screen.CHAT
        }
    }

    // Отпустили палец. Бросок (быстрее порога) решает направление, медленный жест решает положение
    // с небольшим упреждением по скорости: чуть не дотянул, но двигал вперёд, значит откроется.
    fun settleMenu(pos: Float, velocity: Float, drawerPx: Float) {
        val target = when {
            velocity > flingPx -> true
            velocity < -flingPx -> false
            else -> pos + velocity / drawerPx * 0.15f > 0.5f
        }
        val v = velocity / drawerPx
        if (target != drawerOpen) {
            release[0] = v
            drawerOpen = target
        } else {
            scope.launch { progress.animateTo(if (target) 1f else 0f, settleSpec, v) }
        }
    }

    fun go(target: Screen) {
        screen = target
        drawerOpen = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ColDrawer)) {
        val drawerWidth = maxWidth * 0.82f
        val drawerPx = with(LocalDensity.current) { drawerWidth.toPx() }

        // Палец ведёт меню за собой. Скорость считаем по накопленному сдвигу, а не по координатам:
        // сам слой с экраном едет вместе с пальцем, и локальные координаты почти не меняются.
        val dragMenu = Modifier.pointerInput(drawerPx) {
            var pos = 0f
            var acc = 0f
            val tracker = VelocityTracker()
            fun settle(cancelled: Boolean) {
                val v = if (cancelled) 0f else tracker.calculateVelocity().x
                settleMenu(pos, v, drawerPx)
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    pos = progress.value
                    acc = 0f
                    tracker.resetTracking()
                },
                onDragEnd = { settle(false) },
                onDragCancel = { settle(true) },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    acc += dx
                    tracker.addPosition(change.uptimeMillis, Offset(acc, 0f))
                    pos = (pos + dx / drawerPx).coerceIn(0f, 1f)
                    scope.launch { progress.snapTo(pos) }
                },
            )
        }

        // Открытие свайпом вправо по чату. Стоит на слое чата и ловит жест после детей:
        // если вложенный скролл (список, код) забрал жест, мы его не видим как свободный.
        val openSwipe = Modifier.pointerInput(drawerPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (screen != Screen.CHAT || drawerOpen) return@awaitEachGesture
                var overSlop = 0f
                // не успели сдвинуться за время long-press = зажатие, не свайп (выделение текста)
                val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if (over > 0f) { // только вправо
                            overSlop = over
                            change.consume()
                        }
                    }
                } ?: return@awaitEachGesture
                focus.clearFocus()
                keyboard?.hide()
                var pos = (overSlop / drawerPx).coerceIn(0f, 1f)
                var acc = overSlop
                val tracker = VelocityTracker()
                tracker.addPosition(first.uptimeMillis, Offset(acc, 0f))
                scope.launch { progress.snapTo(pos) }
                horizontalDrag(first.id) { change ->
                    val dx = change.positionChange().x
                    change.consume()
                    acc += dx
                    tracker.addPosition(change.uptimeMillis, Offset(acc, 0f))
                    pos = (pos + dx / drawerPx).coerceIn(0f, 1f)
                    scope.launch { progress.snapTo(pos) }
                }
                settleMenu(pos, tracker.calculateVelocity().x, drawerPx)
            }
        }

        Box(Modifier.width(drawerWidth).fillMaxHeight().then(dragMenu)) {
            DrawerContent(
                nick = vm.nick,
                chats = vm.chats,
                currentId = vm.currentId,
                onOpenChat = { id ->
                    vm.openChat(id)
                    go(Screen.CHAT)
                },
                onRenameChat = vm::renameChat,
                onDeleteChat = vm::deleteChat,
                onChats = { go(Screen.CHATS) },
                onArtifacts = { go(Screen.ARTIFACTS) },
                onNewChat = {
                    vm.newChat()
                    go(Screen.CHAT)
                },
                onProfile = { go(Screen.SETTINGS) },
                onLanguage = { go(Screen.LANGUAGE) },
                onHelp = { go(Screen.HELP) },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((progress.value * drawerPx).roundToInt(), 0) }
                .clip(RoundedCornerShape((24f * progress.value).dp))
                .background(ColBg)
                .then(openSwipe)
        ) {
            if (screen == Screen.CHAT) {
                // чат сам рисует контент под системными панелями и сам обрабатывает отступы
                ChatScreen(vm, onMenu = ::openDrawer)
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    when (screen) {
                        Screen.CHATS -> ChatsScreen(
                            vm,
                            onOpen = { id ->
                                vm.openChat(id)
                                screen = Screen.CHAT
                            },
                            onBack = { screen = Screen.CHAT },
                        )
                        Screen.ARTIFACTS -> ComingSoon("Артефакты") { screen = Screen.CHAT }
                        Screen.SETTINGS -> SettingsScreen(vm, onBack = ::leaveSettings)
                        Screen.LANGUAGE -> ComingSoon("Язык", onBack = ::leaveSettings)
                        Screen.HELP -> HelpScreen(onBack = ::leaveSettings)
                        Screen.CHAT -> Unit
                    }
                }
            }
            // Пока меню открыто: тап по сдвинутому экрану закрывает, свайп влево тоже.
            // dragMenu стоит последним в цепочке (внутренний), чтобы свайп забирал жест раньше тапа.
            if (progress.value > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f * progress.value))
                        .pointerInput(Unit) { detectTapGestures { drawerOpen = false } }
                        .then(dragMenu)
                )
            }
        }
    }
}
