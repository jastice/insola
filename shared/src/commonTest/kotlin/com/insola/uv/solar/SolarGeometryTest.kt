package com.insola.uv.solar

import com.insola.uv.domain.GeoPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class SolarGeometryTest {

    @Test
    fun equatorialNoonOnEquinox_isNearOverhead() {
        val equator = GeoPoint(0.0, 0.0)
        val noonUtc = Instant.parse("2026-03-20T12:00:00Z")
        val elev = SolarGeometry.solarElevationDegrees(equator, noonUtc)
        // Should be very close to 90°
        assertTrue(elev > 85.0, "expected > 85°, got $elev")
    }

    @Test
    fun equatorialMidnight_isBelowHorizon() {
        val equator = GeoPoint(0.0, 0.0)
        val midnightUtc = Instant.parse("2026-03-20T00:00:00Z")
        val elev = SolarGeometry.solarElevationDegrees(equator, midnightUtc)
        assertTrue(elev < 0.0, "expected below horizon, got $elev")
    }

    @Test
    fun arcticWinter_sunStaysLow() {
        val reykjavik = GeoPoint(64.13, -21.94)
        val winterNoon = Instant.parse("2026-12-21T12:00:00Z")
        val elev = SolarGeometry.solarElevationDegrees(reykjavik, winterNoon)
        // Sun barely peeks above horizon in deep winter
        assertTrue(elev < 5.0, "expected very low sun, got $elev")
    }

    @Test
    fun mediumLatitudeSummerNoon_isHigh() {
        val berlin = GeoPoint(52.52, 13.40)
        // June 21 local noon ≈ 10:00 UTC for Berlin (UTC+2 summer time, solar noon)
        val solarNoonUtc = Instant.parse("2026-06-21T10:00:00Z")
        val elev = SolarGeometry.solarElevationDegrees(berlin, solarNoonUtc)
        assertTrue(elev > 55.0, "expected high summer sun, got $elev")
    }
}
