package com.agentos.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The parts bin (docs/DESIGN.md): raised keys, plain text buttons, moulded panels, recessed slots
 * and LEDs. Nothing has a sharp corner, and icons never sit in a container.
 */

/** Key cap colours: the cap, the darker edge seen below it, and the label. */
enum class KeyColor { Light, Yellow, Red, Blue, Green, Orange }

private class Cap(val cap: Color, val edge: Color, val text: Color)

@Composable
private fun capFor(c: KeyColor): Cap = when (c) {
    KeyColor.Light -> plate.let { Cap(it.cap, it.capEdge, it.capText) }
    KeyColor.Yellow -> Cap(Ubiq.Yellow, Ubiq.YellowEdge, Ubiq.Ink)
    KeyColor.Red -> Cap(Ubiq.Red, Ubiq.RedEdge, Color.White)
    KeyColor.Blue -> Cap(Ubiq.Blue, Ubiq.BlueEdge, Color.White)
    KeyColor.Green -> Cap(Ubiq.Green, Ubiq.GreenEdge, Color.White)
    KeyColor.Orange -> Cap(Ubiq.Orange, Ubiq.OrangeEdge, Ubiq.Ink)
}

/**
 * Draws a moulded part: the [edge] colour peeking out [depth] below, the cap on top with a faint
 * highlight, and a soft shadow. [press] moves the cap down towards its edge.
 */
fun Modifier.moulded(cap: Color, edge: Color, radius: Dp, depth: Dp, press: Dp = 0.dp, shadow: Boolean = true): Modifier = drawBehind {
    val d = depth.toPx(); val p = press.toPx().coerceIn(0f, d)
    val h = size.height - d
    val r = CornerRadius(radius.toPx())
    if (shadow) drawRoundRect(Color.Black.copy(alpha = 0.16f), topLeft = Offset(0f, d + 2.dp.toPx()), size = Size(size.width, h), cornerRadius = r)
    drawRoundRect(edge, topLeft = Offset(0f, d), size = Size(size.width, h), cornerRadius = r)
    drawRoundRect(Brush.verticalGradient(listOf(lerp(cap, Color.White, 0.16f), cap), startY = p, endY = p + h * 0.55f),
        topLeft = Offset(0f, p), size = Size(size.width, h), cornerRadius = r)
    drawRoundRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(r.x * 0.6f, p + 1f), size = Size(size.width - r.x * 1.2f, 1.2f))
}

/** Shared modifier shapes for other packages. */
object UbiqParts {
    /** A raised cap for a message: the moulded part plus room for its edge. */
    fun raisedCap(cap: Color, edge: Color): Modifier = Modifier.moulded(cap, edge, 16.dp, 3.dp).padding(bottom = 3.dp)
}

/** A raised key. It sinks when pressed. */
@Composable
fun Key(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: KeyColor = KeyColor.Light,
    enabled: Boolean = true, icon: ImageVector? = null, led: Color? = null, compact: Boolean = false,
) {
    val c = capFor(color)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val depth = 4.dp
    val press by animateDpAsState(if (pressed) 3.dp else 0.dp, tween(70), label = "press")
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .semantics { role = Role.Button }
            .clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .moulded(c.cap, c.edge, 11.dp, depth, press)
            .padding(bottom = depth)
            .offset(y = press)
            .defaultMinSize(minHeight = if (compact) 27.dp else 34.dp)
            .padding(horizontal = if (compact) 12.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (led != null) Led(led, size = 7.dp)
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.text)
            Text(text, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A round raised key holding only an icon (send, stop). */
@Composable
fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit, color: KeyColor = KeyColor.Light, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val c = capFor(color)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateDpAsState(if (pressed) 3.dp else 0.dp, tween(70), label = "press")
    Box(
        modifier.size(size, size + 4.dp)
            .semantics { role = Role.Button; contentDescription = description }
            .clickable(src, indication = null, onClick = onClick)
            .moulded(c.cap, c.edge, size / 2, 4.dp, press)
            .padding(bottom = 4.dp).offset(y = press),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.45f), tint = c.text) }
}

/** A button with no shape at all: just its label. */
@Composable
fun TextKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface,
            enabled: Boolean = true, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .defaultMinSize(minHeight = 30.dp).padding(horizontal = 4.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** A small round light. [glow] adds a halo, for lit states. */
@Composable
fun Led(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp, glow: Boolean = true) {
    Box(modifier.size(size).drawBehind {
        val r = this.size.minDimension / 2
        if (glow) drawCircle(color.copy(alpha = 0.28f), radius = r * 1.9f)
        drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.55f), color), center = Offset(r * 0.75f, r * 0.7f), radius = r * 1.2f), radius = r)
    })
}

/** The rounded panel shape used for cards. */
val PebbleShape = RoundedCornerShape(20.dp)

/** A moulded panel sitting on the plate. */
@Composable
fun Pebble(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = plate.plateEdge
    Column(
        modifier.moulded(color, edge, 20.dp, 3.dp, shadow = false)
            .padding(bottom = 3.dp)
            .clip(PebbleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

/** A recessed slot pressed into the plate: inputs and search fields. */
fun Modifier.slot(color: Color, rim: Color, radius: Dp = 22.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(color)
    .drawBehind {
        // An inner shadow along the top edge reads as a dip in the plastic.
        drawRect(Brush.verticalGradient(listOf(rim.copy(alpha = 0.55f), Color.Transparent), endY = 6.dp.toPx()))
        // The lower lip catches the light, so the slot reads on a shaded plate too.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.09f)), startY = size.height - 4.dp.toPx(), endY = size.height))
    }

/** Primary action: a yellow key. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    Key(text, onClick, modifier, KeyColor.Yellow, enabled, icon)

/** Secondary action: a light key. */
@Composable
fun SoftPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    Key(text, onClick, modifier, KeyColor.Light, enabled, icon)

/** Tertiary action: plain text. */
@Composable
fun OutlinePillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) =
    TextKey(text, onClick, modifier, color)

/** An icon button: the icon alone, no circle behind it. [container] is ignored and kept for older callers. */
@Composable
fun BubbleIcon(icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp,
               @Suppress("UNUSED_PARAMETER") container: Color = Color.Transparent, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.size(size).clip(CircleShape).clickable(onClick = onClick).semantics { role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(size * 0.62f), tint = tint)
    }
}

/** A kind of thing (notes, memories, jobs), shown by its icon in its accent colour, with no badge behind it. */
@Composable
fun KindBadge(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val tint = if (isSystemInDarkTheme()) lerp(accent, Color.White, 0.15f) else lerp(accent, Color.Black, 0.12f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.8f), tint = tint) }
}

/** A short status line with an LED. */
@Composable
fun StatusLine(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Led(if (ok) Ubiq.Lit else Ubiq.Yellow)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Screen header: a back icon, a Space Grotesk title, optional actions. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, backIcon: ImageVector? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null && backIcon != null) BubbleIcon(backIcon, "Back", onBack)
        FitText(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
        actions()
    }
}

/** Text fields look like slots: recessed, no outline. */
@Composable
fun brandFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.onSurface,
)
