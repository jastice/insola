package com.insola.uv.domain

import kotlinx.datetime.Instant
import kotlin.math.exp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

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
 * is freely numeric: each [Patch] now smoothly decays via [Patch.transmittanceAt], and the
 * integrator just sub-slices the exposure interval and reads the scalar at each slice.
 *
 * Patches compose by `min`: when multiple are active at the same instant, the strongest
 * (smallest transmittance) wins. This is what makes reapplying safe — a later patch can
 * only ever extend or deepen coverage, never strip a moment an earlier patch already covered.
 *
 * There is no always-on baseline: bare skin (`transmittance = 1.0`) is the floor, and only
 * applied (decaying) patches lift protection above it.
 *
 * See `SunscreenModel.md` for the decay model, the real-world reduction factors baked into
 * the defaults, and the literature behind both.
 */
data class AttenuationTimeline(val patches: List<Patch>) {

    /** Strongest (min) transmittance across all patches at [t], or 1.0 if none cover it. */
    fun transmittanceAt(t: Instant): Double =
        patches.fold(1.0) { acc, p -> minOf(acc, p.transmittanceAt(t)) }

    /**
     * Latest patch still inside its "reapply soon" hint window at [t], or null. The UI uses
     * this for the countdown bar / "active SPF" readout; the integrator does not — it
     * integrates against [transmittanceAt], which continues to count residual protection
     * past the hint window.
     */
    fun activeAt(t: Instant): Patch? =
        patches.filter { it.isActiveAt(t) }.maxByOrNull { it.appliedAt }

    /**
     * Time points at which the integrator should sub-slice an exposure interval so the smooth
     * decay is sampled finely enough. Returns each patch's apply time plus regular samples
     * out to its modelling horizon — beyond that the patch is treated as bare skin.
     */
    fun criticalTimes(): List<Instant> =
        patches.flatMap { it.samplingTimes().toList() }

    operator fun plus(patch: Patch): AttenuationTimeline = AttenuationTimeline(patches + patch)

    /**
     * One application of sunscreen, modelled as an exponential decay of the *excess*
     * protection factor `(S − 1)` from an initial effective SPF `S` toward bare skin
     * (`P = 1`):
     *
     *   `P(t) = 1 + (S − 1) · exp(−(t − appliedAt) / τ)`
     *   `transmittance(t) = 1 / P(t)`
     *
     * The initial effective SPF `S` is *not* the label SPF — it is the label derated for the
     * fact that users almost never apply the 2 mg/cm² lab-standard thickness. We use the linear
     * thickness law (Diffey 1997) `S_eff = 1 + (S_label − 1) · thickness`, which interpolates
     * the excess protection factor between bare skin at `thickness = 0` and the labeled SPF at
     * the lab dose. At [TYPICAL_APPLICATION_THICKNESS] = 0.5 this gives SPF 30 → 15.5,
     * matching everyday experience. See `SunscreenModel.md` for why we picked the linear law
     * over Faurschou & Wulf's exponential.
     *
     * Half-life [NOMINAL_HALF_LIFE_HOURS] = 2 h tracks the dermatology "reapply every 2 h"
     * recommendation (Diffey 2001) — the time after which effective protection has dropped
     * to about half. [wearMultiplier] scales the half-life for water immersion or heavy
     * sweat, which roughly halve it (Stokes & Diffey 1999). See `SunscreenModel.md` for the
     * full derivation and references.
     */
    data class Patch(
        val appliedAt: Instant,
        /** Labeled SPF transmittance (e.g. `1/30` for SPF 30) — what's printed on the bottle. */
        val labelTransmittance: Double,
        /**
         * Fraction of the 2 mg/cm² lab application thickness the user actually used. Default
         * [TYPICAL_APPLICATION_THICKNESS] = 0.5 reflects the field median; set to 1.0 to
         * model "lab-perfect" application.
         */
        val applicationThickness: Double = TYPICAL_APPLICATION_THICKNESS,
        /**
         * Half-life multiplier — 1.0 is dry skin under indoor conditions, ~0.5 for water
         * immersion or heavy sweat (Stokes & Diffey 1999, Diffey 2001).
         */
        val wearMultiplier: Double = 1.0,
    ) {
        /** Effective initial SPF after the linear application-thickness derate. */
        val initialSpf: Double
            get() {
                val label = 1.0 / labelTransmittance
                val t = applicationThickness.coerceIn(0.0, 1.0)
                return 1.0 + (label - 1.0) * t
            }

        /** UV transmittance at the moment of application — `1 / [initialSpf]`. */
        val initialTransmittance: Double get() = 1.0 / initialSpf

        /** Half-life of the excess protection factor `(S − 1)`, after the wear derate. */
        val effectiveHalfLifeHours: Double
            get() = NOMINAL_HALF_LIFE_HOURS * wearMultiplier

        /**
         * How long this patch is modelled. After [MODELLING_HALF_LIVES] half-lives the
         * residual excess factor is ~1.5 %, well below the noise floor of the SPF model, so
         * the integrator treats `t > expiresAt` as bare skin.
         */
        val effectiveLifetime: Duration
            get() = (effectiveHalfLifeHours * MODELLING_HALF_LIVES).hours

        val expiresAt: Instant get() = appliedAt + effectiveLifetime

        fun transmittanceAt(t: Instant): Double {
            val elapsedHours = (t - appliedAt).toDouble(DurationUnit.HOURS)
            val halfLife = effectiveHalfLifeHours
            if (elapsedHours < 0.0 || halfLife <= 0.0) return 1.0
            if (elapsedHours > halfLife * MODELLING_HALF_LIVES) return 1.0
            val s = initialSpf
            if (s <= 1.0) return 1.0
            val tauHours = halfLife / LN_2
            val p = 1.0 + (s - 1.0) * exp(-elapsedHours / tauHours)
            return 1.0 / p
        }

        /** UI "reapply soon" hint window — 2 h matches the dermatology guideline. */
        fun isActiveAt(t: Instant): Boolean {
            val elapsed = t - appliedAt
            return elapsed >= Duration.ZERO && elapsed < REAPPLY_HINT_DURATION
        }

        /** Remaining time in the [REAPPLY_HINT_DURATION] countdown, for the UI bar. */
        fun remainingHintAt(t: Instant): Duration =
            ((appliedAt + REAPPLY_HINT_DURATION) - t).coerceAtLeast(Duration.ZERO)

        /**
         * Sub-slicing points across the modelling horizon — uniform [SAMPLING_STEP] grid so
         * the integrator's piecewise-constant approximation tracks the smooth decay closely.
         */
        fun samplingTimes(): Sequence<Instant> = sequence {
            var t = appliedAt
            val end = expiresAt
            while (t <= end) {
                yield(t)
                t += SAMPLING_STEP
            }
        }
    }

    companion object {
        val Empty: AttenuationTimeline = AttenuationTimeline(emptyList())

        /**
         * Median field application thickness as a fraction of the 2 mg/cm² lab dose
         * (Petersen & Wulf 2014; range ~0.4–1.0 in the literature).
         */
        const val TYPICAL_APPLICATION_THICKNESS: Double = 0.5

        /** Nominal half-life of the excess protection factor — matches "reapply every 2 h". */
        const val NOMINAL_HALF_LIFE_HOURS: Double = 2.0

        /**
         * How many half-lives the integrator continues to count residual protection. At 6
         * half-lives the excess factor has decayed to ~1.5 % — below the SPF model's noise
         * floor — so further sampling adds no signal.
         */
        const val MODELLING_HALF_LIVES: Int = 6

        /** UI countdown window — when the bar hits 0 the user should reapply. */
        val REAPPLY_HINT_DURATION: Duration = 2.hours

        /** Sub-slice resolution for the integrator. 5 min keeps the trapezoid error < 0.5 %. */
        val SAMPLING_STEP: Duration = 5.minutes

        private const val LN_2: Double = 0.6931471805599453
    }
}
