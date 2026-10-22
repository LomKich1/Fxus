package com.lomkich.fxus

/** Кто сказал реплику. A и B это два ИИ, HOST это ты (ведущий), SYSTEM это служебные строки вроде ошибок. */
enum class Speaker { A, B, HOST, SYSTEM }

data class DialogMsg(
    val id: Long,
    val who: Speaker,
    val text: String,
    /** Пока ИИ ещё печатает эту реплику. */
    val streaming: Boolean = false,
)

/**
 * Чистая логика диалога двух ИИ, без Android: отдельно, чтобы её можно было проверить обычным запуском.
 *
 * Главная идея: модель умеет только «user ↔ assistant». Поэтому для каждого собеседника мы
 * пересказываем общую переписку с его точки зрения: его собственные реплики становятся assistant,
 * а всё остальное (второй ИИ и ведущий) становится user, с пометкой «[Имя]: ».
 */
object DialogLogic {

    /** Сколько символов переписки отправляем модели. Хвост длиннее отрезается, иначе контекст рано или поздно кончится. */
    const val HISTORY_CHARS = 12_000

    /** Говорят по очереди: следующий тот, кто не говорил последним. Если ещё никто не говорил, начинает A. */
    fun nextSpeaker(messages: List<DialogMsg>): Speaker {
        val last = messages.lastOrNull { it.who == Speaker.A || it.who == Speaker.B } ?: return Speaker.A
        return if (last.who == Speaker.A) Speaker.B else Speaker.A
    }

    /**
     * Переписка глазами [who] в виде пар (роль, текст).
     * 1. Окно: самое первое сообщение ведущего (тема) закреплено всегда, плюс свежий хвост до [budget] символов.
     * 2. Роли: свои реплики это assistant, чужие это user с пометкой автора.
     * 3. Соседние сообщения одной роли склеиваются: шаблоны чата (например, у Gemma) требуют строгого
     *    чередования user и assistant, а ведущий может вставить реплику между двумя чужими.
     */
    fun history(
        messages: List<DialogMsg>,
        who: Speaker,
        nameOf: (Speaker) -> String,
        budget: Int = HISTORY_CHARS,
    ): List<Pair<String, String>> {
        val all = messages.filter { it.who != Speaker.SYSTEM && it.text.isNotBlank() }
        val first = all.firstOrNull()

        var left = budget
        val tail = ArrayDeque<DialogMsg>()
        for (m in all.asReversed()) {
            // хотя бы одно сообщение берём всегда, даже если оно одно длиннее бюджета
            if (tail.isNotEmpty() && left - m.text.length < 0) break
            left -= m.text.length
            tail.addFirst(m)
        }
        val window =
            if (first != null && first.who == Speaker.HOST && tail.none { it.id == first.id }) listOf(first) + tail
            else tail.toList()

        val out = mutableListOf<Pair<String, String>>()
        for (m in window) {
            val mine = m.who == who
            val role = if (mine) "assistant" else "user"
            val text = if (mine) m.text else "[${nameOf(m.who)}]: ${m.text}"
            val last = out.lastOrNull()
            if (last != null && last.first == role) {
                out[out.lastIndex] = role to (last.second + "\n\n" + text)
            } else {
                out.add(role to text)
            }
        }
        // разговор должен начинаться с user, иначе строгий шаблон отклонит запрос
        if (out.firstOrNull()?.first == "assistant") {
            out.add(0, "user" to "[Ведущий]: Разговор начался раньше, продолжай с этого места.")
        }
        return out
    }

    /** Системный текст собеседника: твой промпт плюс короткие правила, чтобы модель не путалась, кто есть кто. */
    fun systemText(prompt: String, myName: String, otherName: String): String {
        val rules = "Ты — $myName. Ты ведёшь разговор с $otherName. В него может вмешиваться и Ведущий (живой человек). " +
            "Реплики других приходят с пометкой автора в квадратных скобках: «[Имя]: текст». " +
            "Отвечай только от своего лица: одна реплика, без своего имени и скобок в начале, без реплик за других."
        return if (prompt.isBlank()) rules else prompt.trim() + "\n\n" + rules
    }

    /** Модели любят начинать с «[Имя]:», «Имя:» или «**Имя:**», хотя мы просили не надо. Срезаем такое начало. */
    fun nameRegex(name: String): Regex =
        Regex("^\\s*[*_]*\\[?${Regex.escape(name)}\\]?[*_]*\\s*[:：]\\s*[*_]*\\s*", RegexOption.IGNORE_CASE)

    fun stripName(text: String, re: Regex): String = re.replaceFirst(text, "")
}
