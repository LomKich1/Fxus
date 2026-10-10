package com.lomkich.fxus.comfy

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Saved(
    val id: Long,
    val prompt: String,
    val tags: String? = null,
    val file: String,
    val size: String
)

/** Картинки лежат в папке history внутри filesDir (оригиналы PNG от ComfyUI), список в index.json. */
class HistoryStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "history").also { it.mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Saved.serializer())

    fun fileOf(name: String) = File(dir, name)

    fun write(bytes: ByteArray): File {
        val f = File(dir, "${UUID.randomUUID()}.png")
        f.writeBytes(bytes)
        return f
    }

    fun load(): List<Saved> = try {
        if (index.exists()) json.decodeFromString(serializer, index.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Saved>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(index)
    }
}
