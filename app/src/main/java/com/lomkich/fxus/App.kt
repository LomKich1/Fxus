package com.lomkich.fxus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Screen { CHAT, CHATS, ARTIFACTS, SETTINGS }

/**
 * Два слоя: меню лежит сзади, экран (чат/настройки/заглушки) сверху.
 * Открытие меню = слой с экраном плавно уезжает вправо.
 * Открыть меню свайпом нельзя (конфликт с системным «назад» и скроллом),
 * а закрыть можно: свайп влево по меню или по сдвинутому экрану.
 */
@Composable
fun FxusApp(vm: ChatViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // 0 = меню закрыто, 1 = открыто. Animatable, потому что палец тоже двигает значение.
    val progress = remember { Animatable(if (drawerOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(drawerOpen) {
        progress.animateTo(if (drawerOpen) 1f else 0f, tween(300))
    }

    BackHandler(enabled = drawerOpen || screen != Screen.CHAT) {
        if (drawerOpen) drawerOpen = false else screen = Screen.CHAT
    }

    fun go(target: Screen) {
        screen = target
        drawerOpen = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ColDrawer)) {
        val drawerWidth = maxWidth * 0.82f
        val drawerPx = with(LocalDensity.current) { drawerWidth.toPx() }

        // Палец ведёт меню за собой. Отпустили: решает направление последнего движения,
        // а если палец стоял, то проходит ли меню половину пути.
        val dragMenu = Modifier.pointerInput(drawerPx) {
            var pos = 0f
            var last = 0f
            fun settle() {
                val target = when {
                    last < -1f -> false
                    last > 1f -> true
                    else -> pos > 0.5f
                }
                last = 0f
                if (target != drawerOpen) {
                    drawerOpen = target
                } else {
                    scope.launch { progress.animateTo(if (target) 1f else 0f, tween(200)) }
                }
            }
            detectHorizontalDragGestures(
                onDragStart = { pos = progress.value },
                onDragEnd = { settle() },
                onDragCancel = { settle() },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    last = dx
                    pos = (pos + dx / drawerPx).coerceIn(0f, 1f)
                    scope.launch { progress.snapTo(pos) }
                },
            )
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
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((progress.value * drawerPx).roundToInt(), 0) }
                .clip(RoundedCornerShape((24f * progress.value).dp))
                .background(ColBg)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                when (screen) {
                    Screen.CHAT -> ChatScreen(vm, onMenu = { drawerOpen = true })
                    Screen.CHATS -> ChatsScreen(
                        vm,
                        onOpen = { id ->
                            vm.openChat(id)
                            screen = Screen.CHAT
                        },
                        onBack = { screen = Screen.CHAT },
                    )
                    Screen.ARTIFACTS -> ComingSoon("Артефакты") { screen = Screen.CHAT }
                    Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT }
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
