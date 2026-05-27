package com.insola.uv.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.Spf
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.VitaminDModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

data class DashboardState(
    val scenario: Scenario,
    val hourOfDay: Double,
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
)

class DashboardViewModel(
    initialScenarioId: String = Fixtures.all.first().id,
) : ViewModel() {

    private val scenarioIdFlow = MutableStateFlow(initialScenarioId)
    private val hourFlow = MutableStateFlow(wallClockHourOfDay())
    private val profileFlow = MutableStateFlow(SkinProfile.Default)
    private val sessionsFlow = MutableStateFlow<List<OutdoorSession>>(emptyList())
    private val attenuationFlow = MutableStateFlow(AttenuationTimeline.Empty)

    val scenarios: List<Scenario> = Fixtures.all

    val state: StateFlow<DashboardState> =
        combine(scenarioIdFlow, hourFlow, profileFlow, sessionsFlow, attenuationFlow) {
            id, hour, profile, sessions, attenuation ->
            DashboardCompute.compute(Fixtures.byId(id), hour, profile, sessions, attenuation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DashboardCompute.compute(
                Fixtures.byId(initialScenarioId),
                hourFlow.value,
                profileFlow.value,
                sessionsFlow.value,
                attenuationFlow.value,
            ),
        )

    fun selectScenario(id: String) {
        if (scenarioIdFlow.value == id) return
        scenarioIdFlow.value = id
        // Sessions are anchored to the previous scenario's day — clear them so a fresh scenario
        // starts with a clean log.
        sessionsFlow.value = emptyList()
    }

    fun setHourOfDay(hour: Double) {
        hourFlow.value = hour.coerceIn(0.0, 24.0)
    }

    fun setPhototype(p: SkinSensitivity) {
        profileFlow.value = profileFlow.value.copy(phototype = p)
    }

    fun setAcclimatization(a: Acclimatization) {
        profileFlow.value = profileFlow.value.copy(acclimatization = a)
    }

    fun setDefaultSpf(spf: Spf) {
        profileFlow.value = profileFlow.value.copy(defaultSpf = spf)
    }

    /**
     * Drop a fresh attenuation patch onto the timeline at [spf]'s transmittance starting at the
     * current scrubber time. Past patches stay — reapplying composes by `min` per instant so it
     * never retroactively strips coverage that an earlier patch already provided.
     */
    fun applySunscreen(spf: Spf) {
        if (spf == Spf.Off) return
        val patch = AttenuationTimeline.Patch(appliedAt = currentNow(), transmittance = spf.transmittance)
        attenuationFlow.value = attenuationFlow.value + patch
    }

    fun clearSunscreenApplication() {
        attenuationFlow.value = AttenuationTimeline.Empty
    }

    /**
     * Toggle the currently-outside state at the current scrubber time. Closes an open session if
     * one exists; otherwise opens a new one at "now".
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

    private fun currentNow(): Instant =
        Fixtures.byId(scenarioIdFlow.value).hourToInstant(hourFlow.value)

    companion object {
        private fun wallClockHourOfDay(): Double {
            val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            return dt.hour + dt.minute / 60.0 + dt.second / 3600.0
        }
    }
}
