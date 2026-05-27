package com.insola.uv.dose

/**
 * Discrete bands of erythemal exposure, expressed as fractions of one MED. The boundaries are
 * deliberately set so the lay meaning of "burned" lines up with the upper tiers, not with crossing
 * 1 MED — 1 MED is by definition just-noticeable pinkness 24 h later, well below what people
 * describe as a sunburn in the field.
 */
enum class BurnTier(val label: String) {
    Safe("Safe"),
    FirstReaction("First reaction possible"),
    VisibleReddening("Visible reddening likely"),
    Sunburn("Sunburn likely"),
    SeriousBurn("Serious burn likely");

    companion object {
        /** [fractionOfMed] = accumulated dose / MED threshold (1.0 == one MED). */
        fun forFractionOfMed(fractionOfMed: Double): BurnTier = when {
            fractionOfMed < 0.5 -> Safe
            fractionOfMed < 1.0 -> FirstReaction
            fractionOfMed < 2.0 -> VisibleReddening
            fractionOfMed < 4.0 -> Sunburn
            else -> SeriousBurn
        }
    }
}
