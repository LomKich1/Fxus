package com.lomkich.fxus.comfy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Приводит введённый адрес к виду схема://хост[:порт]: добавляет схему (https для trycloudflare,
 * иначе http), порт 8188 для голого IP и выкидывает путь, если вставили ссылку целиком.
 */
fun normalizeUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return s
    if (!s.contains("://")) {
        s = (if (s.substringBefore('/').endsWith(".trycloudflare.com")) "https://" else "http://") + s
    }
    val scheme = s.substringBefore("://").lowercase()
    val host = s.substringAfter("://").substringBefore('/').substringBefore('?').trim()
    if (host.isEmpty()) return s.trimEnd('/')
    val withPort = if (scheme == "http" && !host.contains(':')) "$host:8188" else host
    return "$scheme://$withPort"
}

class SettingsStore(private val ctx: Context) {
    private val urlKey = stringPreferencesKey("url")
    private val seedFixedKey = booleanPreferencesKey("seed_fixed")
    private val seedKey = stringPreferencesKey("seed")
    private val tunnelKey = stringPreferencesKey("tunnel")
    private val ckptKey = stringPreferencesKey("ckpt")
    private val sizeKey = stringPreferencesKey("size")
    private val sizesKey = stringPreferencesKey("recent_sizes")
    private val workflowKey = stringPreferencesKey("workflow")
    private val hiddenKey = stringSetPreferencesKey("hidden_workflows")

    val url: Flow<String> = ctx.dataStore.data.map { it[urlKey] ?: "http://192.168.0.10:8188" }

    /** Адрес для доступа из интернета (Cloudflare Tunnel и т. п.); пусто = не используется. */
    val tunnel: Flow<String> = ctx.dataStore.data.map { it[tunnelKey] ?: "" }

    /** Пустая строка: оставить модель, прописанную в workflow.json. */
    val ckpt: Flow<String> = ctx.dataStore.data.map { it[ckptKey] ?: "" }

    val size: Flow<Size> = ctx.dataStore.data.map { Size.parse(it[sizeKey] ?: "") ?: Size.DEFAULT }

    /** Недавно введённые свои разрешения (до 4, свежие первыми). */
    val recentSizes: Flow<List<Size>> = ctx.dataStore.data.map { p ->
        (p[sizesKey] ?: "").split(';').mapNotNull { Size.parse(it) }
    }

    /** Активный воркфлоу: builtin, pc:<имя> или local:<имя>. */
    /** id скрытых воркфлоу (скрытые не показываются в списке, но их можно вернуть). */
    val hiddenWorkflows: Flow<Set<String>> = ctx.dataStore.data.map { it[hiddenKey] ?: emptySet() }

    val workflow: Flow<String> = ctx.dataStore.data.map { it[workflowKey] ?: WorkflowStore.BUILTIN }

    val seedFixed: Flow<Boolean> = ctx.dataStore.data.map { it[seedFixedKey] ?: false }
    val seed: Flow<String> = ctx.dataStore.data.map { it[seedKey] ?: "" }

    suspend fun setUrl(v: String) {
        ctx.dataStore.edit { it[urlKey] = normalizeUrl(v) }
    }

    suspend fun setHiddenWorkflows(v: Set<String>) {
        ctx.dataStore.edit { it[hiddenKey] = v }
    }

    suspend fun setWorkflow(v: String) {
        ctx.dataStore.edit { it[workflowKey] = v }
    }

    suspend fun setSize(v: Size) {
        ctx.dataStore.edit { it[sizeKey] = v.key }
    }

    suspend fun rememberCustomSize(v: Size) {
        ctx.dataStore.edit { p ->
            val cur = (p[sizesKey] ?: "").split(';').mapNotNull { Size.parse(it) }
            p[sizesKey] = (listOf(v) + cur.filter { it != v }).take(4).joinToString(";") { it.key }
        }
    }

    suspend fun setTunnel(v: String) {
        ctx.dataStore.edit { it[tunnelKey] = normalizeUrl(v) }
    }

    suspend fun setCkpt(v: String) {
        ctx.dataStore.edit { it[ckptKey] = v }
    }

    suspend fun setSeed(fixed: Boolean, value: String) {
        ctx.dataStore.edit {
            it[seedFixedKey] = fixed
            it[seedKey] = value
        }
    }
}
