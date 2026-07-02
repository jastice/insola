package com.insola.uv.location

import com.insola.uv.domain.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.serialization.Serializable

/** Where a [ResolvedLocation] came from, best (most precise) first. Drives the UI source notice. */
enum class LocationSource { Gps, LastKnown, Ip, Timezone }

/**
 * A best-effort location plus the [source] that produced it and, when available, a human-readable
 * [place] (city / metro region) for the UI header. `place` is null when no name could be resolved.
 */
data class ResolvedLocation(val point: GeoPoint, val source: LocationSource, val place: String? = null)

/**
 * Optional reverse geocoder: turns a [GeoPoint] into a human place name (city / metro). Returns null
 * when unavailable (no platform geocoder, offline, no match). Used to name precise device fixes —
 * the IP and timezone strategies already carry their own names.
 */
fun interface ReverseGeocoder {
    suspend fun placeName(point: GeoPoint): String?
}

/**
 * Resolves a best-effort current location, tagged with its [LocationSource]. Implementations **must
 * never throw** — they return `null` when they can't produce a fix so the caller can fall through to
 * the next strategy. The terminal [TimezoneLocationProvider] always succeeds, so a fully-assembled
 * [ChainedLocationProvider] never returns `null` in practice.
 */
interface LocationProvider {
    suspend fun resolve(): ResolvedLocation?
}

/**
 * Platform device location (Android's Fused provider). Yields a fresh fix or a cached last-known
 * fix, or `null` when there's no permission / no provider / it times out. Permission and timeout
 * handling live in the platform impl; like [LocationProvider], it must never throw.
 */
interface DeviceLocationSource {
    /** Fresh GPS-grade fix, tagged [LocationSource.Gps] by [DeviceLocationProvider]. */
    suspend fun currentFix(): GeoPoint?

    /** Cached last-known fix, tagged [LocationSource.LastKnown]. */
    suspend fun lastKnown(): GeoPoint?
}

/**
 * Adapts a [DeviceLocationSource] into the [LocationProvider] chain: prefers a fresh fix
 * ([LocationSource.Gps]) and falls back to the cached one ([LocationSource.LastKnown]). When a
 * [geocoder] is supplied, the fix is named with its city/metro for the UI header.
 */
class DeviceLocationProvider(
    private val source: DeviceLocationSource,
    private val geocoder: ReverseGeocoder? = null,
) : LocationProvider {
    override suspend fun resolve(): ResolvedLocation? {
        val fix = source.currentFix()?.let { it to LocationSource.Gps }
            ?: source.lastKnown()?.let { it to LocationSource.LastKnown }
            ?: return null
        val (point, source) = fix
        val place = try {
            geocoder?.placeName(point)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        return ResolvedLocation(point, source, place)
    }
}

/**
 * Tries each provider in order and returns the first non-null result. With a terminal
 * [TimezoneLocationProvider] last, this always yields *something*. A provider that throws is
 * treated as "no result" so one bad strategy can't sink the chain — except cancellation, which
 * must propagate so a superseded resolve doesn't keep running (and "succeeding") on a dead job.
 */
class ChainedLocationProvider(private val providers: List<LocationProvider>) : LocationProvider {
    override suspend fun resolve(): ResolvedLocation? {
        for (provider in providers) {
            val resolved = try {
                provider.resolve()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (resolved != null) return resolved
        }
        return null
    }
}

/**
 * Keyless IP-geolocation fallback over HTTPS (ipwho.is). Returns `null` on any failure — bad
 * response, missing coordinates, or `success=false`.
 */
class IpLocationProvider(
    private val client: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : LocationProvider {
    override suspend fun resolve(): ResolvedLocation? {
        val response = try {
            client.get(endpoint).body<IpWhoResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        val lat = response.latitude
        val lon = response.longitude
        return if (response.success && lat != null && lon != null) {
            ResolvedLocation(GeoPoint(lat, lon), LocationSource.Ip, response.placeName())
        } else {
            null
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://ipwho.is/"
    }
}

@Serializable
internal data class IpWhoResponse(
    val success: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
) {
    /** Best available place label: city, else region, else country. */
    fun placeName(): String? =
        listOf(city, region, country).firstOrNull { !it.isNullOrBlank() }?.trim()
}

/**
 * Last-resort location: a coarse centroid derived from the device timezone. Always succeeds, so it
 * anchors the bottom of the fallback chain and guarantees the app shows *some* curve. A small table
 * of common zones gives a sensible lat/lon; anything else falls back to a longitude estimated from
 * the zone's current UTC offset (15°/hour) at the equator — crude, but never empty.
 */
class TimezoneLocationProvider(
    private val zoneId: () -> String = { TimeZone.currentSystemDefault().id },
    private val clock: Clock = Clock.System,
) : LocationProvider {
    override suspend fun resolve(): ResolvedLocation = timezoneCentroid(zoneId(), clock)
}

/**
 * Synchronous timezone-centroid resolution — shared by [TimezoneLocationProvider] and the
 * ViewModel's instant fallback estimate (so the dashboard can render a plausible curve at the
 * user's city without waiting on GPS/IP/network).
 */
internal fun timezoneCentroid(zoneId: String, clock: Clock): ResolvedLocation {
    val point = TIMEZONE_CENTROIDS[zoneId] ?: offsetCentroid(zoneId, clock)
    return ResolvedLocation(point, LocationSource.Timezone, placeFromZone(zoneId))
}

/** "Europe/Berlin" → "Berlin", "America/New_York" → "New York". Null for bare-offset zones (UTC, Etc). */
private fun placeFromZone(id: String): String? {
    if ('/' !in id || id.startsWith("Etc/")) return null
    return id.substringAfterLast('/').replace('_', ' ').takeIf { it.isNotBlank() }
}

private fun offsetCentroid(id: String, clock: Clock): GeoPoint {
    val offsetSeconds = try {
        clock.now().offsetIn(TimeZone.of(id)).totalSeconds
    } catch (_: IllegalTimeZoneException) {
        0
    }
    val longitude = (offsetSeconds / 3600.0 * 15.0).coerceIn(-180.0, 180.0)
    return GeoPoint(latitude = 0.0, longitude = longitude)
}

/** Approximate population centroids for common zones — good enough for a UV curve. */
private val TIMEZONE_CENTROIDS: Map<String, GeoPoint> = mapOf(
    "Europe/Berlin" to GeoPoint(52.52, 13.40),
    "Europe/Paris" to GeoPoint(48.85, 2.35),
    "Europe/London" to GeoPoint(51.51, -0.13),
    "Europe/Madrid" to GeoPoint(40.42, -3.70),
    "Europe/Rome" to GeoPoint(41.90, 12.50),
    "America/New_York" to GeoPoint(40.71, -74.01),
    "America/Los_Angeles" to GeoPoint(34.05, -118.24),
    "America/Chicago" to GeoPoint(41.88, -87.63),
    "Asia/Singapore" to GeoPoint(1.35, 103.82),
    "Asia/Tokyo" to GeoPoint(35.68, 139.69),
    "Australia/Sydney" to GeoPoint(-33.87, 151.21),
    "Atlantic/Reykjavik" to GeoPoint(64.13, -21.94),
)
