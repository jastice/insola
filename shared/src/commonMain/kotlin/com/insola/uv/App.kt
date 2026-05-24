package com.insola.uv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.insola.uv.dashboard.DashboardScreen
import com.insola.uv.dashboard.DashboardViewModel

@Composable
fun App() {
    MaterialTheme {
        Scaffold { padding ->
            val vm: DashboardViewModel = viewModel { DashboardViewModel() }
            DashboardScreen(
                viewModel = vm,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
