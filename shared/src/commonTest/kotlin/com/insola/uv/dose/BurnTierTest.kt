package com.insola.uv.dose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BurnTierTest {

    @Test
    fun bandsAlignWithThresholds() {
        assertEquals(BurnTier.Safe, BurnTier.forFractionOfMed(0.0))
        assertEquals(BurnTier.Safe, BurnTier.forFractionOfMed(0.49))
        assertEquals(BurnTier.FirstReaction, BurnTier.forFractionOfMed(0.5))
        assertEquals(BurnTier.FirstReaction, BurnTier.forFractionOfMed(0.99))
        assertEquals(BurnTier.VisibleReddening, BurnTier.forFractionOfMed(1.0))
        assertEquals(BurnTier.VisibleReddening, BurnTier.forFractionOfMed(1.99))
        assertEquals(BurnTier.Sunburn, BurnTier.forFractionOfMed(2.0))
        assertEquals(BurnTier.Sunburn, BurnTier.forFractionOfMed(3.99))
        assertEquals(BurnTier.SeriousBurn, BurnTier.forFractionOfMed(4.0))
        assertEquals(BurnTier.SeriousBurn, BurnTier.forFractionOfMed(10.0))
    }

    @Test
    fun negativeFraction_clampsToSafe() {
        assertEquals(BurnTier.Safe, BurnTier.forFractionOfMed(-0.1))
    }

    @Test
    fun lowerBoundOfEachTier_resolvesToThatTier() {
        // The dashboard bar derives its gradient stops from minFractionOfMed; if a tier's lower
        // bound ever stopped resolving to itself the bar's color would drift off the band edges.
        BurnTier.entries.forEach { tier ->
            assertEquals(
                tier,
                BurnTier.forFractionOfMed(tier.minFractionOfMed),
                "tier ${tier.name} lower bound ${tier.minFractionOfMed} should resolve to itself",
            )
        }
    }

    @Test
    fun minFractionOfMed_isMonotonicallyIncreasing() {
        val bounds = BurnTier.entries.map { it.minFractionOfMed }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(b > a, "tier lower bounds must strictly increase: got $a then $b")
        }
    }

    @Test
    fun ceilingExceedsEveryTierLowerBound() {
        // The bar uses CEILING_FRACTION_OF_MED as its 100% mark; every tier must have headroom
        // inside it, otherwise the highest tier would draw past the end of the track.
        BurnTier.entries.forEach { tier ->
            assertTrue(
                BurnTier.CEILING_FRACTION_OF_MED >= tier.minFractionOfMed,
                "ceiling ${BurnTier.CEILING_FRACTION_OF_MED} must dominate ${tier.name}'s lower bound ${tier.minFractionOfMed}",
            )
        }
    }
}
