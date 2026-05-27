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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.VitaminDModel
import kotlin.time.Duration

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sky = skyColors(state.solarElevationDeg)
    val scheme = MaterialTheme.colorScheme.copy(
        background = sky.background,
        onBackground = sky.onBackground,
        surface = sky.surface,
        surfaceContainerLowest = sky.surface,
        surfaceContainerLow = sky.surface,
        surfaceContainer = sky.surface,
        surfaceContainerHigh = sky.surface,
        surfaceContainerHighest = sky.surface,
        // Tonal-elevation overlay (Card et al. mix surfaceTint into surface based on elevation).
        // Default surfaceTint is primary purple, which drowns our hue back to gray; null it out.
        // (Leaving surfaceVariant alone so the bar tracks stay visibly distinct.)
        surfaceTint = Color.Transparent,
    )
    MaterialTheme(colorScheme = scheme) {
        // Text outside a Surface (e.g. the scenario description) reads LocalContentColor, which
        // defaults to black. Provide the bg-contrast color so labels stay readable at night.
        CompositionLocalProvider(LocalContentColor provides sky.onBackground) {
            DashboardContent(state, viewModel, modifier.background(sky.background))
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    viewModel: DashboardViewModel,
    modifier: Modifier,
) {
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
            )
        }
        item { SunBudgetCard(state) }
        if (state.sessions.isNotEmpty()) {
            item { OutdoorLogCard(state, viewModel::removeSession) }
        }
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
        }
    }
}

@Composable
private fun OutdoorLogCard(state: DashboardState, onRemove: (Int) -> Unit) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Outdoor log", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            SessionList(state, onRemove)
        }
    }
}

@Composable
private fun SessionList(state: DashboardState, onRemove: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.sessions.forEachIndexed { index, session ->
            val startHour = state.scenario.instantToHour(session.start) ?: 0.0
            val endHour = session.end?.let { state.scenario.instantToHour(it) } ?: state.hourOfDay
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

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SunBudgetCard(state: DashboardState) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Today's sun", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("Burn budget", style = MaterialTheme.typography.labelSmall)
                Column(horizontalAlignment = Alignment.End) {
                    BurnTimeLine("first reddening", state.timeToFirstReddening)
                    BurnTimeLine("sunburn", state.timeToSunburn)
                }
            }
            Spacer(Modifier.height(4.dp))
            BurnBudgetBar(state.budgetPercent)
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.burnTier.label + " · " +
                    "${formatNumber(state.budgetPercent.coerceAtLeast(0.0), 0)}% · " +
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
 * Burn budget bar: full width represents 4 MED (the "serious burn" line). A smooth
 * green→yellow→orange→red→purple gradient is positionally anchored to the BurnTier band
 * boundaries (50% MED → yellow, 100% MED → orange, 200% MED → red, 400% MED → purple), and
 * only the portion up to the current accumulated dose is filled so the color under the head of
 * the fill always matches the user's current tier. Ticks at 25% and 50% of the bar mark the
 * 1-MED (first reddening) and 2-MED (sunburn) lines.
 */
@Composable
private fun BurnBudgetBar(budgetPercent: Double) {
    val ceiling = BurnTier.CEILING_FRACTION_OF_MED
    val frac = (budgetPercent / (ceiling * 100.0)).coerceIn(0.0, 1.0).toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurface
    val gradientStops = remember(ceiling) {
        BurnTier.entries
            .map { (it.minFractionOfMed / ceiling).toFloat() to it.color() }
            .toTypedArray()
    }
    // Ticks at the 1-MED (first reddening) and 2-MED (sunburn) lines.
    val tickFractions = remember(ceiling) {
        listOf(BurnTier.VisibleReddening, BurnTier.Sunburn)
            .map { (it.minFractionOfMed / ceiling).toFloat() }
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color = trackColor, topLeft = Offset.Zero, size = Size(w, h))
        if (frac > 0f) {
            val brush = Brush.horizontalGradient(
                colorStops = gradientStops,
                startX = 0f,
                endX = w,
            )
            drawRect(brush = brush, topLeft = Offset.Zero, size = Size(w * frac, h))
        }
        val tickW = 1.5.dp.toPx()
        tickFractions.forEach { x ->
            drawRect(
                color = tickColor,
                topLeft = Offset(x * w - tickW / 2, 0f),
                size = Size(tickW, h),
            )
        }
    }
}

@Composable
private fun BurnTimeLine(label: String, time: Duration?) {
    val text = when {
        time == null -> "$label · —"
        time == Duration.ZERO -> "$label · reached"
        else -> "$label · in ${formatDuration(time)}"
    }
    Text(text, style = MaterialTheme.typography.labelSmall)
}

/**
 * Vitamin-D bar: full width represents 1.5 × SDD (one standard daily dose) so the Likely band
 * has visible headroom past the 1-SDD line. A smooth gradient runs gray → amber → light green →
 * deep green, positionally anchored to the bucket boundaries (0.25, 0.5, 1.0 SDD as fractions
 * of the bar = 0.167, 0.333, 0.667). The fill stops at the current score so the head's color
 * always matches the user's current bucket.
 */
@Composable
private fun VitaminDBar(score: Double, sensitivity: SkinSensitivity) {
    val maxScore = 1.5 * (sensitivity.medThresholdUvIndexHours / 16.0)
    val frac = (score / maxScore).coerceIn(0.0, 1.0).toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color = trackColor, topLeft = Offset.Zero, size = Size(w, h))
        if (frac > 0f) {
            val brush = Brush.horizontalGradient(
                0.000f to VitDTrace,
                0.167f to VitDLow,
                0.333f to VitDAdequate,
                0.667f to VitDLikely,
                1.000f to VitDLikely,
                startX = 0f,
                endX = w,
            )
            drawRect(brush = brush, topLeft = Offset.Zero, size = Size(w * frac, h))
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

