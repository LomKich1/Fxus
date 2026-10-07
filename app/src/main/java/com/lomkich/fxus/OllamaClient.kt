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

/** Один кусок стрима: либо токены ответа, либо токены рассуждения. */
data class Chunk(val content: String, val thinking: String)

class OllamaClient {

    // readTimeout = 0: модель может грузиться минутами до первого токена.
    // Прервать зависший запрос можно кнопкой стоп (отмена корутины рубит call).
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun tags(host: String): List<String> = withContext(Dispatchers.IO) {
        val quick = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("$host/api/tags").build()
        quick.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val arr = JSONObject(resp.body?.string().orEmpty()).getJSONArray("models")
            List(arr.length()) { arr.getJSONObject(it).getString("name") }
        }
    }

    fun chat(host: String, body: JSONObject): Flow<Chunk> = flow {
        val req = Request.Builder()
            .url("$host/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        // readUtf8Line() блокирующий, поэтому при отмене корутины рубим сам call
        val handle = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val msg = runCatching { JSONObject(raw).getString("error") }.getOrDefault(raw)
                    throw IOException("HTTP ${resp.code}: $msg")
                }
                val source = resp.body?.source() ?: throw IOException("Пустое тело ответа")
                // Ollama стримит NDJSON: одна строка = один JSON
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    if (json.has("error")) throw IOException(json.getString("error"))
                    val m = json.optJSONObject("message")
                    emit(Chunk(m?.optString("content").orEmpty(), m?.optString("thinking").orEmpty()))
                    if (json.optBoolean("done")) break
                }
            }
        } finally {
            handle.dispose()
        }
    }.flowOn(Dispatchers.IO)
}
