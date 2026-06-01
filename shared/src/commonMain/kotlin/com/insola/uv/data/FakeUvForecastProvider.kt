package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvDay
import com.insola.uv.domain.UvForecast
import kotlinx.datetime.Instant

/**
 * Returns the [UvDay.forecast] of the day produced by [day]. Location and dayStart arguments are
 * ignored — the dev dashboard owns day selection elsewhere.
 */
class FakeUvForecastProvider(private val day: () -> UvDay) : UvForecastProvider {
    override suspend fun fetchForecast(location: GeoPoint, dayStart: Instant): UvForecast =
        day().forecast
}
