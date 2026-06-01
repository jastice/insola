package com.insola.uv.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insola.uv.data.UvForecastProvider
import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.Spf
import com.insola.uv.domain.UvDay
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.location.LocationProvider
import com.insola.uv.location.LocationSource
import com.insola.uv.location.timezoneCentroid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Duration

data class DashboardState(
    /** The day-model driving every readout — a fixture's day in dev mode, the live forecast otherwise. */
    val day: UvDay,
    /** Draggable, preview-only scrubber marker (dashed). Decoupled from real time. */
    val previewHour: Double,
    /** Real wall-clock hour mapped into [day] — the solid "now" marker. Tracks the clock in live mode. */
    val nowHour: Double,
    /** Real time everything integrates/projects against. */
    val now: Instant,
    val profile: SkinProfile,
    val skinSummary: SkinSummary,
    val currentUv: Double,
    val solarElevationDeg: Double,
    val sunriseHour: Double?,
    val sunsetHour: Double?,
    val accumulatedDose: Double,
    val budgetPercent: Double,
    val burnTier: BurnTier,
    val timeToFirstReddening: Duration?,
    val timeToSunburn: Duration?,
    val vitaminDScore: Double,
    val vitaminDBucket: VitaminDModel.Bucket,
    val sessions: List<OutdoorSession>,
    val timeOutside: Duration,
    val isCurrentlyOutside: Boolean,
    /** Full user-built attenuation timeline (every apply event, including expired ones). */
    val attenuation: AttenuationTimeline,
    /** Latest patch still covering [now], or null. Drives the countdown bar in the UI. */
    val activeAttenuation: AttenuationTimeline.Patch?,
    /**
     * Numeric UV transmittance actually applied to forward-looking projections at [now] —
     * the stronger (smaller) of the always-on default SPF transmittance and whatever the
     * [attenuation] timeline says at [now]. `1.0` means bare skin.
     */
    val effectiveTransmittance: Double,
    /**
     * Forward-looking "top up soon" gauge derived from the UV forecast and remaining burn
     * budget. The chart draws its threshold curve as the red zone, and the Apply button
     * glows when current effective SPF sits below the threshold. See [ReapplyAdvisor].
     */
    val reapplyAdvisor: ReapplyAdvisor,
)

/** What's driving the day-model right now. */
enum class DayMode {
    /** Real Open-Meteo forecast for the resolved location. */
    LiveForecast,

    /** Network-free clear-sky estimate (still loading, or the forecast couldn't be reached). */
    LiveEstimate,

    /** A hidden dev fixture (scrubber-as-now). */
    Fixture,
}

/**
 * The dashboard is **always** renderable — there is no blocking pre-screen. It opens on a clear-sky
 * [DayMode.LiveEstimate] at the device-timezone city, then upgrades in place to [DayMode.LiveForecast]
 * once the real forecast loads. [refreshing] and [error] surface inline (a small status row + retry),
 * never as a full-screen gate, so a failed/slow network just leaves the estimate on screen.
 */
data class DashboardUiState(
    val dashboard: DashboardState,
    /** Human place name (city / metro) for the header, when one could be resolved. */
    val place: String?,
    /** Where the live location came from (drives the precision hint); null in fixture mode. */
    val locationSource: LocationSource?,
    val mode: DayMode,
    /** A forecast load is in flight — show an inline spinner. */
    val refreshing: Boolean,
    /** Inline, dismissable error (friendly summary) — null when there's nothing wrong. */
    val error: String?,
    /** Technical detail (exception type + message) for the error, surfaced for debugging. */
    val errorDetail: String? = null,
)

/**
 * Drives the dashboard from either the live forecast (default) or a hidden dev fixture.
 *
 * Live mode resolves the device location, fetches today's UV curve, and anchors "now" to the real
 * clock (advancing ~1/min). Dev mode replays a synthetic [Scenario] with scrubber-as-now. The
 * scrubber's [setPreviewHour] only ever moves the preview marker — never "now".
 */
class DashboardViewModel(
    private val forecastProvider: UvForecastProvider,
    private val locationProvider: LocationProvider,
    @Suppress("unused") private val devMode: Boolean = false,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val previewHourFlow = MutableStateFlow(wallClockHourOfDay(clock, zone))
    private val profileFlow = MutableStateFlow(SkinProfile.Default)
    private val sessionsFlow = MutableStateFlow<List<OutdoorSession>>(emptyList())
    private val attenuationFlow = MutableStateFlow(AttenuationTimeline.Empty)

    /** The current day-model + load status. Seeded synchronously so the first frame already renders. */
    private val loadStateFlow = MutableStateFlow(initialEstimate())

    /** Selected fixture id, or null while in live mode. Drives the dev picker highlight + "Live" chip. */
    private val selectedScenarioIdFlow = MutableStateFlow<String?>(null)
    val selectedScenarioId: StateFlow<String?> = selectedScenarioIdFlow.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Real-clock tick, re-emitting roughly once a minute so the live "now" marker advances on its
     * own. Dev/fixture mode ignores the value (scrubber-as-now), but keeping it in the combine is
     * harmless — recomputing the same fixture state once a minute costs nothing.
     */
    private val nowTickFlow: StateFlow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(NOW_TICK_MILLIS)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, clock.now())

    /** Bundle the three skin/exposure inputs so the top-level [combine] stays within its 5-arg limit. */
    private val skinInputsFlow: Flow<SkinInputs> =
        combine(profileFlow, sessionsFlow, attenuationFlow) { profile, sessions, attenuation ->
            SkinInputs(profile, sessions, attenuation)
        }

    val scenarios: List<Scenario> = Fixtures.all

    val uiState: StateFlow<DashboardUiState> =
        combine(loadStateFlow, previewHourFlow, nowTickFlow, skinInputsFlow) { load, previewHour, tick, skin ->
            toUiState(load, previewHour, tick, skin)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = toUiState(
                loadStateFlow.value,
                previewHourFlow.value,
                nowTickFlow.value,
                SkinInputs(profileFlow.value, sessionsFlow.value, attenuationFlow.value),
            ),
        )

    init {
        // The screen already shows the estimate; load the real forecast over it.
        refresh()
    }

    private fun toUiState(
        load: LoadState,
        previewHour: Double,
        tick: Instant,
        skin: SkinInputs,
    ): DashboardUiState {
        // Fixture mode tracks the scrubber; live modes anchor "now" to the wall clock.
        val now = if (load.mode == DayMode.Fixture) load.day.hourToInstant(previewHour) else tick
        val dashboard = DashboardCompute.compute(
            load.day, now, previewHour, skin.profile, skin.sessions, skin.attenuation,
        )
        return DashboardUiState(
            dashboard = dashboard,
            place = load.place,
            locationSource = load.source,
            mode = load.mode,
            refreshing = load.refreshing,
            error = load.error,
            errorDetail = load.errorDetail,
        )
    }

    /**
     * (Re)load the live forecast: resolve a location (GPS → last-known → IP → timezone), fetch
     * today's curve, and build the [UvDay]. A no-op in dev mode. Safe to call on launch, on Retry,
     * and after a location-permission grant (upgrades to GPS without restart).
     *
     * Never blocks the screen: the estimate stays up while loading, and a failed fetch leaves the
     * estimate in place with an inline error rather than wiping the dashboard.
     */
    fun refresh() {
        if (selectedScenarioIdFlow.value != null) return // dev fixture is pinned; nothing to fetch
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadStateFlow.value = loadStateFlow.value.copy(refreshing = true, error = null)
            val resolved = locationProvider.resolve()
            // Re-anchor the estimate to the resolved location so the city/curve reflect the best fix
            // we have even before (or without) the network forecast.
            if (resolved != null) {
                loadStateFlow.value = LoadState(
                    day = UvDay.clearSkyEstimate(resolved.point, zone, clock),
                    place = resolved.place,
                    source = resolved.source,
                    mode = DayMode.LiveEstimate,
                    refreshing = true,
                    error = null,
                )
            }
            val point = resolved?.point ?: loadStateFlow.value.day.location
            val day = try {
                val dayStart = clock.todayIn(zone).atStartOfDayIn(zone)
                val forecast = forecastProvider.fetchForecast(point, dayStart)
                UvDay.fromForecast(forecast, zone, clock)
            } catch (e: Throwable) {
                // Full stack trace to logcat (Android routes println to System.out); a compact
                // type+message goes to the UI so the cause is visible without a debugger.
                println("Insola: forecast load failed\n${e.stackTraceToString()}")
                loadStateFlow.value = loadStateFlow.value.copy(
                    refreshing = false,
                    error = "Couldn't reach the forecast service — showing a clear-sky estimate.",
                    errorDetail = "${e::class.simpleName}: ${e.message ?: "no message"}",
                )
                return@launch
            }
            loadStateFlow.value = LoadState(
                day = day,
                place = resolved?.place ?: loadStateFlow.value.place,
                source = resolved?.source,
                mode = DayMode.LiveForecast,
                refreshing = false,
                error = null,
            )
        }
    }

    /** Synchronous opening estimate: clear-sky curve at the device-timezone city (no GPS/IP/network). */
    private fun initialEstimate(): LoadState {
        val located = timezoneCentroid(zone.id, clock)
        return LoadState(
            day = UvDay.clearSkyEstimate(located.point, zone, clock),
            place = located.place,
            source = located.source,
            mode = DayMode.LiveEstimate,
            refreshing = true,
            error = null,
        )
    }

    /** Switch to a dev fixture (hidden long-press gesture). Pins the day; clears the live log. */
    fun selectScenario(id: String) {
        loadJob?.cancel()
        selectedScenarioIdFlow.value = id
        sessionsFlow.value = emptyList()
        attenuationFlow.value = AttenuationTimeline.Empty
        previewHourFlow.value = wallClockHourOfDay(clock, zone)
        loadStateFlow.value = LoadState(
            day = Fixtures.byId(id).day,
            place = null,
            source = null,
            mode = DayMode.Fixture,
            refreshing = false,
            error = null,
        )
    }

    /** Return to live mode from a dev fixture: reset to the estimate and reload. */
    fun goLive() {
        if (selectedScenarioIdFlow.value == null) return
        selectedScenarioIdFlow.value = null
        sessionsFlow.value = emptyList()
        attenuationFlow.value = AttenuationTimeline.Empty
        loadStateFlow.value = initialEstimate()
        refresh()
    }

    /** Clear the inline forecast error (the estimate stays on screen). */
    fun dismissError() {
        if (loadStateFlow.value.error == null) return
        loadStateFlow.value = loadStateFlow.value.copy(error = null, errorDetail = null)
    }

    fun setPreviewHour(hour: Double) {
        previewHourFlow.value = hour.coerceIn(0.0, 24.0)
    }

    fun setPhototype(p: SkinSensitivity) {
        profileFlow.value = profileFlow.value.copy(phototype = p)
    }

    fun setAcclimatization(a: Acclimatization) {
        profileFlow.value = profileFlow.value.copy(acclimatization = a)
    }

    /**
     * Drop a fresh attenuation patch onto the timeline at [spf]'s transmittance starting at "now".
     * Past patches stay — reapplying composes by `min` per instant so it never retroactively strips
     * coverage that an earlier patch already provided.
     */
    fun applySunscreen(spf: Spf) {
        if (spf == Spf.Off) return
        val patch = AttenuationTimeline.Patch(appliedAt = currentNow(), labelTransmittance = spf.transmittance)
        attenuationFlow.value = attenuationFlow.value + patch
    }

    fun clearSunscreenApplication() {
        attenuationFlow.value = AttenuationTimeline.Empty
    }

    /**
     * Toggle the currently-outside state at "now". Closes an open session if one exists; otherwise
     * opens a new one. In live mode "now" is the real wall clock; in dev mode it's the scrubber time.
     */
    fun toggleOutside() {
        val now = currentNow()
        val list = sessionsFlow.value
        val last = list.lastOrNull()
        sessionsFlow.value = when {
            last != null && last.isOpen && last.start <= now ->
                list.dropLast(1) + last.copy(end = now)
            else -> list + OutdoorSession(start = now)
        }
    }

    fun clearSessions() {
        sessionsFlow.value = emptyList()
    }

    fun removeSession(index: Int) {
        val list = sessionsFlow.value
        if (index !in list.indices) return
        sessionsFlow.value = list.toMutableList().apply { removeAt(index) }
    }

    /** The effective "now": the scrubber instant in fixture mode, the real clock otherwise. */
    private fun currentNow(): Instant {
        val load = loadStateFlow.value
        return if (load.mode == DayMode.Fixture) load.day.hourToInstant(previewHourFlow.value) else clock.now()
    }

    private data class LoadState(
        val day: UvDay,
        val place: String?,
        val source: LocationSource?,
        val mode: DayMode,
        val refreshing: Boolean,
        val error: String?,
        val errorDetail: String? = null,
    )

    private data class SkinInputs(
        val profile: SkinProfile,
        val sessions: List<OutdoorSession>,
        val attenuation: AttenuationTimeline,
    )

    companion object {
        private const val NOW_TICK_MILLIS = 60_000L

        private fun wallClockHourOfDay(clock: Clock, zone: TimeZone): Double {
            val dt = clock.now().toLocalDateTime(zone)
            return dt.hour + dt.minute / 60.0 + dt.second / 3600.0
        }
    }
}
