package com.insola.uv.data

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast

/**
 * Source of hourly UV-index forecasts covering at least the location's current local day (plus the
 * trailing midnight). Implementations throw on failure — the caller keeps its estimate on screen
 * and surfaces the error inline.
 */
interface UvForecastProvider {
    suspend fun fetchForecast(location: GeoPoint): UvForecast
}
