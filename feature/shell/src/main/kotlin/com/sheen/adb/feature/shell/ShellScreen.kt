package com.sheen.adb.feature.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.adb.core.ShellOutputMode
import com.sheen.adb.ui.SheenDimensions

@Composable
fun ShellRoute(viewModel: ShellViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShellScreen(state, viewModel)
}

@Composable
fun ShellScreen(state: ShellUiState, actions: ShellViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        Text("Shell 终端", style = MaterialTheme.typography.headlineSmall)
        if (!state.isConnected) Text("请先连接设备")
        OutlinedTextField(
            value = state.commandInput,
            onValueChange = actions::updateCommand,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text("原始 ADB Shell 命令（支持多行）") },
            enabled = state.isConnected && !state.isRunning,
        )
        Text("当前超时：${state.timeoutSeconds} 秒（最多 300 秒）")
        Slider(
            value = state.timeoutSeconds.toFloat(),
            onValueChange = { actions.setTimeout((it / 30).toInt().coerceAtLeast(1) * 30) },
            valueRange = 30f..300f,
            steps = 8,
            enabled = !state.isRunning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions::execute, enabled = state.isConnected && !state.isRunning && state.commandInput.isNotBlank()) {
                Text("执行")
            }
            if (state.isRunning) OutlinedButton(onClick = actions::cancel) { Text("取消运行") }
            OutlinedButton(onClick = actions::clear, enabled = !state.isRunning) { Text("清屏") }
        }
        if (state.outputWasDropped) Text("输出超过 1 MiB，已丢弃最早内容。", color = MaterialTheme.colorScheme.error)
        state.error?.let { Text("${it.userMessage} ${it.nextStep}", color = MaterialTheme.colorScheme.error) }
        state.entries.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("#${entry.sequence} · ${entry.status.displayName()}", style = MaterialTheme.typography.labelLarge)
                    Text(entry.command, style = MaterialTheme.typography.bodyMedium)
                    if (entry.outputMode == ShellOutputMode.MERGED) Text("底层协议仅提供合并输出")
                    if (entry.stdout.isNotEmpty()) Text("标准输出\n${entry.stdout}")
                    if (entry.stderr.isNotEmpty()) Text("标准错误\n${entry.stderr}")
                    entry.exitCode?.let { Text("退出码：$it") }
                    TextButton(onClick = { copy(context, "Shell 会话内容", render(entry)) }) { Text("复制此条") }
                }
            }
        }
    }
    if (state.showRiskNotice) AlertDialog(
        onDismissRequest = actions::dismissRiskNotice,
        title = { Text("Shell 风险说明") },
        text = { Text("命令会以 ADB shell 权限原样发送，可能修改或删除设备数据。应用不设白名单，也不会替你撤销结果。") },
        confirmButton = { TextButton(onClick = actions::dismissRiskNotice) { Text("我已了解") } },
    )
    state.pendingRiskCommand?.let {
        AlertDialog(
            onDismissRequest = actions::dismissRisk,
            title = { Text("命令可能具有破坏性") },
            text = { Text("请确认你理解命令后果。应用将原样执行，不会修改命令。") },
            confirmButton = { TextButton(onClick = actions::confirmRisk) { Text("仍要执行") } },
            dismissButton = { TextButton(onClick = actions::dismissRisk) { Text("取消") } },
        )
    }
}

private fun ShellEntryStatus.displayName(): String = when (this) {
    ShellEntryStatus.RUNNING -> "运行中"
    ShellEntryStatus.SUCCEEDED -> "已完成"
    ShellEntryStatus.FAILED -> "失败"
    ShellEntryStatus.CANCELLED -> "已取消"
    ShellEntryStatus.TIMED_OUT -> "已超时"
    ShellEntryStatus.DISCONNECTED -> "连接已断开"
}

private fun render(entry: ShellEntry): String = buildString {
    append("# ").append(entry.command).append('\n')
    if (entry.outputMode == ShellOutputMode.MERGED) append("[合并输出]\n")
    append(entry.stdout)
    if (entry.stderr.isNotEmpty()) append("\n[stderr]\n").append(entry.stderr)
    entry.exitCode?.let { append("\n[exit=").append(it).append(']') }
}

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
