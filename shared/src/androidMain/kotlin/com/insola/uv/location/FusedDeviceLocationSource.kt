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

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFine(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || hasFine()

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    override suspend fun currentFix(): GeoPoint? {
        if (!hasPermission()) return null
        // HIGH_ACCURACY engages the GPS provider (needed on emulators, where an injected `geo fix`
        // is GPS-only and BALANCED would time out into the stale last-known default) — but it
        // requires FINE. With a coarse-only grant ("Approximate location" on Android 12+) it throws
        // SecurityException, so fall back to BALANCED rather than losing the fix entirely.
        val priority =
            if (hasFine()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                client.getCurrentLocation(priority, cancellation.token)
                    .awaitOrNull()
                    ?.let { GeoPoint(it.latitude, it.longitude) }
            }
        } finally {
            // Idempotent; also stops the in-flight (GPS-on) request when the *caller* is cancelled,
            // a path a plain "cancel on null" would skip.
            cancellation.cancel()
        }
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
