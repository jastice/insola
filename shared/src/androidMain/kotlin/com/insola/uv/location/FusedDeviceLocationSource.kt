package com.insola.uv.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.insola.uv.domain.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [DeviceLocationSource] backed by Play Services' Fused location provider.
 *
 * Both calls are permission-checked (so a denied permission falls through to the next chain link
 * rather than crashing) and bounded by [withTimeoutOrNull] so a hung GPS never blocks launch — the
 * chain just moves on to IP-geo. Every Task failure/cancellation resolves to `null`; nothing throws.
 *
 * Constructed with an Activity/Application [Context], which is why the whole location stack is wired
 * by manual DI from `MainActivity` rather than an expect/actual factory.
 */
class FusedDeviceLocationSource(context: Context) : DeviceLocationSource {

    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    override suspend fun currentFix(): GeoPoint? {
        if (!hasPermission()) return null
        val cancellation = CancellationTokenSource()
        val point = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            // HIGH_ACCURACY engages the GPS provider. BALANCED relies on network/passive location,
            // which on an emulator never sees the injected `geo fix` (a GPS-provider fix) and so
            // times out into the stale last-known default. A one-shot fix, bounded by the timeout.
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .awaitOrNull()
                ?.let { GeoPoint(it.latitude, it.longitude) }
        }
        if (point == null) cancellation.cancel()
        return point
    }

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    override suspend fun lastKnown(): GeoPoint? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(LAST_KNOWN_TIMEOUT_MS) {
            client.lastLocation.awaitOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
        }
    }

    /** Bridge a Play-Services [Task] to a coroutine, resolving to `null` on failure/cancellation. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 5_000L
        const val LAST_KNOWN_TIMEOUT_MS = 3_000L
    }
}
