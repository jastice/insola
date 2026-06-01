package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvDay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Open-Meteo parse + slice tests. The HTTP path itself is thin (a Ktor GET); the logic worth
 * pinning is the DTO decoding (`@SerialName` mapping, `null` → 0.0) and the DST-safe resample in
 * [UvDay.fromForecast]. Both run against captured JSON / fixed clocks — no network, no engine.
 */
class OpenMeteoUvForecastProviderTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Fixed clock so "today" is deterministic regardless of where the test runs. */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    // Singapore (+08:00). 25 hourly samples 2026-03-20T00:00 .. 2026-03-21T00:00 local. A null at
    // 03:00 must decode to 0.0; the curve peaks at 11.5 at local noon (index 12).
    private val singaporeJson = """
        {
          "latitude": 1.35,
          "longitude": 103.82,
          "timezone": "Asia/Singapore",
          "utc_offset_seconds": 28800,
          "hourly": {
            "time": [
              "2026-03-20T00:00","2026-03-20T01:00","2026-03-20T02:00","2026-03-20T03:00",
              "2026-03-20T04:00","2026-03-20T05:00","2026-03-20T06:00","2026-03-20T07:00",
              "2026-03-20T08:00","2026-03-20T09:00","2026-03-20T10:00","2026-03-20T11:00",
              "2026-03-20T12:00","2026-03-20T13:00","2026-03-20T14:00","2026-03-20T15:00",
              "2026-03-20T16:00","2026-03-20T17:00","2026-03-20T18:00","2026-03-20T19:00",
              "2026-03-20T20:00","2026-03-20T21:00","2026-03-20T22:00","2026-03-20T23:00",
              "2026-03-21T00:00"
            ],
            "uv_index": [
              0.0,0.0,0.0,null,0.0,0.0,1.0,2.0,4.0,6.0,8.0,10.0,
              11.5,10.0,8.0,6.0,4.0,2.0,1.0,0.0,0.0,0.0,0.0,0.0,0.0
            ]
          }
        }
    """.trimIndent()

    @Test
    fun decodesCapturedJson_mapsNullToZero_andReattachesOffset() {
        val dto = json.decodeFromString(AirQualityResponse.serializer(), singaporeJson)
        val forecast = dto.toForecast(GeoPoint(1.35, 103.82))

        assertEquals(25, forecast.samples.size)
        // The null at 03:00 collapsed to 0.0.
        assertEquals(0.0, forecast.samples[3].uvIndex, 1e-12)
        // Local midnight 2026-03-20 at +08:00 == 2026-03-19T16:00Z.
        assertEquals(Instant.parse("2026-03-19T16:00:00Z"), forecast.samples.first().time)
        assertEquals(11.5, forecast.samples.maxOf { it.uvIndex }, 1e-12)
    }

    @Test
    fun fromForecast_resamples25Slots_slot0AtLocalMidnight_peakPreserved() {
        val dto = json.decodeFromString(AirQualityResponse.serializer(), singaporeJson)
        val forecast = dto.toForecast(GeoPoint(1.35, 103.82))
        val zone = TimeZone.of("Asia/Singapore")
        // 14:00 SGT on 2026-03-20 == 2026-03-20T06:00Z.
        val clock = FixedClock(Instant.parse("2026-03-20T06:00:00Z"))

        val day = UvDay.fromForecast(forecast, zone, clock)

        assertEquals(25, day.hourlyUv.size)
        // Slot 0 is local midnight and matches the forecast's first sample.
        assertEquals(Instant.parse("2026-03-19T16:00:00Z"), day.dayStart)
        assertEquals(0.0, day.hourlyUv[0], 1e-9)
        // Peak survives the resample at the exact hour.
        assertEquals(11.5, day.hourlyUv.max(), 0.1)
    }

    @Test
    fun fromForecast_dstSpringForward_yields25SlotsAtLocalMidnight() {
        // 2026-03-08 is the US spring-forward (23-hour day). atStartOfDayIn must still resolve a
        // clean local midnight and the resample must produce exactly 25 slots without throwing.
        val zone = TimeZone.of("America/New_York")
        val forecast = dto25("2026-03-08", offsetSeconds = -18000).toForecast(GeoPoint(40.71, -74.01))
        val clock = FixedClock(Instant.parse("2026-03-08T17:00:00Z")) // ~noon EST/EDT that day

        val day = UvDay.fromForecast(forecast, zone, clock)

        assertEquals(25, day.hourlyUv.size)
        assertLocalMidnight(day.dayStart, zone)
    }

    @Test
    fun fromForecast_dstFallBack_yields25SlotsAtLocalMidnight() {
        // 2026-11-01 is the US fall-back (25-hour day).
        val zone = TimeZone.of("America/New_York")
        val forecast = dto25("2026-11-01", offsetSeconds = -14400).toForecast(GeoPoint(40.71, -74.01))
        val clock = FixedClock(Instant.parse("2026-11-01T16:00:00Z"))

        val day = UvDay.fromForecast(forecast, zone, clock)

        assertEquals(25, day.hourlyUv.size)
        assertLocalMidnight(day.dayStart, zone)
    }

    private fun assertLocalMidnight(instant: Instant, zone: TimeZone) {
        val local = instant.toLocalDateTime(zone)
        assertEquals(0, local.hour, "dayStart should be local midnight, got $local")
        assertEquals(0, local.minute)
    }

    /** Build a 25-entry response covering [date]'s local hours at a fixed [offsetSeconds]. */
    private fun dto25(date: String, offsetSeconds: Int): AirQualityResponse {
        val times = (0..24).map { h ->
            if (h < 24) "${date}T${h.toString().padStart(2, '0')}:00" else "${date}T23:00"
        }
        val uv = (0..24).map { h -> if (h in 8..16) (h - 4).toDouble() else 0.0 }
        return AirQualityResponse(
            utcOffsetSeconds = offsetSeconds,
            hourly = HourlyBlock(time = times, uvIndex = uv),
        )
    }

    @Test
    fun providerEndpoint_isHttps() {
        assertTrue(OpenMeteoUvForecastProvider.ENDPOINT.startsWith("https://"))
    }
}
