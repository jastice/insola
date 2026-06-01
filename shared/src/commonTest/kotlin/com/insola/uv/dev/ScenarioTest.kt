package com.insola.uv.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ScenarioTest {

    private val day = Fixtures.byId("berlin").day

    @Test
    fun hourToInstant_atZero_isDayStart() {
        assertEquals(day.dayStart, day.hourToInstant(0.0))
    }

    @Test
    fun hourToInstant_at24_isDayEnd() {
        assertEquals(day.dayStart + 24.hours, day.hourToInstant(24.0))
    }

    @Test
    fun roundTrip_preservesHours() {
        // The compute pipeline goes scrubber-hour → Instant → back to hour-of-day for chart
        // rendering; the round-trip needs to be tight enough that drag latency-free.
        listOf(0.0, 0.5, 6.25, 12.0, 17.987, 23.999).forEach { h ->
            val rt = day.instantToHour(day.hourToInstant(h))
            assertNotNull(rt)
            assertEquals(h, rt, 1e-3, "round-trip lost precision at hour=$h")
        }
    }

    @Test
    fun instantToHour_beforeDayStart_isNull() {
        // The chart relies on null here to skip sessions whose start instant predates the
        // day (e.g., after a scenario switch leaves stale session timestamps).
        val before = day.dayStart - 1.hours
        assertNull(day.instantToHour(before))
    }

    @Test
    fun instantToHour_atDayStart_isZero() {
        assertEquals(0.0, day.instantToHour(day.dayStart))
    }

    @Test
    fun instantToHour_pastDayEnd_returnsHoursElapsed() {
        // No clamping at the upper bound — callers (e.g. the chart's `coerceIn(0.0, 24.0)`) do
        // their own coercion. Pinning that the helper itself reports the full elapsed hours.
        val past = day.dayStart + 30.hours
        val h = day.instantToHour(past)
        assertNotNull(h)
        assertTrue(h > 24.0, "expected hours > 24 for an instant past day end, got $h")
    }
}
