package com.insola.uv.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.dose.VitaminDModel
import kotlinx.datetime.Instant

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScenarioPicker(viewModel.scenarios.map { it.id to it.name }, state.scenario.id, viewModel::selectScenario) }
        item { Text(state.scenario.description, style = MaterialTheme.typography.bodySmall) }
        item {
            UvTodayCard(
                state = state,
                onHourChange = viewModel::setHourOfDay,
                onToggleOutside = viewModel::toggleOutside,
                onRemoveSession = viewModel::removeSession,
            )
        }
        item { SunBudgetCard(state) }
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
private fun UvTodayCard(
    state: DashboardState,
    onHourChange: (Double) -> Unit,
    onToggleOutside: () -> Unit,
    onRemoveSession: (Int) -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("UV today", style = MaterialTheme.typography.labelMedium)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatClock(state.hourOfDay),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.timeOutside.inWholeMinutes > 0) {
                        Text(
                            "${formatDuration(state.timeOutside)} outside",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            UvCurveChart(
                scenario = state.scenario,
                hourOfDay = state.hourOfDay,
                currentUv = state.currentUv,
                solarElevationDeg = state.solarElevationDeg,
                sunriseHour = state.sunriseHour,
                sunsetHour = state.sunsetHour,
                sessions = state.sessions,
                onHourChange = onHourChange,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(OutdoorGreen, "Outdoor")
                LegendDot(IndoorGray.copy(alpha = 0.55f), "Indoor")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onToggleOutside,
                modifier = Modifier.fillMaxWidth(),
                colors = if (state.isCurrentlyOutside) ButtonDefaults.buttonColors(
                    containerColor = OutdoorGreen,
                    contentColor = Color.White,
                ) else ButtonDefaults.buttonColors(),
            ) {
                Text(if (state.isCurrentlyOutside) "Go inside" else "Go outside")
            }
            if (state.sessions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SessionList(state, onRemoveSession)
            }
        }
    }
}

@Composable
private fun SessionList(state: DashboardState, onRemove: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.sessions.forEachIndexed { index, session ->
            val startHour = hoursFromDayStart(session.start, state)
            val endHour = session.end?.let { hoursFromDayStart(it, state) } ?: state.hourOfDay
            val durationMin = ((endHour - startHour).coerceAtLeast(0.0) * 60).toLong()
            val timeRange = if (session.isOpen) {
                "${formatClock(startHour)} → now"
            } else {
                "${formatClock(startHour)} – ${formatClock(endHour)}"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$timeRange · ${durationMin}m",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onRemove(index) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("×") }
            }
        }
    }
}

private fun hoursFromDayStart(instant: Instant, state: DashboardState): Double {
    val ms = (instant - state.scenario.dayStart).inWholeMilliseconds
    return ms / 3_600_000.0
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// Vitamin-D bucket colors. Trace = neutral gray, Low = amber building, Adequate = healthy
// green, Likely = saturated deep green.
private val VitDTrace = Color(0xFFBDBDBD)
private val VitDLow = Color(0xFFFFB300)
private val VitDAdequate = Color(0xFF66BB6A)
private val VitDLikely = Color(0xFF2E7D32)

@Composable
private fun SunBudgetCard(state: DashboardState) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Today's sun", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Burn budget", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = state.timeToBurn?.let { "burn in ${formatDuration(it)}" }
                        ?: "no burn risk",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            BurnBudgetBar(state.budgetPercent)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatNumber(state.budgetPercent.coerceAtLeast(0.0), 0)}% · " +
                    "${formatNumber(state.accumulatedDose, 2)} of " +
                    "${formatNumber(state.sensitivity.medThresholdUvIndexHours, 1)} UV-idx·h",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))

            Text("Vitamin D", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            VitaminDBar(state.vitaminDScore, state.sensitivity)
            Spacer(Modifier.height(2.dp))
            Text(vitaminDLabel(state.vitaminDBucket), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Burn budget bar: gradient anchored to absolute 0–100% (green→yellow→orange→red→purple),
 * filled proportionally from the left. Colors stay pinned to the underlying % so the band
 * the fill is currently in matches the UV-band palette of the chart above.
 */
@Composable
private fun BurnBudgetBar(budgetPercent: Double) {
    val frac = (budgetPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color = trackColor, topLeft = Offset.Zero, size = Size(w, h))
        if (frac > 0f) {
            val brush = Brush.horizontalGradient(
                colors = listOf(UvGreen, UvYellow, UvOrange, UvRed, UvPurple),
                startX = 0f,
                endX = w,
            )
            drawRect(brush = brush, topLeft = Offset.Zero, size = Size(w * frac, h))
        }
    }
}

/**
 * Vitamin-D bar: bar length is the actual score (normalized to a fixed multiple of MED so the
 * scale is comparable across skin types). The fill is split into bucket-colored segments so the
 * user reads both "how much" (length) and "what tier" (color).
 */
@Composable
private fun VitaminDBar(score: Double, sensitivity: SkinSensitivity) {
    val sdd = sensitivity.medThresholdUvIndexHours / 16.0
    val maxScore = 1.5 * sdd // shows all four bucket bands at full width with headroom past 1 SDD
    val frac = (score / maxScore).coerceIn(0.0, 1.0).toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val segments = listOf(
        0.25 * sdd to VitDTrace,
        0.5 * sdd to VitDLow,
        1.0 * sdd to VitDAdequate,
        maxScore to VitDLikely,
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color = trackColor, topLeft = Offset.Zero, size = Size(w, h))
        val filledScore = (frac * maxScore)
        var cursor = 0.0
        segments.forEach { (boundary, color) ->
            val segEnd = minOf(boundary, filledScore)
            if (segEnd > cursor) {
                val x0 = (cursor / maxScore).toFloat() * w
                val x1 = (segEnd / maxScore).toFloat() * w
                drawRect(color = color, topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
                cursor = segEnd
            }
        }
    }
}

private fun vitaminDLabel(bucket: VitaminDModel.Bucket): String = when (bucket) {
    VitaminDModel.Bucket.None -> "None"
    VitaminDModel.Bucket.Trace -> "Trace"
    VitaminDModel.Bucket.Low -> "Low"
    VitaminDModel.Bucket.Adequate -> "Adequate"
    VitaminDModel.Bucket.Likely -> "Likely sufficient"
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

