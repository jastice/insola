package com.insola.uv.dose

/**
 * Discrete bands of erythemal exposure, expressed as fractions of one MED. The boundaries are
 * deliberately set so the lay meaning of "burned" lines up with the upper tiers, not with crossing
 * 1 MED — 1 MED is by definition just-noticeable pinkness 24 h later, well below what people
 * describe as a sunburn in the field.
 *
 * [minFractionOfMed] is the lower edge of this tier (inclusive). The next tier's lower edge is
 * this tier's upper edge.
 */
enum class BurnTier(val label: String, val minFractionOfMed: Double) {
    Safe("Safe", 0.0),
    FirstReaction("First reaction possible", 0.5),
    VisibleReddening("Visible reddening likely", 1.0),
    Sunburn("Sunburn likely", 2.0),
    SeriousBurn("Serious burn likely", 4.0);

    companion object {
        /** Upper edge of the highest band — the "serious burn" line, expressed as a fraction of MED. */
        const val CEILING_FRACTION_OF_MED: Double = 4.0

        /** [fractionOfMed] = accumulated dose / MED threshold (1.0 == one MED). */
        fun forFractionOfMed(fractionOfMed: Double): BurnTier =
            entries.lastOrNull { fractionOfMed >= it.minFractionOfMed } ?: Safe
    }
}
