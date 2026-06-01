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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.Spf
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.location.LocationSource
import kotlinx.datetime.Instant
import kotlin.time.Duration

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, devMode: Boolean = false, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (val s = uiState) {
        DashboardUiState.Loading -> StatusScreen("Finding today's UV…", showSpinner = true, modifier = modifier)
        is DashboardUiState.Error -> StatusScreen(
            message = s.message,
            showSpinner = false,
            onRetry = viewModel::refresh,
            modifier = modifier,
        )
        is DashboardUiState.Ready -> ReadyScreen(s, viewModel, devMode, modifier)
    }
}

/** Plain centered Loading/Error surface shown before a [UvDay] is available. */
@Composable
private fun StatusScreen(
    message: String,
    showSpinner: Boolean,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    MaterialTheme {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showSpinner) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ReadyScreen(
    ready: DashboardUiState.Ready,
    viewModel: DashboardViewModel,
    devMode: Boolean,
    modifier: Modifier,
) {
    val state = ready.dashboard
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
            DashboardContent(ready, viewModel, devMode, modifier.background(sky.background))
        }
    }
}

private enum class DashboardTab(val label: String) { Day("Day"), Skin("Skin") }

@Composable
private fun DashboardContent(
    ready: DashboardUiState.Ready,
    viewModel: DashboardViewModel,
    devMode: Boolean,
    modifier: Modifier,
) {
    val state = ready.dashboard
    var selectedTab by rememberSaveable { mutableStateOf(DashboardTab.Day) }
    // Skin-tab "what-if" — a pure preview that stretches the sundial's burn ticks; it never
    // feeds the Day-tab integrals (those are driven only by applied, decaying patches). The SPF
    // choice is scenario-independent; the UV level resets to each scenario's peak.
    var previewSpf by rememberSaveable { mutableStateOf(Spf.Off) }
    var previewUv by remember(state.day.dayStart) {
        mutableFloatStateOf(state.skinSummary.peakUv.toFloat())
    }
    val selectedScenarioId by viewModel.selectedScenarioId.collectAsStateWithLifecycle()
    // Hidden dev tools, revealed by long-pressing the card header (debug builds only).
    var devToolsVisible by rememberSaveable { mutableStateOf(false) }
    val scenarioName = viewModel.scenarios.firstOrNull { it.id == selectedScenarioId }?.name
    // The card header names where the forecast is for: the resolved place (live) or the scenario.
    val headerTitle = when {
        !ready.isLive -> scenarioName ?: "UV today"
        !ready.place.isNullOrBlank() -> ready.place!!
        else -> "Current location"
    }
    val headerSubtitle = if (ready.isLive) locationPrecisionLabel(ready.locationSource) else null
    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            DashboardTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (selectedTab) {
                DashboardTab.Day -> {
                    if (devMode && devToolsVisible) {
                        item {
                            DevPanel(
                                scenarios = viewModel.scenarios.map { it.id to it.name },
                                selectedScenarioId = selectedScenarioId,
                                isLive = ready.isLive,
                                onSelectScenario = viewModel::selectScenario,
                                onGoLive = viewModel::goLive,
                            )
                        }
                    }
                    if (!ready.isLive) {
                        viewModel.scenarios.firstOrNull { it.id == selectedScenarioId }?.let { scenario ->
                            item { Text(scenario.description, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    item {
                        UvTodayCard(
                            title = headerTitle,
                            subtitle = headerSubtitle,
                            state = state,
                            onHourChange = viewModel::setPreviewHour,
                            onToggleOutside = viewModel::toggleOutside,
                            onRemoveSession = viewModel::removeSession,
                            onTitleLongPress = if (devMode) {
                                { devToolsVisible = !devToolsVisible }
                            } else {
                                null
                            },
                        )
                    }
                    item {
                        ApplySunscreenCard(
                            timeline = state.attenuation,
                            activePatch = state.activeAttenuation,
                            advisor = state.reapplyAdvisor,
                            now = state.now,
                            onApply = viewModel::applySunscreen,
                            onClear = viewModel::clearSunscreenApplication,
                        )
                    }
                    item { SunBudgetCard(state) }
                }
                DashboardTab.Skin -> {
                    item { PhototypePicker(state.profile.phototype, viewModel::setPhototype) }
                    item {
                        AcclimatizationPicker(
                            profile = state.profile,
                            onSelect = viewModel::setAcclimatization,
                        )
                    }
                    item {
                        SkinSummaryChart(
                            summary = state.skinSummary,
                            previewSpf = previewSpf,
                            onPreviewSpfChange = { previewSpf = it },
                            previewUv = previewUv,
                            onPreviewUvChange = { previewUv = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hidden dev tools (debug builds, revealed by long-pressing the "UV today" title): a "Live" chip
 * to return to the real forecast, plus the fixture scenario picker.
 */
@Composable
private fun DevPanel(
    scenarios: List<Pair<String, String>>,
    selectedScenarioId: String?,
    isLive: Boolean,
    onSelectScenario: (String) -> Unit,
    onGoLive: () -> Unit,
) {
    Column {
        Text("Dev scenarios", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = isLive,
                onClick = onGoLive,
                label = { Text("Live forecast") },
            )
            scenarios.forEach { (id, name) ->
                FilterChip(
                    selected = !isLive && id == selectedScenarioId,
                    onClick = { onSelectScenario(id) },
                    label = { Text(name) },
                )
            }
        }
    }
}

/**
 * Caption under the location header conveying how precise the fix is. A precise GPS fix needs no
 * qualifier (the place name stands alone); coarser sources are flagged as approximate.
 */
private fun locationPrecisionLabel(source: LocationSource?): String? = when (source) {
    LocationSource.Gps -> null
    LocationSource.LastKnown -> "Last known location"
    LocationSource.Ip -> "Approximate · based on IP"
    LocationSource.Timezone -> "Approximate · based on time zone"
    null -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UvTodayCard(
    title: String,
    subtitle: String?,
    state: DashboardState,
    onHourChange: (Double) -> Unit,
    onToggleOutside: () -> Unit,
    onRemoveSession: (Int) -> Unit,
    onTitleLongPress: (() -> Unit)? = null,
) {
    var showLog by remember { mutableStateOf(false) }
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Header names the location (live) or scenario (dev). Clock lives in the chart
                // readout ("Sun 33° • 17:00"), so it isn't repeated here. Long-press reveals the
                // hidden dev tools (debug builds only).
                Column(
                    modifier = if (onTitleLongPress != null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onTitleLongPress() })
                        }
                    } else {
                        Modifier
                    },
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilterChip(
                    selected = state.isCurrentlyOutside,
                    onClick = onToggleOutside,
                    label = { Text(if (state.isCurrentlyOutside) "Outside" else "Inside") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OutdoorGreen,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            UvCurveChart(
                day = state.day,
                previewHour = state.previewHour,
                nowHour = state.nowHour,
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
                // The session intervals are already drawn as green bands on the timeline above;
                // the chip opens a sheet for the exact times + delete. Outlined chip = tappable.
                if (state.sessions.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    val outsideLabel = if (state.timeOutside.inWholeMinutes > 0) {
                        "${formatDuration(state.timeOutside)} outside"
                    } else {
                        "Outdoor log"
                    }
                    AssistChip(
                        onClick = { showLog = true },
                        label = { Text("$outsideLabel ›") },
                    )
                }
            }
        }
    }

    if (showLog) {
        ModalBottomSheet(onDismissRequest = { showLog = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Text("Outdoor log", style = MaterialTheme.typography.titleMedium)
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
            val startHour = state.day.instantToHour(session.start) ?: 0.0
            val endHour = session.end?.let { state.day.instantToHour(it) } ?: state.nowHour
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
        Column(Modifier.padding(12.dp)) {
            Text("Today's sun", style = MaterialTheme.typography.titleSmall)
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
                text = burnBudgetSubtitle(state),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))

            Text("Vitamin D", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            VitaminDBar(state.vitaminDScore, state.profile)
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
 * Vitamin-D bar: full width represents 1.5 × SDD (one standard daily dose) so the Sufficient band
 * has visible headroom past the 1-SDD line. A smooth gradient runs gray → amber → light green →
 * deep green, positionally anchored to the bucket boundaries (0.25, 0.5, 1.0 SDD as fractions
 * of the bar = 0.167, 0.333, 0.667). The fill stops at the current score so the head's color
 * always matches the user's current bucket.
 */
private fun burnBudgetSubtitle(state: DashboardState): String {
    val baseline = state.profile.phototype.medThresholdUvIndexHours
    val effective = state.profile.effectiveMedUvIndexHours
    val tan = state.profile.effectiveAcclimatizationFactor
    val medText = if (tan > 1.0001) {
        "${formatNumber(effective, 1)} UV-idx·h (×${formatNumber(tan, 1)} tan, " +
            "base ${formatNumber(baseline, 1)})"
    } else {
        "${formatNumber(baseline, 1)} UV-idx·h"
    }
    return state.burnTier.label + " · " +
        "${formatNumber(state.budgetPercent.coerceAtLeast(0.0), 0)}% · " +
        "${formatNumber(state.accumulatedDose, 2)} of $medText"
}

@Composable
private fun VitaminDBar(score: Double, profile: SkinProfile) {
    // Fill matches the bucket: SDD anchored to baseline phototype MED, score attenuated by
    // melanin (acclimatization) — see VitaminDModel.bucket / effectiveYield.
    val effective = VitaminDModel.effectiveYield(score, profile)
    val maxScore = 1.5 * (profile.phototype.medThresholdUvIndexHours / 16.0)
    val frac = (effective / maxScore).coerceIn(0.0, 1.0).toFloat()
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
                0.667f to VitDSufficient,
                1.000f to VitDSufficient,
                startX = 0f,
                endX = w,
            )
            drawRect(brush = brush, topLeft = Offset.Zero, size = Size(w * frac, h))
        }
    }
}

@Composable
private fun PhototypePicker(selected: SkinSensitivity, onSelect: (SkinSensitivity) -> Unit) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Skin type (Fitzpatrick)", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val accent = MaterialTheme.colorScheme.onBackground
                SkinSensitivity.entries.forEach { type ->
                    val tone = skinTone(type)
                    val labelColor = tone.contrastingOnTone()
                    val isSelected = type == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(type) },
                        label = {
                            Text(
                                type.name,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        modifier = if (isSelected) Modifier.weight(1f).scale(1.1f) else Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = tone,
                            selectedContainerColor = tone,
                            labelColor = labelColor,
                            selectedLabelColor = labelColor,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color.Transparent,
                            selectedBorderColor = accent,
                            borderWidth = 0.dp,
                            selectedBorderWidth = 1.5.dp,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(selected.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AcclimatizationPicker(
    profile: SkinProfile,
    onSelect: (Acclimatization) -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Tan", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val accent = MaterialTheme.colorScheme.onBackground
                Acclimatization.entries.forEach { level ->
                    val tone = skinTone(profile.phototype, level)
                    val labelColor = tone.contrastingOnTone()
                    val isSelected = level == profile.acclimatization
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(level) },
                        label = { Text(level.name) },
                        modifier = if (isSelected) Modifier.scale(1.1f) else Modifier,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = tone,
                            selectedContainerColor = tone,
                            labelColor = labelColor,
                            selectedLabelColor = labelColor,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color.Transparent,
                            selectedBorderColor = accent,
                            borderWidth = 0.dp,
                            selectedBorderWidth = 1.5.dp,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Show the actually-applied multiplier (after the phototype cap clips it).
            val applied = profile.effectiveAcclimatizationFactor
            val capped = applied < profile.acclimatization.factor - 1e-9
            val capNote = if (capped) {
                " (capped at ×${formatNumber(applied, 1)} for type ${profile.phototype.name})"
            } else {
                ""
            }
            Text(
                profile.acclimatization.description + " · MED ×${formatNumber(applied, 1)}" + capNote,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** SPF levels offered for ad-hoc application — bare skin (`Off`) is never an "apply" choice. */
private val ApplySpfChoices: List<Spf> = listOf(Spf.Spf15, Spf.Spf30, Spf.Spf50)

@Composable
private fun ApplySunscreenCard(
    timeline: AttenuationTimeline,
    activePatch: AttenuationTimeline.Patch?,
    advisor: ReapplyAdvisor,
    now: Instant,
    onApply: (Spf) -> Unit,
    onClear: () -> Unit,
) {
    var applySelection by rememberSaveable {
        mutableStateOf(activePatch?.let { Spf.nearestForTransmittance(it.labelTransmittance) } ?: Spf.Spf30)
    }
    val hasAnyPatch = timeline.patches.isNotEmpty()
    val nudgeReapply = hasAnyPatch && isInReapplyZone(timeline, advisor, now)

    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("SPF", style = MaterialTheme.typography.labelLarge)
                ApplySpfChoices.forEach { spf ->
                    val isSelected = spf == applySelection
                    val accent = MaterialTheme.colorScheme.onBackground
                    FilterChip(
                        selected = isSelected,
                        onClick = { applySelection = spf },
                        label = { Text(spf.factor.toString()) },
                        modifier = if (isSelected) Modifier.scale(1.1f) else Modifier,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color.Transparent,
                            selectedBorderColor = accent,
                            borderWidth = 0.dp,
                            selectedBorderWidth = 1.5.dp,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hasAnyPatch) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                ApplyButton(
                    label = "Apply",
                    glow = nudgeReapply,
                    onClick = { onApply(applySelection) },
                )
            }
            Spacer(Modifier.height(10.dp))
            AttenuationChart(timeline = timeline, advisor = advisor, now = now)
        }
    }
}

@Composable
private fun ApplyButton(label: String, glow: Boolean, onClick: () -> Unit) {
    if (!glow) {
        TextButton(onClick = onClick) { Text(label) }
        return
    }
    val transition = rememberInfiniteTransition(label = "reapplyGlow")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reapplyGlowPulse",
    )
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = pulse),
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(label)
    }
}


