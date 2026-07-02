package com.insola.uv.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.insola.uv.domain.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ReverseGeocoder] backed by Android's platform [Geocoder]. Names a device fix with its city /
 * metro region for the UI header. Bounded by a timeout and fully null-safe — geocoding is a
 * best-effort label, never a blocker. Uses the deprecated synchronous `getFromLocation` (fine on
 * minSdk 26) off the main thread; the API 33+ async variant isn't worth the branch for one label.
 *
 * `getFromLocation` can block for tens of seconds and does not respond to coroutine cancellation
 * (or reliably to thread interruption), so the lookup runs on a detached IO scope and only the
 * *await* is timeout-bounded: on timeout the caller moves on immediately while the stuck lookup
 * finishes (and is discarded) in the background.
 */
class AndroidReverseGeocoder(context: Context) : ReverseGeocoder {

    private val appContext = context.applicationContext
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun placeName(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        val lookup = lookupScope.async {
            @Suppress("DEPRECATION")
            try {
                Geocoder(appContext)
                    .getFromLocation(point.latitude, point.longitude, 1)
                    ?.firstOrNull()
                    ?.bestLabel()
            } catch (_: Throwable) {
                null
            }
        }
        return withTimeoutOrNull(TIMEOUT_MS) { lookup.await() }
            .also { if (it == null) lookup.cancel() }
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
