package com.lomkich.fxus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMeta(val id: String, val title: String, val updatedAt: Long)

/**
 * Хранилище чатов: один JSON-файл на чат + index.json со списком (id, название, время).
 * Список чатов читается из индекса, сами сообщения открываются только когда чат выбран.
 * Все методы блокирующие, вызывать из Dispatchers.IO.
 */
class ChatStore(context: Context) {

    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    private fun chatFile(id: String) = File(dir, "$id.json")

    // сначала пишем во временный файл, потом переименовываем: обрыв посреди записи не портит данные
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readIndex(): List<ChatMeta> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONObject(indexFile.readText()).getJSONArray("chats")
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                ChatMeta(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(list: List<ChatMeta>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updatedAt", it.updatedAt))
        }
        atomicWrite(indexFile, JSONObject().put("chats", arr).toString())
    }

    @Synchronized
    fun listMeta(): List<ChatMeta> = readIndex().sortedByDescending { it.updatedAt }

    /** Название у уже сохранённого чата не трогаем, defaultTitle нужен только для нового. */
    @Synchronized
    fun save(id: String, defaultTitle: String, msgs: List<Msg>) {
        val arr = JSONArray()
        msgs.forEach {
            arr.put(
                JSONObject()
                    .put("role", it.role.name)
                    .put("content", it.content)
                    .put("thinking", it.thinking)
                    .put("thinkMs", it.thinkMs)
                    .put("turnId", it.turnId)
            )
        }
        atomicWrite(chatFile(id), JSONObject().put("messages", arr).toString())

        val index = readIndex().toMutableList()
        val at = index.indexOfFirst { it.id == id }
        val title = if (at >= 0) index[at].title else defaultTitle
        val meta = ChatMeta(id, title, System.currentTimeMillis())
        if (at >= 0) index[at] = meta else index.add(meta)
        writeIndex(index)
    }

    /** id у возвращённых сообщений пустышки, их переназначает ViewModel. */
    @Synchronized
    fun load(id: String): List<Msg> = try {
        val arr = JSONObject(chatFile(id).readText()).getJSONArray("messages")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Msg(
                id = 0,
                role = Role.valueOf(o.getString("role")),
                content = o.getString("content"),
                thinking = o.optString("thinking"),
                thinkMs = o.optLong("thinkMs"),
                turnId = o.optLong("turnId"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun rename(id: String, title: String) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(title = title) else it })
    }

    @Synchronized
    fun delete(id: String) {
        chatFile(id).delete()
        writeIndex(readIndex().filter { it.id != id })
    }

    /** id чатов, где запрос встречается в названии или в тексте любого сообщения. */
    fun search(query: String): Set<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptySet()
        val hits = HashSet<String>()
        for (meta in readIndex()) {
            if (meta.title.lowercase().contains(q) || contains(meta.id, q)) hits.add(meta.id)
        }
        return hits
    }

    private fun contains(id: String, q: String): Boolean = try {
        val arr = JSONObject(chatFile(id).readText()).getJSONArray("messages")
        (0 until arr.length()).any { arr.getJSONObject(it).getString("content").lowercase().contains(q) }
    } catch (e: Exception) {
        false
    }
}
