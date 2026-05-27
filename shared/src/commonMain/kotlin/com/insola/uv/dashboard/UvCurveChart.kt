package com.insola.uv.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.OutdoorSession
import kotlin.math.ceil
import kotlin.math.max

private val ChartLeftPad = 30.dp
private val ChartHeight = 160.dp
private val TimelineHeight = 14.dp

/** Shared horizontal-axis mapping for the chart and the timeline strip. */
private class ChartCoords(val leftPad: Float, val totalWidth: Float) {
    val plotWidth: Float get() = (totalWidth - leftPad).coerceAtLeast(1f)
    fun hourToX(hour: Double): Float =
        leftPad + (hour.coerceIn(0.0, 24.0) / 24.0).toFloat() * plotWidth
    fun xToHour(x: Float): Double =
        ((x - leftPad) / plotWidth * 24.0).coerceIn(0.0, 24.0)
}

/** Tap-or-drag-to-scrub gesture, shared by the chart and timeline. */
private fun Modifier.scrubHour(onHourChange: (Double) -> Unit): Modifier = pointerInput(Unit) {
    val leftPad = ChartLeftPad.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val coords = ChartCoords(leftPad, size.width.toFloat())
        onHourChange(coords.xToHour(down.position.x))
        drag(down.id) { change ->
            onHourChange(coords.xToHour(change.position.x))
            change.consume()
        }
    }
}

/**
 * 24-hour UV projection + outdoor timeline + interactive time scrubber.
 *
 * - Filled UV curve colored by a vertical gradient that follows the WHO UV-index bands
 *   (green ≤2, yellow 3-5, orange 6-7, red 8-10, purple ≥11).
 * - Y-axis ticks at the band breakpoints.
 * - Inline overlay shows the UV value + solar elevation at the scrubbed hour.
 * - Bottom timeline strip: gray indoor, green outdoor.
 * - Tap or drag the chart or strip to set [hourOfDay] via [onHourChange].
 */
@Composable
fun UvCurveChart(
    scenario: Scenario,
    hourOfDay: Double,
    currentUv: Double,
    solarElevationDeg: Double,
    sunriseHour: Double?,
    sunsetHour: Double?,
    sessions: List<OutdoorSession>,
    onHourChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val peakUv = max(1.0, scenario.hourlyUv.max())
    val yMax = ceil(peakUv).coerceAtLeast(1.0)
    val gradient = remember(yMax) { buildUvGradient(yMax) }
    val nowColor = MaterialTheme.colorScheme.onSurface
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = tickColor)
    val sessionBandColor = OutdoorGreen.copy(alpha = 0.14f)
    val nightShade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val textMeasurer = rememberTextMeasurer()
    val ticks = remember(yMax) { uvTicks(yMax) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .scrubHour(onHourChange),
        ) {
            val coords = ChartCoords(ChartLeftPad.toPx(), size.width)
            val h = size.height
            val yFor: (Double) -> Float = { uv -> h - (uv / yMax).toFloat() * h }

            // Night bands (before sunrise / after sunset). Polar night = fully shaded plot;
            // polar day = no shading.
            drawNightBands(coords, h, sunriseHour, sunsetHour, nightShade)

            // Y-axis tick lines + labels.
            ticks.forEach { uv ->
                val y = yFor(uv)
                drawLine(
                    color = axisColor.copy(alpha = 0.5f),
                    start = Offset(coords.leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                val layout = textMeasurer.measure(uv.toInt().toString(), style = tickStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = coords.leftPad - layout.size.width - 4f,
                        y = y - layout.size.height / 2f,
                    ),
                )
            }

            // Outdoor session bands behind the curve.
            drawSessionBands(coords, h, scenario, sessions, hourOfDay, sessionBandColor)

            // Baseline.
            drawLine(axisColor, Offset(coords.leftPad, h), Offset(size.width, h), strokeWidth = 1f)

            // Filled area + outline, both painted with the UV-band gradient brush.
            val area = Path().apply {
                moveTo(coords.leftPad, h)
                scenario.hourlyUv.forEachIndexed { i, uv ->
                    lineTo(coords.hourToX(i.toDouble()), yFor(uv))
                }
                lineTo(coords.leftPad + coords.plotWidth, h)
                close()
            }
            drawPath(area, brush = gradient, alpha = 0.45f)

            val outline = Path().apply {
                scenario.hourlyUv.forEachIndexed { i, uv ->
                    val x = coords.hourToX(i.toDouble())
                    val y = yFor(uv)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(outline, brush = gradient, style = Stroke(width = 2.5f))

            // "Now" marker.
            val nowX = coords.hourToX(hourOfDay)
            drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, h), strokeWidth = 2f)
            drawCircle(uvBandColor(currentUv), radius = 5f, center = Offset(nowX, yFor(currentUv)))
        }

        // Inline current-UV readout, top-end.
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = "UV ${formatNumber(currentUv, 1)}",
                style = MaterialTheme.typography.headlineSmall,
                color = uvBandColor(currentUv),
            )
            Text(
                text = "Sun ${formatNumber(solarElevationDeg, 0)}° • ${formatClock(hourOfDay)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    Spacer(Modifier.height(6.dp))

    // Timeline strip: gray (indoor) + green (outdoor). Same left padding so it aligns with the
    // chart's plot area.
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .scrubHour(onHourChange),
    ) {
        val coords = ChartCoords(ChartLeftPad.toPx(), size.width)
        val h = size.height
        drawRect(
            color = IndoorGray.copy(alpha = 0.35f),
            topLeft = Offset(coords.leftPad, 0f),
            size = Size(coords.plotWidth, h),
        )
        drawNightBands(coords, h, sunriseHour, sunsetHour, nightShade)
        drawSessionBands(coords, h, scenario, sessions, hourOfDay, OutdoorGreen)
        val nowX = coords.hourToX(hourOfDay)
        drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, h), strokeWidth = 2f)
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ChartLeftPad),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("0", "6", "12", "18", "24").forEach { label ->
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Shades the plot area before sunrise and after sunset. `null` for either bound means that
 * transition doesn't happen on the chart's day (polar night ⇒ both null ⇒ full plot shaded;
 * polar day ⇒ `0.0` / `24.0` ⇒ no shading).
 */
private fun DrawScope.drawNightBands(
    coords: ChartCoords,
    h: Float,
    sunriseHour: Double?,
    sunsetHour: Double?,
    color: Color,
) {
    if (sunriseHour == null && sunsetHour == null) {
        drawRect(color = color, topLeft = Offset(coords.leftPad, 0f), size = Size(coords.plotWidth, h))
        return
    }
    sunriseHour?.takeIf { it > 0.0 }?.let { sr ->
        val w = coords.hourToX(sr) - coords.leftPad
        if (w > 0f) drawRect(color = color, topLeft = Offset(coords.leftPad, 0f), size = Size(w, h))
    }
    sunsetHour?.takeIf { it < 24.0 }?.let { ss ->
        val x0 = coords.hourToX(ss)
        val w = coords.leftPad + coords.plotWidth - x0
        if (w > 0f) drawRect(color = color, topLeft = Offset(x0, 0f), size = Size(w, h))
    }
}

private fun DrawScope.drawSessionBands(
    coords: ChartCoords,
    h: Float,
    scenario: Scenario,
    sessions: List<OutdoorSession>,
    hourOfDay: Double,
    color: Color,
) {
    sessions.forEach { s ->
        val startHour = scenario.instantToHour(s.start) ?: return@forEach
        val endHour = s.end?.let { scenario.instantToHour(it) } ?: hourOfDay
        val x0 = coords.hourToX(startHour)
        val x1 = coords.hourToX(endHour)
        if (x1 > x0) drawRect(color, topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
    }
}

/**
 * Builds a vertical gradient brush mapping y-position to UV band color. High UV at top (purple/red),
 * low UV at bottom (green). Sharp-ish band transitions via near-duplicate stops.
 */
private fun buildUvGradient(yMax: Double): Brush {
    val raw = listOf(
        0.0 to UvGreen,
        2.999 to UvGreen,
        3.0 to UvYellow,
        5.999 to UvYellow,
        6.0 to UvOrange,
        7.999 to UvOrange,
        8.0 to UvRed,
        10.999 to UvRed,
        11.0 to UvPurple,
    ).filter { it.first <= yMax }
    val withTop = if (raw.last().first < yMax) raw + (yMax to uvBandColor(yMax)) else raw
    val stops = withTop
        .map { (uv, color) -> (1.0 - uv / yMax).toFloat().coerceIn(0f, 1f) to color }
        .sortedBy { it.first }
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}

/** Y-axis ticks at the standard UV breakpoints that fit within [yMax]. */
private fun uvTicks(yMax: Double): List<Double> =
    listOf(0.0, 3.0, 6.0, 8.0, 11.0).filter { it <= yMax }
