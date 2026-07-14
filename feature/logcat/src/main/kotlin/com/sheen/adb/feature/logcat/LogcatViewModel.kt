package com.sheen.adb.feature.logcat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.LogcatBuffer as AdbLogcatBuffer
import com.sheen.adb.core.LogcatConfig
import com.sheen.adb.core.LogcatLevel
import com.sheen.adb.data.TextExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogcatUiState(
    val isConnected: Boolean = false,
    val sessionId: String? = null,
    val isCapturing: Boolean = false,
    val isPaused: Boolean = false,
    val minimumLevel: LogcatLevel = LogcatLevel.INFO,
    val buffers: Set<com.sheen.adb.core.LogcatBuffer> = setOf(
        com.sheen.adb.core.LogcatBuffer.MAIN,
        com.sheen.adb.core.LogcatBuffer.SYSTEM,
        com.sheen.adb.core.LogcatBuffer.CRASH,
    ),
    val keyword: String = "",
    val visibleLines: List<String> = emptyList(),
    val droppedOldest: Boolean = false,
    val error: AdbError? = null,
    val exportNotice: String? = null,
)

class LogcatViewModel(
    private val manager: AdbSessionManager,
    private val exporter: TextExporter,
) : ViewModel() {
    private val buffer = LogcatBuffer()
    private val mutableState = MutableStateFlow(LogcatUiState())
    val state: StateFlow<LogcatUiState> = mutableState.asStateFlow()
    private var capture: Job? = null
    private var pendingPublish: Job? = null
    private var foreground = false

    init {
        viewModelScope.launch {
            manager.connectionState.collect { connection ->
                val connected = connection as? AdbConnectionState.Connected
                if (connected?.sessionId != mutableState.value.sessionId) {
                    stop()
                    buffer.clear()
                    mutableState.value = LogcatUiState(
                        isConnected = connected != null,
                        sessionId = connected?.sessionId,
                    )
                } else if (connected == null) {
                    stop()
                    mutableState.update { it.copy(isConnected = false) }
                } else mutableState.update { it.copy(isConnected = true) }
            }
        }
    }

    fun setForeground(value: Boolean) { foreground = value; if (!value) stop() }
    fun setLevel(level: LogcatLevel) { if (!mutableState.value.isCapturing) mutableState.update { it.copy(minimumLevel = level) } }
    fun toggleBuffer(value: com.sheen.adb.core.LogcatBuffer) {
        if (mutableState.value.isCapturing) return
        mutableState.update { current ->
            val updated = if (value in current.buffers) current.buffers - value else current.buffers + value
            current.copy(buffers = updated.takeIf { it.isNotEmpty() } ?: current.buffers)
        }
    }
    fun updateKeyword(value: String) { mutableState.update { it.copy(keyword = value) }; publish() }

    fun start() {
        val current = mutableState.value
        if (!foreground || !current.isConnected || current.isCapturing || current.buffers.isEmpty()) return
        mutableState.update { it.copy(isCapturing = true, isPaused = false, error = null, exportNotice = null) }
        capture = viewModelScope.launch {
            val config = LogcatConfig(current.minimumLevel, current.buffers)
            manager.streamLogcat(config).collect { result ->
                when (result) {
                    is AdbOperationResult.Success -> {
                        val prefix = if (result.value.fromStandardError) "[stderr] " else ""
                        buffer.add(prefix + result.value.text)
                        if (!mutableState.value.isPaused) requestPublish()
                    }
                    is AdbOperationResult.Failure -> mutableState.update { it.copy(error = result.error) }
                    AdbOperationResult.Cancelled -> Unit
                }
            }
            mutableState.update { it.copy(isCapturing = false, isPaused = false) }
            capture = null
        }
    }

    fun stop() {
        capture?.cancel()
        capture = null
        pendingPublish?.cancel()
        pendingPublish = null
        mutableState.update { it.copy(isCapturing = false, isPaused = false) }
        publish()
    }

    fun togglePause() {
        mutableState.update { it.copy(isPaused = !it.isPaused) }
        if (!mutableState.value.isPaused) publish()
    }

    fun clear() { buffer.clear(); publish() }

    fun export(target: Uri) {
        val text = mutableState.value.visibleLines.joinToString("\n")
        viewModelScope.launch {
            val success = exporter.writeUtf8(target, text)
            mutableState.update { it.copy(exportNotice = if (success) "导出完成" else "导出失败，请重新选择位置") }
        }
    }

    fun visibleText(): String = mutableState.value.visibleLines.joinToString("\n")

    private fun publish() = mutableState.update { current ->
        val needle = current.keyword.trim()
        current.copy(
            visibleLines = buffer.snapshot().filter { needle.isEmpty() || it.contains(needle, ignoreCase = true) },
            droppedOldest = buffer.droppedOldest,
        )
    }

    private fun requestPublish() {
        if (pendingPublish?.isActive == true) return
        pendingPublish = viewModelScope.launch {
            delay(100)
            publish()
            pendingPublish = null
        }
    }

    override fun onCleared() { stop(); buffer.clear(); super.onCleared() }
}
