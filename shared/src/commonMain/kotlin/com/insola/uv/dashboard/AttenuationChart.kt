package com.insola.uv.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insola.uv.domain.AttenuationTimeline
import kotlinx.datetime.Instant
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/** Visible time window starting at `now`. */
private val WINDOW: Duration = 4.hours

/** Sampling resolution for the polyline — 2 min gives ~120 segments across the window. */
private val SAMPLE_STEP: Duration = 2.minutes

/** Y-axis floor. We never plot below SPF 1 (bare skin). */
private const val MIN_SPF: Double = 1.0

/** Minimum y-axis ceiling so a "default Off + no patches" chart still has visible vertical space. */
private const val MIN_AXIS_TOP: Double = 5.0

/** Marker value for "budget already spent → threshold is +∞" — clamped to plot top when drawn. */
private const val BUDGET_SPENT_SENTINEL: Double = 1e6

/** True when current effective SPF is below the advisor's threshold curve at [now]. */
internal fun isInReapplyZone(
    timeline: AttenuationTimeline,
    advisor: ReapplyAdvisor,
    now: Instant,
): Boolean = advisor.needsTopUp(effectiveSpfAt(timeline, now), now)

/**
 * Effective SPF over the next [WINDOW] starting at [now], read straight from the [timeline] at
 * each instant (bare skin = SPF 1 where no patch covers it) — the same protection the integrator
 * sees feeding the burn meter. Apply events are marked with thin vertical lines.
 *
 * The translucent red band shows [advisor]'s threshold curve `S(t) = ∫UV/safeDose over the next
 * horizon at t`. Where the SPF curve dips below the band, the user would run out of safe outdoor
 * time within the horizon without a top-up.
 */
@Composable
internal fun AttenuationChart(
    timeline: AttenuationTimeline,
    advisor: ReapplyAdvisor,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = onSurface.copy(alpha = 0.15f)
    val markerColor = onSurface.copy(alpha = 0.4f)
    val reapplyZoneColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = onSurface)
    val textMeasurer = rememberTextMeasurer()

    val samples = remember(timeline, now) { sampleEffectiveSpf(timeline, now) }
    val thresholdCurve = remember(advisor, now) { sampleThresholdSpf(advisor, now) }
    // Include the threshold curve in axis-top so a high-UV midday threshold isn't clipped off
    // the chart and the curve relationship reads honestly. Capped infinities (budget already
    // spent) are filtered out so they don't blow the axis.
    val axisTop = remember(samples, thresholdCurve) {
        val finiteThresholds = thresholdCurve.map { it.spf }.filter { it < BUDGET_SPENT_SENTINEL }
        axisTopFor(samples.map { it.spf } + finiteThresholds)
    }
    val applyMarkers = remember(timeline, now) { applyMarkersInsideWindow(timeline, now) }

    val currentSpf = effectiveSpfAt(timeline, now)
    Box(modifier = modifier.fillMaxWidth().height(72.dp)) {
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription =
                    "Sunscreen protection over the next 4 hours. Current effective SPF " +
                        formatNumber(currentSpf, 0) +
                        if (isInReapplyZone(timeline, advisor, now)) ". Reapply soon." else "."
            },
        ) {
            val leftPad = 22.dp.toPx()
            val rightPad = 4.dp.toPx()
            val topPad = 4.dp.toPx()
            val bottomPad = 12.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            val plotLeft = leftPad
            val plotTop = topPad
            val plotBottom = topPad + plotH

            drawReapplyZone(
                thresholdCurve = thresholdCurve,
                plotLeft = plotLeft,
                plotBottom = plotBottom,
                plotW = plotW,
                plotH = plotH,
                axisTop = axisTop,
                color = reapplyZoneColor,
            )

            drawGridAndLabels(
                plotLeft = plotLeft,
                plotTop = plotTop,
                plotW = plotW,
                plotH = plotH,
                axisTop = axisTop,
                gridColor = gridColor,
                labelStyle = labelStyle,
                measurer = textMeasurer,
            )

            drawApplyMarkers(
                markers = applyMarkers,
                plotLeft = plotLeft,
                plotTop = plotTop,
                plotW = plotW,
                plotH = plotH,
                color = markerColor,
            )

            drawCurve(
                samples = samples,
                plotLeft = plotLeft,
                plotBottom = plotBottom,
                plotW = plotW,
                plotH = plotH,
                axisTop = axisTop,
                color = curveColor,
            )
        }
    }
}

private data class TimedSpf(val elapsedHours: Double, val spf: Double)

private fun sampleEffectiveSpf(
    timeline: AttenuationTimeline,
    now: Instant,
): List<TimedSpf> {
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    val stepHours = SAMPLE_STEP.toDouble(DurationUnit.HOURS)
    val n = ceil(windowHours / stepHours).toInt()
    return (0..n).map { i ->
        val dt = (i * stepHours).coerceAtMost(windowHours)
        TimedSpf(dt, effectiveSpfAt(timeline, now + dt.hours))
    }
}

private fun effectiveSpfAt(timeline: AttenuationTimeline, t: Instant): Double {
    val transmittance = timeline.transmittanceAt(t)
    if (transmittance <= 0.0) return MIN_SPF
    return (1.0 / transmittance).coerceAtLeast(MIN_SPF)
}

private fun sampleThresholdSpf(advisor: ReapplyAdvisor, now: Instant): List<TimedSpf> {
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    val stepHours = SAMPLE_STEP.toDouble(DurationUnit.HOURS)
    val n = ceil(windowHours / stepHours).toInt()
    return (0..n).map { i ->
        val dt = (i * stepHours).coerceAtMost(windowHours)
        val raw = advisor.thresholdSpfAt(now + dt.hours)
        val capped = if (raw.isFinite()) raw else BUDGET_SPENT_SENTINEL
        TimedSpf(dt, capped.coerceAtLeast(MIN_SPF))
    }
}

private fun axisTopFor(spfSamples: List<Double>): Double {
    val peak = spfSamples.maxOrNull() ?: MIN_SPF
    return maxOf(MIN_AXIS_TOP, peak * 1.15)
}

private fun applyMarkersInsideWindow(timeline: AttenuationTimeline, now: Instant): List<Double> {
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    return timeline.patches
        .map { (it.appliedAt - now).toDouble(DurationUnit.HOURS) }
        .filter { it in 0.0..windowHours }
}

private fun DrawScope.drawGridAndLabels(
    plotLeft: Float,
    plotTop: Float,
    plotW: Float,
    plotH: Float,
    axisTop: Double,
    gridColor: Color,
    labelStyle: TextStyle,
    measurer: TextMeasurer,
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)
    val baselineY = plotTop + plotH
    val topY = plotTop

    drawLine(
        color = gridColor,
        start = Offset(plotLeft, baselineY),
        end = Offset(plotLeft + plotW, baselineY),
        strokeWidth = 1f,
    )
    drawLine(
        color = gridColor,
        start = Offset(plotLeft, topY),
        end = Offset(plotLeft + plotW, topY),
        strokeWidth = 1f,
        pathEffect = dashed,
    )

    val bottomLabel = measurer.measure("1", style = labelStyle)
    val topLabel = measurer.measure(formatSpf(axisTop), style = labelStyle)
    drawText(
        bottomLabel,
        topLeft = Offset(plotLeft - bottomLabel.size.width - 4.dp.toPx(), baselineY - bottomLabel.size.height / 2f),
    )
    drawText(
        topLabel,
        topLeft = Offset(plotLeft - topLabel.size.width - 4.dp.toPx(), topY - topLabel.size.height / 2f),
    )

    val windowHours = WINDOW.toDouble(DurationUnit.HOURS).toInt()
    for (h in 0..windowHours) {
        val x = plotLeft + plotW * (h / windowHours.toDouble()).toFloat()
        if (h > 0 && h < windowHours) {
            drawLine(
                color = gridColor,
                start = Offset(x, plotTop),
                end = Offset(x, baselineY),
                strokeWidth = 1f,
                pathEffect = dashed,
            )
        }
        val label = if (h == 0) "now" else "+${h}h"
        val layout = measurer.measure(label, style = labelStyle)
        drawText(
            layout,
            topLeft = Offset(x - layout.size.width / 2f, baselineY + 2.dp.toPx()),
        )
    }
}

private fun DrawScope.drawApplyMarkers(
    markers: List<Double>,
    plotLeft: Float,
    plotTop: Float,
    plotW: Float,
    plotH: Float,
    color: Color,
) {
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    markers.forEach { hours ->
        val x = plotLeft + plotW * (hours / windowHours).toFloat()
        drawLine(
            color = color,
            start = Offset(x, plotTop),
            end = Offset(x, plotTop + plotH),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawReapplyZone(
    thresholdCurve: List<TimedSpf>,
    plotLeft: Float,
    plotBottom: Float,
    plotW: Float,
    plotH: Float,
    axisTop: Double,
    color: Color,
) {
    if (thresholdCurve.isEmpty()) return
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    val range = (axisTop - MIN_SPF).coerceAtLeast(1e-9)
    fun xFor(elapsed: Double) = plotLeft + plotW * (elapsed / windowHours).toFloat()
    fun yFor(spf: Double) = plotBottom - plotH * ((spf - MIN_SPF) / range).toFloat().coerceIn(0f, 1f)

    val path = Path().apply {
        // Start at baseline-left, trace the threshold curve across the window, then close back
        // down to baseline-right so the area below the curve is filled.
        val first = thresholdCurve.first()
        val last = thresholdCurve.last()
        moveTo(xFor(first.elapsedHours), plotBottom)
        thresholdCurve.forEach { lineTo(xFor(it.elapsedHours), yFor(it.spf)) }
        lineTo(xFor(last.elapsedHours), plotBottom)
        close()
    }
    drawPath(path, color = color, style = Fill)
}

private fun DrawScope.drawCurve(
    samples: List<TimedSpf>,
    plotLeft: Float,
    plotBottom: Float,
    plotW: Float,
    plotH: Float,
    axisTop: Double,
    color: Color,
) {
    if (samples.isEmpty()) return
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    val range = (axisTop - MIN_SPF).coerceAtLeast(1e-9)
    fun xFor(elapsed: Double) = plotLeft + plotW * (elapsed / windowHours).toFloat()
    fun yFor(spf: Double) = plotBottom - plotH * ((spf - MIN_SPF) / range).toFloat().coerceIn(0f, 1f)

    val path = Path().apply {
        moveTo(xFor(samples.first().elapsedHours), yFor(samples.first().spf))
        samples.drop(1).forEach { lineTo(xFor(it.elapsedHours), yFor(it.spf)) }
    }
    drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
}

private fun formatSpf(value: Double): String {
    val v = value.coerceAtLeast(MIN_SPF)
    return if (v >= 10.0) v.toInt().toString() else formatNumber(v, 1)
}
