package com.insola.uv.domain

import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * A neutral one-day UV model: a [location], the [Instant] of **local** midnight ([dayStart]) at
 * that location, and 25 hourly UV-index samples covering 00:00..24:00 local.
 *
 * This is the data shape the dev [com.insola.uv.dev.Scenario] always was, minus its dev metadata
 * (id/name/description). Both the fixtures and the live Open-Meteo provider produce a `UvDay`, so
 * the entire [com.insola.uv.dashboard.DashboardCompute] pipeline is source-agnostic.
 *
 * Because [dayStart] is local midnight expressed in UTC, the per-sample Instants line up with the
 * actual solar geometry for the location — so [com.insola.uv.solar.SolarGeometry] gives realistic
 * elevations at "local hour H = dayStart + H.hours".
 */
data class UvDay(
    val location: GeoPoint,
    val dayStart: Instant,
    val hourlyUv: List<Double>,
) {
    init {
        require(hourlyUv.size == 25) { "expected 25 hourly samples, got ${hourlyUv.size}" }
    }

    val forecast: UvForecast by lazy {
        UvForecast(
            location = location,
            samples = hourlyUv.mapIndexed { hour, uv -> UvSample(dayStart + hour.hours, uv) },
        )
    }

    val dayEnd: Instant get() = dayStart + 24.hours

    /** Convert an hour-of-day (0..24) to the corresponding [Instant] within this day. */
    fun hourToInstant(hour: Double): Instant =
        dayStart + (hour * 3_600_000.0).toLong().milliseconds

    /**
     * Inverse of [hourToInstant]: how many hours past [dayStart] is [instant]? Returns null if the
     * instant falls before the day begins.
     */
    fun instantToHour(instant: Instant): Double? {
        val ms = (instant - dayStart).inWholeMilliseconds
        if (ms < 0) return null
        return ms / 3_600_000.0
    }

    companion object {
        /**
         * Build today's [UvDay] from a fetched [forecast] by anchoring [dayStart] at local midnight
         * in [zone] and resampling 25 nominal hourly slots through the tested [UvForecast.uvAt].
         *
         * Resampling (rather than slicing 25 raw entries) is what makes the day **DST-safe**:
         * [LocalDate.atStartOfDayIn] resolves the real instant of local midnight even on 23/25-hour
         * days, and each "local hour H" then maps to `dayStart + H.hours`, the same nominal grid the
         * fixtures use. Hours outside the forecast window clamp to its endpoints (see [UvForecast.uvAt]).
         */
        fun fromForecast(
            forecast: UvForecast,
            zone: TimeZone,
            clock: Clock = Clock.System,
        ): UvDay {
            val dayStart = clock.todayIn(zone).atStartOfDayIn(zone)
            val hourlyUv = (0..24).map { hour -> forecast.uvAt(dayStart + hour.hours) }
            return UvDay(
                location = forecast.location,
                dayStart = dayStart,
                hourlyUv = hourlyUv,
            )
        }

        /**
         * A network-free **clear-sky estimate** for today at [location]: UV ∝ sin(solar elevation),
         * scaled so an overhead sun peaks near [CLEAR_SKY_PEAK_UV]. Used as the instant fallback so
         * the dashboard always renders something plausible while the live forecast loads (or if it
         * can't be reached). It ignores clouds/ozone/altitude — it's an estimate, labelled as such.
         */
        fun clearSkyEstimate(
            location: GeoPoint,
            zone: TimeZone,
            clock: Clock = Clock.System,
        ): UvDay {
            val dayStart = clock.todayIn(zone).atStartOfDayIn(zone)
            val hourlyUv = (0..24).map { hour ->
                val elevation = SolarGeometry.solarElevationDegrees(location, dayStart + hour.hours)
                if (elevation <= 0.0) 0.0 else CLEAR_SKY_PEAK_UV * sin(elevation * PI / 180.0)
            }
            return UvDay(location = location, dayStart = dayStart, hourlyUv = hourlyUv)
        }

        /** Clear-sky UV index at an overhead sun — a strong tropical noon. */
        private const val CLEAR_SKY_PEAK_UV: Double = 12.0
    }
}
