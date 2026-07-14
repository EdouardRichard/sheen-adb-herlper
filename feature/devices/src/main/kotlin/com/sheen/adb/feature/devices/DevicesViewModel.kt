package com.sheen.adb.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbEndpointParser
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.EndpointParseResult
import com.sheen.adb.data.DeviceProfile
import com.sheen.adb.data.DeviceProfileRepository
import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val endpointInput: String = "",
    val pairingEndpointInput: String = "",
    val pairingCode: String = "",
    val showPairing: Boolean = false,
    val showDiagnostics: Boolean = false,
    val inputError: String? = null,
    val notice: String? = null,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected(),
    val profiles: List<DeviceProfile> = emptyList(),
    val diagnosticEvents: List<AdbDiagnosticEvent> = emptyList(),
    val pendingDeleteProfile: DeviceProfile? = null,
    val pendingRenameProfile: DeviceProfile? = null,
    val renameInput: String = "",
)

class DevicesViewModel(
    private val manager: AdbSessionManager,
    private val repository: DeviceProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var operationGeneration = 0L

    init {
        viewModelScope.launch {
            manager.connectionState.collect { connection -> mutableState.update { it.copy(connectionState = connection) } }
        }
        viewModelScope.launch {
            manager.diagnosticEvents.collect { events -> mutableState.update { it.copy(diagnosticEvents = events) } }
        }
        viewModelScope.launch {
            repository.profiles.collect { profiles -> mutableState.update { it.copy(profiles = profiles) } }
        }
    }

    fun updateEndpoint(value: String) = mutableState.update { it.copy(endpointInput = value, inputError = null, notice = null) }

    fun prefillLocalhost() = mutableState.update {
        it.copy(endpointInput = "127.0.0.1:", inputError = null, notice = "请填写无线调试主页面显示的当前调试端口")
    }

    fun connect() {
        val endpoint = parse(mutableState.value.endpointInput) ?: return
        connect(endpoint)
    }

    fun reconnect(profile: DeviceProfile) {
        val raw = if (':' in profile.host) "[${profile.host}]:${profile.debugPort}" else "${profile.host}:${profile.debugPort}"
        mutableState.update { it.copy(endpointInput = raw, inputError = null, notice = null) }
        connect(AdbEndpoint(profile.host, profile.debugPort))
    }

    private fun connect(endpoint: AdbEndpoint) = startOperation { generation ->
        mutableState.update { it.copy(showPairing = false, pairingCode = "", notice = null) }
        val result = manager.connect(endpoint)
        if (result is AdbOperationResult.Success && generation == operationGeneration) {
            repository.recordSuccessfulConnection(
                host = endpoint.host,
                port = endpoint.port,
                suggestedName = if (endpoint.host == "127.0.0.1") "本机设备" else endpoint.host,
                isLocal = endpoint.host == "127.0.0.1" || endpoint.host == "::1",
                identityReference = HOST_IDENTITY_REFERENCE,
                nowEpochMillis = clock.millis(),
            )
            mutableState.update { it.copy(notice = "连接成功") }
        }
    }

    fun cancelCurrentOperation() {
        operationGeneration++
        operation?.cancel()
        operation = null
        clearPairingCode()
    }

    fun openPairing() {
        val error = (mutableState.value.connectionState as? AdbConnectionState.Error)?.error ?: return
        if (!error.allowsPairingFallback) return
        val host = (AdbEndpointParser.parse(mutableState.value.endpointInput) as? EndpointParseResult.Valid)?.endpoint?.host
        val prefix = host?.let { if (':' in it) "[$it]:" else "$it:" }.orEmpty()
        mutableState.update {
            it.copy(showPairing = true, pairingEndpointInput = prefix, pairingCode = "", inputError = null, notice = null)
        }
    }

    fun closePairing() = mutableState.update {
        it.copy(showPairing = false, pairingCode = "", inputError = null)
    }

    fun updatePairingEndpoint(value: String) = mutableState.update {
        it.copy(pairingEndpointInput = value, inputError = null)
    }

    fun updatePairingCode(value: String) = mutableState.update {
        it.copy(pairingCode = value.filter(Char::isDigit).take(6), inputError = null)
    }

    fun pair() {
        val current = mutableState.value
        val endpoint = parse(current.pairingEndpointInput) ?: return
        if (current.pairingCode.length != 6) {
            mutableState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        val code = current.pairingCode.toCharArray()
        clearPairingCode()
        startOperation { _ ->
            try {
                when (manager.pair(endpoint, code)) {
                    is AdbOperationResult.Success -> mutableState.update {
                        it.copy(
                            showPairing = false,
                            pairingCode = "",
                            notice = "配对成功。请填写无线调试主页面的调试端口后连接。",
                        )
                    }
                    else -> clearPairingCode()
                }
            } finally {
                code.fill('\u0000')
                clearPairingCode()
            }
        }
    }

    fun disconnect() = startOperation { _ ->
        manager.disconnect()
        mutableState.update { it.copy(pairingCode = "", showPairing = false, notice = "已断开连接") }
    }

    fun toggleDiagnostics() = mutableState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    fun clearDiagnostics() = manager.clearDiagnosticEvents()

    fun requestRename(profile: DeviceProfile) = mutableState.update {
        it.copy(pendingRenameProfile = profile, renameInput = profile.displayName)
    }

    fun updateRename(value: String) = mutableState.update { it.copy(renameInput = value.take(80)) }

    fun confirmRename() {
        val profile = mutableState.value.pendingRenameProfile ?: return
        val name = mutableState.value.renameInput
        viewModelScope.launch {
            repository.rename(profile.id, name)
            mutableState.update { it.copy(pendingRenameProfile = null, renameInput = "") }
        }
    }

    fun dismissRename() = mutableState.update { it.copy(pendingRenameProfile = null, renameInput = "") }

    fun requestDelete(profile: DeviceProfile) = mutableState.update { it.copy(pendingDeleteProfile = profile) }
    fun dismissDelete() = mutableState.update { it.copy(pendingDeleteProfile = null) }

    fun confirmDelete() {
        val profile = mutableState.value.pendingDeleteProfile ?: return
        viewModelScope.launch {
            repository.delete(profile.id)
            val remaining = repository.profiles.first()
            if (remaining.none { it.identityReference == profile.identityReference }) manager.clearHostIdentity()
            mutableState.update { it.copy(pendingDeleteProfile = null, notice = "设备档案及关联身份引用已删除") }
        }
    }

    private fun startOperation(block: suspend (Long) -> Unit) {
        operation?.cancel()
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            try {
                block(generation)
            } finally {
                if (generation == operationGeneration) operation = null
            }
        }
    }

    private fun parse(raw: String): AdbEndpoint? = when (val result = AdbEndpointParser.parse(raw)) {
        is EndpointParseResult.Valid -> result.endpoint
        is EndpointParseResult.Invalid -> {
            manager.reportInvalidAddress(result.reason)
            mutableState.update { it.copy(inputError = result.reason) }
            null
        }
    }

    private fun clearPairingCode() = mutableState.update { it.copy(pairingCode = "") }

    override fun onCleared() {
        operationGeneration++
        operation?.cancel()
        clearPairingCode()
        super.onCleared()
    }

    companion object { const val HOST_IDENTITY_REFERENCE = "android-keystore-host-v1" }
}
