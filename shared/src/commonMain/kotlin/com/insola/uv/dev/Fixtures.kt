package com.insola.uv.dev

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * A synthetic dev scenario: a location, a calendar day (expressed as the [Instant] of midnight
 * **local** at that location), and 25 hourly UV-index samples covering 00:00..24:00 local.
 *
 * Because [dayStart] is local midnight expressed in UTC, the per-sample Instants line up with the
 * actual solar geometry for the location — so [com.insola.uv.solar.SolarGeometry] gives realistic
 * elevations at "local hour H = dayStart + H.hours".
 */
data class Scenario(
    val id: String,
    val name: String,
    val description: String,
    val location: GeoPoint,
    val dayStart: Instant,
    val hourlyUv: List<Double>,
) {
    init {
        require(hourlyUv.size == 25) { "scenario $id: expected 25 hourly samples, got ${hourlyUv.size}" }
    }

    val forecast: UvForecast by lazy {
        UvForecast(
            location = location,
            samples = hourlyUv.mapIndexed { hour, uv -> UvSample(dayStart + hour.hours, uv) },
        )
    }

    val dayEnd: Instant get() = dayStart + 24.hours

    /** Convert an hour-of-day (0..24) to the corresponding [Instant] within this scenario's day. */
    fun hourToInstant(hour: Double): Instant =
        dayStart + (hour * 3_600_000.0).toLong().milliseconds

    /**
     * Inverse of [hourToInstant]: how many hours past [dayStart] is [instant]? Returns null if the
     * instant falls before the scenario's day begins.
     */
    fun instantToHour(instant: Instant): Double? {
        val ms = (instant - dayStart).inWholeMilliseconds
        if (ms < 0) return null
        return ms / 3_600_000.0
    }
}

object Fixtures {

    /**
     * Synthesize 25 hourly UV samples whose shape tracks the **actual** solar elevation at the
     * location, normalized so the day's peak hits [peakUvIndex]. Hours where the sun is below the
     * horizon are zero. The shape is the simplest physically defensible one: UV ∝ sin(elev).
     *
     * Why not hand-author the curves: dayStart is "local-clock midnight" (nominal timezone offset),
     * but within a timezone the actual solar noon depends on longitude. Singapore (UTC+8, lon
     * 103.82°E) hits solar noon at ~13:12 local; Reykjavík (UTC+0, lon -21.94°E) at ~13:26;
     * Paris (UTC+2, lon 2.35°E) at ~13:52. A curve hand-authored to peak at "hour 12 local" can
     * sit 1–2 h before real solar noon, which silently miscalibrates [BurnModel] (uses raw UV)
     * against [VitaminDModel] (weights UV by sin-of-elevation): the morning UV samples line up
     * with hours where the sun is still near or below the horizon, so burn integrates phantom
     * dose while vit-D correctly stays at zero.
     */
    private fun clearSkyUv(
        location: GeoPoint,
        dayStart: Instant,
        peakUvIndex: Double,
    ): List<Double> {
        val sinElev = (0..24).map { hour ->
            val e = SolarGeometry.solarElevationDegrees(location, dayStart + hour.hours)
            if (e <= 0.0) 0.0 else sin(e * PI / 180.0)
        }
        val peak = sinElev.max()
        if (peak == 0.0) return List(25) { 0.0 }
        return sinElev.map { it / peak * peakUvIndex }
    }

    private fun clearSkyScenario(
        id: String,
        name: String,
        description: String,
        location: GeoPoint,
        dayStart: Instant,
        peakUvIndex: Double,
        cloudFactor: (Int) -> Double = { 1.0 },
    ): Scenario = Scenario(
        id = id,
        name = name,
        description = description,
        location = location,
        dayStart = dayStart,
        hourlyUv = clearSkyUv(location, dayStart, peakUvIndex).mapIndexed { h, uv -> uv * cloudFactor(h) },
    )

    val equatorialNoon: Scenario = clearSkyScenario(
        id = "equator",
        name = "Equatorial noon",
        description = "Singapore equinox, clear-sky peak UV 12",
        location = GeoPoint(1.35, 103.82),
        // Singapore = UTC+8. Local midnight 2026-03-20 = 2026-03-19T16:00 UTC.
        dayStart = Instant.parse("2026-03-19T16:00:00Z"),
        peakUvIndex = 12.0,
    )

    val berlinSummer: Scenario = clearSkyScenario(
        id = "berlin",
        name = "Mid-latitude summer",
        description = "Berlin solstice, clear-sky peak UV 7",
        location = GeoPoint(52.52, 13.40),
        // Berlin in June = UTC+2. Local midnight 2026-06-21 = 2026-06-20T22:00 UTC.
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        peakUvIndex = 7.0,
    )

    val reykjavikWinter: Scenario = clearSkyScenario(
        id = "reykjavik",
        name = "Arctic winter",
        description = "Reykjavík December, peak UV 1, narrow daylight",
        location = GeoPoint(64.13, -21.94),
        // Iceland = UTC year-round. Sun barely clears the horizon; the synthesized UV curve is
        // tiny except for the few hours around solar noon.
        dayStart = Instant.parse("2026-12-21T00:00:00Z"),
        peakUvIndex = 1.0,
    )

    val cloudyAfternoon: Scenario = clearSkyScenario(
        id = "cloudy",
        name = "Cloudy afternoon",
        description = "Paris June, light morning cloud thickens after 14:00 local",
        location = GeoPoint(48.85, 2.35),
        // Paris in June = UTC+2. Local midnight 2026-06-21 = 2026-06-20T22:00 UTC.
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        peakUvIndex = 6.0,
        // Light haze through the morning (~½ clear-sky), heavier overcast moves in at 14:00 local.
        cloudFactor = { hour -> if (hour < 14) 0.5 else 0.2 },
    )

    /**
     * Rising vs falling pair share the same total area (one is the reverse of the other) but
     * deliver dose at very different times of day — the cleanest way to see time-integration
     * actually working. Intentionally NOT a clear-sky shape: the asymmetric morning-vs-afternoon
     * loading is the whole point.
     */
    private val mirrorCurve = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        1.0, 2.0, 3.0, 4.0, 5.0,
        6.0, 7.0, 8.0,
        7.0, 6.0, 5.0,
        4.0, 3.0, 2.0, 1.0,
        0.0, 0.0, 0.0,
    )

    val mirrorRising = Scenario(
        id = "rising",
        name = "Mirrored — rising",
        description = "Late-afternoon peak. Same total area as 'falling'.",
        location = GeoPoint(52.52, 13.40),
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        hourlyUv = mirrorCurve,
    )

    val mirrorFalling = Scenario(
        id = "falling",
        name = "Mirrored — falling",
        description = "Late-morning peak. Same total area as 'rising'.",
        location = GeoPoint(52.52, 13.40),
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        hourlyUv = mirrorCurve.reversed(),
    )

    val all: List<Scenario> = listOf(
        equatorialNoon,
        berlinSummer,
        reykjavikWinter,
        cloudyAfternoon,
        mirrorRising,
        mirrorFalling,
    )

    fun byId(id: String): Scenario = all.first { it.id == id }
}
