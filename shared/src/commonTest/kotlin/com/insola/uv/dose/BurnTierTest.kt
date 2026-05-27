package com.insola.uv.dose

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
