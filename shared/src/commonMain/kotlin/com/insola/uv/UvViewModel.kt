package com.insola.uv

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UvState(
    val uvIndex: Int? = null,
    val location: String = "unknown",
)

class UvViewModel : ViewModel() {
    private val _state = MutableStateFlow(UvState(uvIndex = 0, location = "—"))
    val state: StateFlow<UvState> = _state.asStateFlow()
}
