package com.lomkich.fxus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// =====================================================================
// 1. Разбор блоков. Ничего про Compose здесь нет: текст на входе, список блоков на выходе.
// =====================================================================

private sealed interface Block
private data class Para(val text: String) : Block
private data class Heading(val level: Int, val text: String) : Block
private data class Item(val marker: String, val level: Int, val text: String) : Block
private data class Quote(val text: String) : Block
private data class Code(val lang: String, val code: String) : Block
private data class Table(val header: List<String>, val aligns: List<TextAlign>, val rows: List<List<String>>) : Block
private object Rule : Block

private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

// Таблица: строка с «|», под ней строка-разделитель из ---, дальше строки с «|».
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

private fun isTableStart(lines: List<String>, i: Int): Boolean =
    i + 1 < lines.size && lines[i].contains('|') && lines[i + 1].contains('|') &&
        lines[i + 1].contains('-') && TABLE_SEP.matches(lines[i + 1])

/** Разбить строку таблицы на ячейки. \| внутри ячейки остаётся символом |, <br> становится переносом строки. */
private fun splitRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.replace("\\|", "\u0000").split("|").map {
        it.replace("\u0000", "|").trim().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    }
}

private fun parseMarkdown(src: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.split("\n")
    val para = StringBuilder()
    var inQuote = false

    fun flush() {
        if (para.isNotEmpty()) {
            out.add(Para(para.toString()))
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        var quote = false

        // Окно кода появляется, когда после ``` уже есть перевод строки.
        // Пока ``` последняя строка текста, это ещё обычный текст.
        if (t.startsWith("```") && i < lines.size - 1) {
            flush()
            val lang = t.drop(3).trim().takeWhile { !it.isWhitespace() }
            val body = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                body.add(lines[i])
                i++
            }
            // i стоит на закрывающем ``` (или за концом: блок ещё не закрыт, идёт стрим).
            // Общий i++ внизу перешагнёт закрывающую строку.
            out.add(Code(lang, body.joinToString("\n")))
        } else {
            val h = HEADING.matchEntire(t)
            val li = LIST.matchEntire(line)
            when {
                isTableStart(lines, i) -> {
                    flush()
                    val header = splitRow(line)
                    val aligns = splitRow(lines[i + 1]).map {
                        when {
                            it.startsWith(":") && it.endsWith(":") -> TextAlign.Center
                            it.endsWith(":") -> TextAlign.End
                            else -> TextAlign.Start
                        }
                    }
                    var j = i + 2
                    val rows = ArrayList<List<String>>()
                    while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|')) {
                        rows.add(splitRow(lines[j]))
                        j++
                    }
                    // строки подгоняем под число столбцов шапки: при стриме последняя может быть недописана
                    val n = header.size
                    out.add(
                        Table(
                            header,
                            List(n) { aligns.getOrElse(it) { TextAlign.Start } },
                            rows.map { r -> List(n) { r.getOrElse(it) { "" } } },
                        )
                    )
                    i = j - 1 // общий i++ внизу цикла встанет на первую строку после таблицы
                }
                h != null -> {
                    flush()
                    out.add(Heading(h.groupValues[1].length, h.groupValues[2]))
                }
                RULE.matches(t) -> {
                    flush()
                    out.add(Rule)
                }
                t.startsWith(">") -> {
                    flush()
                    val text = t.removePrefix(">").trim()
                    val last = out.lastOrNull()
                    if (inQuote && last is Quote) out[out.lastIndex] = Quote(last.text + "\n" + text)
                    else out.add(Quote(text))
                    quote = true
                }
                li != null -> {
                    flush()
                    val indent = li.groupValues[1].length
                    val marker = li.groupValues[2]
                    out.add(
                        Item(
                            marker = if (marker[0].isDigit()) marker else "•",
                            level = ((indent + 2) / 4).coerceAtMost(4),
                            text = li.groupValues[3],
                        )
                    )
                }
                t.isEmpty() -> flush()
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(t)
                }
            }
        }
        inQuote = quote
        i++
    }
    flush()
    return out
}

// =====================================================================
// 2. Инлайн-разметка: **жирный**, *курсив*, `код`, ~~зачёркнутый~~, [текст](url)
//    Незакрытая разметка остаётся буквальным текстом, пока не закроется (важно для стрима).
// =====================================================================

private const val CODE_TAG = "code"

/** Цвет плашки инлайн-кода: у пользователя светлее, чтобы читалась на фоне его пузыря. */
private val LocalCodeBg = compositionLocalOf { Color.Unspecified } // реальный цвет задаёт Markdown()

private fun AnnotatedString.Builder.md(src: String, link: Color) {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i + 1) {
                // фон рисуем сами со скруглением (см. MdText), SpanStyle.background скруглять не умеет.
                // Аннотация только помечает диапазон.
                val from = length
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)) {
                    append(src.substring(i + 1, end))
                }
                addStringAnnotation(CODE_TAG, "", from, length)
                i = end + 1
                continue
            }
        } else if (src.startsWith("**", i) || src.startsWith("__", i)) {
            val mark = src.substring(i, i + 2)
            val end = src.indexOf(mark, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { md(src.substring(i + 2, end), link) }
                i = end + 2
            } else {
                append(mark)
                i += 2
            }
            continue
        } else if (src.startsWith("~~", i)) {
            val end = src.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { md(src.substring(i + 2, end), link) }
                i = end + 2
            } else {
                append("~~")
                i += 2
            }
            continue
        } else if ((c == '*' || c == '_') && i + 1 < src.length && !src[i + 1].isWhitespace() && src[i + 1] != c) {
            // подчёркивание внутри слова (snake_case) курсивом не считаем
            val startOk = c == '*' || i == 0 || !src[i - 1].isLetterOrDigit()
            val end = if (startOk) src.indexOf(c, i + 1) else -1
            val endOk = end > i + 1 && !src[end - 1].isWhitespace() &&
                (c == '*' || end + 1 >= src.length || !src[end + 1].isLetterOrDigit())
            if (endOk) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { md(src.substring(i + 1, end), link) }
                i = end + 1
                continue
            }
        } else if (c == '[') {
            val mid = src.indexOf("](", i + 1)
            val close = if (mid > i) src.indexOf(')', mid + 2) else -1
            if (mid > i && close > mid) {
                // ссылка пока только выглядит как ссылка, перехода по тапу нет
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = link)) {
                    md(src.substring(i + 1, mid), link)
                }
                i = close + 1
                continue
            }
        }
        append(c)
        i++
    }
}

// =====================================================================
// 3. Рисование
// =====================================================================

/** Текст с markdown. Оборачивай в SelectionContainer снаружи, если нужно выделение. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, codeBg: Color = ColCodeBg) {
    val blocks = remember(text) { parseMarkdown(text) }
    CompositionLocalProvider(LocalCodeBg provides codeBg) {
        Column(modifier) {
            blocks.forEachIndexed { idx, b ->
                val gap = when {
                    idx == 0 -> 0.dp
                    b is Item && blocks[idx - 1] is Item -> 4.dp
                    else -> 10.dp
                }
                Box(Modifier.padding(top = gap)) { RenderBlock(b) }
            }
        }
    }
}

@Composable
private fun RenderBlock(b: Block) {
    when (b) {
        is Para -> MdText(b.text, 16.sp)
        is Heading -> MdText(
            b.text,
            when (b.level) {
                1 -> 22.sp
                2 -> 20.sp
                3 -> 18.sp
                else -> 16.sp
            },
            weight = FontWeight.SemiBold,
        )
        is Item -> Row {
            Spacer(Modifier.width((b.level * 18).dp))
            Box(Modifier.width(22.dp)) { Text(b.marker, color = ColMuted, fontSize = 16.sp) }
            MdText(b.text, 16.sp, modifier = Modifier.weight(1f))
        }
        is Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(ColMuted.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(10.dp))
            MdText(b.text, 16.sp, color = ColMuted, modifier = Modifier.weight(1f))
        }
        Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(ColUser))
        is Code -> CodeBlock(b)
        is Table -> TableView(b)
    }
}

@Composable
private fun MdText(
    text: String,
    size: TextUnit,
    color: Color = ColText,
    weight: FontWeight? = null,
    align: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    val link = ColLink
    val styled = remember(text, link) { buildAnnotatedString { md(text, link) } }
    val codeBg = LocalCodeBg.current
    // раскладку держим в обычном массиве, не в state: рисование идёт после layout в том же кадре
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        styled,
        color = color,
        fontSize = size,
        lineHeight = size * 1.5f,
        fontWeight = weight,
        textAlign = align,
        onTextLayout = { layout[0] = it },
        modifier = modifier.drawBehind {
            val l = layout[0] ?: return@drawBehind
            drawCodeBackgrounds(l, styled, codeBg)
        },
    )
}

/**
 * Скруглённые плашки под инлайн-кодом. Для каждой строки, которую занимает фрагмент,
 * рисуем свой прямоугольник: левый край по первому символу, правый по последнему.
 * Плашка рисуется под текстом, поэтому системное выделение поверх неё остаётся видимым.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCodeBackgrounds(
    layout: TextLayoutResult,
    text: AnnotatedString,
    color: Color,
) {
    val padX = 2.dp.toPx()
    val padY = 2.dp.toPx()
    val radius = CornerRadius(5.dp.toPx())
    val total = layout.layoutInput.text.length
    for (r in text.getStringAnnotations(CODE_TAG, 0, text.length)) {
        val start = r.start.coerceIn(0, total)
        val end = r.end.coerceIn(0, total)
        if (end <= start) continue
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line))
            if (to <= from) continue
            val a = layout.getBoundingBox(from)
            val b = layout.getBoundingBox(to - 1)
            val left = minOf(a.left, b.left) - padX
            val right = maxOf(a.right, b.right) + padX
            val top = layout.getLineTop(line) + padY
            val bottom = layout.getLineBottom(line) - padY
            drawRoundRect(color, Offset(left, top), Size(right - left, bottom - top), radius)
        }
    }
}

/** Позиции линий сетки; заполняются при измерении, читаются при рисовании (оно идёт после layout). */
private class Grid {
    var xs = IntArray(0)
    var ys = IntArray(0)
}

@Composable
private fun TableCell(text: String, align: TextAlign, header: Boolean) {
    MdText(
        text,
        14.sp,
        weight = if (header) FontWeight.SemiBold else null,
        align = align,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Таблица: серое окно со скруглением, шапка чуть светлее, тонкая сетка.
 * Ширина столбца по самой длинной ячейке (но не больше 260dp, дальше перенос строк),
 * вся таблица прокручивается вбок, если не влезла.
 */
@Composable
private fun TableView(t: Table) {
    val grid = remember { Grid() }
    val cols = t.header.size
    val lineColor = ColMuted.copy(alpha = 0.28f)
    val headerBg = ColCodeBg
    val maxColPx = with(LocalDensity.current) { 260.dp.roundToPx() }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ColCode)
            .horizontalScroll(rememberScrollState())
    ) {
        Layout(
            content = {
                t.header.forEachIndexed { c, cell -> TableCell(cell, t.aligns[c], header = true) }
                t.rows.forEach { row -> for (c in 0 until cols) TableCell(row[c], t.aligns[c], header = false) }
            },
            modifier = Modifier.drawBehind {
                val xs = grid.xs
                val ys = grid.ys
                if (ys.size > 1 && xs.size > 1) {
                    drawRect(headerBg, Offset.Zero, Size(this.size.width, ys[1].toFloat()))
                    val w = 1.dp.toPx()
                    for (k in 1 until ys.size - 1) drawLine(lineColor, Offset(0f, ys[k].toFloat()), Offset(this.size.width, ys[k].toFloat()), w)
                    for (k in 1 until xs.size - 1) drawLine(lineColor, Offset(xs[k].toFloat(), 0f), Offset(xs[k].toFloat(), this.size.height), w)
                }
            },
        ) { measurables, _ ->
            val rowCount = measurables.size / cols
            val widths = IntArray(cols)
            for (r in 0 until rowCount) for (c in 0 until cols) {
                val w = measurables[r * cols + c].maxIntrinsicWidth(Int.MAX_VALUE)
                widths[c] = maxOf(widths[c], minOf(w, maxColPx))
            }
            val placeables = measurables.mapIndexed { idx, m ->
                m.measure(Constraints(minWidth = widths[idx % cols], maxWidth = widths[idx % cols]))
            }
            val heights = IntArray(rowCount)
            placeables.forEachIndexed { idx, p -> heights[idx / cols] = maxOf(heights[idx / cols], p.height) }
            val xs = IntArray(cols + 1)
            for (c in 0 until cols) xs[c + 1] = xs[c] + widths[c]
            val ys = IntArray(rowCount + 1)
            for (r in 0 until rowCount) ys[r + 1] = ys[r] + heights[r]
            grid.xs = xs
            grid.ys = ys
            layout(xs[cols], ys[rowCount]) {
                placeables.forEachIndexed { idx, p -> p.placeRelative(xs[idx % cols], ys[idx / cols]) }
            }
        }
    }
}

/** Серое окно: сверху язык (расширение) и «копировать», ниже код с горизонтальной прокруткой. */
@Composable
private fun CodeBlock(b: Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ColCode)) {
        // шапку окна выделять не нужно, иначе «копировать» попадёт в выделенный текст
        DisableSelection {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    b.lang.ifBlank { "code" },
                    color = ColMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (copied) "скопировано" else "копировать",
                    color = ColMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(b.code))
                            copied = true
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            b.code,
            color = ColText,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}
