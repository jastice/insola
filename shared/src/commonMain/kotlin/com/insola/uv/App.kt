package com.insola.uv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.insola.uv.dashboard.DashboardScreen
import com.insola.uv.dashboard.DashboardViewModel

/**
 * App root. The [viewModel] is built and owned by the platform entrypoint (e.g. `MainActivity`),
 * which supplies the live forecast/location providers via manual DI — that's also where the
 * location-permission launcher lives, so it can call [DashboardViewModel.refresh] on a grant.
 *
 * [devMode] gates the hidden dev affordances (long-press scenario picker); it's typically the
 * platform's debug flag.
 */
@Composable
fun App(viewModel: DashboardViewModel, devMode: Boolean = false) {
    MaterialTheme {
        Scaffold { padding ->
            DashboardScreen(
                viewModel = viewModel,
                devMode = devMode,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
