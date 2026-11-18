package com.agentos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp

/**
 * The plate behind home, the conversation and all apps (docs/DESIGN.md): lit from above like a
 * panel under a lamp, so the top catches the light and the bottom falls into shade, with a fine
 * plastic grain. Chosen over a flat plate, a studded baseplate and a blue plate (2026-11-08).
 */
@Composable
fun Modifier.plateBackground(): Modifier {
    val dark = isSystemInDarkTheme()
    val p = plate
    val grain = remember { grainBrush() }
    return drawBehind {
        val base = p.plate
        val top = lerp(base, Color.White, if (dark) 0.07f else 0.35f)
        // Not too deep: the ask bar's recessed slot sits at the bottom and must still read.
        val bottom = lerp(base, Color.Black, if (dark) 0.22f else 0.06f)
        drawRect(Brush.verticalGradient(0f to top, 0.45f to base, 1f to bottom))
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) 0.06f else 0.25f), Color.Transparent),
            center = Offset(size.width / 2, size.height * 0.12f), radius = size.width * 0.9f))
        drawRect(grain, alpha = if (dark) 0.06f else 0.05f)
    }
}

/** Fine plastic grain: a small tile of random light and dark specks, repeated. */
private fun grainBrush(): Brush {
    val n = 96
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val rnd = java.util.Random(7)
    for (y in 0 until n) for (x in 0 until n) {
        val v = rnd.nextInt(256)
        bmp.setPixel(x, y, android.graphics.Color.argb(255, v, v, v))
    }
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
