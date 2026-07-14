package com.sheen.adb.feature.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sheen.adb.core.LogcatBuffer
import com.sheen.adb.core.LogcatLevel
import com.sheen.adb.ui.SheenDimensions

@Composable
fun LogcatRoute(viewModel: LogcatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        viewModel.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }
    LogcatScreen(state, viewModel)
}

@Composable
fun LogcatScreen(state: LogcatUiState, actions: LogcatViewModel) {
    val context = LocalContext.current
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(actions::export)
    }
    LazyColumn(
        Modifier.padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing)) {
                Text("Logcat", style = MaterialTheme.typography.headlineSmall)
                Text("仅在本页前台且你点击“开始”后采集；离开页面立即停止。格式固定为线程时间。")
                if (!state.isConnected) Text("请先连接设备")
                Text("最低等级")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogcatLevel.entries.forEach { level ->
                        FilterChip(state.minimumLevel == level, { actions.setLevel(level) }, { Text(level.displayName()) }, enabled = !state.isCapturing)
                    }
                }
                Text("缓冲区")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogcatBuffer.entries.forEach { buffer ->
                        FilterChip(buffer in state.buffers, { actions.toggleBuffer(buffer) }, { Text(buffer.argument) }, enabled = !state.isCapturing)
                    }
                }
                OutlinedTextField(state.keyword, actions::updateKeyword, Modifier.fillMaxWidth(), label = { Text("关键字过滤") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.isCapturing) Button(actions::start, enabled = state.isConnected) { Text("开始") }
                    if (state.isCapturing) {
                        Button(actions::stop) { Text("停止") }
                        OutlinedButton(actions::togglePause) { Text(if (state.isPaused) "继续" else "暂停") }
                    }
                    OutlinedButton(actions::clear) { Text("清屏") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ copy(context, "可见 Logcat", actions.visibleText()) }, enabled = state.visibleLines.isNotEmpty()) { Text("复制可见内容") }
                    OutlinedButton({ createDocument.launch("sheen-logcat.txt") }, enabled = state.visibleLines.isNotEmpty()) { Text("导出 UTF-8 文本") }
                }
                if (state.droppedOldest) Text("已达到 10,000 行或 4 MiB 上限，最早内容已丢弃。", color = MaterialTheme.colorScheme.error)
                state.exportNotice?.let { Text(it) }
                state.error?.let { Text("${it.userMessage} ${it.nextStep}", color = MaterialTheme.colorScheme.error) }
                if (state.visibleLines.isEmpty()) Text("暂无可见 Logcat")
            }
        }
        items(state.visibleLines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun LogcatLevel.displayName(): String = when (this) {
    LogcatLevel.VERBOSE -> "详细 V"
    LogcatLevel.DEBUG -> "调试 D"
    LogcatLevel.INFO -> "信息 I"
    LogcatLevel.WARN -> "警告 W"
    LogcatLevel.ERROR -> "错误 E"
    LogcatLevel.FATAL -> "致命 F"
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
