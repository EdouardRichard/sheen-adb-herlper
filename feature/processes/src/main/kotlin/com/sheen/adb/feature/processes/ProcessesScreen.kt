package com.sheen.adb.feature.processes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.adb.core.DeviceProcess
import com.sheen.adb.ui.SheenDimensions

@Composable
fun ProcessesRoute(viewModel: ProcessesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProcessesScreen(state, viewModel)
}

@Composable
fun ProcessesScreen(state: ProcessesUiState, actions: ProcessesViewModel) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("进程监控", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = actions::refresh, enabled = state.isConnected && !state.isLoading) { Text("刷新") }
                }
                Text("只读列表；本页面不提供结束进程、强制停止或应用管理。")
                if (!state.isConnected) Text("请先连接设备")
                OutlinedTextField(state.query, actions::updateQuery, Modifier.fillMaxWidth(), label = { Text("搜索进程名或 PID") }, singleLine = true)
                if (state.isLoading) {
                    CircularProgressIndicator()
                    OutlinedButton(onClick = actions::cancel) { Text("取消刷新") }
                }
                state.degradedReason?.let { Text(it) }
                state.error?.let { Text("${it.userMessage} ${it.nextStep}", color = MaterialTheme.colorScheme.error) }
                if (!state.isLoading && state.visibleProcesses.isEmpty()) Text("没有可显示的进程")
            }
        }
        items(state.visibleProcesses, key = { it.pid }) { process -> ProcessCard(process, context) }
    }
}

@Composable
private fun ProcessCard(process: DeviceProcess, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(process.name, style = MaterialTheme.typography.titleMedium)
            Text("PID ${process.pid} · UID ${process.uid ?: "设备未提供"} · 状态 ${process.state ?: "设备未提供"}")
            Text("常驻内存：${process.residentMemoryBytes?.let { "$it B" } ?: "设备未提供"}")
            Row {
                TextButton(onClick = { copy(context, "PID", process.pid.toString()) }) { Text("复制 PID") }
                TextButton(onClick = { copy(context, "进程名", process.name) }) { Text("复制进程名") }
            }
        }
    }
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
