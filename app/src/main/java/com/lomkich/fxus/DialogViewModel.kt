package com.lomkich.fxus

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Собеседник: имя, системный промпт и модель (пусто = та, что выбрана в обычном чате). */
data class Persona(val name: String, val prompt: String = "", val model: String = "")

/** Куда ходить за ответами. Берём из настроек обычного чата, чтобы не вводить адрес дважды. */
data class Conn(val host: String, val backend: Backend, val apiKey: String, val defaultModel: String)

/**
 * Два ИИ переписываются друг с другом в одном чате. Цикл простой: говорит A, потом B, потом снова A,
 * и так, пока не нажмут стоп. Ведущий (ты) может вставить реплику в любой момент: её увидят оба на своём следующем ходу.
 */
class DialogViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("dialog", Context.MODE_PRIVATE)
    private val ollama = OllamaClient()
    private val llama = LlamaClient()

    val messages = mutableStateListOf<DialogMsg>()

    var a by mutableStateOf(loadPersona("a", "Алиса"))
        private set
    var b by mutableStateOf(loadPersona("b", "Борис"))
        private set

    var running by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var nextId = 1L

    // ---------- настройки собеседников ----------

    private fun loadPersona(key: String, defaultName: String) = Persona(
        name = prefs.getString("${key}_name", defaultName).orEmpty(),
        prompt = prefs.getString("${key}_prompt", "").orEmpty(),
        model = prefs.getString("${key}_model", "").orEmpty(),
    )

    private fun savePersona(key: String, p: Persona) {
        prefs.edit()
            .putString("${key}_name", p.name)
            .putString("${key}_prompt", p.prompt)
            .putString("${key}_model", p.model)
            .apply()
    }

    fun editA(p: Persona) {
        a = p
        savePersona("a", p)
    }

    fun editB(p: Persona) {
        b = p
        savePersona("b", p)
    }

    /** Имя для интерфейса и для пометок «[Имя]:». Пустое имя заменяем буквой, чтобы пометка не вышла пустой. */
    fun nameOf(s: Speaker): String = when (s) {
        Speaker.A -> a.name.trim().ifEmpty { "A" }
        Speaker.B -> b.name.trim().ifEmpty { "B" }
        Speaker.HOST -> "Ведущий"
        Speaker.SYSTEM -> ""
    }

    // ---------- управление разговором ----------

    /** Реплика ведущего. Если разговор стоял (или это самое начало), он запускается. */
    fun say(text: String, conn: Conn) {
        val t = text.trim()
        if (t.isEmpty()) return
        messages.add(DialogMsg(nextId++, Speaker.HOST, t))
        if (!running) startLoop(conn)
    }

    /** Продолжить после стопа, ничего не добавляя от себя. */
    fun resume(conn: Conn) {
        if (!running && messages.any { it.who != Speaker.SYSTEM }) startLoop(conn)
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    fun clear() {
        stop()
        messages.clear()
    }

    private fun startLoop(conn: Conn) {
        running = true
        val me = viewModelScope.launch {
            try {
                while (true) turn(DialogLogic.nextSpeaker(messages), conn)
            } catch (e: CancellationException) {
                throw e   // это нажали стоп, ошибки тут нет
            } catch (e: Exception) {
                addSystem(describe(e, conn))
            }
        }
        job = me
        me.invokeOnCompletion {
            if (job === me) {
                job = null
                running = false
            }
        }
    }

    /** Один ход: собеседник who смотрит на переписку и пишет свою реплику потоком. */
    private suspend fun turn(who: Speaker, conn: Conn) {
        val me = if (who == Speaker.A) a else b
        val other = if (who == Speaker.A) Speaker.B else Speaker.A
        val myName = nameOf(who)
        val model = me.model.ifBlank { conn.defaultModel }
        if (model.isBlank()) throw IOException("Не выбрана модель: выбери её для «$myName» или в обычном чате.")

        val history = DialogLogic.history(messages, who, nameOf = { nameOf(it) })
        val body = buildBody(conn.backend, model, DialogLogic.systemText(me.prompt, myName, nameOf(other)), history)

        val id = nextId++
        messages.add(DialogMsg(id, who, "", streaming = true))
        val re = DialogLogic.nameRegex(myName)
        val raw = StringBuilder()
        try {
            stream(conn, body).collect { c ->
                // рассуждения модели (reasoning) в разговор не берём, только саму реплику
                if (c.content.isNotEmpty()) {
                    raw.append(c.content)
                    setText(id, DialogLogic.stripName(raw.toString(), re))
                }
            }
        } finally {
            // дошли до конца или нажали стоп: снимаем «печатает», пустую реплику убираем совсем
            val i = messages.indexOfLast { it.id == id }
            if (i >= 0) {
                val text = DialogLogic.stripName(raw.toString(), re).trim()
                if (text.isEmpty()) messages.removeAt(i) else messages[i] = messages[i].copy(text = text, streaming = false)
            }
        }
        if (DialogLogic.stripName(raw.toString(), re).isBlank()) {
            throw IOException("«$myName» (модель $model) вернул пустой ответ.")
        }
    }

    private fun setText(id: Long, text: String) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = text)
    }

    private fun addSystem(text: String) {
        messages.add(DialogMsg(nextId++, Speaker.SYSTEM, text))
    }

    // ---------- запрос ----------

    private fun buildBody(be: Backend, model: String, system: String, history: List<Pair<String, String>>): JSONObject {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (role, text) -> arr.put(JSONObject().put("role", role).put("content", text)) }
        val body = JSONObject().put("model", model).put("messages", arr).put("stream", true)
        // У Ollama контекст по умолчанию маленький и молча обрезает начало (вместе с системным промптом).
        // У llama-server он задан при запуске флагом -c, там просить нечего.
        if (be == Backend.OLLAMA) body.put("options", JSONObject().put("num_ctx", OLLAMA_CTX))
        return body
    }

    private fun stream(conn: Conn, body: JSONObject): Flow<Chunk> = when (conn.backend) {
        Backend.OLLAMA -> ollama.chat(conn.host, body)
        Backend.LLAMA -> llama.chat(conn.host, conn.apiKey, body)
    }

    private fun describe(e: Exception, conn: Conn): String = when (e) {
        is ConnectException, is UnknownHostException, is SocketTimeoutException ->
            "Нет связи с ${conn.host}. Разговор остановлен."
        else -> "${e.message ?: "Ошибка"}. Разговор остановлен."
    }

    private companion object {
        const val OLLAMA_CTX = 8192
    }
}
