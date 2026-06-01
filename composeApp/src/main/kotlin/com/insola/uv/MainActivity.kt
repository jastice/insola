package com.insola.uv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.insola.uv.dashboard.DashboardViewModel
import com.insola.uv.data.OpenMeteoUvForecastProvider
import com.insola.uv.location.AndroidReverseGeocoder
import com.insola.uv.location.ChainedLocationProvider
import com.insola.uv.location.DeviceLocationProvider
import com.insola.uv.location.FusedDeviceLocationSource
import com.insola.uv.location.IpLocationProvider
import com.insola.uv.location.TimezoneLocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // Bound every request so a dead network fails fast instead of hanging the load.
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            // Ride out transient DNS/network blips transparently (benefits the IP-geo call too).
            install(HttpRequestRetry) {
                retryOnExceptionOrServerErrors(maxRetries = 3)
                exponentialDelay()
            }
        }
    }

    private lateinit var viewModel: DashboardViewModel

    // Registered before onCreate completes. The chain already shows an IP/timezone fallback without
    // permission, so a grant just upgrades the next [refresh] to a GPS fix — no restart needed.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val reverseGeocoder = AndroidReverseGeocoder(this)
        val locationProvider = ChainedLocationProvider(
            listOf(
                DeviceLocationProvider(FusedDeviceLocationSource(this), reverseGeocoder),
                IpLocationProvider(httpClient),
                TimezoneLocationProvider(),
            ),
        )
        val forecastProvider = OpenMeteoUvForecastProvider(httpClient)

        viewModel = ViewModelProvider(
            this,
            viewModelFactory(forecastProvider, locationProvider, devMode = BuildConfig.DEBUG),
        )[DashboardViewModel::class.java]

        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }

        setContent { App(viewModel = viewModel, devMode = BuildConfig.DEBUG) }
    }

    override fun onDestroy() {
        super.onDestroy()
        httpClient.close()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun viewModelFactory(
        forecastProvider: OpenMeteoUvForecastProvider,
        locationProvider: ChainedLocationProvider,
        devMode: Boolean,
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DashboardViewModel(forecastProvider, locationProvider, devMode) as T
    }
}
