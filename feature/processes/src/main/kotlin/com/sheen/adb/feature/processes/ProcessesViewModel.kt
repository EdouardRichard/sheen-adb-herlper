package com.sheen.adb.feature.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.DeviceProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProcessesUiState(
    val isConnected: Boolean = false,
    val sessionId: String? = null,
    val isLoading: Boolean = false,
    val query: String = "",
    val processes: List<DeviceProcess> = emptyList(),
    val degradedReason: String? = null,
    val error: AdbError? = null,
) {
    val visibleProcesses: List<DeviceProcess>
        get() = query.trim().takeIf(String::isNotEmpty)?.let { needle ->
            processes.filter { it.name.contains(needle, ignoreCase = true) || it.pid.toString().contains(needle) }
        } ?: processes
}

class ProcessesViewModel(private val manager: AdbSessionManager) : ViewModel() {
    private val mutableState = MutableStateFlow(ProcessesUiState())
    val state: StateFlow<ProcessesUiState> = mutableState.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            manager.connectionState.collect { connection ->
                val connected = connection as? AdbConnectionState.Connected
                if (connected?.sessionId != mutableState.value.sessionId) {
                    operation?.cancel()
                    mutableState.value = ProcessesUiState(
                        isConnected = connected != null,
                        sessionId = connected?.sessionId,
                    )
                } else mutableState.update { it.copy(isConnected = connected != null) }
            }
        }
    }

    fun updateQuery(value: String) = mutableState.update { it.copy(query = value) }

    fun refresh() {
        if (!mutableState.value.isConnected || operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            when (val result = manager.listProcesses()) {
                is AdbOperationResult.Success -> mutableState.update {
                    it.copy(
                        isLoading = false,
                        processes = result.value.processes,
                        degradedReason = result.value.degradedReason,
                        error = null,
                    )
                }
                is AdbOperationResult.Failure -> mutableState.update { it.copy(isLoading = false, error = result.error) }
                AdbOperationResult.Cancelled -> mutableState.update { it.copy(isLoading = false) }
            }
            operation = null
        }
    }

    fun cancel() { operation?.cancel(); operation = null; mutableState.update { it.copy(isLoading = false) } }
    override fun onCleared() { operation?.cancel(); super.onCleared() }
}
