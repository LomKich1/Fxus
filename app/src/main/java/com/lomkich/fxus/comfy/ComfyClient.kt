package com.lomkich.fxus.comfy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Клиент ComfyUI.
 *
 * Устойчивость к обрывам:
 *  - у всех REST-запросов жёсткие таймауты (раньше висели бесконечно и блокировали приложение);
 *  - WebSocket пингуется каждые 10 с, «мёртвое» соединение обнаруживается за ~10-20 с;
 *  - при обрыве идёт переподключение с нарастающей паузой (clientId тот же, сервер продолжает
 *    слать события), а результат при необходимости добирается из /history;
 *  - prompt_id задаём сами, поэтому повторная отправка безопасна и дубликатов не плодит.
 */
data class RemoteWorkflow(val name: String, val hash: String, val mtime: Long, val valid: Boolean)

class ComfyClient(private val baseUrl: String) {

    private val rest = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val wsHttp = rest.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val download = rest.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val clientId = UUID.randomUUID().toString()

    private class Stop : CancellationException("stop")

    private enum class Status { RUNNING, DONE, LOST, UNKNOWN }

    private sealed interface Frame {
        data object Opened : Frame
        class Text(val s: String) : Frame
        class Bin(val b: ByteString) : Frame
        data class Closed(val error: Throwable?) : Frame
    }

    /** Одно WebSocket-соединение как поток кадров. Завершается, когда соединение закрыто или оборвано. */
    private fun frames(): Flow<Frame> = callbackFlow {
        val opened = AtomicBoolean(false)
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws?clientId=$clientId"
        val ws = wsHttp.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                trySend(Frame.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(Frame.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(Frame.Bin(bytes))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Frame.Closed(null))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(Frame.Closed(t))
                close()
            }
        })
        // рукопожатие без таймаута чтения может висеть вечно, поэтому режем сами
        launch {
            delay(8_000)
            if (!opened.get()) ws.cancel()
        }
        awaitClose { ws.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun stageFor(node: String, spec: WorkflowSpec): String? = when (node) {
        spec.translator -> "Перевожу описание в теги…"
        spec.sampler -> "Генерирую…"
        spec.decode -> "Декодирую…"
        else -> null
    }

    private fun parseImages(arr: JsonArray?): List<ImageRef> =
        arr?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["filename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ImageRef(
                name,
                o["subfolder"]?.jsonPrimitive?.contentOrNull ?: "",
                o["type"]?.jsonPrimitive?.contentOrNull ?: "temp"
            )
        }.orEmpty()

    private fun firstText(out: JsonObject?): String? =
        (out?.get("text") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull

    // Бинарные кадры (big-endian): [1][формат:4][картинка] или [4][длина метаданных:4][json][картинка]
    private fun decodePreview(bytes: ByteString): Bitmap? {
        val b = bytes.toByteArray()
        if (b.size < 8) return null
        val bb = ByteBuffer.wrap(b)
        val offset = when (bb.getInt(0)) {
            1 -> 8
            4 -> 8 + bb.getInt(4)
            else -> return null
        }
        if (offset >= b.size) return null
        return BitmapFactory.decodeByteArray(b, offset, b.size - offset)
    }

    fun generate(workflow: JsonObject, spec: WorkflowSpec): Flow<GenEvent> = channelFlow {
        val images = mutableListOf<ImageRef>()
        var promptId = UUID.randomUUID().toString()
        var finished = false
        var submitted = false
        var tried = false
        var failures = 0

        suspend fun finish(ev: GenEvent) {
            if (!finished) {
                finished = true
                send(ev)
            }
        }

        suspend fun applyOutputs(outputs: JsonObject?) {
            spec.tagsPreview?.let { tp ->
                firstText(outputs?.get(tp) as? JsonObject)?.let { send(GenEvent.Tags(it)) }
            }
            // выходных нод может быть несколько (ветки Branch): берём картинки из той, что реально отработала
            val imgs = spec.outputs.flatMap { id ->
                parseImages((outputs?.get(id) as? JsonObject)?.get("images") as? JsonArray)
            }
            if (imgs.isNotEmpty()) {
                images.clear()
                images.addAll(imgs)
            }
        }

        /** true — задача уже завершена и finish() вызван. Бросает IOException при проблемах сети. */
        suspend fun checkHistory(): Boolean {
            val body = rest.newCall(Request.Builder().url("$baseUrl/history/$promptId").build())
                .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?: return false
            val entry = json.parseToJsonElement(body).jsonObject[promptId]?.jsonObject ?: return false
            val st = entry["status"] as? JsonObject
            val statusStr = st?.get("status_str")?.jsonPrimitive?.contentOrNull
            val completed = st?.get("completed")?.jsonPrimitive?.booleanOrNull == true
            if (statusStr == "error") {
                finish(GenEvent.Failed("Генерация завершилась ошибкой на стороне ComfyUI"))
                return true
            }
            if (completed) {
                applyOutputs(entry["outputs"] as? JsonObject)
                finish(GenEvent.Done(images.toList()))
                return true
            }
            return false
        }

        /** Узнаёт судьбу задачи после обрыва: готова, ещё идёт, потеряна или неизвестно (сеть всё ещё лежит). */
        suspend fun recover(): Status = try {
            if (checkHistory()) {
                Status.DONE
            } else {
                val q = rest.newCall(Request.Builder().url("$baseUrl/queue").build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                if (q == null) {
                    Status.UNKNOWN
                } else {
                    val o = json.parseToJsonElement(q).jsonObject
                    val items = (o["queue_running"] as? JsonArray).orEmpty() +
                        (o["queue_pending"] as? JsonArray).orEmpty()
                    val inQueue = items.any {
                        (it as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.contentOrNull == promptId
                    }
                    when {
                        inQueue -> Status.RUNNING
                        checkHistory() -> Status.DONE   // успела закончиться между двумя запросами
                        else -> Status.LOST
                    }
                }
            }
        } catch (e: IOException) {
            Status.UNKNOWN
        } catch (e: IllegalArgumentException) {
            Status.UNKNOWN
        }

        /** true — сервер ответил (принял или отклонил); false — до него не достучались. */
        suspend fun submit(): Boolean = try {
            val body = buildJsonObject {
                put("prompt", workflow)
                put("client_id", clientId)
                put("prompt_id", promptId)
            }.toString().toRequestBody("application/json".toMediaType())
            rest.newCall(Request.Builder().url("$baseUrl/prompt").post(body).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    runCatching {
                        json.parseToJsonElement(text).jsonObject["prompt_id"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.let { promptId = it }
                } else {
                    finish(GenEvent.Failed("ComfyUI отклонил workflow (${r.code}): ${text.take(400)}"))
                }
            }
            true
        } catch (e: IOException) {
            false
        }

        suspend fun complete() {
            if (images.isEmpty()) {
                recover()   // события могли потеряться при обрыве, добираем из истории
                if (finished) return
            }
            finish(GenEvent.Done(images.toList()))
        }

        suspend fun handleText(text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = msg["type"]?.jsonPrimitive?.contentOrNull
            val data = msg["data"] as? JsonObject ?: return
            when (type) {
                "executing" -> {
                    val node = data["node"]?.jsonPrimitive?.contentOrNull
                    if (node == null) complete() else stageFor(node, spec)?.let { send(GenEvent.Stage(it)) }
                }
                "progress" -> {
                    val node = data["node"]?.jsonPrimitive?.contentOrNull
                    val v = data["value"]?.jsonPrimitive?.intOrNull ?: return
                    val m = data["max"]?.jsonPrimitive?.intOrNull ?: return
                    if (m > 0 && (node == null || node == spec.sampler)) send(GenEvent.Progress(v, m))
                }
                "executed" -> {
                    val node = data["node"]?.jsonPrimitive?.contentOrNull
                    val out = data["output"] as? JsonObject
                    if (spec.tagsPreview != null && node == spec.tagsPreview) firstText(out)?.let { send(GenEvent.Tags(it)) }
                    if (node != null && node in spec.outputs) images.addAll(parseImages(out?.get("images") as? JsonArray))
                }
                "execution_success" -> complete()
                "execution_interrupted" -> finish(GenEvent.Failed("Остановлено"))
                "execution_error" -> {
                    val node = data["node_type"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val m = data["exception_message"]?.jsonPrimitive?.contentOrNull ?: "неизвестная ошибка"
                    finish(GenEvent.Failed("Ошибка в ноде $node: ${m.trim()}"))
                }
            }
        }

        while (!finished) {
            var abort = false
            try {
                frames().collect { f ->
                    when (f) {
                        is Frame.Opened -> {
                            if (!submitted) {
                                val st = if (tried) recover() else Status.UNKNOWN
                                tried = true
                                if (!finished) {
                                    submitted = st == Status.RUNNING || submit()
                                    if (!submitted) abort = true
                                }
                            } else {
                                send(GenEvent.Stage("Связь восстановлена"))
                                if (recover() == Status.LOST) {
                                    finish(GenEvent.Failed("ComfyUI потерял задачу (возможно, сервер перезапускали)"))
                                }
                            }
                        }
                        is Frame.Text -> {
                            failures = 0
                            handleText(f.s)
                        }
                        is Frame.Bin -> decodePreview(f.b)?.let { send(GenEvent.Preview(it)) }
                        is Frame.Closed -> {}
                    }
                    if (finished || abort) throw Stop()
                }
            } catch (e: Stop) {
                // нормальный выход из цикла приёма
            }

            if (finished) break

            failures++
            val limit = if (submitted) 8 else 3
            if (failures > limit) {
                finish(
                    GenEvent.Failed(
                        if (submitted) "Связь с ComfyUI потеряна и не восстановилась"
                        else "Не достучался до $baseUrl. Проверь адрес, запущен ли ComfyUI и не мешает ли VPN"
                    )
                )
                break
            }
            send(
                GenEvent.Stage(
                    if (submitted) "Связь потеряна, переподключаюсь… ($failures/$limit)"
                    else "Нет связи, пробую снова ($failures/$limit)…"
                )
            )
            delay(minOf(1000L shl (failures - 1), 8000L))
            if (submitted) recover()   // вдруг задача уже закончилась, пока связи не было
        }
    }.flowOn(Dispatchers.IO)

    /** Скачивание результата (оригинальный PNG) с повторами: сеть могла моргнуть как раз в этот момент. */
    suspend fun fetchImageBytes(ref: ImageRef): ByteArray? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/view".toHttpUrl().newBuilder()
            .addQueryParameter("filename", ref.filename)
            .addQueryParameter("subfolder", ref.subfolder)
            .addQueryParameter("type", ref.type)
            .build()
        repeat(3) { attempt ->
            try {
                val bytes = download.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
                if (bytes != null && bytes.isNotEmpty()) return@withContext bytes
            } catch (e: IOException) {
                // повторим
            }
            delay(1000L * (attempt + 1))
        }
        null
    }

    /** Чекпоинты, которые видит сервер. Блокирующий вызов, дёргать из IO. */
    fun listCheckpoints(): List<String> {
        val body = rest.newCall(Request.Builder().url("$baseUrl/object_info/CheckpointLoaderSimple").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        val field = json.parseToJsonElement(body).jsonObject["CheckpointLoaderSimple"]?.jsonObject
            ?.get("input")?.jsonObject?.get("required")?.jsonObject?.get("ckpt_name") as? JsonArray
            ?: return emptyList()
        val first = field.firstOrNull()
        // старый формат: [[имена], {...}], новый: ["COMBO", {"options": [имена]}]
        val list = when {
            first is JsonArray -> first
            first?.jsonPrimitive?.contentOrNull == "COMBO" -> (field.getOrNull(1) as? JsonObject)?.get("options") as? JsonArray
            else -> null
        }
        return list?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }

    /** Воркфлоу, лежащие на ПК (расширение app_bridge). Блокирующий вызов, дёргать из IO. */
    fun listWorkflows(): List<RemoteWorkflow> {
        val body = rest.newCall(Request.Builder().url("$baseUrl/app_bridge/workflows").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        return (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteWorkflow(
                name = name,
                hash = o["hash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                mtime = o["mtime"]?.jsonPrimitive?.longOrNull ?: 0L,
                valid = o["valid"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    /** Сырые байты файла с ПК: хэш на телефоне считается ровно от них. */
    fun fetchWorkflow(name: String): ByteArray {
        val url = "$baseUrl/app_bridge/workflows".toHttpUrl().newBuilder().addPathSegment(name).build()
        return rest.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.bytes() ?: throw IOException("пустой ответ")
        }
    }

    /** Блокирующий вызов, дёргать из IO. Таймаут 30 с, так что зависнуть надолго не может. */
    fun interrupt() {
        rest.newCall(Request.Builder().url("$baseUrl/interrupt").post("".toRequestBody()).build())
            .execute().close()
    }
}
