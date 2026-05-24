package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import kotlinx.datetime.Instant

/**
 * Source of hourly UV-index forecasts. Phase 2 will introduce an Open-Meteo implementation; for
 * now only [FakeUvForecastProvider] exists and the dev dashboard talks to fixtures directly.
 */
interface UvForecastProvider {
    suspend fun fetchForecast(location: GeoPoint, dayStart: Instant): UvForecast
}
