package com.agentos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.R

/**
 * The Ubiq look (docs/DESIGN.md, docs/prototypes/ubiq-rom.html): a spaceship control panel moulded
 * in toy-brick plastic. A grey plate, raised keys with a darker edge underneath, LEDs, and five
 * brick colours. Light and dark are only the phone's light and dark modes.
 */
object Ubiq {
    val Red = Color(0xFFD01012); val RedEdge = Color(0xFF8E0A0C)
    val Yellow = Color(0xFFFFCD03); val YellowEdge = Color(0xFFC29A00)
    val Blue = Color(0xFF006CB7); val BlueEdge = Color(0xFF00497C)
    val Green = Color(0xFF00852B); val GreenEdge = Color(0xFF005A1D)
    val Orange = Color(0xFFF57D20); val OrangeEdge = Color(0xFFB5570F)
    /** A lit green LED (finished). */
    val Lit = Color(0xFF16D873)
    val Ink = Color(0xFF16161A)
    val Paper = Color(0xFFF4F4EF)
    /** The dark deck: recents, the control panel, always-on. */
    val Deck = Color(0xFF17171B)
}

/** Plate, key and edge colours for the current mode. */
class Plate(
    val plate: Color, val plateLow: Color, val plateEdge: Color,
    val cap: Color, val capEdge: Color, val capText: Color,
    val ink: Color, val muted: Color,
)

private val LightPlate = Plate(
    plate = Color(0xFFE6E6E0), plateLow = Color(0xFFD6D6CF), plateEdge = Color(0xFFBCBCB3),
    cap = Color(0xFFF6F6F1), capEdge = Color(0xFFA9A9A0), capText = Ubiq.Ink,
    ink = Ubiq.Ink, muted = Color(0xFF5E5E61),
)
private val DarkPlate = Plate(
    plate = Color(0xFF222226), plateLow = Color(0xFF17171B), plateEdge = Color(0xFF000000),
    cap = Color(0xFF3A3A42), capEdge = Color(0xFF0C0C0F), capText = Ubiq.Paper,
    ink = Ubiq.Paper, muted = Color(0xFFA9A9A6),
)

/** The plate colours for the phone's current mode. */
val plate: Plate
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DarkPlate else LightPlate

/**
 * Older names, kept so screens not yet redesigned still compile. They now point at the brick
 * colours: needs-you is yellow, done is the lit green, working is white.
 */
object Brand {
    val Tangerine = Ubiq.Orange
    val TangerineDeep = Ubiq.OrangeEdge
    val TangerineLight = Ubiq.Orange
    val Butter = Ubiq.Yellow
    val Mint = Ubiq.Lit
    val Lilac = Ubiq.Blue
    val Blush = Ubiq.Orange
    val Ink = Ubiq.Ink
    val Cream = LightPlate.plate
    val Night = Ubiq.Deck
}

private val LightColors = lightColorScheme(
    primary = Ubiq.Ink, onPrimary = LightPlate.cap,
    primaryContainer = Ubiq.Yellow, onPrimaryContainer = Ubiq.Ink,
    inversePrimary = Ubiq.Yellow,
    secondary = Ubiq.Blue, onSecondary = Color.White,
    secondaryContainer = LightPlate.cap, onSecondaryContainer = Ubiq.Ink,
    tertiary = Ubiq.Green, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EBDC), onTertiaryContainer = Ubiq.GreenEdge,
    background = LightPlate.plate, onBackground = Ubiq.Ink,
    surface = Color(0xFFEFEFEA), onSurface = Ubiq.Ink,
    surfaceVariant = LightPlate.plateLow, onSurfaceVariant = LightPlate.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Ubiq.Deck, inverseOnSurface = Ubiq.Paper,
    error = Ubiq.Red, onError = Color.White,
    errorContainer = Color(0xFFF6D3D3), onErrorContainer = Ubiq.RedEdge,
    outline = LightPlate.plateEdge, outlineVariant = LightPlate.plateLow, scrim = Color.Black,
    surfaceBright = LightPlate.cap, surfaceDim = LightPlate.plateLow,
    surfaceContainerLowest = LightPlate.cap, surfaceContainerLow = Color(0xFFEDEDE8),
    surfaceContainer = Color(0xFFEAEAE4), surfaceContainerHigh = Color(0xFFE0E0DA),
    surfaceContainerHighest = LightPlate.plateLow,
)

private val DarkColors = darkColorScheme(
    primary = Ubiq.Paper, onPrimary = Ubiq.Ink,
    primaryContainer = Ubiq.Yellow, onPrimaryContainer = Ubiq.Ink,
    inversePrimary = Ubiq.Yellow,
    secondary = Color(0xFF4FA3E0), onSecondary = Ubiq.Ink,
    secondaryContainer = DarkPlate.cap, onSecondaryContainer = Ubiq.Paper,
    tertiary = Ubiq.Lit, onTertiary = Ubiq.Ink,
    tertiaryContainer = Color(0xFF1D3A27), onTertiaryContainer = Ubiq.Lit,
    background = DarkPlate.plate, onBackground = Ubiq.Paper,
    surface = Color(0xFF2B2B30), onSurface = Ubiq.Paper,
    surfaceVariant = DarkPlate.plateLow, onSurfaceVariant = DarkPlate.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = Ubiq.Paper, inverseOnSurface = Ubiq.Ink,
    error = Color(0xFFFF5A55), onError = Ubiq.Ink,
    errorContainer = Color(0xFF4A1414), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A4A52), outlineVariant = Color(0xFF34343A), scrim = Color.Black,
    surfaceBright = DarkPlate.cap, surfaceDim = DarkPlate.plateLow,
    surfaceContainerLowest = DarkPlate.plateLow, surfaceContainerLow = Color(0xFF26262B),
    surfaceContainer = Color(0xFF2B2B30), surfaceContainerHigh = Color(0xFF323238),
    surfaceContainerHighest = DarkPlate.cap,
)

// Static weights cut from the variable fonts by scripts/brand/fonts.py: Compose applies variation
// settings to resource variable fonts unreliably (one cached typeface per resource).

/** Numbers, titles and short names. SIL OFL 1.1. */
val SpaceGrotesk = FontFamily(
    Font(R.font.spacegrotesk_w500, FontWeight.Medium), Font(R.font.spacegrotesk_w600, FontWeight.SemiBold),
    Font(R.font.spacegrotesk_w700, FontWeight.Bold),
)
/** Everything you read. SIL OFL 1.1. */
val Inter = FontFamily(
    Font(R.font.inter_w400, FontWeight.Normal), Font(R.font.inter_w500, FontWeight.Medium),
    Font(R.font.inter_w600, FontWeight.SemiBold),
)

private fun display(size: Int, line: Int, w: FontWeight = FontWeight.SemiBold, track: Double = -0.02) =
    TextStyle(fontFamily = SpaceGrotesk, fontWeight = w, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (track * size).sp)

private fun text(size: Int, line: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = w, fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp)

// Sizes are the prototype's own (docs/prototypes), in canvas units (see [CANVAS_WIDTH]).
private val UbiqTypography = Typography(
    displayLarge = display(44, 48), displayMedium = display(38, 42), displaySmall = display(36, 38),
    // Page titles are kept modest (user feedback 2026-11-08: headlines were too big and a long chat name showed one letter).
    headlineLarge = display(24, 28), headlineMedium = display(21, 25), headlineSmall = display(19, 23),
    titleLarge = display(17, 21), titleMedium = text(15, 20, FontWeight.SemiBold), titleSmall = text(14, 19, FontWeight.SemiBold),
    bodyLarge = text(14, 20), bodyMedium = text(13, 19, FontWeight.Medium), bodySmall = text(12, 16, FontWeight.Medium),
    labelLarge = text(14, 18, FontWeight.SemiBold), labelMedium = text(12, 15, FontWeight.Medium),
    labelSmall = text(11, 13, FontWeight.Medium),
)

/** Nothing has a sharp corner: keys are rounded, panels more so. */
private val UbiqShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Every screen is designed on the prototype's phone, whose screen is 286 units wide
 * (docs/prototypes), and all sizes in this app are in those units. The theme scales them to the
 * real screen (411 dp on a Pixel 9, so x1.44), keeping the prototype's proportions: the clock,
 * keys and text take the same share of the screen as in the design.
 */
const val CANVAS_WIDTH = 286f

/** All text a step smaller than the prototype's sizes; keys, panels and icons keep theirs. */
const val TEXT_SCALE = 0.9f
private val LocalOnCanvas = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun AgentOsTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    val themed = @Composable {
        MaterialTheme(colorScheme = scheme, typography = UbiqTypography, shapes = UbiqShapes) {
            // Screens without a Surface would otherwise get Compose's black fallback content colour.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
    if (LocalOnCanvas.current) { themed(); return }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val scale = (minOf(conf.screenWidthDp, conf.screenHeightDp) / CANVAS_WIDTH).coerceIn(1f, 1.7f)
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale * TEXT_SCALE),
        LocalOnCanvas provides true,
    ) { themed() }
}

/**
 * A recessed tint for things that sit on the plate (your chat bubble, code blocks): a see-through ink, so it reads the
 * same from the lit top to the shaded bottom. A solid grey matched the gradient's bottom and vanished there (2026-11-10).
 */
val inset: Color
    @androidx.compose.runtime.Composable get() =
        if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White.copy(alpha = 0.09f) else Color(0xFF16161A).copy(alpha = 0.08f)
