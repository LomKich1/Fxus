package com.lomkich.fxus

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

const val DEFAULT_HOST = "http://127.0.0.1:11434"

private const val HELP = """Команды (как в ollama):
/clear — очистить контекст
/set nothink — выключить рассуждения
/set think [high|medium|low] — включить
/set system <текст> — системный промпт
/set parameter <имя> <значение>
/show — текущие настройки
/load <модель> — сменить модель (контекст сбросится)
Модель и адрес сервера — в шапке и в настройках."""

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("fxus", Context.MODE_PRIVATE)
    private val client = OllamaClient()
    private val store = ChatStore(app)
    private val saveLock = Mutex()

    val messages = mutableStateListOf<Msg>()
    var busy by mutableStateOf(false)
        private set
    var model by mutableStateOf(prefs.getString("model", "").orEmpty())
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var serverError by mutableStateOf<String?>(null)
        private set
    var nick by mutableStateOf(prefs.getString("nick", "").orEmpty())
        private set
    var host by mutableStateOf(prefs.getString("host", DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST })
        private set
    var systemPrompt by mutableStateOf(prefs.getString("system", "").orEmpty())
        private set
    /** Список сохранённых чатов, свежие сверху. */
    var chats by mutableStateOf<List<ChatMeta>>(emptyList())
        private set
    /** null = новый чат, который ещё ни разу не сохранялся. */
    var currentId by mutableStateOf<String?>(null)
        private set
    /** Растёт, когда экран должен безусловно прыгнуть в конец списка (открыли чат из истории). */
    var jumpSignal by mutableIntStateOf(0)
        private set

    private var think: Any? = null          // null = не слать поле think вообще
    private val options = JSONObject()
    private var job: Job? = null
    private var stopped = false
    private var nextId = 0L

    init {
        refreshModels()
        refreshChats()
    }

    // ---------- ввод ----------

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        if (text.startsWith("/")) command(text) else chat(text)
    }

    fun stop() {
        stopped = true
        job?.cancel()
    }

    fun newChat() {
        stop()
        messages.clear()
        currentId = null
    }

    fun saveSettings(newNick: String, newHost: String, newSystem: String) {
        nick = newNick.trim()
        host = newHost.trim().trimEnd('/').ifBlank { DEFAULT_HOST }
        systemPrompt = newSystem.trim()
        prefs.edit()
            .putString("nick", nick)
            .putString("host", host)
            .putString("system", systemPrompt)
            .apply()
        refreshModels()
    }

    // ---------- история чатов ----------

    fun refreshChats() {
        viewModelScope.launch { chats = withContext(Dispatchers.IO) { store.listMeta() } }
    }

    fun openChat(id: String) {
        stop()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load(id) }
            messages.clear()
            messages.addAll(loaded.map { it.copy(id = nextId++) })
            currentId = id
            jumpSignal++
        }
    }

    fun renameChat(id: String, title: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, title) }
            refreshChats()
        }
    }

    fun deleteChat(id: String) {
        if (id == currentId) newChat()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshChats()
        }
    }

    suspend fun searchChats(query: String): Set<String> =
        withContext(Dispatchers.IO) { store.search(query) }

    /**
     * Сохраняет текущий разговор. Системные заметки интерфейса не сохраняются.
     * Запись идёт в одной очереди (Mutex), чтобы старый снимок не затёр новый.
     */
    private fun persist() {
        val snapshot = messages.filter {
            (it.role == Role.USER || it.role == Role.ASSISTANT) && (it.content.isNotEmpty() || it.thinking.isNotEmpty())
        }
        if (snapshot.isEmpty()) return
        val id = currentId ?: System.currentTimeMillis().toString(36).also { currentId = it }
        val title = autoTitle(snapshot.firstOrNull { it.role == Role.USER }?.content.orEmpty())
        viewModelScope.launch {
            saveLock.withLock { withContext(Dispatchers.IO) { store.save(id, title, snapshot) } }
            chats = withContext(Dispatchers.IO) { store.listMeta() }
        }
    }

    // первые 4 слова первого сообщения, не длиннее 30 символов
    private fun autoTitle(first: String): String {
        val words = first.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4).joinToString(" ")
        return words.take(30).trim().ifBlank { "Новый чат" }
    }

    fun refreshModels() {
        viewModelScope.launch { loadModels() }
    }

    fun selectModel(name: String) {
        model = name
        prefs.edit().putString("model", name).apply()
    }

    // ---------- чат ----------

    private fun chat(text: String) {
        add(Role.USER, text)
        if (model.isBlank()) {
            sys("Модель не выбрана. Выбери её в шапке.")
            return
        }
        persist()
        val body = buildBody()
        val replyId = add(Role.ASSISTANT, streaming = true)
        busy = true
        stopped = false
        job = viewModelScope.launch {
            val content = StringBuilder()
            val thinking = StringBuilder()
            var thinkStart = 0L
            var thinkMs = 0L
            try {
                client.chat(host, body).collect { c ->
                    val now = System.currentTimeMillis()
                    if (thinkStart == 0L && c.thinking.isNotEmpty()) thinkStart = now
                    if (thinkStart != 0L && thinkMs == 0L && c.content.isNotEmpty()) thinkMs = now - thinkStart
                    content.append(c.content)
                    thinking.append(c.thinking)
                    update(replyId) {
                        it.copy(
                            content = content.toString(),
                            thinking = thinking.toString(),
                            thinkStart = thinkStart,
                            thinkMs = thinkMs,
                        )
                    }
                }
                if (content.isEmpty() && thinking.isNotEmpty()) {
                    sys("Модель выдала только рассуждения, без ответа. Попробуй /set nothink.")
                } else if (content.isEmpty()) {
                    sys("Пустой ответ от модели.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopped) sys(describe(e))
            } finally {
                if (thinkStart != 0L && thinkMs == 0L) thinkMs = System.currentTimeMillis() - thinkStart
                val i = messages.indexOfFirst { it.id == replyId }
                if (i >= 0) {
                    val m = messages[i]
                    if (m.content.isEmpty() && m.thinking.isEmpty()) messages.removeAt(i)
                    else messages[i] = m.copy(streaming = false, thinkMs = thinkMs)
                }
                busy = false
                persist()
            }
        }
    }

    private fun buildBody(): JSONObject {
        val arr = JSONArray()
        val sysText = listOfNotNull(
            systemPrompt.takeIf { it.isNotBlank() },
            nick.takeIf { it.isNotBlank() }?.let { "Пользователя зовут $it." },
        ).joinToString("\n")
        if (sysText.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", sysText))
        messages
            .filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.content.isNotBlank() }
            .forEach {
                val role = if (it.role == Role.USER) "user" else "assistant"
                arr.put(JSONObject().put("role", role).put("content", it.content))
            }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("stream", true)
        think?.let { body.put("think", it) }
        if (options.length() > 0) body.put("options", options)
        return body
    }

    // ---------- команды ----------
    // REPL-команды ollama живут в CLI, а не в API, поэтому каждая
    // здесь переведена в изменение состояния или параметра запроса.

    private fun command(line: String) {
        val parts = line.removePrefix("/").trim().split(Regex("\\s+"), limit = 3)
        when (val cmd = parts[0].lowercase()) {
            "clear" -> {
                val hadChat = currentId != null
                newChat()
                sys(if (hadChat) "Контекст очищен. Прошлый чат остался в списке." else "Контекст очищен.")
            }
            "set" -> set(parts.getOrNull(1)?.lowercase(), parts.getOrNull(2))
            "show" -> sys(
                "model: ${model.ifBlank { "—" }}\nhost: $host\nnick: ${nick.ifBlank { "—" }}\nthink: ${think ?: "по умолчанию"}\n" +
                    "system: ${systemPrompt.ifBlank { "—" }}\noptions: $options"
            )
            "load" -> {
                val name = parts.getOrNull(1)
                if (name.isNullOrBlank()) {
                    sys("Использование: /load <модель>")
                } else {
                    selectModel(name)
                    newChat()
                    sys("Модель: $name. Контекст сброшен.")
                }
            }
            "help", "?" -> sys(HELP)
            "double" -> sys(String(android.util.Base64.decode(DOUBLE, android.util.Base64.DEFAULT)))
            else -> sys("Неизвестная команда /$cmd. /help — список.")
        }
    }

    private fun set(sub: String?, arg: String?) {
        when (sub) {
            "nothink" -> {
                think = false
                sys("think: выкл")
            }
            "think" -> {
                val level = arg?.trim()?.lowercase()
                think = when (level) {
                    "high", "medium", "low" -> level
                    "false" -> false
                    else -> true
                }
                sys("think: $think")
            }
            "system" -> {
                if (arg.isNullOrBlank()) {
                    sys("Использование: /set system <текст>")
                } else {
                    systemPrompt = arg.trim()
                    prefs.edit().putString("system", systemPrompt).apply()
                    sys("System-промпт задан (он же в настройках).")
                }
            }
            "parameter" -> {
                val kv = arg?.trim()?.split(Regex("\\s+"), limit = 2)
                if (kv == null || kv.size < 2) {
                    sys("Использование: /set parameter <имя> <значение>")
                } else {
                    options.put(kv[0], parseValue(kv[1]))
                    sys("${kv[0]} = ${kv[1]}")
                }
            }
            else -> sys("Не знаю /set ${sub.orEmpty()}. Есть: think, nothink, system, parameter.")
        }
    }

    private fun parseValue(v: String): Any =
        v.toIntOrNull() ?: v.toDoubleOrNull() ?: v.toBooleanStrictOrNull() ?: v

    // ---------- сервер ----------

    private suspend fun loadModels() {
        try {
            val list = client.tags(host)
            models = list
            serverError = null
            if (model.isBlank() && list.isNotEmpty()) selectModel(list.first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            models = emptyList()
            serverError = describe(e)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ConnectException, is UnknownHostException, is SocketTimeoutException ->
            "Нет связи с $host. Запусти ollama serve в Termux."
        else -> "Ошибка: ${e.message}"
    }

    // ---------- список сообщений ----------

    private fun add(role: Role, text: String = "", streaming: Boolean = false): Long {
        val id = nextId++
        messages.add(Msg(id, role, text, streaming = streaming))
        return id
    }

    private fun sys(text: String) {
        add(Role.SYSTEM, text)
    }

    private inline fun update(id: Long, f: (Msg) -> Msg) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    private companion object {
        const val DOUBLE =
            "VGVtcGVzdCBEb3VibGU6INC60L7Qv9C40Y8g0YHQvtC30LTQsNC90LAuINCa0LDQutCw0Y8g0LjQtyDQvdC40YUg0L3QsNGB0YLQvtGP0YnQsNGPIOKAlCDQstC+0L/RgNC+0YEg0L7RgtC60YDRi9GC0YvQuS4="
    }
}
