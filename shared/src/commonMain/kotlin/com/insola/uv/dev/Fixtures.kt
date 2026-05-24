package com.insola.uv.dev

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

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
}

object Fixtures {

    val equatorialNoon = Scenario(
        id = "equator",
        name = "Equatorial noon",
        description = "Singapore equinox, clear-sky peak UV 12",
        location = GeoPoint(1.35, 103.82),
        // Singapore = UTC+8. Local midnight 2026-03-20 = 2026-03-19T16:00 UTC.
        dayStart = Instant.parse("2026-03-19T16:00:00Z"),
        hourlyUv = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.5, 2.0, 5.0, 8.0, 10.5, 12.0,
            12.0, 11.5, 9.5, 6.5, 3.0, 1.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ),
    )

    val berlinSummer = Scenario(
        id = "berlin",
        name = "Mid-latitude summer",
        description = "Berlin solstice, peak UV 7, slow afternoon decay",
        location = GeoPoint(52.52, 13.40),
        // Berlin in June = UTC+2. Local midnight 2026-06-21 = 2026-06-20T22:00 UTC.
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        hourlyUv = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.2,
            0.8, 1.8, 3.0, 4.5, 6.0, 7.0,
            7.0, 7.0, 6.5, 5.5, 4.0, 2.5,
            1.2, 0.5, 0.1, 0.0, 0.0, 0.0, 0.0,
        ),
    )

    val reykjavikWinter = Scenario(
        id = "reykjavik",
        name = "Arctic winter",
        description = "Reykjavík December, peak UV 1, narrow daylight",
        location = GeoPoint(64.13, -21.94),
        // Iceland = UTC year-round. Curve total ~2.0 UV-idx·h — under Type I's 2.2 MED so a full
        // day of continuous outdoor exposure still doesn't burn the model.
        dayStart = Instant.parse("2026-12-21T00:00:00Z"),
        hourlyUv = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.5,
            1.0, 0.5, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ),
    )

    val cloudyAfternoon = Scenario(
        id = "cloudy",
        name = "Cloudy afternoon",
        description = "Paris June, flat UV 3 → drops to 1 after 14:00",
        location = GeoPoint(48.85, 2.35),
        // Paris in June = UTC+2. Local midnight 2026-06-21 = 2026-06-20T22:00 UTC.
        dayStart = Instant.parse("2026-06-20T22:00:00Z"),
        hourlyUv = listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            1.0, 2.0, 3.0, 3.0, 3.0, 3.0,
            3.0, 3.0, 1.0, 1.0, 1.0, 1.0,
            0.5, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0,
        ),
    )

    /**
     * Rising vs falling pair share the same total area (one is the reverse of the other) but
     * deliver dose at very different times of day — the cleanest way to see time-integration
     * actually working.
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
