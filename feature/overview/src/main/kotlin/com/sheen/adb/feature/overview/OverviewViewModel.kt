package com.sheen.adb.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.DeviceOverview
import com.sheen.adb.core.DynamicDeviceMetrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OverviewUiState(
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val overview: DeviceOverview? = null,
    val error: AdbError? = null,
    val sessionId: String? = null,
)

class OverviewViewModel(private val manager: AdbSessionManager) : ViewModel() {
    private val mutableState = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = mutableState.asStateFlow()
    private var foreground = false
    private var polling: Job? = null
    private var load: Job? = null

    init {
        viewModelScope.launch {
            manager.connectionState.collect { connection ->
                val connected = connection as? AdbConnectionState.Connected
                val changed = connected?.sessionId != mutableState.value.sessionId
                mutableState.update {
                    it.copy(
                        isConnected = connected != null,
                        sessionId = connected?.sessionId,
                        overview = if (changed) null else it.overview,
                        error = if (changed) null else it.error,
                    )
                }
                if (connected == null) stopWork() else if (foreground) startWork()
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && mutableState.value.isConnected) startWork() else stopWork()
    }

    fun refresh() {
        if (!mutableState.value.isConnected) return
        load?.cancel()
        load = viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            when (val result = manager.loadDeviceOverview()) {
                is AdbOperationResult.Success -> mutableState.update {
                    it.copy(isLoading = false, overview = result.value, error = null)
                }
                is AdbOperationResult.Failure -> mutableState.update { it.copy(isLoading = false, error = result.error) }
                AdbOperationResult.Cancelled -> mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun startWork() {
        if (mutableState.value.overview == null && load?.isActive != true) refresh()
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive && foreground && mutableState.value.isConnected) {
                delay(5_000)
                when (val result = manager.refreshDynamicMetrics()) {
                    is AdbOperationResult.Success -> applyDynamic(result.value)
                    is AdbOperationResult.Failure -> mutableState.update { it.copy(error = result.error) }
                    AdbOperationResult.Cancelled -> Unit
                }
            }
        }
    }

    private fun applyDynamic(metrics: DynamicDeviceMetrics) = mutableState.update { current ->
        current.copy(
            overview = current.overview?.copy(
                memoryTotalBytes = metrics.memoryTotalBytes,
                memoryAvailableBytes = metrics.memoryAvailableBytes,
                batteryPercent = metrics.batteryPercent,
                chargingState = metrics.chargingState,
                temperatureCelsius = metrics.temperatureCelsius,
                uptimeSeconds = metrics.uptimeSeconds,
            ),
            error = null,
        )
    }

    private fun stopWork() {
        polling?.cancel()
        polling = null
        load?.cancel()
        load = null
        mutableState.update { it.copy(isLoading = false) }
    }

    override fun onCleared() {
        stopWork()
        super.onCleared()
    }
}
