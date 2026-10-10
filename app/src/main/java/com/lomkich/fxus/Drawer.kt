package com.lomkich.fxus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DrawerContent(
    nick: String,
    chats: List<ChatMeta>,
    currentId: String?,
    onOpenChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onChats: () -> Unit,
    onArtifacts: () -> Unit,
    onImages: () -> Unit,
    onNewChat: () -> Unit,
    onProfile: () -> Unit,
    onLanguage: () -> Unit,
    onHelp: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Fxus",
            color = ColText,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 20.dp),
        )
        DrawerItem("Чаты", onChats)
        DrawerItem("Изображения", onImages)
        DrawerItem("Артефакты", onArtifacts)
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(ColSurface))
        Text("Недавние", color = ColMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        if (chats.isEmpty()) {
            Text("Пока пусто", color = ColMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
        } else {
            // все чаты и поиск по ним на экране «Чаты», здесь только свежие
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(chats.take(15), key = { it.id }) { meta ->
                    ChatRow(
                        meta = meta,
                        selected = meta.id == currentId,
                        onOpen = { onOpenChat(meta.id) },
                        onRename = { onRenameChat(meta.id, it) },
                        onDelete = { onDeleteChat(meta.id) },
                    )
                }
            }
        }

        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // профиль: тап открывает меню со списком (настройки, язык, помощь)
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .clickable { menu = true }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(nick, 46.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nick.ifBlank { "Профиль" },
                        color = ColText,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    ChevronIcon(up = true, size = 12.dp)
                }
                ProfileMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    onSettings = onProfile,
                    onLanguage = onLanguage,
                    onHelp = onHelp,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ColAccent)
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+  Новый чат", color = ColOnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Ставит окно меню над якорем (над строкой профиля), прижав левым краем к якорю. */
private class AboveAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = maxOf(0, anchorBounds.top - popupContentSize.height - gapPx)
        return IntOffset(x, y)
    }
}

/**
 * Меню профиля: карточка разворачивается из угла у аватара и сворачивается обратно.
 * Анимация только на слое отрисовки (масштаб + прозрачность), размер окна не меняется,
 * поэтому позиция не дёргается. Пока играет сворачивание, окно остаётся на экране.
 */
@Composable
private fun ProfileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onLanguage: () -> Unit,
    onHelp: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positioner = remember(gap) { AboveAnchor(gap) }
    val origin = TransformOrigin(0.12f, 1f)

    if (state.currentState || state.targetState) {
        Popup(
            popupPositionProvider = positioner,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(tween(140)) +
                    scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.25f, transformOrigin = origin),
                exit = fadeOut(tween(130)) +
                    scaleOut(tween(200, easing = FastOutSlowInEasing), targetScale = 0.25f, transformOrigin = origin),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ColSurface,
                    border = BorderStroke(1.dp, ColUser),
                ) {
                    Column(Modifier.width(240.dp).padding(vertical = 6.dp)) {
                        ProfileMenuItem("Настройки", { GearIcon() }) {
                            onDismiss()
                            onSettings()
                        }
                        ProfileMenuItem("Язык", { GlobeIcon() }) {
                            onDismiss()
                            onLanguage()
                        }
                        ProfileMenuItem("Помощь", { HelpIcon() }) {
                            onDismiss()
                            onHelp()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuItem(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(label, color = ColText, fontSize = 16.sp)
    }
}

@Composable
private fun DrawerItem(title: String, onClick: () -> Unit) {
    Text(
        title,
        color = ColText,
        fontSize = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

/** С ником: первая буква в кружке. Без ника: силуэт «человек по плечи». */
@Composable
fun Avatar(nick: String, diameter: Dp, modifier: Modifier = Modifier) {
    val bg = ColUser
    val fg = ColMuted
    if (nick.isNotBlank()) {
        Box(
            Modifier.size(diameter).clip(CircleShape).then(modifier).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                nick.trim().first().uppercase(),
                color = ColText,
                fontSize = (diameter.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        Canvas(Modifier.size(diameter).clip(CircleShape).then(modifier)) {
            val w = size.width
            val h = size.height
            drawRect(bg)
            drawCircle(fg, radius = w * 0.15f, center = Offset(w / 2f, h * 0.38f))
            // плечи: скруглённый прямоугольник, низ срезается кругом
            drawRoundRect(
                fg,
                topLeft = Offset(w * 0.24f, h * 0.58f),
                size = Size(w * 0.52f, h * 0.50f),
                cornerRadius = CornerRadius(w * 0.26f),
            )
        }
    }
}
