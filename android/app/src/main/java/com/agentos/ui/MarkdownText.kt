package com.agentos.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The agent's replies as light markdown: paragraphs, headings, lists, code blocks and tables, with
 * bold, italic, `code` and links inline. Tables are flat, like a table in a doc: a hairline outline,
 * a quiet header row and hairlines between rows. Too many columns to fit scroll sideways.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge,
                 color: Color = MaterialTheme.colorScheme.onBackground, mark: String? = null, markColor: Color = Color.Unspecified) {
    val blocks = remember(text) { Md.parse(text) }
    // Words found by a chat search are marked wherever they show (not inside code or tables).
    fun inline(s: String) = Md.mark(Md.inline(s), mark, markColor)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Md.Block.Para -> Text(inline(b.text), style = style, color = color)
                is Md.Block.Heading -> Text(inline(b.text), style = style.copy(fontWeight = FontWeight.SemiBold), color = color)
                is Md.Block.Items -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    b.items.forEachIndexed { i, it ->
                        Row {
                            Text(if (b.ordered) "${i + 1}." else "•", Modifier.width(18.dp), style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(inline(it), Modifier.weight(1f), style = style, color = color)
                        }
                    }
                }
                is Md.Block.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(inset).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                    style = style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.85f), color = color, softWrap = false)
                is Md.Block.Table -> MdTable(b, style, color)
            }
        }
    }
}

@Composable
private fun MdTable(t: Md.Block.Table, style: TextStyle, color: Color) {
    val cell = style.copy(fontSize = style.fontSize * 0.88f, lineHeight = style.lineHeight * 0.88f)
    val head = cell.copy(fontWeight = FontWeight.SemiBold)
    val cols = t.header.size
    val line = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(10.dp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pad = if (cols > 2) 8.dp else 10.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Column widths: never narrower than the column's longest word (no words split mid-way), never
        // wider than its longest cell on one line; the space left between those is shared out. When even
        // the longest words don't fit, the table keeps readable widths and scrolls sideways.
        val widths = remember(t, maxWidth, cell) {
            with(density) {
                val all = listOf(t.header) + t.rows
                fun w(text: String, st: TextStyle) = measurer.measure(text, st, softWrap = false, maxLines = 1).size.width.toDp()
                val padding = pad * 2
                val min = (0 until cols).map { c ->
                    all.mapIndexed { i, r ->
                        Md.plain(r.getOrElse(c) { "" }).split(' ').maxOfOrNull { word -> w(word, if (i == 0) head else cell) } ?: 0.dp
                    }.max() + padding
                }
                val max = (0 until cols).map { c ->
                    all.mapIndexed { i, r -> w(Md.plain(r.getOrElse(c) { "" }), if (i == 0) head else cell) }.max().coerceAtMost(220.dp) + padding
                }.mapIndexed { c, m -> maxOf(m, min[c]) }
                val avail = maxWidth - 2.dp
                val sumMin = min.fold(0.dp) { a, b -> a + b }
                val sumMax = max.fold(0.dp) { a, b -> a + b }
                when {
                    sumMin > avail -> max.map { it.coerceAtMost(180.dp) }.mapIndexed { c, m -> maxOf(m, min[c]) }
                    sumMax <= avail -> max.map { it + (avail - sumMax) / cols }
                    else -> { val f = (avail - sumMin) / (sumMax - sumMin); min.mapIndexed { c, m -> m + (max[c] - m) * f } }
                }
            }
        }
        Box(Modifier.clip(shape).border(1.dp, line, shape).horizontalScroll(rememberScrollState())) {
            Column {
                @Composable
                fun TableRow(cells: List<String>, header: Boolean) {
                    Row(Modifier.height(IntrinsicSize.Min).then(if (header) Modifier.background(inset) else Modifier)) {
                        for (c in 0 until cols) {
                            Text(Md.inline(cells.getOrElse(c) { "" }), Modifier.width(widths[c]).padding(horizontal = pad, vertical = 7.dp),
                                style = if (header) head else cell,
                                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else color,
                                textAlign = when (t.align.getOrNull(c)) { 'r' -> TextAlign.End; 'c' -> TextAlign.Center; else -> TextAlign.Start })
                        }
                    }
                }
                TableRow(t.header, true)
                t.rows.forEach { r ->
                    HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, b -> a + b }), thickness = 1.dp, color = line)
                    TableRow(r, false)
                }
            }
        }
    }
}

/** A small markdown parser for model replies: blocks first, then inline spans. */
object Md {
    sealed class Block {
        data class Para(val text: String) : Block()
        data class Heading(val text: String) : Block()
        data class Items(val items: List<String>, val ordered: Boolean) : Block()
        data class Code(val text: String) : Block()
        /** [align] per column: 'l', 'c' or 'r', from the separator row's colons. */
        data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<Char>) : Block()
    }

    private val bullet = Regex("^\\s*[-*+]\\s+(.*)")
    private val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)")
    private val heading = Regex("^#{1,6}\\s+(.*)")
    private val separator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }

    fun parse(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>(); i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code += lines[i]; i++ }
                    out += Block.Code(code.joinToString("\n").trimEnd())
                }
                l.contains('|') && i + 1 < lines.size && separator.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    flush()
                    val header = cells(l)
                    val align = cells(lines[i + 1]).map { s -> when { s.startsWith(":") && s.endsWith(":") -> 'c'; s.endsWith(":") -> 'r'; else -> 'l' } }
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                    out += Block.Table(header, rows, align)
                    continue
                }
                heading.matches(l) -> { flush(); out += Block.Heading(heading.find(l)!!.groupValues[1]) }
                bullet.matches(l) || numbered.matches(l) -> {
                    flush()
                    val ordered = numbered.matches(l)
                    val re = if (ordered) numbered else bullet
                    val items = mutableListOf<String>()
                    while (i < lines.size && re.matches(lines[i])) {
                        var item = re.find(lines[i])!!.groupValues[1]; i++
                        // Wrapped continuation lines (indented, not a new item) join the item.
                        while (i < lines.size && lines[i].startsWith("  ") && !bullet.matches(lines[i]) && !numbered.matches(lines[i])) {
                            item += " " + lines[i].trim(); i++
                        }
                        items += item
                    }
                    out += Block.Items(items, ordered)
                    continue
                }
                l.isBlank() -> flush()
                l.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> flush()
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l.trimEnd()) }
            }
            i++
        }
        flush()
        return out
    }

    private val span = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|(?<![\\w*])\\*(\\S[^*]*?)\\*(?![\\w*])|(?<![\\w_])_(\\S[^_]*?)_(?![\\w_])")

    fun inline(s: String): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (m in span.findAll(s)) {
            append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[1].ifEmpty { g[2] })) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[3]) }
                g[4].isNotEmpty() -> append(g[4])
                g[6].isNotEmpty() || g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6].ifEmpty { g[7] }) }
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }

    /** [a] with every [q] (any case) given a [color] background; [a] itself when there is nothing to mark. */
    fun mark(a: AnnotatedString, q: String?, color: Color): AnnotatedString {
        if (q.isNullOrEmpty()) return a
        var i = a.text.indexOf(q, ignoreCase = true)
        if (i < 0) return a
        return androidx.compose.ui.text.AnnotatedString.Builder(a).apply {
            while (i >= 0) { addStyle(SpanStyle(background = color), i, i + q.length); i = a.text.indexOf(q, i + q.length, ignoreCase = true) }
        }.toAnnotatedString()
    }

    /** Text without markup, for measuring cells. */
    fun plain(s: String): String = inline(s).text
}
