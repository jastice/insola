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

/**
 * Top-level screen state. The day-model is loaded asynchronously in live mode (location → forecast),
 * so the dashboard is only [Ready] once a [UvDay] exists; until then the UI shows [Loading] or
 * [Error] (with a working retry).
 */
sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Error(val message: String) : DashboardUiState
    data class Ready(
        val dashboard: DashboardState,
        /** Where the live location came from (for the precision hint); null in dev/fixture mode. */
        val locationSource: LocationSource?,
        /** Human place name (city / metro) for the header, when one could be resolved. */
        val place: String?,
        /** True when driven by the live forecast; false when a dev fixture is selected. */
        val isLive: Boolean,
    ) : DashboardUiState
}

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

    /** The loaded day (live or fixture), or its loading/error state. */
    private val dayResultFlow = MutableStateFlow<DayResult>(DayResult.Loading)

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
        combine(dayResultFlow, previewHourFlow, nowTickFlow, skinInputsFlow) { result, previewHour, tick, skin ->
            toUiState(result, previewHour, tick, skin)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DashboardUiState.Loading,
        )

    init {
        // Live data is the app — start a load immediately.
        refresh()
    }

    private fun toUiState(
        result: DayResult,
        previewHour: Double,
        tick: Instant,
        skin: SkinInputs,
    ): DashboardUiState = when (result) {
        DayResult.Loading -> DashboardUiState.Loading
        is DayResult.Error -> DashboardUiState.Error(result.message)
        is DayResult.Loaded -> {
            // Live mode anchors "now" to the wall clock; dev mode tracks the scrubber.
            val now = if (result.isLive) tick else result.day.hourToInstant(previewHour)
            val dashboard = DashboardCompute.compute(
                result.day, now, previewHour, skin.profile, skin.sessions, skin.attenuation,
            )
            DashboardUiState.Ready(dashboard, result.source, result.place, result.isLive)
        }
    }

    /**
     * (Re)load the live forecast: resolve a location (GPS → last-known → IP → timezone), fetch
     * today's curve, and build the [UvDay]. A no-op in dev mode. Safe to call on launch, on Retry,
     * and after a location-permission grant (upgrades to GPS without restart).
     */
    fun refresh() {
        if (selectedScenarioIdFlow.value != null) return // dev fixture is pinned; nothing to fetch
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            dayResultFlow.value = DayResult.Loading
            val resolved = locationProvider.resolve()
            if (resolved == null) {
                dayResultFlow.value = DayResult.Error("Couldn't determine your location.")
                return@launch
            }
            val day = try {
                val dayStart = clock.todayIn(zone).atStartOfDayIn(zone)
                val forecast = forecastProvider.fetchForecast(resolved.point, dayStart)
                UvDay.fromForecast(forecast, zone, clock)
            } catch (_: Throwable) {
                dayResultFlow.value = DayResult.Error("Couldn't load the UV forecast.")
                return@launch
            }
            dayResultFlow.value = DayResult.Loaded(day, resolved.source, resolved.place, isLive = true)
        }
    }

    /** Switch to a dev fixture (hidden long-press gesture). Pins the day; clears the live log. */
    fun selectScenario(id: String) {
        loadJob?.cancel()
        selectedScenarioIdFlow.value = id
        sessionsFlow.value = emptyList()
        attenuationFlow.value = AttenuationTimeline.Empty
        previewHourFlow.value = wallClockHourOfDay(clock, zone)
        dayResultFlow.value = DayResult.Loaded(Fixtures.byId(id).day, source = null, place = null, isLive = false)
    }

    /** Return to live mode from a dev fixture and reload. */
    fun goLive() {
        if (selectedScenarioIdFlow.value == null) return
        selectedScenarioIdFlow.value = null
        sessionsFlow.value = emptyList()
        attenuationFlow.value = AttenuationTimeline.Empty
        refresh()
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

    /** The effective "now": the real clock in live mode, the scrubber instant in dev mode. */
    private fun currentNow(): Instant {
        val result = dayResultFlow.value
        return if (result is DayResult.Loaded && !result.isLive) {
            result.day.hourToInstant(previewHourFlow.value)
        } else {
            clock.now()
        }
    }

    private sealed interface DayResult {
        data object Loading : DayResult
        data class Error(val message: String) : DayResult
        data class Loaded(
            val day: UvDay,
            val source: LocationSource?,
            val place: String?,
            val isLive: Boolean,
        ) : DayResult
    }

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
