package com.lomkich.fxus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Клиент llama-server (llama.cpp). Он говорит на OpenAI-совместимом API:
 * список моделей в /v1/models, чат в /v1/chat/completions, стрим в формате SSE.
 */
class LlamaClient {

    // readTimeout = 0 по той же причине, что и в OllamaClient: первый токен может ждать долго
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Ключ (если задан --api-key на сервере) идёт заголовком Authorization: Bearer. */
    private fun Request.Builder.auth(key: String): Request.Builder =
        if (key.isBlank()) this else header("Authorization", "Bearer ${key.trim()}")

    /**
     * У org.json есть ловушка: если в JSON стоит null, optString вернёт строку "null".
     * А llama-server первым куском шлёт именно "content": null, так что проверяем через isNull.
     */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    private fun httpError(code: Int, raw: String): IOException {
        val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
            .getOrDefault(raw.take(300))
        val hint = if (code == 401) " (проверь API-ключ в настройках)" else ""
        return IOException("HTTP $code: $msg$hint")
    }

    suspend fun models(host: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val quick = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("$host/v1/models").auth(key).build()
        quick.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, raw)
            val arr = JSONObject(raw).getJSONArray("data")
            List(arr.length()) { arr.getJSONObject(it).getString("id") }
        }
    }

    fun chat(host: String, key: String, body: JSONObject): Flow<Chunk> = flow {
        val req = Request.Builder()
            .url("$host/v1/chat/completions")
            .auth(key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        // readUtf8Line() блокирующий, поэтому при отмене корутины рубим сам call
        val handle = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw IOException("Пустое тело ответа")
                // SSE: события приходят строками «data: {json}», в конце «data: [DONE]».
                // Пустые строки между событиями и комментарии (начинаются с «:») пропускаем.
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    val json = JSONObject(payload)
                    json.optJSONObject("error")?.let { throw IOException(it.optString("message", it.toString())) }
                    // последний кусок со статистикой приходит с пустым choices: просто пропускаем
                    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val content = delta.text("content")
                    val thinking = delta.text("reasoning_content")
                    if (content.isNotEmpty() || thinking.isNotEmpty()) emit(Chunk(content, thinking))
                }
            }
        } finally {
            handle.dispose()
        }
    }.flowOn(Dispatchers.IO)
}
