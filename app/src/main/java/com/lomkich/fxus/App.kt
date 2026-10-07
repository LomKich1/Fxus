package com.lomkich.fxus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.roundToInt

enum class Screen { CHAT, CHATS, ARTIFACTS, SETTINGS }

/**
 * Два слоя: меню лежит сзади, экран (чат/настройки/заглушки) сверху.
 * Открытие меню = слой с экраном плавно уезжает вправо.
 */
@Composable
fun FxusApp(vm: ChatViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = tween(300),
        label = "drawer",
    )

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

        Box(Modifier.width(drawerWidth).fillMaxHeight()) {
            DrawerContent(
                nick = vm.nick,
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
                .offset { IntOffset((progress * drawerPx).roundToInt(), 0) }
                .clip(RoundedCornerShape((24f * progress).dp))
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
                    Screen.CHATS -> ComingSoon("Чаты") { screen = Screen.CHAT }
                    Screen.ARTIFACTS -> ComingSoon("Артефакты") { screen = Screen.CHAT }
                    Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT }
                }
            }
            // пока меню открыто, тап по сдвинутому экрану закрывает меню
            if (progress > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f * progress))
                        .pointerInput(Unit) { detectTapGestures { drawerOpen = false } }
                )
            }
        }
    }
}
