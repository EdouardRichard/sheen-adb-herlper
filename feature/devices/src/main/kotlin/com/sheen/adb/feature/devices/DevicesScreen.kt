package com.sheen.adb.feature.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbDiagnosticOutcome
import com.sheen.adb.data.DeviceProfile
import com.sheen.adb.ui.SheenDimensions

@Composable
fun DevicesRoute(viewModel: DevicesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.closePairing()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.closePairing()
        }
    }
    DevicesScreen(state, viewModel)
}

@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing)) {
            OutlinedTextField(
                value = state.endpointInput,
                onValueChange = actions::updateEndpoint,
                modifier = Modifier.weight(1f).semantics { contentDescription = "ADB 调试地址" },
                label = { Text("IP:端口") },
                singleLine = true,
                isError = state.inputError != null,
                supportingText = { Text("支持 IPv4、主机名与 [IPv6]:端口") },
            )
            Button(
                onClick = actions::connect,
                enabled = state.connectionState is AdbConnectionState.Disconnected || state.connectionState is AdbConnectionState.Error,
                modifier = Modifier.semantics { contentDescription = "连接设备" },
            ) { Text("连接") }
        }
        state.inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.connectionState.isBusy()) OutlinedButton(onClick = actions::cancelCurrentOperation) { Text("取消当前操作") }
        if ((state.connectionState as? AdbConnectionState.Error)?.error?.allowsPairingFallback == true) {
            Button(onClick = actions::openPairing) { Text("使用配对码") }
        }
        (state.connectionState as? AdbConnectionState.Error)?.let { error ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${error.error.stage}：${error.error.userMessage}", style = MaterialTheme.typography.titleMedium)
                    Text("下一步：${error.error.nextStep}")
                    Text("技术代码：${error.error.technicalCode}")
                    TextButton(onClick = { copy(context, "Sheen ADB 脱敏技术详情", error.technicalDetails) }) {
                        Text("复制脱敏详情")
                    }
                }
            }
        }
        if (state.connectionState is AdbConnectionState.Connected) {
            OutlinedButton(onClick = actions::disconnect) { Text("断开连接") }
        }
        Text("最近设备", style = MaterialTheme.typography.titleLarge)
        if (state.profiles.isEmpty()) Text("暂无设备档案。应用不会自动扫描局域网。")
        state.profiles.forEach { profile -> ProfileCard(profile, actions) }
        OutlinedButton(onClick = actions::toggleDiagnostics) {
            Text(if (state.showDiagnostics) "收起脱敏诊断事件" else "查看脱敏诊断事件（${state.diagnosticEvents.size}）")
        }
        if (state.showDiagnostics) DiagnosticsCard(state.diagnosticEvents, actions::clearDiagnostics)
    }
    if (state.showPairing) PairingDialog(state, actions)
    state.pendingRenameProfile?.let {
        AlertDialog(
            onDismissRequest = actions::dismissRename,
            title = { Text("编辑显示名") },
            text = { OutlinedTextField(state.renameInput, actions::updateRename, label = { Text("显示名") }) },
            confirmButton = { TextButton(onClick = actions::confirmRename) { Text("保存") } },
            dismissButton = { TextButton(onClick = actions::dismissRename) { Text("取消") } },
        )
    }
    state.pendingDeleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = actions::dismissDelete,
            title = { Text("删除设备档案？") },
            text = { Text("将删除“${profile.displayName}”及不再被其他档案引用的主机身份，之后可能需要重新配对。") },
            confirmButton = { TextButton(onClick = actions::confirmDelete) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = actions::dismissDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun ProfileCard(profile: DeviceProfile, actions: DevicesViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
            Text(if (profile.isLocal) "本机连接 · 端口 ${profile.debugPort}" else "已保存地址 · 端口 ${profile.debugPort}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.reconnect(profile) }) { Text("重新连接") }
                TextButton(onClick = { actions.requestRename(profile) }) { Text("编辑") }
                TextButton(onClick = { actions.requestDelete(profile) }) { Text("删除") }
            }
        }
    }
}

@Composable
private fun PairingDialog(state: DevicesUiState, actions: DevicesViewModel) {
    AlertDialog(
        onDismissRequest = actions::closePairing,
        title = { Text("使用 6 位配对码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在被控端打开“无线调试 → 使用配对码配对设备”。配对端口与调试端口不同。")
                OutlinedTextField(
                    state.pairingEndpointInput,
                    actions::updatePairingEndpoint,
                    label = { Text("IP:配对端口") },
                    singleLine = true,
                )
                OutlinedTextField(
                    state.pairingCode,
                    actions::updatePairingCode,
                    label = { Text("6 位配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = actions::pair, enabled = state.pairingCode.length == 6) { Text("配对") } },
        dismissButton = { TextButton(onClick = actions::closePairing) { Text("取消") } },
    )
}

@Composable
private fun DiagnosticsCard(events: List<AdbDiagnosticEvent>, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("当前进程最近 100 条；不含真实地址、命令、输出、密钥或配对码。")
            if (events.isEmpty()) Text("暂无诊断事件")
            events.forEach { event ->
                val outcome = when (event.outcome) {
                    AdbDiagnosticOutcome.STARTED -> "开始"
                    AdbDiagnosticOutcome.SUCCEEDED -> "成功"
                    AdbDiagnosticOutcome.CANCELLED -> "取消"
                    AdbDiagnosticOutcome.FAILED -> "失败"
                    AdbDiagnosticOutcome.RESOURCE_CLOSED -> "资源关闭"
                }
                Text("#${event.sequence} ${event.stage} · $outcome · ${event.technicalCode}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClear) { Text("清空") }
        }
    }
}

private fun AdbConnectionState.isBusy(): Boolean = this is AdbConnectionState.Connecting ||
    this is AdbConnectionState.Pairing || this is AdbConnectionState.Disconnecting ||
    this is AdbConnectionState.AwaitingAuthorization

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
