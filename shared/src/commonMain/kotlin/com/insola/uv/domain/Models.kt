package com.insola.uv.domain

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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

/**
 * Fitzpatrick skin phototypes I–VI.
 *
 * [medThresholdUvIndexHours] is one Minimum Erythemal Dose for **untanned, unacclimatized**
 * skin, expressed in UV-index-hours. Conversion: 1 UV-index unit ≈ 25 mW/m² erythemally
 * weighted, so 1 UV-index-hour ≈ 90 J/m². Published per-type MEDs in J/m² → UV-index-hours:
 *   I   ~200 J/m² → 2.2     (always burns, never tans)
 *   II  ~250 J/m² → 2.8     (usually burns, tans minimally)
 *   III ~350 J/m² → 3.9     (sometimes burns, tans gradually)
 *   IV  ~450 J/m² → 5.0     (rarely burns, tans well)
 *   V   ~600 J/m² → 6.7     (very rarely burns, tans deeply)
 *   VI ~1000 J/m² → 11.1    (never burns, deeply pigmented)
 *
 * [maxAcclimatizationFactor] caps how much repeated sub-erythemal exposure can raise the MED
 * above baseline. Tanning capacity is itself phototype-dependent: type I shows minimal melanin
 * response while V–VI can roughly quadruple their baseline tolerance. See `BurnModel.md` for
 * the literature (Diffey 1991, Sheehan 2002).
 */
enum class SkinSensitivity(
    val medThresholdUvIndexHours: Double,
    val maxAcclimatizationFactor: Double,
    val description: String,
) {
    I(2.2, 1.4, "Always burns, never tans"),
    II(2.8, 1.8, "Usually burns, tans minimally"),
    III(3.9, 2.5, "Sometimes burns, tans gradually"),
    IV(5.0, 3.0, "Rarely burns, tans well"),
    V(6.7, 3.5, "Very rarely burns, tans deeply"),
    VI(11.1, 4.0, "Never burns, deeply pigmented");

    companion object {
        val Default: SkinSensitivity = III
    }
}

/**
 * Self-reported tan / acclimatization state.
 *
 * Photoadaptation raises the MED via two roughly independent mechanisms:
 *   - melanogenesis (facultative pigmentation) — develops over ~2 weeks of regular sub-erythemal
 *     exposure and provides up to ~2× protection
 *   - stratum-corneum thickening — adds another ~1.5× on top, slower onset
 * Together they cap at roughly 3× for moderately fair skin and up to ~4× for naturally
 * pigmented skin (Sheehan 2002, Miyamura 2011). Both decay over ~4–6 weeks without
 * continued exposure.
 *
 * The chosen [factor] is intersected with [SkinSensitivity.maxAcclimatizationFactor] inside
 * [SkinProfile] so a user can't push a type-I profile beyond its physiological ceiling. See
 * `BurnModel.md` for references.
 */
enum class Acclimatization(val factor: Double, val description: String) {
    None(1.0, "Untanned — winter skin or no recent sun"),
    Light(1.5, "Light tan — occasional sun the past 2 weeks"),
    Moderate(2.2, "Moderate tan — regular sun, established base"),
    Deep(3.0, "Deep tan — frequent unprotected sun");

    companion object {
        val Default: Acclimatization = None
    }
}

/**
 * Full skin model: an immutable Fitzpatrick [phototype] plus a mutable [acclimatization]
 * state. Consumers (burn integrator, dose budget) should read [effectiveMedUvIndexHours]
 * rather than going through [SkinSensitivity.medThresholdUvIndexHours] directly, so the
 * tan multiplier is applied consistently.
 *
 * Vitamin-D buckets intentionally use the *baseline* (untanned) MED because Holick's
 * SDD calibration is anchored to untanned MED — tanning reduces vit-D synthesis
 * efficiency rather than scaling its target dose with it.
 */
data class SkinProfile(
    val phototype: SkinSensitivity,
    val acclimatization: Acclimatization = Acclimatization.None,
    val defaultSpf: Spf = Spf.Off,
) {
    /** Tan multiplier actually applied, capped by the phototype's biological ceiling. */
    val effectiveAcclimatizationFactor: Double
        get() = minOf(acclimatization.factor, phototype.maxAcclimatizationFactor)

    /** Baseline MED scaled by the (capped) acclimatization factor. */
    val effectiveMedUvIndexHours: Double
        get() = phototype.medThresholdUvIndexHours * effectiveAcclimatizationFactor

    companion object {
        val Default: SkinProfile = SkinProfile(SkinSensitivity.Default, Acclimatization.Default)
    }
}

/**
 * Sunscreen sun-protection factor. SPF `n` means the labelled product transmits roughly `1/n`
 * of incident erythemally-weighted UV through to the skin (FDA/EU regulatory definition;
 * Diffey 2001). [transmittance] is the fraction of UV that gets through and is the value
 * model integrators multiply against incident UV.
 *
 * Caveats baked into [Spf] but worth knowing:
 *  - Real-world transmittance depends heavily on application thickness; users typically reach
 *    a third to a half of the labelled SPF (Petersen & Wulf 2014). We assume label SPF here.
 *  - SPF is defined against the erythemal action spectrum, the same one the UV index carries,
 *    so a single multiplicative factor applies symmetrically to burn dose and to vitamin-D
 *    score (Faurschou & Wulf 2007).
 *  - Filters degrade with sweat, water and time; [SunscreenApplication.MAX_DURATION] is a
 *    coarse hand-wave at that decay (reapplication advice is "every 2 hours").
 */
enum class Spf(val factor: Int, val description: String) {
    Off(1, "No sunscreen"),
    Spf15(15, "SPF 15"),
    Spf30(30, "SPF 30"),
    Spf50(50, "SPF 50");

    /** Fraction of incident UV reaching the skin (1/[factor]). */
    val transmittance: Double get() = 1.0 / factor.toDouble()

    companion object {
        val Default: Spf = Off

        /**
         * Closest enum entry to an arbitrary numeric transmittance. Used by the UI to pick a
         * label for a [AttenuationTimeline.Patch] whose transmittance is just a `Double` —
         * patches are numeric so a future decay model can produce values between the chip
         * presets without breaking the rendering.
         */
        fun nearestForTransmittance(transmittance: Double): Spf =
            entries.minBy { kotlin.math.abs(it.transmittance - transmittance) }
    }
}

/**
 * Piecewise UV transmittance vs. time, modelling whatever sunscreen the user is wearing.
 * The dose integrator only ever queries [transmittanceAt] — never an SPF enum — so the model
 * is freely numeric: today each [Patch] is a 2-hour step at a fixed transmittance, tomorrow
 * we can swap in a gradual-decay curve by changing only [Patch.transmittanceAt] without
 * touching the integrator or the ViewModel.
 *
 * Patches compose by `min`: when multiple are active at the same instant, the strongest
 * (smallest transmittance) wins. This is what makes reapplying safe — a later patch can
 * only ever extend or deepen coverage, never strip a moment an earlier patch already covered.
 *
 * The always-on [SkinProfile.defaultSpf] is *not* a patch; the integrator applies it as a
 * baseline alongside whatever the timeline says.
 */
data class AttenuationTimeline(val patches: List<Patch>) {

    /** Strongest (min) transmittance across all patches at [t], or 1.0 if none cover it. */
    fun transmittanceAt(t: Instant): Double =
        patches.fold(1.0) { acc, p -> minOf(acc, p.transmittanceAt(t)) }

    /**
     * Latest patch still covering [t], or null. The UI uses this for the countdown bar /
     * "active SPF" readout; the integrator does not — it integrates against [transmittanceAt].
     */
    fun activeAt(t: Instant): Patch? =
        patches.filter { it.isActiveAt(t) }.maxByOrNull { it.appliedAt }

    /** Instants where transmittance may step. Used by the integrator to slice exposure intervals. */
    fun criticalTimes(): List<Instant> =
        patches.flatMap { listOf(it.appliedAt, it.expiresAt) }

    operator fun plus(patch: Patch): AttenuationTimeline = AttenuationTimeline(patches + patch)

    /**
     * One patch of attenuation. The current shape is a step — full protection at [transmittance]
     * for [duration], then bare skin (transmittance 1.0). To model gradual decay later, swap
     * the body of [transmittanceAt] with a smooth curve; callers only see the scalar result.
     */
    data class Patch(
        val appliedAt: Instant,
        val transmittance: Double,
        val duration: Duration = DEFAULT_DURATION,
    ) {
        val expiresAt: Instant get() = appliedAt + duration

        fun transmittanceAt(t: Instant): Double {
            val elapsed = t - appliedAt
            return if (elapsed >= Duration.ZERO && elapsed < duration) transmittance else 1.0
        }

        fun isActiveAt(t: Instant): Boolean = t >= appliedAt && t < expiresAt

        fun remainingAt(t: Instant): Duration = (expiresAt - t).coerceAtLeast(Duration.ZERO)
    }

    companion object {
        val Empty: AttenuationTimeline = AttenuationTimeline(emptyList())

        /** Default patch lifetime — dermatology's "reapply every 2h" boundary. */
        val DEFAULT_DURATION: Duration = 2.hours
    }
}
