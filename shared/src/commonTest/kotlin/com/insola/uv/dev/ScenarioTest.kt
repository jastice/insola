package com.insola.uv.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ScenarioTest {

    private val scenario = Fixtures.byId("berlin")

    @Test
    fun hourToInstant_atZero_isDayStart() {
        assertEquals(scenario.dayStart, scenario.hourToInstant(0.0))
    }

    @Test
    fun hourToInstant_at24_isDayEnd() {
        assertEquals(scenario.dayStart + 24.hours, scenario.hourToInstant(24.0))
    }

    @Test
    fun roundTrip_preservesHours() {
        // The compute pipeline goes scrubber-hour → Instant → back to hour-of-day for chart
        // rendering; the round-trip needs to be tight enough that drag latency-free.
        listOf(0.0, 0.5, 6.25, 12.0, 17.987, 23.999).forEach { h ->
            val rt = scenario.instantToHour(scenario.hourToInstant(h))
            assertNotNull(rt)
            assertEquals(h, rt, 1e-3, "round-trip lost precision at hour=$h")
        }
    }

    @Test
    fun instantToHour_beforeDayStart_isNull() {
        // The chart relies on null here to skip sessions whose start instant predates the
        // scenario day (e.g., after a scenario switch leaves stale session timestamps).
        val before = scenario.dayStart - 1.hours
        assertNull(scenario.instantToHour(before))
    }

    @Test
    fun instantToHour_atDayStart_isZero() {
        assertEquals(0.0, scenario.instantToHour(scenario.dayStart))
    }

    @Test
    fun instantToHour_pastDayEnd_returnsHoursElapsed() {
        // No clamping at the upper bound — callers (e.g. the chart's `coerceIn(0.0, 24.0)`) do
        // their own coercion. Pinning that the helper itself reports the full elapsed hours.
        val past = scenario.dayStart + 30.hours
        val h = scenario.instantToHour(past)
        assertNotNull(h)
        assertTrue(h > 24.0, "expected hours > 24 for an instant past day end, got $h")
    }
}
