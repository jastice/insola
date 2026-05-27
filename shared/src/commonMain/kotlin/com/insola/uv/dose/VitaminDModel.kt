package com.insola.uv.dose

import com.insola.uv.domain.ExposureInterval
import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.domain.lerp
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.math.PI
import kotlin.math.cos

/**
 * Vitamin-D dose model.
 *
 * The forecast's `uvIndex` is the CIE-erythemally-weighted UV that [BurnModel] integrates
 * against the user's MED. Vitamin-D synthesis is driven by a *different* action spectrum
 * (CIE previtamin-D3) sitting in a narrow UV-B window, so its atmospheric attenuation
 * with solar zenith angle is much steeper. The VitD/erythemal irradiance ratio is itself
 * a function of solar elevation — ~2.0 with the sun overhead, ~1.0 near SZA 55°, ~0.5
 * near SZA 75°, 0 at the horizon (McKenzie 2009; Webb & Engelsen 2006 / FastRT).
 *
 * Integration shape mirrors [DoseIntegrator] / [BurnModel] — UV-index over time — but
 * reweighted by [vitDRatio]. Accumulated score has units of *vitamin-D-weighted
 * UV-index-hours* (with body fraction folded in via [skinExposedFraction]). Holick's
 * rule (¼ MED on ¼ body → ~1000 IU) puts 1 Standard Vitamin D Dose at ¹⁄₁₆ MED of
 * vit-D-weighted score at mid-elevation sun, so vit-D saturates at <10 min around solar
 * noon while burn dose is still well under MED — matching the well-established fact that
 * vitamin-D synthesis saturates long before erythema.
 *
 * Caveats:
 *  - The lumisterol/tachysterol back-reaction is not modelled; saturation is a bucket
 *    ceiling, not a curve flattening.
 *  - Ozone column is assumed typical; cloud/aerosol effects ride along inside the
 *    forecast UVI.
 *  - The CIE previtamin-D3 action spectrum was revised in 2021 (PNAS) with a ~5 nm blue
 *    shift; this model still tracks the original CIE curve underpinning McKenzie 2009.
 */
object VitaminDModel {

    enum class Bucket { None, Trace, Low, Adequate, Sufficient }

    fun accumulate(
        forecast: UvForecast,
        from: Instant,
        to: Instant,
        skinExposedFraction: Double,
    ): Double {
        if (to <= from || forecast.samples.size < 2 || skinExposedFraction <= 0.0) return 0.0
        return forecast.samples
            .zipWithNext()
            .sumOf { (a, b) -> segmentScore(a, b, from, to, forecast.location) } * skinExposedFraction
    }

    fun accumulateOverIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
        skinExposedFraction: Double,
    ): Double = intervals.sumOf { i ->
        accumulate(forecast, i.start, i.end, skinExposedFraction * i.exposureFactor)
    }

    /**
     * Yield-adjusted score: the fraction of raw exposure that actually produces previtamin
     * D3 once melanin attenuation is accounted for. Tanned (and naturally pigmented) skin's
     * 7-dehydrocholesterol competes with melanin for UV-B photons, so vit-D synthesis per
     * unit UV drops in rough proportion to the acclimatization photoprotection factor
     * (Webb 2010, Bogh 2010). Phototype-level pigmentation is *not* applied here because
     * the bucket compares against an SDD anchored to baseline phototype MED, which already
     * scales with constitutive melanin.
     */
    fun effectiveYield(rawScore: Double, profile: SkinProfile): Double =
        rawScore / profile.effectiveAcclimatizationFactor

    fun bucket(score: Double, profile: SkinProfile): Bucket {
        // Holick's rule: 1 SDD (~1000 IU) ≈ ¼ MED of erythemal exposure on ¼ of the body
        // surface. The 25% body fraction is already folded into the [skinExposedFraction]
        // passed to [accumulate], so the SDD expressed in our vit-D-weighted score units
        // is ¼ × ¼ = ¹⁄₁₆ of an MED at R≈1 (mid-elevation sun). Higher-angle sun produces
        // proportionally more vit-D per unit time — R > 1 pushes the user into Sufficient
        // sooner than into MED, which matches reality (vit-D saturates well before burn).
        //
        // SDD threshold uses *baseline* phototype MED — Holick's calibration is anchored
        // there. Acclimatization (tan) is applied as melanin attenuation on the score via
        // [effectiveYield]; the two corrections compose symmetrically with the burn-side
        // multiplier (tan extends burn budget AND extends time-to-Adequate).
        val effective = effectiveYield(score, profile)
        val sdd = profile.phototype.medThresholdUvIndexHours / 16.0
        return when {
            effective <= 0.0        -> Bucket.None
            effective < 0.25 * sdd  -> Bucket.Trace     // < ¼ SDD
            effective < 0.5 * sdd   -> Bucket.Low       // ¼ – ½ SDD
            effective < sdd         -> Bucket.Adequate  // ½ – 1 SDD
            else                    -> Bucket.Sufficient    // ≥ 1 SDD
        }
    }

    fun bucketForIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
        profile: SkinProfile,
        skinExposedFraction: Double,
    ): Bucket = bucket(
        accumulateOverIntervals(forecast, intervals, skinExposedFraction),
        profile,
    )

    /**
     * Ratio of vitamin-D-weighted to erythemally-weighted UV at the given solar elevation.
     * Linear-in-cosine fit to McKenzie 2009 / FastRT tables: ~2.0 at zenith (SZA 0°),
     * ~1.0 near SZA 60°, ~0.5 near SZA 75°, 0 at the horizon.
     */
    fun vitDRatio(elevationDeg: Double): Double {
        if (elevationDeg <= 0.0) return 0.0
        return 2.0 * cos((90.0 - elevationDeg) * PI / 180.0)
    }

    private fun segmentScore(
        a: UvSample,
        b: UvSample,
        from: Instant,
        to: Instant,
        location: GeoPoint,
    ): Double {
        if (b.time <= from || a.time >= to) return 0.0
        val segStart = maxOf(a.time, from)
        val segEnd = minOf(b.time, to)
        if (segEnd <= segStart) return 0.0
        val mid = segStart + (segEnd - segStart) / 2
        val ratio = vitDRatio(SolarGeometry.solarElevationDegrees(location, mid))
        val uvMid = lerp(a.time, a.uvIndex, b.time, b.uvIndex, mid)
        val hours = (segEnd - segStart).inWholeMilliseconds / 3_600_000.0
        return uvMid * ratio * hours
    }
}
