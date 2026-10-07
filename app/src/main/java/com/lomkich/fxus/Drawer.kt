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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DrawerContent(
    nick: String,
    onChats: () -> Unit,
    onArtifacts: () -> Unit,
    onNewChat: () -> Unit,
    onProfile: () -> Unit,
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
        DrawerItem("Артефакты", onArtifacts)
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(ColSurface))
        Text("Недавние", color = ColMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        // сюда в 2c приедет список последних чатов
        Text("Пока пусто", color = ColMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(nick, 52.dp, Modifier.clickable(onClick = onProfile))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(ColAccent)
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+  Новый чат", color = ColBg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
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
    val bg = Color(0xFF343A47)
    val fg = Color(0xFF9AA0B4)
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
