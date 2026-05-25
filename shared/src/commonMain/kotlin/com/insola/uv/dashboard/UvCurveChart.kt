package com.insola.uv.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.OutdoorSession
import kotlinx.datetime.Instant
import kotlin.math.ceil
import kotlin.math.max

private val OutdoorGreen = Color(0xFF34A853)
private val IndoorGray = Color(0xFF9E9E9E)

/**
 * 24-hour UV projection + outdoor timeline + interactive time scrubber.
 *
 * - Top: filled UV curve with a vertical "now" marker.
 * - Bottom: a thin timeline bar — gray (indoor) overlaid with green (outdoor) segments.
 * - Tapping or dragging anywhere on the chart sets [hourOfDay] via [onHourChange].
 */
@Composable
fun UvCurveChart(
    scenario: Scenario,
    hourOfDay: Double,
    sessions: List<OutdoorSession>,
    onHourChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val peakUv = max(1.0, scenario.hourlyUv.max())
    val yMax = ceil(peakUv).coerceAtLeast(1.0)
    val areaColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val lineColor = MaterialTheme.colorScheme.primary
    val sessionBandColor = OutdoorGreen.copy(alpha = 0.18f)
    val nowColor = MaterialTheme.colorScheme.secondary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val nowMarkerColor = nowColor

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        onHourChange((down.position.x / width * 24.0).coerceIn(0.0, 24.0))
                        drag(down.id) { change ->
                            onHourChange((change.position.x / width * 24.0).coerceIn(0.0, 24.0))
                            change.consume()
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val xFor: (Double) -> Float = { hour -> (hour / 24.0).toFloat() * w }
            val yFor: (Double) -> Float = { uv -> h - (uv / yMax).toFloat() * h }

            // Soft vertical bands under each session so they're visible behind the curve too.
            sessions.forEach { s ->
                val startHour = hourOfScenario(scenario, s.start) ?: return@forEach
                val endHour = s.end?.let { hourOfScenario(scenario, it) } ?: hourOfDay
                val x0 = xFor(startHour.coerceIn(0.0, 24.0))
                val x1 = xFor(endHour.coerceIn(0.0, 24.0))
                if (x1 > x0) {
                    drawRect(
                        color = sessionBandColor,
                        topLeft = Offset(x0, 0f),
                        size = Size(x1 - x0, h),
                    )
                }
            }

            drawLine(axisColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)

            val area = Path().apply {
                moveTo(0f, h)
                scenario.hourlyUv.forEachIndexed { i, uv -> lineTo(xFor(i.toDouble()), yFor(uv)) }
                lineTo(w, h)
                close()
            }
            drawPath(area, color = areaColor)

            val outline = Path().apply {
                scenario.hourlyUv.forEachIndexed { i, uv ->
                    val x = xFor(i.toDouble())
                    val y = yFor(uv)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(outline, color = lineColor, style = Stroke(width = 2.5f))

            val nowX = xFor(hourOfDay.coerceIn(0.0, 24.0))
            drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, h), strokeWidth = 2f)
        }
        Text(
            text = "UV ${yMax.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }

    Spacer(Modifier.height(6.dp))

    // Timeline strip: gray = indoor, green = outdoor.
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    onHourChange((down.position.x / width * 24.0).coerceIn(0.0, 24.0))
                    drag(down.id) { change ->
                        onHourChange((change.position.x / width * 24.0).coerceIn(0.0, 24.0))
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRect(IndoorGray.copy(alpha = 0.35f), size = Size(w, h))
        sessions.forEach { s ->
            val startHour = hourOfScenario(scenario, s.start) ?: return@forEach
            val endHour = s.end?.let { hourOfScenario(scenario, it) } ?: hourOfDay
            val x0 = (startHour.coerceIn(0.0, 24.0) / 24.0).toFloat() * w
            val x1 = (endHour.coerceIn(0.0, 24.0) / 24.0).toFloat() * w
            if (x1 > x0) {
                drawRect(OutdoorGreen, topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
            }
        }
        // Now marker.
        val nowX = (hourOfDay.coerceIn(0.0, 24.0) / 24.0).toFloat() * w
        drawLine(
            color = nowMarkerColor,
            start = Offset(nowX, 0f),
            end = Offset(nowX, h),
            strokeWidth = 2f,
        )
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("0", "6", "12", "18", "24").forEach { label ->
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun hourOfScenario(scenario: Scenario, instant: Instant): Double? {
    val ms = (instant - scenario.dayStart).inWholeMilliseconds
    if (ms < 0) return null
    return ms / 3_600_000.0
}
