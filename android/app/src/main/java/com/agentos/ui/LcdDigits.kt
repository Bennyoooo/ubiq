package com.agentos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

// Seven-segment LCD digits, the Ubiq panel's readout (Live mode's call timer; the ROM's home clock).

private val SEGMENTS = mapOf(0 to "abcdef", 1 to "bc", 2 to "abged", 3 to "abgcd", 4 to "fgbc", 5 to "afgcd", 6 to "afgedc", 7 to "abc", 8 to "abcdefg", 9 to "abcfgd")
private val ENDS = mapOf(
    'a' to floatArrayOf(7f, 4f, 23f, 4f), 'b' to floatArrayOf(26f, 8f, 26f, 22f), 'c' to floatArrayOf(26f, 30f, 26f, 44f),
    'd' to floatArrayOf(7f, 48f, 23f, 48f), 'e' to floatArrayOf(4f, 30f, 4f, 44f), 'f' to floatArrayOf(4f, 8f, 4f, 22f),
    'g' to floatArrayOf(7f, 26f, 23f, 26f),
)

/** Four digits on a 30 x 52 grid each, with a colon after the second, slanted 7 degrees like a real LCD. */
internal fun DrawScope.drawDigits(digits: List<Int?>, ink: Color, offAlpha: Float) {
    val s = size.height / 52f
    val digitW = 30f * s; val gap = 4.dp.toPx(); val colonW = 10.dp.toPx()
    val skew = kotlin.math.tan(Math.toRadians(7.0)).toFloat()
    val stroke = 5.5f * s
    fun pt(x: Float, y: Float, ox: Float) = Offset(ox + x * s + (size.height - y * s - size.height / 2) * skew, y * s)
    var x = 0f
    digits.forEachIndexed { i, d ->
        val on = d?.let { SEGMENTS[it] } ?: ""
        for ((k, e) in ENDS) {
            drawLine(if (k in on) ink else ink.copy(alpha = offAlpha), pt(e[0], e[1], x), pt(e[2], e[3], x), stroke, StrokeCap.Round)
        }
        x += digitW + gap
        if (i == 1) {
            val cx = x + colonW / 2 - gap / 2
            drawCircle(ink, 3.dp.toPx(), Offset(cx + 6f * s * skew, size.height * 0.32f))
            drawCircle(ink, 3.dp.toPx(), Offset(cx - 6f * s * skew, size.height * 0.68f))
            x += colonW
        }
    }
}
