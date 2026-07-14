package com.sheen.adbhelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbDiagnosticOutcome
import com.sheen.adb.core.DisconnectionReason

@Composable
fun PocDiagnosticScreen(viewModel: PocViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { viewModel.closePairing() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sheen ADB 助手", style = MaterialTheme.typography.headlineMedium)
        Text("Phase 0 · 无线 ADB 诊断 PoC", style = MaterialTheme.typography.titleMedium)
        Text(
            "应用不会开启无线调试或绕过系统授权。请仅连接你有权调试的设备。",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(uiState.connectionState)

        OutlinedTextField(
            value = uiState.endpointInput,
            onValueChange = viewModel::updateEndpoint,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("调试地址 IP:端口") },
            supportingText = {
                Text("Android 11+ 请填写“无线调试”主页面的动态调试端口；5555 仅用于已启用的旧式 ADB TCP/IP")
            },
            singleLine = true,
            isError = uiState.inputError != null,
        )
        uiState.inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::connect, enabled = uiState.connectionState.canStartConnection()) {
                Text("连接")
            }
            OutlinedButton(onClick = viewModel::prefillLocalhost) { Text("本机 127.0.0.1") }
        }
        Text(
            "Android 11+ 首次配对：先连接无线调试主页面的动态调试端口；出现 ADB_AUTH_FAILED 后再点“使用配对码”。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (uiState.connectionState.isBusy()) {
            OutlinedButton(onClick = viewModel::cancelCurrentOperation) { Text("取消当前操作") }
        }

        val error = uiState.connectionState as? AdbConnectionState.Error
        if (error?.error?.allowsPairingFallback == true && !uiState.showPairing) {
            Button(onClick = viewModel::openPairing) { Text("使用配对码") }
        }

        if (uiState.showPairing) {
            PairingCard(uiState, viewModel)
        }

        if (uiState.connectionState is AdbConnectionState.Connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::runPoc) { Text("运行 echo sheen-poc") }
                OutlinedButton(onClick = viewModel::disconnect) { Text("断开连接") }
            }
        }

        uiState.pocResult?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PoC 命令结果", style = MaterialTheme.typography.titleMedium)
                    Text("退出码：${result.exitCode}")
                    Text(result.stdout.ifBlank { "（无标准输出）" })
                    if (result.stderr.isNotBlank()) Text("标准错误：${result.stderr}")
                    Text("输出仅保存在当前页面内存中，断开后清除。")
                }
            }
        }

        if (error != null) {
            ErrorCard(error) { details -> copySanitizedDetails(context, details) }
        }

        OutlinedButton(onClick = viewModel::toggleDiagnostics) {
            Text(if (uiState.showDiagnostics) "收起诊断日志" else "查看诊断日志（${uiState.diagnosticEvents.size}）")
        }

        if (uiState.showDiagnostics) {
            DiagnosticLogCard(uiState.diagnosticEvents, viewModel::clearDiagnostics)
        }
    }
}

@Composable
private fun StatusCard(state: AdbConnectionState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isBusy()) CircularProgressIndicator(modifier = Modifier.height(24.dp))
            Column {
                Text("连接状态", style = MaterialTheme.typography.labelLarge)
                Text(state.displayName(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PairingCard(uiState: PocUiState, viewModel: PocViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("配对码回退", style = MaterialTheme.typography.titleMedium)
            Text("在被控设备打开“无线调试 → 使用配对码配对设备”，保持系统弹窗可见。")
            OutlinedTextField(
                value = uiState.pairingEndpointInput,
                onValueChange = viewModel::updatePairingEndpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对地址 IP:配对端口") },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.pairingCode,
                onValueChange = viewModel::updatePairingCode,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6 位配对码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::pair, enabled = uiState.pairingCode.length == 6) { Text("配对") }
                OutlinedButton(onClick = viewModel::closePairing) { Text("返回") }
            }
            Text("配对端口与调试端口不同。配对成功后，请返回上方输入无线调试主页面的调试端口。")
        }
    }
}

@Composable
private fun ErrorCard(error: AdbConnectionState.Error, onCopy: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接诊断", style = MaterialTheme.typography.titleMedium)
            Text("阶段：${error.error.stage}")
            Text(error.error.userMessage)
            Text("下一步：${error.error.nextStep}")
            Text("技术代码：${error.error.technicalCode}")
            OutlinedButton(onClick = { onCopy(error.technicalDetails) }) { Text("复制脱敏技术详情") }
        }
    }
}

@Composable
private fun DiagnosticLogCard(events: List<AdbDiagnosticEvent>, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PoC 脱敏诊断日志", style = MaterialTheme.typography.titleMedium)
            Text("仅保留当前进程最近 100 条事件；不包含真实地址、配对码、命令、Shell 输出或设备 Logcat。")
            if (events.isEmpty()) {
                Text("暂无诊断事件")
            } else {
                events.forEach { event ->
                    Text(event.displayText(), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onClear) { Text("清空诊断日志") }
            }
        }
    }
}

private fun AdbDiagnosticEvent.displayText(): String {
    val outcomeText = when (outcome) {
        AdbDiagnosticOutcome.STARTED -> "开始"
        AdbDiagnosticOutcome.SUCCEEDED -> "成功"
        AdbDiagnosticOutcome.CANCELLED -> "已取消"
        AdbDiagnosticOutcome.FAILED -> "失败"
        AdbDiagnosticOutcome.RESOURCE_CLOSED -> "资源已关闭"
    }
    val cause = causeType?.let { "; type=$it" }.orEmpty()
    return "#$sequence $stage · $outcomeText · $technicalCode · $redactedTarget$cause"
}

private fun AdbConnectionState.canStartConnection() =
    this is AdbConnectionState.Disconnected || this is AdbConnectionState.Error

private fun AdbConnectionState.isBusy() =
    this is AdbConnectionState.Connecting || this is AdbConnectionState.Pairing ||
        this is AdbConnectionState.Disconnecting || this is AdbConnectionState.AwaitingAuthorization

private fun AdbConnectionState.displayName(): String = when (this) {
    is AdbConnectionState.Disconnected -> when (reason) {
        DisconnectionReason.NONE -> "未连接"
        DisconnectionReason.CONNECT_CANCELLED -> "未连接 · 连接已取消"
        DisconnectionReason.PAIR_CANCELLED -> "未连接 · 配对已取消"
        DisconnectionReason.SHELL_CANCELLED -> "未连接 · 命令已取消"
        DisconnectionReason.DISCONNECT_CANCELLED -> "未连接 · 断开已取消"
    }
    is AdbConnectionState.Connecting -> "连接中"
    is AdbConnectionState.AwaitingAuthorization -> "等待设备授权"
    is AdbConnectionState.Connected -> "已连接 · ${endpoint.redacted()}"
    is AdbConnectionState.Pairing -> "配对中"
    AdbConnectionState.Disconnecting -> "断开中"
    is AdbConnectionState.Error -> "错误 · ${error.technicalCode}"
}

private fun copySanitizedDetails(context: Context, details: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Sheen ADB 脱敏诊断", details))
}
