package com.insola.uv.domain

import kotlinx.datetime.Instant

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class UvSample(
    val time: Instant,
    val uvIndex: Double,
)

data class UvForecast(
    val location: GeoPoint,
    val samples: List<UvSample>,
)

enum class Confidence { Low, Medium, High }

data class ExposureInterval(
    val start: Instant,
    val end: Instant,
    val exposureFactor: Double,
    val confidence: Confidence = Confidence.Medium,
)

data class ManualExposureState(
    val outdoors: Boolean = false,
    val inShade: Boolean = false,
    val sunscreenSpf: Int? = null,
    val covered: Boolean = false,
) {
    fun toFactor(): Double {
        if (!outdoors) return 0.0
        var f = 1.0
        if (inShade) f *= 0.3
        if (covered) f *= 0.2
        if (sunscreenSpf != null && sunscreenSpf > 0) f *= 1.0 / sunscreenSpf
        return f
    }
}

/**
 * Fitzpatrick skin phototypes I–VI.
 *
 * [medThresholdUvIndexHours] is one Minimum Erythemal Dose expressed in UV-index-hours.
 * Conversion: 1 UV-index unit ≈ 25 mW/m² erythemally weighted, so 1 UV-index-hour ≈ 90 J/m².
 * Published per-type MEDs in J/m² → UV-index-hours:
 *   I   ~200 J/m² → 2.2     (always burns, never tans)
 *   II  ~250 J/m² → 2.8     (usually burns, tans minimally)
 *   III ~350 J/m² → 3.9     (sometimes burns, tans gradually)
 *   IV  ~450 J/m² → 5.0     (rarely burns, tans well)
 *   V   ~600 J/m² → 6.7     (very rarely burns, tans deeply)
 *   VI ~1000 J/m² → 11.1    (never burns, deeply pigmented)
 */
enum class SkinSensitivity(val medThresholdUvIndexHours: Double, val description: String) {
    I(2.2, "Always burns, never tans"),
    II(2.8, "Usually burns, tans minimally"),
    III(3.9, "Sometimes burns, tans gradually"),
    IV(5.0, "Rarely burns, tans well"),
    V(6.7, "Very rarely burns, tans deeply"),
    VI(11.1, "Never burns, deeply pigmented");

    companion object {
        val Default: SkinSensitivity = III
    }
}
