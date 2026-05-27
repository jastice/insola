package com.insola.uv.domain

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class LerpTest {

    private val t0 = Instant.parse("2026-06-21T06:00:00Z")
    private val t1 = t0 + 2.hours

    @Test
    fun atStart_returnsV0() {
        assertEquals(10.0, lerp(t0, 10.0, t1, 30.0, t0), 1e-12)
    }

    @Test
    fun atEnd_returnsV1() {
        assertEquals(30.0, lerp(t0, 10.0, t1, 30.0, t1), 1e-12)
    }

    @Test
    fun midpoint_returnsMean() {
        val mid = t0 + 1.hours
        assertEquals(20.0, lerp(t0, 10.0, t1, 30.0, mid), 1e-12)
    }

    @Test
    fun beforeStart_clampsToV0() {
        // Out-of-range inputs clamp rather than extrapolating; the integrator code relies on this
        // when a segment-start instant precedes the sample window.
        val before = t0 - 1.hours
        assertEquals(10.0, lerp(t0, 10.0, t1, 30.0, before), 1e-12)
    }

    @Test
    fun afterEnd_clampsToV1() {
        val after = t1 + 1.hours
        assertEquals(30.0, lerp(t0, 10.0, t1, 30.0, after), 1e-12)
    }

    @Test
    fun zeroSpan_returnsV0() {
        // When t0 == t1 the slope is undefined; lerp must not divide by zero.
        assertEquals(10.0, lerp(t0, 10.0, t0, 30.0, t0), 1e-12)
    }

    @Test
    fun descendingValues_workSymmetrically() {
        val mid = t0 + 1.hours
        assertEquals(20.0, lerp(t0, 30.0, t1, 10.0, mid), 1e-12)
    }
}
