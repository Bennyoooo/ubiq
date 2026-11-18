package com.agentos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * The prototype's own icons (docs/prototypes): bare coloured glyphs with a gradient and a darker
 * moulded edge under them, never a flat white system icon. Drawn on a 24 x 24 grid like the SVGs.
 */
enum class Glyph { Mic, Search, Notes }

private class Tone(val top: Color, val mid: Color, val bottom: Color, val edge: Color)
private val RedTone = Tone(Color(0xFFFF4A3D), Ubiq.Red, Color(0xFFAD0B0D), Ubiq.RedEdge)
private val BlueTone = Tone(Color(0xFF2F9CEC), Ubiq.Blue, Color(0xFF005994), Ubiq.BlueEdge)
private val YellowTone = Tone(Color(0xFFFFE45C), Ubiq.Yellow, Color(0xFFE5B400), Ubiq.YellowEdge)

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val tone = when (glyph) { Glyph.Mic -> RedTone; Glyph.Search -> BlueTone; Glyph.Notes -> YellowTone }
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val fill = Brush.verticalGradient(0f to tone.top, 0.55f to tone.mid, 1f to tone.bottom, startY = 2 * u, endY = 22 * u)
        // The moulded edge: the same shape in the edge colour, 1.5 units lower; then a soft shadow.
        translate(top = 2.2f * u) { draw(glyph, u, SolidOrBrush.Solid(Color.Black.copy(alpha = 0.22f))) }
        translate(top = 1.5f * u) { draw(glyph, u, SolidOrBrush.Solid(tone.edge)) }
        draw(glyph, u, SolidOrBrush.Grad(fill))
    }
}

/** A glyph you can tap, with a touch target around it. */
@Composable
fun GlyphButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 22.dp, target: Dp = 34.dp) {
    Box(modifier.size(target).clip(CircleShape).clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center) { GlyphIcon(glyph, size = size) }
}

private sealed class SolidOrBrush { class Solid(val c: Color) : SolidOrBrush(); class Grad(val b: Brush) : SolidOrBrush() }

private fun DrawScope.draw(g: Glyph, u: Float, paint: SolidOrBrush) {
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = when (paint) {
        is SolidOrBrush.Solid -> drawRoundRect(paint.c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
        is SolidOrBrush.Grad -> drawRoundRect(paint.b, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
    }
    fun line(path: Path, width: Float) = when (paint) {
        is SolidOrBrush.Solid -> drawPath(path, paint.c, style = Stroke(width * u, cap = StrokeCap.Round))
        is SolidOrBrush.Grad -> drawPath(path, paint.b, style = Stroke(width * u, cap = StrokeCap.Round))
    }
    when (g) {
        Glyph.Mic -> {
            rr(8f, 2f, 8f, 13.5f, 4f)
            line(Path().apply {
                moveTo(5 * u, 11.5f * u); cubicTo(5 * u, 20.8f * u, 19 * u, 20.8f * u, 19 * u, 11.5f * u)
                moveTo(12 * u, 18.5f * u); lineTo(12 * u, 22 * u)
            }, 2.2f)
        }
        Glyph.Search -> {
            line(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(10.5f * u, 10.5f * u), 6.5f * u)) }, 3.4f)
            line(Path().apply { moveTo(15.5f * u, 15.5f * u); lineTo(21 * u, 21 * u) }, 4f)
        }
        Glyph.Notes -> {
            rr(3.5f, 2.5f, 17f, 19f, 3f)
            if (paint is SolidOrBrush.Grad) {
                val ink = Color(0xFF16161A).copy(alpha = 0.72f)
                listOf(8f to 16f, 8f to 16f, 8f to 13f).forEachIndexed { i, (a, b) ->
                    val y = (8 + i * 4) * u
                    drawLine(ink, Offset(a * u, y), Offset(b * u, y), 1.8f * u, StrokeCap.Round)
                }
            }
        }
    }
}
