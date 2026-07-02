package com.insola.uv.dashboard

import androidx.compose.ui.graphics.Color
import com.insola.uv.dose.BurnTier

// WHO / EPA UV index band colors.
internal val UvGreen = Color(0xFF299501)
internal val UvYellow = Color(0xFFF7E400)
internal val UvOrange = Color(0xFFF95901)
internal val UvRed = Color(0xFFD8001D)
internal val UvPurple = Color(0xFF6B49C8)

/**
 * WHO/EPA UV-index band breakpoints paired with their colors — the single source every gradient,
 * tick list, and band lookup derives from so the scale can't drift between charts.
 */
internal val UvBandStops: List<Pair<Double, Color>> = listOf(
    0.0 to UvGreen,
    3.0 to UvYellow,
    6.0 to UvOrange,
    8.0 to UvRed,
    11.0 to UvPurple,
)

internal val OutdoorGreen = Color(0xFF34A853)
internal val IndoorGray = Color(0xFF9E9E9E)

// Sunscreen "shield" accent — the SPF what-if overlay on the Skin-tab sundial. A cool blue
// reads as protection and stays distinct from the green→red burn gradient underneath it.
internal val ShieldBlue = Color(0xFF1E88E5)

// Vitamin-D bucket colors. Trace = neutral gray, Low = amber building, Adequate = healthy green,
// Sufficient = saturated deep green.
internal val VitDTrace = Color(0xFFBDBDBD)
internal val VitDLow = Color(0xFFFFB300)
internal val VitDAdequate = Color(0xFF66BB6A)
internal val VitDSufficient = Color(0xFF2E7D32)

/** UV-index band color, matching the WHO/EPA scale. */
internal fun uvBandColor(uv: Double): Color =
    UvBandStops.lastOrNull { uv >= it.first }?.second ?: UvBandStops.first().second

/** Visual color for each [BurnTier] band, sharing the UV-index palette. */
internal fun BurnTier.color(): Color = when (this) {
    BurnTier.Safe -> UvGreen
    BurnTier.FirstReaction -> UvYellow
    BurnTier.VisibleReddening -> UvOrange
    BurnTier.Sunburn -> UvRed
    BurnTier.SeriousBurn -> UvPurple
}

