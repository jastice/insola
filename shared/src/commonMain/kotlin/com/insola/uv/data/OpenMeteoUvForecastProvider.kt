package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Live hourly UV forecast from Open-Meteo's keyless Air-Quality API (HTTPS, no key).
 *
 * The API (with `timezone=auto`) returns hourly `uv_index` samples stamped in the location's local
 * clock, plus the matching `utc_offset_seconds`. We reattach that offset to each nominal local
 * timestamp to recover the absolute [Instant], so the samples line up with solar geometry exactly
 * the way the fixtures do. Missing (`null`) UV entries collapse to `0.0`.
 *
 * Two forecast days are requested so resampling 25 nominal slots (00:00..24:00) always has data for
 * the trailing midnight; [com.insola.uv.domain.UvDay.fromForecast] does the local-midnight anchoring.
 */
class OpenMeteoUvForecastProvider(
    private val client: HttpClient,
) : UvForecastProvider {

    override suspend fun fetchForecast(location: GeoPoint, dayStart: Instant): UvForecast {
        val response: AirQualityResponse = client.get(ENDPOINT) {
            parameter("latitude", location.latitude)
            parameter("longitude", location.longitude)
            parameter("hourly", "uv_index")
            parameter("timezone", "auto")
            parameter("forecast_days", FORECAST_DAYS)
        }.body()
        return response.toForecast(location)
    }

    companion object {
        const val ENDPOINT: String = "https://air-quality-api.open-meteo.com/v1/air-quality"
        private const val FORECAST_DAYS: Int = 2
    }
}

/**
 * Open-Meteo Air-Quality response, pared down to the fields we read. Lenient defaults keep decoding
 * resilient if the API omits a block. Internal (not private) so the parse path is unit-testable
 * against captured JSON without spinning up an HTTP engine.
 */
@Serializable
internal data class AirQualityResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: HourlyBlock = HourlyBlock(),
) {
    fun toForecast(location: GeoPoint): UvForecast {
        val offset = UtcOffset(seconds = utcOffsetSeconds)
        val samples = hourly.time.indices.map { i ->
            UvSample(
                time = LocalDateTime.parse(hourly.time[i]).toInstant(offset),
                uvIndex = hourly.uvIndex.getOrNull(i) ?: 0.0,
            )
        }
        return UvForecast(location = location, samples = samples)
    }
}

@Serializable
internal data class HourlyBlock(
    val time: List<String> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
)
