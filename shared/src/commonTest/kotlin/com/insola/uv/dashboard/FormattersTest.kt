package com.insola.uv.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormattersTest {

    // formatNumber

    @Test
    fun formatNumber_zeroDecimals_roundsToInt() {
        assertEquals("3", formatNumber(2.7, 0))
        assertEquals("3", formatNumber(3.49, 0))
        assertEquals("4", formatNumber(3.5, 0))
    }

    @Test
    fun formatNumber_padsTrailingZeros() {
        // The dashboard relies on this for "0.50" / "1.00" style displays so the column doesn't
        // shimmer between widths as values change.
        assertEquals("1.50", formatNumber(1.5, 2))
        assertEquals("2.00", formatNumber(2.0, 2))
        assertEquals("0.1", formatNumber(0.1, 1))
    }

    @Test
    fun formatNumber_truncatesExcessDigits() {
        assertEquals("3.14", formatNumber(3.14159, 2))
    }

    @Test
    fun formatNumber_nanAndInfinity_renderAsDash() {
        assertEquals("—", formatNumber(Double.NaN, 1))
        assertEquals("—", formatNumber(Double.POSITIVE_INFINITY, 1))
        assertEquals("—", formatNumber(Double.NEGATIVE_INFINITY, 2))
    }

    @Test
    fun formatNumber_negativeValues_keepSign() {
        assertEquals("-1.50", formatNumber(-1.5, 2))
        assertEquals("-3", formatNumber(-2.7, 0))
    }

    // formatClock

    @Test
    fun formatClock_padsHoursAndMinutes() {
        assertEquals("00:00", formatClock(0.0))
        assertEquals("09:30", formatClock(9.5))
        assertEquals("13:15", formatClock(13.25))
    }

    @Test
    fun formatClock_atTwentyFour_clampsTo23_59() {
        // The scrubber lets the user drag exactly to 24.0; we want a displayable string rather
        // than a "24:00" that looks like the next day.
        assertEquals("23:59", formatClock(24.0))
    }

    @Test
    fun formatClock_negativeInputs_clampToZero() {
        assertEquals("00:00", formatClock(-1.0))
    }

    // formatDuration

    @Test
    fun formatDuration_subHour_minutesOnly() {
        assertEquals("0m", formatDuration(0.minutes))
        assertEquals("45m", formatDuration(45.minutes))
    }

    @Test
    fun formatDuration_wholeHours_dropMinutes() {
        assertEquals("1h", formatDuration(1.hours))
        assertEquals("3h", formatDuration(3.hours))
    }

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("1h 30m", formatDuration(90.minutes))
        assertEquals("2h 5m", formatDuration(2.hours + 5.minutes))
    }

    @Test
    fun formatDuration_truncatesSubMinuteRemainder() {
        // 1h 30m 45s should display as 1h 30m, not round up.
        assertEquals("1h 30m", formatDuration(1.hours + 30.minutes + 45.seconds))
    }

    @Test
    fun formatDuration_negative_clampsToZero() {
        assertEquals("0m", formatDuration((-5).minutes))
    }
}
