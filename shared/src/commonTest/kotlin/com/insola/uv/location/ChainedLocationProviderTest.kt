package com.insola.uv.location

import com.insola.uv.domain.GeoPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fallback-chain ordering: a fresh GPS fix beats a cached one beats IP-geo beats the timezone
 * centroid. Built from fakes so no Play Services / network is involved.
 */
class ChainedLocationProviderTest {

    private fun fakeDevice(fix: GeoPoint?, last: GeoPoint?) = object : DeviceLocationSource {
        override suspend fun currentFix(): GeoPoint? = fix
        override suspend fun lastKnown(): GeoPoint? = last
    }

    private fun fakeProvider(result: ResolvedLocation?) = object : LocationProvider {
        override suspend fun resolve(): ResolvedLocation? = result
    }

    private val gps = GeoPoint(1.0, 1.0)
    private val last = GeoPoint(2.0, 2.0)
    private val ip = ResolvedLocation(GeoPoint(3.0, 3.0), LocationSource.Ip)
    private val tz = ResolvedLocation(GeoPoint(4.0, 4.0), LocationSource.Timezone)

    private fun chain(device: DeviceLocationSource, ipResult: ResolvedLocation?) =
        ChainedLocationProvider(
            listOf(
                DeviceLocationProvider(device),
                fakeProvider(ipResult),
                fakeProvider(tz),
            ),
        )

    @Test
    fun freshFix_wins() = runTest {
        val r = chain(fakeDevice(fix = gps, last = last), ip).resolve()
        assertEquals(ResolvedLocation(gps, LocationSource.Gps), r)
    }

    @Test
    fun lastKnown_winsWhenNoFreshFix() = runTest {
        val r = chain(fakeDevice(fix = null, last = last), ip).resolve()
        assertEquals(ResolvedLocation(last, LocationSource.LastKnown), r)
    }

    @Test
    fun ip_winsWhenDeviceEmpty() = runTest {
        val r = chain(fakeDevice(fix = null, last = null), ip).resolve()
        assertEquals(ip, r)
    }

    @Test
    fun timezone_winsWhenEverythingElseFails() = runTest {
        val r = chain(fakeDevice(fix = null, last = null), ipResult = null).resolve()
        assertEquals(tz, r)
    }

    @Test
    fun throwingProvider_isSkipped_notFatal() = runTest {
        val throwing = object : LocationProvider {
            override suspend fun resolve(): ResolvedLocation = throw IllegalStateException("boom")
        }
        val r = ChainedLocationProvider(listOf(throwing, fakeProvider(tz))).resolve()
        assertEquals(tz, r)
    }

    @Test
    fun emptyChain_resolvesNull() = runTest {
        assertNull(ChainedLocationProvider(emptyList()).resolve())
    }
}

/**
 * The timezone centroid always resolves — it's the chain's guaranteed terminal. A known zone maps
 * to its curated centroid; an offset-only zone estimates longitude from the UTC offset.
 */
class TimezoneLocationProviderTest {

    @Test
    fun knownZone_usesCuratedCentroid_andNamesTheCity() = runTest {
        val provider = TimezoneLocationProvider(zoneId = { "Asia/Singapore" })
        val r = provider.resolve()
        assertEquals(LocationSource.Timezone, r.source)
        assertEquals(1.35, r.point.latitude, 1e-9)
        assertEquals(103.82, r.point.longitude, 1e-9)
        assertEquals("Singapore", r.place)
    }

    @Test
    fun zoneCity_isDerivedFromMultiWordZoneId() = runTest {
        val r = TimezoneLocationProvider(zoneId = { "America/New_York" }).resolve()
        assertEquals("New York", r.place)
    }

    @Test
    fun unknownZone_estimatesLongitudeFromOffset_withNoPlaceName() = runTest {
        // UTC has offset 0 → longitude 0, latitude 0, and no city name. Always succeeds, never throws.
        val r = TimezoneLocationProvider(zoneId = { "UTC" }).resolve()
        assertEquals(LocationSource.Timezone, r.source)
        assertEquals(0.0, r.point.longitude, 1e-9)
        assertNull(r.place)
    }
}

class IpLocationPlaceNameTest {

    @Test
    fun placeName_prefersCity_thenRegion_thenCountry() {
        assertEquals("Berlin", IpWhoResponse(city = "Berlin", region = "Berlin", country = "Germany").placeName())
        assertEquals("Bavaria", IpWhoResponse(city = " ", region = "Bavaria", country = "Germany").placeName())
        assertEquals("Germany", IpWhoResponse(city = null, region = null, country = "Germany").placeName())
        assertNull(IpWhoResponse(city = null, region = null, country = null).placeName())
    }
}
