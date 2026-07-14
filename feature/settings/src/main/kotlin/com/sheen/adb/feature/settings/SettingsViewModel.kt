package com.sheen.adb.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.data.DeviceProfileRepository
import com.sheen.adb.data.TemporaryDataCleaner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val versionLabel: String,
    val showClearConfirmation: Boolean = false,
    val isClearing: Boolean = false,
    val clearResult: String? = null,
    val settingsHelp: String? = null,
)

class SettingsViewModel(
    versionLabel: String,
    private val repository: DeviceProfileRepository,
    private val manager: AdbSessionManager,
    private val temporaryDataCleaner: TemporaryDataCleaner,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(versionLabel))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun requestClear() = mutableState.update(SettingsUiState::requestClearConfirmation)
    fun dismissClear() = mutableState.update(SettingsUiState::dismissClearConfirmation)
    fun showManualSettingsPath() = mutableState.update {
        it.copy(settingsHelp = "请手动打开：系统设置 → 关于手机 → 连续点击系统版本开启开发者选项 → 更多设置 → 开发者选项 → 无线调试。")
    }

    fun clearAll() {
        if (mutableState.value.isClearing) return
        mutableState.update { it.copy(showClearConfirmation = false, isClearing = true, clearResult = null) }
        viewModelScope.launch {
            repository.clearAll()
            val identity = manager.clearHostIdentity()
            val temporary = temporaryDataCleaner.clear()
            val profilesEmpty = repository.profiles.first().isEmpty()
            val success = profilesEmpty && identity is AdbOperationResult.Success && temporary
            mutableState.update {
                it.copy(
                    isClearing = false,
                    clearResult = if (success) "所有本地数据已清除并验证不可继续使用旧身份。" else
                        "清除未完全成功，请重启应用后重试。",
                )
            }
        }
    }
}

internal fun SettingsUiState.requestClearConfirmation(): SettingsUiState =
    copy(showClearConfirmation = true, clearResult = null)

internal fun SettingsUiState.dismissClearConfirmation(): SettingsUiState = copy(showClearConfirmation = false)
