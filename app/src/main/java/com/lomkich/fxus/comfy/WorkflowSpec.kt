package com.lomkich.fxus.comfy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Понятная пользователю ошибка разбора воркфлоу. */
class WorkflowError(message: String) : Exception(message)

/** Конкретное поле конкретной ноды (куда подставлять значение). */
data class NodeInput(val node: String, val input: String)

/**
 * Где в воркфлоу что лежит. Раньше ID нод были зашиты константами, теперь определяются
 * по самому JSON, поэтому подойдёт любой API-экспорт.
 *
 * Обязательны: prompt (куда писать текст), sampler, output.
 * Остальное необязательно: нет ноды, значит, соответствующая функция просто отключена.
 */
class WorkflowSpec(
    val prompt: NodeInput,
    val sampler: String,
    val seed: NodeInput?,
    val outputs: Set<String>,
    val translator: String?,
    val tagsPreview: String?,
    val checkpoint: String?,
    val latent: String?,
    val decode: String?
) {
    companion object {
        private val TEXT_NAMES = listOf("text", "value", "string", "prompt", "text_g")
        private val OUTPUT_CLASSES = setOf("PreviewImage", "SaveImage")
        private const val MARKER = "APP_INPUT"

        private fun JsonObject.cls(id: String): String? =
            (this[id] as? JsonObject)?.get("class_type")?.let { (it as? JsonPrimitive)?.contentOrNull }

        private fun JsonObject.inputsOf(id: String): JsonObject? =
            (this[id] as? JsonObject)?.get("inputs") as? JsonObject

        private fun JsonObject.title(id: String): String? =
            ((this[id] as? JsonObject)?.get("_meta") as? JsonObject)?.get("title")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }

        /** Ссылка на выход другой ноды в API-формате: ["id", слот]. */
        private fun link(el: JsonElement?): Pair<String, Int>? {
            val a = el as? JsonArray ?: return null
            if (a.size != 2) return null
            val id = (a[0] as? JsonPrimitive)?.contentOrNull ?: return null
            val slot = (a[1] as? JsonPrimitive)?.intOrNull ?: return null
            return id to slot
        }

        private fun isStringLiteral(el: JsonElement?): Boolean = el is JsonPrimitive && el.isString

        /** Поле либо литерал (берём его), либо ссылка на ноду-источник с литералом (берём её). */
        private fun resolveLiteral(wf: JsonObject, node: String, input: String): NodeInput? {
            val v = wf.inputsOf(node)?.get(input) ?: return null
            val l = link(v) ?: return NodeInput(node, input)
            val src = wf.inputsOf(l.first) ?: return null
            val key = listOf("value", "seed", "int").firstOrNull { src[it] is JsonPrimitive } ?: return null
            return NodeInput(l.first, key)
        }

        private fun textInput(wf: JsonObject, id: String): NodeInput? {
            val inputs = wf.inputsOf(id) ?: return null
            val name = TEXT_NAMES.firstOrNull { isStringLiteral(inputs[it]) } ?: return null
            return NodeInput(id, name)
        }

        /**
         * Идём от positive у сэмплера вверх по ссылкам. Входом считаем самую «верхнюю» ноду
         * с текстовым литералом: в простом графе это сам CLIPTextEncode, в твоём
         * это PrimitiveStringMultiline перед переводчиком.
         */
        private fun findPrompt(wf: JsonObject, start: String): NodeInput? {
            var best: NodeInput? = null
            val seen = HashSet<String>().apply { add(start) }
            val queue = ArrayDeque<String>().apply { add(start) }
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                textInput(wf, id)?.let { best = it }
                val inputs = wf.inputsOf(id) ?: continue
                for (v in inputs.values) {
                    val t = link(v)?.first ?: continue
                    if (seen.add(t)) queue.add(t)
                }
            }
            return best
        }

        fun parse(bytes: ByteArray): Pair<JsonObject, WorkflowSpec> {
            val root = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
            } catch (e: Exception) {
                throw WorkflowError("Файл не похож на JSON-воркфлоу")
            }
            return root to detect(root)
        }

        fun detect(wf: JsonObject): WorkflowSpec {
            if (wf.containsKey("nodes") || wf.containsKey("links")) {
                throw WorkflowError("Это обычный (UI) экспорт. Нужен Workflow → Export (API)")
            }
            if (wf.isEmpty() || wf.keys.any { wf.cls(it) == null }) {
                throw WorkflowError("Не API-формат: у нод нет class_type")
            }
            val ids = wf.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

            val sampler = ids.firstOrNull {
                val c = wf.cls(it).orEmpty()
                c.startsWith("KSampler") || c.startsWith("SamplerCustom")
            } ?: throw WorkflowError("В воркфлоу нет KSampler: нечего показывать в превью")

            val sInputs = wf.inputsOf(sampler)
            val seedName = listOf("seed", "noise_seed").firstOrNull { sInputs?.containsKey(it) == true }
            val seed = seedName?.let { resolveLiteral(wf, sampler, it) }
                ?: ids.firstOrNull { wf.cls(it) == "RandomNoise" }?.let { resolveLiteral(wf, it, "noise_seed") }

            // 1) явная метка в названии ноды, 2) автопоиск от positive
            val marked = ids.firstOrNull { wf.title(it)?.contains(MARKER, ignoreCase = true) == true }
            val prompt = marked?.let { m ->
                textInput(wf, m) ?: wf.inputsOf(m)?.entries
                    ?.firstOrNull { isStringLiteral(it.value) }?.let { NodeInput(m, it.key) }
            } ?: link(sInputs?.get("positive"))?.first?.let { findPrompt(wf, it) }
            ?: throw WorkflowError(
                "Не нашёл, куда подставлять текст. Назови нужную ноду с «$MARKER» в заголовке"
            )

            val decode = ids.firstOrNull { wf.cls(it).orEmpty().startsWith("VAEDecode") }
            // все выходные ноды: при ветвлении (Branch) отработает только одна из них
            val outputs = ids.filter { wf.cls(it) in OUTPUT_CLASSES }.toSet()
            if (outputs.isEmpty()) throw WorkflowError("В воркфлоу нет PreviewImage или SaveImage")

            val translator = ids.firstOrNull { wf.cls(it) == "RuDanbooruTags" }
            val tagsPreview = translator?.let { t ->
                ids.firstOrNull { id ->
                    wf.cls(id) == "PreviewAny" && link(wf.inputsOf(id)?.get("source")) == (t to 0)
                }
            }

            val checkpoint = ids.firstOrNull { isStringLiteral(wf.inputsOf(it)?.get("ckpt_name")) }
            val latent = ids.firstOrNull { id ->
                wf.cls(id).orEmpty().contains("Latent") &&
                    wf.inputsOf(id)?.get("width") is JsonPrimitive &&
                    wf.inputsOf(id)?.get("height") is JsonPrimitive
            }

            return WorkflowSpec(prompt, sampler, seed, outputs, translator, tagsPreview, checkpoint, latent, decode)
        }
    }
}
