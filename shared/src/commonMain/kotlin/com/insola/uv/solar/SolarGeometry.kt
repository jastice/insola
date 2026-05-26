package com.insola.uv.solar

import com.insola.uv.domain.GeoPoint
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

object SolarGeometry {

    private const val DEG = PI / 180.0
    private const val RAD = 180.0 / PI

    fun solarElevationDegrees(point: GeoPoint, time: Instant): Double {
        val utc = time.toLocalDateTime(TimeZone.UTC)
        val dayOfYear = utc.date.dayOfYear
        val fractionalHour = utc.hour + utc.minute / 60.0 + utc.second / 3600.0

        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1 + (fractionalHour - 12.0) / 24.0)

        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) -
                0.040849 * sin(2 * gamma)
            )

        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) +
            0.00148 * sin(3 * gamma)

        val timeOffsetMin = eqTime + 4.0 * point.longitude
        val trueSolarTimeMin = fractionalHour * 60.0 + timeOffsetMin
        val hourAngleDeg = trueSolarTimeMin / 4.0 - 180.0
        val ha = hourAngleDeg * DEG
        val latRad = point.latitude * DEG

        val cosZenith = sin(latRad) * sin(decl) + cos(latRad) * cos(decl) * cos(ha)
        val zenith = acos(cosZenith.coerceIn(-1.0, 1.0))
        val elevation = (PI / 2.0 - zenith) * RAD
        return elevation
    }

    /**
     * Sunrise and sunset as hour-of-day offsets from [dayStart], or null when the sun never crosses
     * the horizon (polar night). When the sun stays above the horizon all day (polar day) the
     * window is `0.0..24.0`.
     *
     * Implementation: coarse 10-min scan to bracket each transition, then 5-step bisection,
     * landing at sub-minute precision — plenty for chart shading.
     */
    fun daylightWindow(point: GeoPoint, dayStart: Instant): DaylightWindow {
        val stepMinutes = 10
        val samples = (0..(24 * 60) step stepMinutes).map { mins ->
            mins / 60.0 to solarElevationDegrees(point, dayStart + mins.minutes)
        }
        val above = samples.map { it.second > 0.0 }
        if (above.none { it }) return DaylightWindow(null, null)
        if (above.all { it }) return DaylightWindow(0.0, 24.0)

        fun bisectCrossing(lowHour: Double, highHour: Double, rising: Boolean): Double {
            var lo = lowHour
            var hi = highHour
            repeat(8) {
                val mid = (lo + hi) / 2.0
                val midMinutes = (mid * 60).toLong()
                val elev = solarElevationDegrees(point, dayStart + midMinutes.minutes)
                if ((elev > 0.0) == rising) hi = mid else lo = mid
            }
            return (lo + hi) / 2.0
        }

        val sunrise = samples.zipWithNext().firstOrNull { (a, b) -> !(a.second > 0.0) && b.second > 0.0 }
            ?.let { (a, b) -> bisectCrossing(a.first, b.first, rising = true) }
        val sunset = samples.zipWithNext().lastOrNull { (a, b) -> a.second > 0.0 && !(b.second > 0.0) }
            ?.let { (a, b) -> bisectCrossing(a.first, b.first, rising = false) }
        return DaylightWindow(sunrise, sunset)
    }

    data class DaylightWindow(val sunriseHour: Double?, val sunsetHour: Double?)
}
