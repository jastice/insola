package com.insola.uv.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.insola.uv.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ReverseGeocoder] backed by Android's platform [Geocoder]. Names a device fix with its city /
 * metro region for the UI header. Bounded by a timeout and fully null-safe — geocoding is a
 * best-effort label, never a blocker. Uses the deprecated synchronous `getFromLocation` (fine on
 * minSdk 26) off the main thread; the API 33+ async variant isn't worth the branch for one label.
 */
class AndroidReverseGeocoder(context: Context) : ReverseGeocoder {

    private val appContext = context.applicationContext

    override suspend fun placeName(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val address = try {
                    Geocoder(appContext)
                        .getFromLocation(point.latitude, point.longitude, 1)
                        ?.firstOrNull()
                } catch (_: Throwable) {
                    null
                }
                address?.bestLabel()
            }
        }
    }

    /** Prefer the city; fall back to county → state → country so we always show *something* named. */
    private fun Address.bestLabel(): String? =
        listOf(locality, subAdminArea, adminArea, countryName)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
