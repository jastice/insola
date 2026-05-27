package com.insola.uv.dashboard

import androidx.compose.ui.graphics.Color
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.VitaminDModel

// WHO / EPA UV index band colors.
internal val UvGreen = Color(0xFF299501)
internal val UvYellow = Color(0xFFF7E400)
internal val UvOrange = Color(0xFFF95901)
internal val UvRed = Color(0xFFD8001D)
internal val UvPurple = Color(0xFF6B49C8)

internal val OutdoorGreen = Color(0xFF34A853)
internal val IndoorGray = Color(0xFF9E9E9E)

// Vitamin-D bucket colors. Trace = neutral gray, Low = amber building, Adequate = healthy green,
// Sufficient = saturated deep green.
internal val VitDTrace = Color(0xFFBDBDBD)
internal val VitDLow = Color(0xFFFFB300)
internal val VitDAdequate = Color(0xFF66BB6A)
internal val VitDSufficient = Color(0xFF2E7D32)

/** UV-index band color, matching the WHO/EPA scale. */
internal fun uvBandColor(uv: Double): Color = when {
    uv < 3.0 -> UvGreen
    uv < 6.0 -> UvYellow
    uv < 8.0 -> UvOrange
    uv < 11.0 -> UvRed
    else -> UvPurple
}

/** Visual color for each [BurnTier] band, sharing the UV-index palette. */
internal fun BurnTier.color(): Color = when (this) {
    BurnTier.Safe -> UvGreen
    BurnTier.FirstReaction -> UvYellow
    BurnTier.VisibleReddening -> UvOrange
    BurnTier.Sunburn -> UvRed
    BurnTier.SeriousBurn -> UvPurple
}

/** Visual color for each vitamin-D [VitaminDModel.Bucket]. */
internal fun VitaminDModel.Bucket.color(): Color = when (this) {
    VitaminDModel.Bucket.None -> VitDTrace
    VitaminDModel.Bucket.Trace -> VitDTrace
    VitaminDModel.Bucket.Low -> VitDLow
    VitaminDModel.Bucket.Adequate -> VitDAdequate
    VitaminDModel.Bucket.Sufficient -> VitDSufficient
}
