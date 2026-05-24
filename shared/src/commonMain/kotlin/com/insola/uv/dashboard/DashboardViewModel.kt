package com.insola.uv.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.dose.VitaminDModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlin.time.Duration

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
)

class DashboardViewModel(
    initialScenarioId: String = Fixtures.all.first().id,
) : ViewModel() {

    private val scenarioIdFlow = MutableStateFlow(initialScenarioId)
    private val hourFlow = MutableStateFlow(12.0)
    private val sensitivityFlow = MutableStateFlow(SkinSensitivity.Default)

    val scenarios: List<Scenario> = Fixtures.all

    val state: StateFlow<DashboardState> =
        combine(scenarioIdFlow, hourFlow, sensitivityFlow) { id, hour, skin ->
            DashboardCompute.compute(Fixtures.byId(id), hour, skin)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DashboardCompute.compute(
                Fixtures.byId(initialScenarioId),
                hourFlow.value,
                sensitivityFlow.value,
            ),
        )

    fun selectScenario(id: String) {
        scenarioIdFlow.value = id
    }

    fun setHourOfDay(hour: Double) {
        hourFlow.value = hour.coerceIn(0.0, 24.0)
    }

    fun setSensitivity(s: SkinSensitivity) {
        sensitivityFlow.value = s
    }

}
