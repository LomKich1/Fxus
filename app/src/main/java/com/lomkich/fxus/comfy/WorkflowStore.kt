package com.lomkich.fxus.comfy

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Файлы воркфлоу на телефоне.
 *  builtin       — assets/workflow.json (запасной, есть всегда);
 *  pc:<имя>      — скачано с ПК через app_bridge (кэш, работает и без связи);
 *  local:<имя>   — импортировано с телефона.
 */
class WorkflowStore(private val ctx: Context) {
    private val pcDir = File(ctx.filesDir, "workflows/pc").apply { mkdirs() }
    private val localDir = File(ctx.filesDir, "workflows/local").apply { mkdirs() }

    private fun dirOf(id: String): File? = when {
        id.startsWith("pc:") -> pcDir
        id.startsWith("local:") -> localDir
        else -> null
    }

    /** Только имя файла и только .json: никаких путей. */
    private fun safeName(id: String): String? {
        val n = id.substringAfter(':', "")
        return n.takeIf { it.isNotEmpty() && File(it).name == n && n.endsWith(".json", ignoreCase = true) }
    }

    fun read(id: String): ByteArray? {
        if (id == BUILTIN) return ctx.assets.open("workflow.json").use { it.readBytes() }
        val dir = dirOf(id) ?: return null
        val f = File(dir, safeName(id) ?: return null)
        return if (f.isFile) f.readBytes() else null
    }

    fun write(id: String, bytes: ByteArray) {
        val dir = dirOf(id) ?: return
        File(dir, safeName(id) ?: return).writeBytes(bytes)
    }

    fun exists(id: String): Boolean {
        val dir = dirOf(id) ?: return false
        return File(dir, safeName(id) ?: return false).isFile
    }

    /** Удаляет только файл на телефоне (builtin не трогается). */
    fun delete(id: String) {
        val dir = dirOf(id) ?: return
        File(dir, safeName(id) ?: return).delete()
    }

    fun names(prefix: String): List<String> =
        (if (prefix == "pc") pcDir else localDir).listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
            ?.map { it.name }?.sortedBy { it.lowercase() }.orEmpty()

    /** Тот же хэш, что считает сервер: первые 16 символов sha256 от байтов файла. */
    fun hashOf(id: String): String? = read(id)?.let { hash(it) }

    companion object {
        const val BUILTIN = "builtin"

        fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

        fun title(id: String): String =
            if (id == BUILTIN) "Встроенный" else id.substringAfter(':').removeSuffix(".json")
    }
}
