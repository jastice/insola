package com.insola.uv.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.SkinSensitivity

// Archetypal Fitzpatrick skin tones used as picker-chip swatches on the Skin tab. Each
// phototype carries a baseline (untanned) tone and a "max tan" tone; the acclimatization
// chips lerp between them according to the chosen level, clipped by the phototype's
// biological ceiling so an impossible state (e.g. type I "Deep") visually saturates at
// what that type can actually reach.
private val BaseTone: Map<SkinSensitivity, Color> = mapOf(
    SkinSensitivity.I to Color(0xFFF5D5C0),
    SkinSensitivity.II to Color(0xFFECC4A6),
    SkinSensitivity.III to Color(0xFFD9A074),
    SkinSensitivity.IV to Color(0xFFB57848),
    SkinSensitivity.V to Color(0xFF7B4A2E),
    SkinSensitivity.VI to Color(0xFF3D2418),
)

private val MaxTanTone: Map<SkinSensitivity, Color> = mapOf(
    SkinSensitivity.I to Color(0xFFE6B49A),
    SkinSensitivity.II to Color(0xFFC78F60),
    SkinSensitivity.III to Color(0xFFA46A3C),
    SkinSensitivity.IV to Color(0xFF7B4F2A),
    SkinSensitivity.V to Color(0xFF4F3020),
    SkinSensitivity.VI to Color(0xFF2A1810),
)

internal fun skinTone(
    phototype: SkinSensitivity,
    acclimatization: Acclimatization = Acclimatization.None,
): Color {
    val base = BaseTone.getValue(phototype)
    val maxTan = MaxTanTone.getValue(phototype)
    val cap = phototype.maxAcclimatizationFactor
    val applied = minOf(acclimatization.factor, cap)
    val frac = if (cap > 1.0) ((applied - 1.0) / (cap - 1.0)).toFloat() else 0f
    return lerp(base, maxTan, frac.coerceIn(0f, 1f))
}

/** Black or white, whichever contrasts better against [this]. */
internal fun Color.contrastingOnTone(): Color =
    if (luminance() > 0.5f) Color.Black else Color.White
