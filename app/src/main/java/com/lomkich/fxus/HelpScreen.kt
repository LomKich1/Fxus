package com.lomkich.fxus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Помощь", onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HelpTitle("Что это")
            HelpText(
                "Fxus: чат с локальными моделями через Ollama. Сервер (ollama serve) обычно работает " +
                    "в Termux на этом же телефоне, но может быть и на другом устройстве. " +
                    "Адрес задаётся в настройках, по умолчанию http://127.0.0.1:11434."
            )

            HelpTitle("Как пользоваться")
            HelpText("•  Модель выбирается в шапке чата. Внизу того же списка кнопка «Запустить Ollama» или «Остановить Ollama» (только для локального адреса).")
            HelpText("•  Чаты сохраняются сами. Свежие видны в меню, все на экране «Чаты», там же поиск. Долгий тап по чату: переименовать или удалить.")
            HelpText("•  Меню открывается свайпом вправо по чату или кнопкой слева вверху. Новый чат: кнопка внизу меню.")
            HelpText("•  Ответы показываются с форматированием: списки, таблицы, блоки кода.")
            HelpText("•  Ник из настроек: модель будет обращаться к тебе по нему.")

            HelpTitle("Сервер и Termux")
            HelpText(
                "Если включить «Запускать Ollama автоматически», Fxus сам запустит ollama serve через Termux, " +
                    "когда сервер не отвечает. Это работает только для локального адреса."
            )
            HelpText(
                "Для этого в Termux в файл ~/.termux/termux.properties нужна строка allow-external-apps=true " +
                    "(после неё Termux надо перезапустить). Fxus при первом запуске попросит разрешение на команды Termux."
            )
            HelpText(
                "«Остановка при простое» выключает сервер, который запустил Fxus, если им давно не пользовались. " +
                    "0 значит не останавливать."
            )

            HelpTitle("Команды в чате")
            HelpText("Строка, которая начинается с /, обрабатывается приложением и в модель не уходит.")
            HelpCommand("/help", "список команд прямо в чате")
            HelpCommand("/clear", "начать с чистого контекста; прошлый чат остаётся в списке")
            HelpCommand("/load <модель>", "сменить модель; контекст при этом сбрасывается")
            HelpCommand("/show", "текущие настройки: модель, адрес, ник, think, system, параметры")
            HelpCommand("/set think [high|medium|low]", "включить рассуждения; уровень можно не указывать")
            HelpCommand("/set nothink", "выключить рассуждения")
            HelpCommand("/set system <текст>", "системный промпт; это то же поле, что в настройках, и оно сохраняется")
            HelpCommand("/set parameter <имя> <значение>", "параметр модели, например temperature 0.7 или num_ctx 8192")
            HelpText("think и parameter действуют, пока приложение не закрыто. Свои параметры можно проверить через /show.")

            HelpTitle("Оформление")
            HelpText("Кнопка справа вверху в настройках переключает тёмную и светлую тему.")

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HelpTitle(text: String) {
    Text(text, color = ColMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
}

@Composable
private fun HelpText(text: String) {
    Text(text, color = ColText, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun HelpCommand(cmd: String, desc: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ColSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(cmd, color = ColText, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        Text(desc, color = ColMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
