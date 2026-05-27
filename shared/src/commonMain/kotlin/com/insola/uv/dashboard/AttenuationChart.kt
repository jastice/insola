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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.Spf
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

/**
 * Effective-SPF floor for the "reapply soon" nudge. Below this the chart paints a translucent
 * red band and the Apply button glows. Chosen so a typical SPF 30 application (initial effective
 * SPF ~5.5 at thickness 0.5) crosses it roughly around the 2 h nominal reapply mark.
 */
internal const val REAPPLY_THRESHOLD_SPF: Double = 3.0

/** True if the effective SPF at [now] sits in the reapply zone. */
internal fun isInReapplyZone(timeline: AttenuationTimeline, defaultSpf: Spf, now: Instant): Boolean =
    effectiveSpfAt(timeline, defaultSpf.transmittance, now) < REAPPLY_THRESHOLD_SPF

/**
 * Effective SPF over the next [WINDOW] starting at [now]. Combines the always-on [defaultSpf]
 * with whatever the [timeline] says at each instant via `min(defaultT, timelineT)` — the same
 * composition the integrator uses, so the chart reads as the actual protection feeding the
 * burn meter. Apply events are marked with thin vertical lines.
 */
@Composable
internal fun AttenuationChart(
    timeline: AttenuationTimeline,
    defaultSpf: Spf,
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

    val samples = remember(timeline, defaultSpf, now) { sampleEffectiveSpf(timeline, defaultSpf, now) }
    val axisTop = remember(samples) { axisTopFor(samples.map { it.spf }) }
    val applyMarkers = remember(timeline, now) { applyMarkersInsideWindow(timeline, now) }

    Box(modifier = modifier.fillMaxWidth().height(72.dp)) {
        Canvas(Modifier.fillMaxSize()) {
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
    defaultSpf: Spf,
    now: Instant,
): List<TimedSpf> {
    val windowHours = WINDOW.toDouble(DurationUnit.HOURS)
    val stepHours = SAMPLE_STEP.toDouble(DurationUnit.HOURS)
    val n = ceil(windowHours / stepHours).toInt()
    val defaultT = defaultSpf.transmittance
    return (0..n).map { i ->
        val dt = (i * stepHours).coerceAtMost(windowHours)
        TimedSpf(dt, effectiveSpfAt(timeline, defaultT, now + dt.hours))
    }
}

private fun effectiveSpfAt(timeline: AttenuationTimeline, defaultT: Double, t: Instant): Double {
    val transmittance = minOf(defaultT, timeline.transmittanceAt(t))
    if (transmittance <= 0.0) return MIN_SPF
    return (1.0 / transmittance).coerceAtLeast(MIN_SPF)
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
    plotLeft: Float,
    plotBottom: Float,
    plotW: Float,
    plotH: Float,
    axisTop: Double,
    color: Color,
) {
    val range = (axisTop - MIN_SPF).coerceAtLeast(1e-9)
    val topFrac = ((REAPPLY_THRESHOLD_SPF - MIN_SPF) / range).coerceIn(0.0, 1.0).toFloat()
    if (topFrac <= 0f) return
    val zoneH = plotH * topFrac
    drawRect(
        color = color,
        topLeft = Offset(plotLeft, plotBottom - zoneH),
        size = Size(plotW, zoneH),
        style = Fill,
    )
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
