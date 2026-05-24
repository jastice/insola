package com.insola.uv.solar

import com.insola.uv.domain.GeoPoint
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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
}
