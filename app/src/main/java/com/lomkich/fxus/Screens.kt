package com.lomkich.fxus

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("←", color = ColText, fontSize = 22.sp)
        }
        Text(title, color = ColText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun ComingSoon(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Скоро будет", color = ColMuted, fontSize = 16.sp)
        }
    }
}

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(vm.host) }
    var nick by rememberSaveable { mutableStateOf(vm.nick) }
    var system by rememberSaveable { mutableStateOf(vm.systemPrompt) }
    var saved by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // Разрешение com.termux.permission.RUN_COMMAND: системный диалог, потом сразу запускаем
    val askTermux = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startOllama()
        } else {
            vm.reportLaunch("Разрешение не выдано. Настройки Android → Приложения → Fxus → Разрешения")
        }
    }

    val status: String? = when {
        vm.serverError != null -> vm.serverError
        vm.models.isNotEmpty() -> "Подключено. Моделей: ${vm.models.size}"
        else -> null
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Настройки", onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Field(
                label = "Адрес сервера Ollama",
                value = host,
                onChange = { host = it; saved = false },
                hint = status,
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ColSurface)
                    .clickable(enabled = !vm.launching) {
                        if (Termux.hasPermission(ctx)) vm.startOllama() else askTermux.launch(Termux.PERMISSION)
                    }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.launching) "Запускаю…" else "Запустить Ollama в Termux",
                    color = if (vm.launching) ColMuted else ColText,
                    fontSize = 15.sp,
                )
            }
            vm.launchNote?.let {
                Text(it, color = ColMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            Spacer(Modifier.height(20.dp))
            Field(
                label = "Ник",
                value = nick,
                onChange = { nick = it; saved = false },
                hint = "Модель будет обращаться к тебе по нику. Без ника в меню силуэт.",
            )
            Spacer(Modifier.height(20.dp))
            Field(
                label = "Системный промпт",
                value = system,
                onChange = {
                    system = it
                    saved = false
                },
                hint = "Для всех чатов. Команда /set system меняет это же поле.",
                singleLine = false,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ColAccent)
                    .clickable {
                        vm.saveSettings(nick, host, system)
                        saved = true
                    }
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Сохранить", color = ColBg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (saved) {
                Text("Сохранено", color = ColMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
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
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!hint.isNullOrBlank()) {
            Text(hint, color = ColMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}
