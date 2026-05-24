package com.insola.uv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App() {
    MaterialTheme {
        Scaffold { padding ->
            val vm: UvViewModel = viewModel { UvViewModel() }
            val state by vm.state.collectAsStateWithLifecycle()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("UV Index", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.uvIndex?.toString() ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(state.location, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
