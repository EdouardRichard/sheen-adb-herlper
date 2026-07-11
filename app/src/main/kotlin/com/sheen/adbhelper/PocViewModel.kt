package com.sheen.adbhelper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpointParser
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.EndpointParseResult
import com.sheen.adb.core.ShellResult
import com.sheen.adb.core.runSheenPoc
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PocUiState(
    val endpointInput: String = "",
    val pairingEndpointInput: String = "",
    val pairingCode: String = "",
    val showPairing: Boolean = false,
    val inputError: String? = null,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected(),
    val pocResult: ShellResult? = null,
)

class PocViewModel(private val manager: AdbSessionManager) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PocUiState())
    val uiState: StateFlow<PocUiState> = mutableUiState.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            manager.connectionState.collect { state ->
                mutableUiState.update { it.copy(connectionState = state) }
            }
        }
    }

    fun updateEndpoint(value: String) = mutableUiState.update {
        it.copy(endpointInput = value, inputError = null)
    }

    fun prefillLocalhost() = mutableUiState.update {
        it.copy(endpointInput = "127.0.0.1:", inputError = null)
    }

    fun connect() {
        val endpoint = parseOrShowError(mutableUiState.value.endpointInput) ?: return
        startOperation {
            mutableUiState.update { it.copy(pocResult = null, showPairing = false) }
            manager.connect(endpoint)
        }
    }

    fun cancelCurrentOperation() {
        operation?.cancel()
        operation = null
        clearPairingCode()
    }

    fun openPairing() {
        val state = mutableUiState.value.connectionState
        if (state !is AdbConnectionState.Error || !state.error.allowsPairingFallback) return
        val parsed = AdbEndpointParser.parse(mutableUiState.value.endpointInput)
        val hostPrefix = (parsed as? EndpointParseResult.Valid)?.endpoint?.host?.let { host ->
            if (':' in host) "[$host]:" else "$host:"
        }.orEmpty()
        mutableUiState.update {
            it.copy(showPairing = true, pairingEndpointInput = hostPrefix, pairingCode = "", inputError = null)
        }
    }

    fun closePairing() = mutableUiState.update {
        it.copy(showPairing = false, pairingCode = "", inputError = null)
    }

    fun updatePairingEndpoint(value: String) = mutableUiState.update {
        it.copy(pairingEndpointInput = value, inputError = null)
    }

    fun updatePairingCode(value: String) {
        val digits = value.filter(Char::isDigit).take(6)
        mutableUiState.update { it.copy(pairingCode = digits, inputError = null) }
    }

    fun pair() {
        val current = mutableUiState.value
        val endpoint = parseOrShowError(current.pairingEndpointInput) ?: return
        if (current.pairingCode.length != 6) {
            mutableUiState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        val code = current.pairingCode.toCharArray()
        mutableUiState.update { it.copy(pairingCode = "") }
        startOperation {
            val result = manager.pair(endpoint, code)
            if (result is AdbOperationResult.Success) {
                mutableUiState.update {
                    it.copy(showPairing = false, pairingCode = "", inputError = null)
                }
            } else {
                clearPairingCode()
            }
            result
        }
    }

    fun runPoc() = startOperation {
        val result = manager.runSheenPoc()
        if (result is AdbOperationResult.Success) {
            mutableUiState.update { it.copy(pocResult = result.value) }
        }
        result
    }

    fun disconnect() = startOperation {
        val result = manager.disconnect()
        mutableUiState.update { it.copy(pocResult = null, pairingCode = "", showPairing = false) }
        result
    }

    private fun startOperation(block: suspend () -> AdbOperationResult<*>) {
        operation?.cancel()
        operation = viewModelScope.launch {
            try {
                block()
            } finally {
                operation = null
            }
        }
    }

    private fun parseOrShowError(raw: String) = when (val parsed = AdbEndpointParser.parse(raw)) {
        is EndpointParseResult.Valid -> parsed.endpoint
        is EndpointParseResult.Invalid -> {
            mutableUiState.update { it.copy(inputError = parsed.reason) }
            null
        }
    }

    private fun clearPairingCode() = mutableUiState.update { it.copy(pairingCode = "") }

    override fun onCleared() {
        operation?.cancel()
        clearPairingCode()
        manager.close()
        super.onCleared()
    }
}
