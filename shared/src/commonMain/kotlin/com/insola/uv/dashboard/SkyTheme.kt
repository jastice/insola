package com.insola.uv.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Time-of-day theme colors driven by solar elevation.
 *
 * [background] is vivid (deep night-blue → twilight purple → sunrise orange → piercing daylight
 * yellow) and fills the screen behind the cards. [surface] is a tinted-paper card color sharing
 * the bg's hue but light enough for dark text. [onBackground] is the contrast color for text
 * drawn directly on the bg (white at night, dark when the sky is bright).
 *
 * Solar elevation is location-aware: the equator at noon reaches the bright end of the palette,
 * while polar winter stays near the twilight/night end without ever brightening up.
 */
data class SkyColors(val background: Color, val surface: Color, val onBackground: Color)

private data class SkyStop(val elevDeg: Double, val background: Color, val surface: Color)

// Anchored at real-world twilight boundaries (astronomical −18°, nautical −12°, civil −6°,
// sunrise 0°, golden hour ≈ 6°) plus extra stops through the blue-hour and golden-hour bands so
// the dawn/dusk gradient walks blue → indigo → purple → mauve → rose → peach → amber → gold
// without muddy sRGB lerps. Stops are clustered between −6° and +10° where the sky changes
// fastest, and spread out above 30° where brightness changes more than hue.
// Surface tones share the bg's hue with significant saturation — clearly slate-blue at night,
// peach/coral at dawn, golden at noon — while staying at ≥72% lightness so dark text remains
// readable. Below the horizon the surface is lighter than the bg (lit card on a dark sky); above
// ~25° the surface becomes slightly darker/richer than the bright sky for hierarchy.
private val SkyStops = listOf(
    SkyStop(-18.0, Color(0xFF050B22), Color(0xFF9CB0DE)),
    SkyStop(-12.0, Color(0xFF0E1A48), Color(0xFFA2B3DD)),
    SkyStop(-9.0,  Color(0xFF1A2664), Color(0xFFA6B5DC)),
    SkyStop(-6.0,  Color(0xFF2C2D78), Color(0xFFAAB2D8)),
    SkyStop(-4.0,  Color(0xFF4E3478), Color(0xFFB8AACA)),
    SkyStop(-2.0,  Color(0xFF813E72), Color(0xFFD1A3BF)),
    SkyStop(-0.5,  Color(0xFFB3525E), Color(0xFFE5A89E)),
    SkyStop(0.0,   Color(0xFFD67450), Color(0xFFEDB683)),
    SkyStop(1.5,   Color(0xFFEA9156), Color(0xFFEFC18C)),
    SkyStop(3.0,   Color(0xFFF2A865), Color(0xFFF1CB97)),
    SkyStop(6.0,   Color(0xFFF7BF77), Color(0xFFF4D4A0)),
    SkyStop(10.0,  Color(0xFFFAD18B), Color(0xFFF5DBAA)),
    SkyStop(18.0,  Color(0xFFFCDFA0), Color(0xFFF5E2B0)),
    SkyStop(30.0,  Color(0xFFFDE9B6), Color(0xFFF4DE9C)),
    SkyStop(50.0,  Color(0xFFFFE980), Color(0xFFF0D466)),
    SkyStop(70.0,  Color(0xFFFFEB3B), Color(0xFFEAC52E)),
)

fun skyColors(elevationDeg: Double): SkyColors {
    val first = SkyStops.first()
    val last = SkyStops.last()
    val bg: Color
    val surface: Color
    if (elevationDeg <= first.elevDeg) {
        bg = first.background; surface = first.surface
    } else if (elevationDeg >= last.elevDeg) {
        bg = last.background; surface = last.surface
    } else {
        val upperIdx = SkyStops.indexOfFirst { it.elevDeg >= elevationDeg }
        val upper = SkyStops[upperIdx]
        val lower = SkyStops[upperIdx - 1]
        val t = ((elevationDeg - lower.elevDeg) / (upper.elevDeg - lower.elevDeg)).toFloat()
        bg = lerp(lower.background, upper.background, t)
        surface = lerp(lower.surface, upper.surface, t)
    }
    // Flip text contrast against the bg: white on dark skies, near-black once the sky is bright.
    val onBg = if (bg.luminance() < 0.5f) Color(0xFFF5F5F5) else Color(0xFF1A1A1A)
    return SkyColors(background = bg, surface = surface, onBackground = onBg)
}
