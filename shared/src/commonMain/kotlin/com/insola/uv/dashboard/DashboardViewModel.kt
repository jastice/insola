package com.insola.uv.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinSensitivity
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
import kotlin.time.Duration.Companion.minutes

data class DashboardState(
    val scenario: Scenario,
    val hourOfDay: Double,
    val now: Instant,
    val sensitivity: SkinSensitivity,
    val currentUv: Double,
    val solarElevationDeg: Double,
    val accumulatedDose: Double,
    val budgetPercent: Double,
    val timeToBurn: Duration?,
    val vitaminDBucket: VitaminDModel.Bucket,
    val sessions: List<OutdoorSession>,
    val isCurrentlyOutside: Boolean,
)

class DashboardViewModel(
    initialScenarioId: String = Fixtures.all.first().id,
) : ViewModel() {

    private val scenarioIdFlow = MutableStateFlow(initialScenarioId)
    private val hourFlow = MutableStateFlow(wallClockHourOfDay())
    private val sensitivityFlow = MutableStateFlow(SkinSensitivity.Default)
    private val sessionsFlow = MutableStateFlow<List<OutdoorSession>>(emptyList())

    val scenarios: List<Scenario> = Fixtures.all

    val state: StateFlow<DashboardState> =
        combine(scenarioIdFlow, hourFlow, sensitivityFlow, sessionsFlow) { id, hour, skin, sessions ->
            DashboardCompute.compute(Fixtures.byId(id), hour, skin, sessions)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DashboardCompute.compute(
                Fixtures.byId(initialScenarioId),
                hourFlow.value,
                sensitivityFlow.value,
                sessionsFlow.value,
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

    fun setSensitivity(s: SkinSensitivity) {
        sensitivityFlow.value = s
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

    private fun currentNow(): Instant {
        val scenario = Fixtures.byId(scenarioIdFlow.value)
        return scenario.dayStart + (hourFlow.value * 60).minutes
    }

    companion object {
        private fun wallClockHourOfDay(): Double {
            val dt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            return dt.hour + dt.minute / 60.0 + dt.second / 3600.0
        }
    }
}
