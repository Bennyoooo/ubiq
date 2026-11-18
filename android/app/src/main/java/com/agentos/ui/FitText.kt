package com.agentos.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified

/**
 * A title or reading that never overloads its space: it first shrinks, a step at a time, down to [minScale] of its
 * size, and only then ends in "…". Nothing is drawn until it fits, so it never flashes at the wrong size.
 * Use it for every big or single-line text whose words come from data (chat names, app names, widget readings).
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    minScale: Float = 0.7f,
) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    var ready by remember(text, style) { mutableStateOf(false) }
    val s = if (scale == 1f) style else style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
        letterSpacing = if (style.letterSpacing.isSpecified) style.letterSpacing * scale else style.letterSpacing,
    )
    Text(text, modifier.drawWithContent { if (ready) drawContent() }, style = s, color = color, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && scale > minScale) scale = maxOf(minScale, scale - 0.06f) else ready = true
        })
}
