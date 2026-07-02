package com.insola.uv.dashboard

import com.insola.uv.data.UvForecastProvider
import com.insola.uv.dev.Fixtures
import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.location.LocationProvider
import com.insola.uv.location.LocationSource
import com.insola.uv.location.ResolvedLocation
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Live-mode ViewModel behavior: load/upgrade/error flow, cancellation of superseded loads, the
 * action-visibility tick bump, and the midnight rollover. Runs on a [StandardTestDispatcher]
 * installed as Main (viewModelScope), with a mutable fake clock — no real time, no network.
 *
 * `advanceTimeBy` is used with exact amounts; `advanceUntilIdle` would never return because the
 * minute-ticker reschedules itself forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone = TimeZone.of("UTC") // keeps "local midnight" trivial to reason about
    private val point = GeoPoint(52.52, 13.40)

    @BeforeTest
    fun installMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class MutableClock(var current: Instant) : Clock {
        override fun now(): Instant = current
    }

    private class FakeLocationProvider(private val point: GeoPoint) : LocationProvider {
        override suspend fun resolve() = ResolvedLocation(point, LocationSource.Ip, "Berlin")
    }

    /**
     * Serves a synthetic two-day forecast anchored at the local midnight current when the fetch
     * *starts* (mirroring a request issued to the real API), after first running [onFetch] — which
     * tests use to suspend the fetch or move the clock mid-flight.
     */
    private class FakeForecastProvider(
        private val clock: Clock,
        private val zone: TimeZone,
        var failWith: Throwable? = null,
        var onFetch: suspend () -> Unit = {},
    ) : UvForecastProvider {
        var fetchCount = 0

        override suspend fun fetchForecast(location: GeoPoint): UvForecast {
            fetchCount++
            val requestDayStart = clock.todayIn(zone).atStartOfDayIn(zone)
            onFetch()
            failWith?.let { throw it }
            val samples = (0..48).map { h ->
                UvSample(requestDayStart + h.hours, if (h % 24 in 8..16) 5.0 else 0.0)
            }
            return UvForecast(location, samples)
        }
    }

    private val createdViewModels = mutableListOf<DashboardViewModel>()

    private fun viewModel(
        clock: MutableClock,
        forecast: FakeForecastProvider = FakeForecastProvider(clock, zone),
    ): DashboardViewModel =
        DashboardViewModel(forecast, FakeLocationProvider(point), clock, zone)
            .also { createdViewModels += it }

    /**
     * [runTest] with ViewModel teardown *inside* the test: the VM's minute-ticker reschedules
     * itself forever, and runTest's end-of-test idling would otherwise never terminate.
     */
    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher.scheduler) {
        try {
            body()
        } finally {
            createdViewModels.forEach { it.viewModelScope.cancel() }
            createdViewModels.clear()
        }
    }

    @Test
    fun load_upgradesEstimateToLiveForecast_anchoredAtLocalMidnight() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val vm = viewModel(clock)

        testScheduler.runCurrent()

        val state = vm.uiState.value
        assertEquals(DayMode.LiveForecast, state.mode)
        assertEquals("Berlin", state.place)
        assertNull(state.error)
        assertEquals(Instant.parse("2026-07-02T00:00:00Z"), state.dashboard.day.dayStart)
    }

    @Test
    fun fetchFailure_keepsEstimateOnScreen_withInlineError() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val forecast = FakeForecastProvider(clock, zone, failWith = RuntimeException("boom"))
        val vm = viewModel(clock, forecast)

        testScheduler.runCurrent()

        val state = vm.uiState.value
        assertEquals(DayMode.LiveEstimate, state.mode)
        assertNotNull(state.error)
        assertTrue(state.errorDetail.orEmpty().contains("RuntimeException"))
        assertEquals(25, state.dashboard.day.hourlyUv.size) // still renderable
    }

    @Test
    fun supersededLoad_isCancelledSilently_neverWritesErrorOverFixture() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val gate = CompletableDeferred<Unit>()
        val forecast = FakeForecastProvider(clock, zone, onFetch = { gate.await() })
        val vm = viewModel(clock, forecast)
        testScheduler.runCurrent() // initial refresh is now suspended inside the fetch

        vm.selectScenario(Fixtures.all.first().id) // cancels the in-flight load and pins the fixture
        testScheduler.runCurrent() // the cancelled coroutine resumes with CancellationException

        val state = vm.uiState.value
        assertEquals(DayMode.Fixture, state.mode)
        assertNull(state.error, "a superseded load must not surface a spurious error")
        assertNull(state.errorDetail)
        assertEquals(false, state.refreshing)
    }

    @Test
    fun toggleOutside_isVisibleImmediately_notAfterTheNextMinuteTick() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val vm = viewModel(clock)
        testScheduler.runCurrent()

        // The wall clock has moved on since the last tick; the action must still show up now.
        clock.current = Instant.parse("2026-07-02T10:00:30Z")
        vm.toggleOutside()
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.dashboard.isCurrentlyOutside)
        assertEquals(1, vm.uiState.value.dashboard.sessions.size)
    }

    @Test
    fun applySunscreen_isVisibleImmediately_notAfterTheNextMinuteTick() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val vm = viewModel(clock)
        testScheduler.runCurrent()

        clock.current = Instant.parse("2026-07-02T10:00:30Z")
        vm.applySunscreen(com.insola.uv.domain.Spf.Spf30)
        testScheduler.runCurrent()

        assertNotNull(
            vm.uiState.value.dashboard.activeAttenuation,
            "a just-applied patch must be active at the recomputed 'now'",
        )
    }

    @Test
    fun fetchStraddlingLocalMidnight_staysAnchoredToTheRequestDay() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T23:59:00Z"))
        val forecast = FakeForecastProvider(
            clock, zone,
            onFetch = { clock.current = Instant.parse("2026-07-03T00:00:30Z") },
        )
        val vm = viewModel(clock, forecast)

        testScheduler.runCurrent()

        // The day must be anchored to July 2 (the day the fetch was issued for), not re-derived
        // from the post-fetch clock — otherwise yesterday's forecast renders as a zero-UV "today".
        assertEquals(Instant.parse("2026-07-02T00:00:00Z"), vm.uiState.value.dashboard.day.dayStart)
    }

    @Test
    fun tickPastLocalMidnight_rollsTheDayOver_toAFreshForecast() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T23:59:30Z"))
        val forecast = FakeForecastProvider(clock, zone)
        val vm = viewModel(clock, forecast)
        testScheduler.runCurrent()
        assertEquals(Instant.parse("2026-07-02T00:00:00Z"), vm.uiState.value.dashboard.day.dayStart)

        clock.current = Instant.parse("2026-07-03T00:00:30Z")
        testScheduler.advanceTimeBy(61_000) // let the minute ticker fire
        testScheduler.runCurrent()

        val state = vm.uiState.value
        assertEquals(Instant.parse("2026-07-03T00:00:00Z"), state.dashboard.day.dayStart)
        assertEquals(DayMode.LiveForecast, state.mode)
        assertEquals(2, forecast.fetchCount)
    }

    @Test
    fun tickWithinTheSameDay_doesNotRefetch() = runVmTest {
        val clock = MutableClock(Instant.parse("2026-07-02T10:00:00Z"))
        val forecast = FakeForecastProvider(clock, zone)
        val vm = viewModel(clock, forecast)
        testScheduler.runCurrent()

        clock.current = Instant.parse("2026-07-02T10:01:00Z")
        testScheduler.advanceTimeBy(61_000)
        testScheduler.runCurrent()

        assertEquals(1, forecast.fetchCount)
        assertEquals(Instant.parse("2026-07-02T00:00:00Z"), vm.uiState.value.dashboard.day.dayStart)
    }
}
