package com.insola.uv.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.dose.VitaminDModel
import kotlin.time.Duration

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScenarioPicker(viewModel.scenarios.map { it.id to it.name }, state.scenario.id, viewModel::selectScenario) }
        item { Text(state.scenario.description, style = MaterialTheme.typography.bodySmall) }
        item { CurrentUvCard(state) }
        item { BudgetCard(state) }
        item { TimeToBurnCard(state.timeToBurn) }
        item { VitaminDCard(state.vitaminDBucket) }
        item { ClockScrubber(state.hourOfDay, viewModel::setHourOfDay) }
        item { SensitivityPicker(state.sensitivity, viewModel::setSensitivity) }
    }
}

@Composable
private fun ScenarioPicker(
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text("Scenario", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (id, name) ->
                FilterChip(
                    selected = id == selectedId,
                    onClick = { onSelect(id) },
                    label = { Text(name) },
                )
            }
        }
    }
}

@Composable
private fun CurrentUvCard(state: DashboardState) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Current UV", style = MaterialTheme.typography.labelMedium)
            Text(
                text = formatNumber(state.currentUv, 1),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = "Sun ${formatNumber(state.solarElevationDeg, 0)}° above horizon",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BudgetCard(state: DashboardState) {
    val pct = state.budgetPercent.coerceAtLeast(0.0)
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("UV budget today", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatNumber(pct, 0)}% · ${formatNumber(state.accumulatedDose, 2)} of " +
                    "${formatNumber(state.sensitivity.medThresholdUvIndexHours, 1)} UV-idx·h",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TimeToBurnCard(timeToBurn: Duration?) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Time to burn", style = MaterialTheme.typography.labelMedium)
            Text(
                text = timeToBurn?.let(::formatDuration) ?: "No burn risk today",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
private fun VitaminDCard(bucket: VitaminDModel.Bucket) {
    val label = when (bucket) {
        VitaminDModel.Bucket.None -> "None"
        VitaminDModel.Bucket.Trace -> "Trace"
        VitaminDModel.Bucket.Low -> "Low"
        VitaminDModel.Bucket.Adequate -> "Adequate"
        VitaminDModel.Bucket.Likely -> "Likely sufficient"
    }
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Vitamin D so far", style = MaterialTheme.typography.labelMedium)
            Text(label, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ClockScrubber(hourOfDay: Double, onChange: (Double) -> Unit) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Simulated clock — ${formatClock(hourOfDay)} local",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = hourOfDay.toFloat(),
                onValueChange = { onChange(it.toDouble()) },
                valueRange = 0f..24f,
                steps = 95, // 15-minute increments
            )
        }
    }
}

@Composable
private fun SensitivityPicker(selected: SkinSensitivity, onSelect: (SkinSensitivity) -> Unit) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Skin type (Fitzpatrick)", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkinSensitivity.entries.forEach { type ->
                    FilterChip(
                        selected = type == selected,
                        onClick = { onSelect(type) },
                        label = { Text(type.name) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(selected.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatNumber(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val factor = listOf(1.0, 10.0, 100.0, 1000.0).getOrElse(decimals) { 1.0 }
    val rounded = kotlin.math.round(value * factor) / factor
    return if (decimals == 0) rounded.toLong().toString()
    else {
        val s = rounded.toString()
        val dotIdx = s.indexOf('.')
        if (dotIdx < 0) "$s.${"0".repeat(decimals)}"
        else {
            val frac = s.substring(dotIdx + 1).padEnd(decimals, '0').take(decimals)
            s.substring(0, dotIdx) + "." + frac
        }
    }
}

private fun formatClock(hourOfDay: Double): String {
    val totalMinutes = (hourOfDay * 60).toInt().coerceIn(0, 24 * 60)
    val hh = (totalMinutes / 60).coerceAtMost(23)
    val mm = totalMinutes % 60
    return hh.toString().padStart(2, '0') + ":" + mm.toString().padStart(2, '0')
}

private fun formatDuration(d: Duration): String {
    val totalMinutes = d.inWholeMinutes.coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h <= 0 -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
