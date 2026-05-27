package com.insola.uv.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insola.uv.dose.BurnTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val BurnStroke = 16.dp
private val VitDStroke = 10.dp
private val LabelGap = 6.dp

/** Outer burn dial is 6h end-to-end; inner vit-D dial is 30 min end-to-end. */
private const val BURN_DIAL_MINUTES = 360.0
private const val VITD_DIAL_MINUTES = 30.0

/** Drop ticks landing within this many minutes of an already-kept tick to avoid label crowding. */
private const val TICK_MIN_GAP_MINUTES = 1.0

/** Number of arc segments rendered per dial — small enough to draw smoothly, large enough that
 *  per-segment lerping reads as a continuous gradient (≈ 1° per segment for 180°). */
private const val GRADIENT_SEGMENTS = 180

/**
 * Two concentric sundial-style half-arcs sharing a center at the bottom of the canvas.
 *
 *   - **Outer (burn)** — 180° = 6 h. Smooth gradient through [BurnTier] colors, anchored at
 *     the same MED-fraction stops the Day-tab burn bar uses (½, 1, 2, 4 MED). Ticks at the
 *     "Reddening" (1 MED) and "Sunburn" (2 MED) lines plus the 6h endpoint, labels rendered
 *     *outside* the arc. Names match the Day-tab burn-time chips.
 *
 *   - **Inner (vit-D)** — 180° = 30 min. Smooth gradient grey → amber → mid-green →
 *     deep-green positioned at ¼ / ½ / 1 SDD. Ticks named after the matching Day-tab
 *     vit-D buckets ("Low" / "Adequate" / "Sufficient") plus the 30m endpoint, labels rendered
 *     *inside* the arc.
 *
 * Both scales are fixed so visual comparison across scenarios stays honest. Move the
 * phototype or acclimatization pickers and the tick positions slide live; the arc lengths
 * never change.
 */
@Composable
internal fun SkinSummaryChart(summary: SkinSummary, modifier: Modifier = Modifier) {
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("At today's peak UV", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Peak UV ${formatNumber(summary.peakUv, 1)} · " +
                    "MED ${formatNumber(summary.effectiveMedUvIndexHours, 1)} UV-idx·h",
                style = MaterialTheme.typography.bodySmall,
            )

            if (summary.peakUv <= 0.0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No daytime UV in this scenario — neither burn nor vitamin-D apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            ConcentricSundial(summary)
            Spacer(Modifier.height(10.dp))
            SafeWindowCaption(summary)
        }
    }
}

private data class ArcTick(val minutes: Double, val label: String, val showMinutes: Boolean = true)

@Composable
private fun ConcentricSundial(summary: SkinSummary) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = onSurface)
    val textMeasurer = rememberTextMeasurer()

    val burnStops = burnStops(summary)
    val burnTicks = dedupTicks(burnTicks(summary))
    val vitDStops = vitDStops(summary)
    val vitDTicks = dedupTicks(vitDTicks(summary))

    Box(Modifier.fillMaxWidth().aspectRatio(1.7f)) {
        Canvas(Modifier.fillMaxSize()) {
            val burnStroke = BurnStroke.toPx()
            val vitDStroke = VitDStroke.toPx()
            val labelGap = LabelGap.toPx()
            val outerLabelBand = 26.dp.toPx()
            val outerRadius = minOf(
                size.width / 2f - outerLabelBand,
                size.height - outerLabelBand - burnStroke / 2f,
            )
            val center = Offset(size.width / 2f, size.height - burnStroke / 2f - 2.dp.toPx())

            // Inner arc small enough that its inside-anchored labels fit between the two
            // strokes without colliding with either.
            val vitDOuter = (outerRadius - burnStroke / 2f) * 0.62f

            drawGradientArc(center, outerRadius, burnStroke, burnStops)
            drawGradientArc(center, vitDOuter, vitDStroke, vitDStops)

            drawTicks(center, outerRadius, burnStroke, burnTicks, BURN_DIAL_MINUTES, onSurface)
            drawTicks(center, vitDOuter, vitDStroke, vitDTicks, VITD_DIAL_MINUTES, onSurface)

            drawLabels(
                center = center,
                arcRadius = outerRadius,
                stroke = burnStroke,
                labelGap = labelGap,
                ticks = burnTicks,
                axisMax = BURN_DIAL_MINUTES,
                style = labelStyle,
                measurer = textMeasurer,
                canvasWidth = size.width,
                anchorOutside = true,
            )
            drawLabels(
                center = center,
                arcRadius = vitDOuter,
                stroke = vitDStroke,
                labelGap = labelGap,
                ticks = vitDTicks,
                axisMax = VITD_DIAL_MINUTES,
                style = labelStyle,
                measurer = textMeasurer,
                canvasWidth = size.width,
                anchorOutside = false,
            )
        }
    }
}

/** Day-tab burn-bar gradient positioned on the 6 h axis via t_med = minutesToFirstReddening. */
private fun burnStops(summary: SkinSummary): List<Pair<Float, Color>> {
    val tMed = summary.minutesToFirstReddening
    if (tMed == null || tMed <= 0.0) {
        return listOf(0f to BurnTier.Safe.color(), 1f to BurnTier.Safe.color())
    }
    val raw = BurnTier.entries.map { tier ->
        (tier.minFractionOfMed * tMed / BURN_DIAL_MINUTES).toFloat().coerceIn(0f, 1f) to tier.color()
    }
    return clampToFullRange(raw, finalColor = BurnTier.SeriousBurn.color())
}

private fun vitDStops(summary: SkinSummary): List<Pair<Float, Color>> {
    val tFull = summary.minutesToAdequateVitD
    if (tFull == null || tFull <= 0.0) {
        return listOf(0f to VitDTrace, 1f to VitDTrace)
    }
    fun frac(min: Double) = (min / VITD_DIAL_MINUTES).toFloat().coerceIn(0f, 1f)
    val raw = listOf(
        0f to VitDTrace,
        frac(tFull * 0.25) to VitDLow,
        frac(tFull * 0.5) to VitDAdequate,
        frac(tFull) to VitDSufficient,
    )
    return clampToFullRange(raw, finalColor = VitDSufficient)
}

/**
 * Make sure a stops list begins at 0 and ends at 1, and that positions are strictly
 * non-decreasing. Per-segment lerping relies on a well-formed monotonic list.
 */
private fun clampToFullRange(
    stops: List<Pair<Float, Color>>,
    finalColor: Color,
): List<Pair<Float, Color>> {
    val sanitized = stops.fold(mutableListOf<Pair<Float, Color>>()) { acc, (pos, color) ->
        val last = acc.lastOrNull()
        when {
            last == null -> acc.add(pos to color)
            pos < last.first -> acc.add(last.first to color)
            else -> acc.add(pos to color)
        }
        acc
    }
    if (sanitized.first().first > 0f) sanitized.add(0, 0f to sanitized.first().second)
    if (sanitized.last().first < 1f) sanitized.add(1f to finalColor)
    return sanitized
}

private fun burnTicks(summary: SkinSummary): List<ArcTick> {
    val ticks = mutableListOf<ArcTick>()
    summary.minutesToFirstReddening?.takeIf { it < BURN_DIAL_MINUTES }
        ?.let { ticks += ArcTick(it, "Reddening") }
    summary.minutesToSunburn?.takeIf { it < BURN_DIAL_MINUTES }
        ?.let { ticks += ArcTick(it, "Sunburn") }
    ticks += ArcTick(BURN_DIAL_MINUTES, "6h", showMinutes = false)
    return ticks
}

private fun vitDTicks(summary: SkinSummary): List<ArcTick> {
    val tFull = summary.minutesToAdequateVitD
    val ticks = mutableListOf<ArcTick>()
    summary.minutesToTraceVitD?.takeIf { it < VITD_DIAL_MINUTES }
        ?.let { ticks += ArcTick(it, "Low") }
    summary.minutesToLowVitD?.takeIf { it < VITD_DIAL_MINUTES }
        ?.let { ticks += ArcTick(it, "Adequate") }
    if (tFull != null && tFull < VITD_DIAL_MINUTES) {
        ticks += ArcTick(tFull, "Sufficient")
    }
    ticks += ArcTick(VITD_DIAL_MINUTES, "30m", showMinutes = false)
    return ticks
}

/** Walk ticks in time order; drop any landing within [TICK_MIN_GAP_MINUTES] of a kept tick. */
private fun dedupTicks(ticks: List<ArcTick>): List<ArcTick> =
    ticks.sortedBy { it.minutes }.fold(mutableListOf()) { acc, t ->
        val last = acc.lastOrNull()
        if (last == null || t.minutes - last.minutes >= TICK_MIN_GAP_MINUTES) acc.add(t)
        acc
    }

/**
 * Draw the half-arc as [GRADIENT_SEGMENTS] small slices, each colored by interpolating
 * [stops] at the slice's midpoint. Compose has no native gradient-along-arc primitive; this
 * is the standard substitute and reads as continuous at typical phone DPIs.
 */
private fun DrawScope.drawGradientArc(
    center: Offset,
    radius: Float,
    stroke: Float,
    stops: List<Pair<Float, Color>>,
) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val sweepPerSeg = 180f / GRADIENT_SEGMENTS
    for (i in 0 until GRADIENT_SEGMENTS) {
        val midFrac = (i + 0.5f) / GRADIENT_SEGMENTS
        drawArc(
            color = colorAtStop(stops, midFrac),
            startAngle = 180f + sweepPerSeg * i,
            sweepAngle = sweepPerSeg + 0.5f,        // 0.5° overlap erases seams between segments
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }
}

private fun colorAtStop(stops: List<Pair<Float, Color>>, frac: Float): Color {
    if (frac <= stops.first().first) return stops.first().second
    if (frac >= stops.last().first) return stops.last().second
    for (i in 0 until stops.lastIndex) {
        val (f0, c0) = stops[i]
        val (f1, c1) = stops[i + 1]
        if (frac in f0..f1) {
            if (f1 == f0) return c1
            return lerp(c0, c1, ((frac - f0) / (f1 - f0)).coerceIn(0f, 1f))
        }
    }
    return stops.last().second
}

private fun DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    stroke: Float,
    ticks: List<ArcTick>,
    axisMax: Double,
    color: Color,
) {
    val tickHalf = stroke / 2f + 2.dp.toPx()
    val tickWidth = 1.5.dp.toPx()
    ticks.forEach { tick ->
        val (c, s) = arcCosSin(tick.minutes, axisMax)
        drawLine(
            color = color,
            start = Offset(center.x + c * (radius - tickHalf), center.y + s * (radius - tickHalf)),
            end = Offset(center.x + c * (radius + tickHalf), center.y + s * (radius + tickHalf)),
            strokeWidth = tickWidth,
        )
    }
}

/**
 * Place each tick's label at a radius offset from the arc.
 *
 *   - [anchorOutside] = true: label sits just outside the arc with its inside edge on the
 *     anchor. For the top half (sin ∈ [-1,0]):
 *       cos=+1 → topLeft.x = anchorX        (left edge at anchor, label to the east)
 *       cos= 0 → topLeft.x = anchorX − w/2  (horizontally centered, label above)
 *       cos=-1 → topLeft.x = anchorX − w    (right edge at anchor, label to the west)
 *       sin= 0 → topLeft.y = anchorY − h/2
 *       sin=-1 → topLeft.y = anchorY − h    (bottom edge at anchor, label above)
 *   - [anchorOutside] = false: label sits *inside* the arc with its outside edge on the
 *     anchor (radially flipped — shifts are inverted).
 */
private fun DrawScope.drawLabels(
    center: Offset,
    arcRadius: Float,
    stroke: Float,
    labelGap: Float,
    ticks: List<ArcTick>,
    axisMax: Double,
    style: TextStyle,
    measurer: TextMeasurer,
    canvasWidth: Float,
    anchorOutside: Boolean,
) {
    val labelRadius = if (anchorOutside) {
        arcRadius + stroke / 2f + labelGap
    } else {
        arcRadius - stroke / 2f - labelGap
    }
    ticks.forEach { tick ->
        val (c, s) = arcCosSin(tick.minutes, axisMax)
        val text = if (tick.showMinutes) "${tick.label} · ${formatMinutes(tick.minutes)}" else tick.label
        val layout = measurer.measure(text, style = style)
        val anchorX = center.x + c * labelRadius
        val anchorY = center.y + s * labelRadius
        val (offsetX, offsetY) = if (anchorOutside) {
            -layout.size.width * (1f - c) / 2f to -layout.size.height * (1f - s) / 2f
        } else {
            -layout.size.width * (1f + c) / 2f to -layout.size.height * (1f + s) / 2f
        }
        val left = (anchorX + offsetX)
            .coerceIn(0f, (canvasWidth - layout.size.width).coerceAtLeast(0f))
        val top = anchorY + offsetY
        drawText(layout, topLeft = Offset(left, top))
    }
}

private fun arcCosSin(minutes: Double, axisMax: Double): Pair<Float, Float> {
    val frac = (minutes / axisMax).coerceIn(0.0, 1.0)
    val theta = (180.0 + 180.0 * frac) * PI / 180.0
    return cos(theta).toFloat() to sin(theta).toFloat()
}

@Composable
private fun SafeWindowCaption(summary: SkinSummary) {
    val style = MaterialTheme.typography.bodySmall
    val tAdequate = summary.minutesToAdequateVitD
    val tFirst = summary.minutesToFirstReddening
    val text = when {
        tAdequate == null || tFirst == null ->
            "Either vitamin D or burn never reached at this peak UV."
        tAdequate >= tFirst ->
            "Vitamin D and first reddening land at roughly the same time " +
                "(${formatMinutes(tAdequate)} vs ${formatMinutes(tFirst)}). No safe sun window today."
        else -> {
            val window = tFirst - tAdequate
            "Safe sun window ≈ ${formatMinutes(window)} " +
                "(Sufficient at ${formatMinutes(tAdequate)} → first reddening at ${formatMinutes(tFirst)})."
        }
    }
    Text(text, style = style)
}

private fun formatMinutes(min: Double): String {
    val total = min.coerceAtLeast(0.0).toInt()
    return if (total < 60) "${total}m" else "${total / 60}h ${total % 60}m"
}
