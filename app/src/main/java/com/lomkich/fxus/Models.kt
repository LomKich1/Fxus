package com.lomkich.fxus

enum class Role { USER, ASSISTANT, SYSTEM }

data class Msg(
    val id: Long,
    val role: Role,
    val content: String,
    val thinking: String = "",
    val streaming: Boolean = false,
    /** Когда пошли первые токены рассуждения (мс) и сколько оно длилось (мс). */
    val thinkStart: Long = 0L,
    val thinkMs: Long = 0L,
    /** id картинки в истории ComfyUI (0 = обычное сообщение). Сама картинка живёт в экране «Артефакты». */
    val turnId: Long = 0L,
    /** Прикреплённые файлы. content здесь только то, что напечатал сам пользователь. */
    val attachments: List<Attachment> = emptyList(),
)

enum class AttachKind { TEXT, IMAGE }

/** Вложение: текстовый/кодовый файл (его содержимое уходит в промпт) или картинка (сжатый JPEG на диске). */
data class Attachment(
    val kind: AttachKind,
    val name: String,
    /** TEXT: содержимое файла. */
    val text: String = "",
    /** IMAGE: имя файла в filesDir/attachments. */
    val file: String = "",
    /** Размер для подписи на плашке. */
    val bytes: Int = 0,
)

/** Что за сервер на том конце: у Ollama и llama-server разные API. */
enum class Backend(val label: String) {
    OLLAMA("Ollama"),
    LLAMA("llama.cpp"),
}
