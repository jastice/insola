package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.dev.Scenario
import kotlinx.datetime.Instant

/**
 * Returns the [Scenario.forecast] of the scenario produced by [scenario]. Location and dayStart
 * arguments are ignored — the dev dashboard owns scenario selection elsewhere.
 */
class FakeUvForecastProvider(private val scenario: () -> Scenario) : UvForecastProvider {
    override suspend fun fetchForecast(location: GeoPoint, dayStart: Instant): UvForecast =
        scenario().forecast
}
