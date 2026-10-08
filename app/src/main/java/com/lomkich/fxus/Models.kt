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
)
