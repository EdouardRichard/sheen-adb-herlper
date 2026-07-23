package com.sheen.adb.feature.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.sheen.adb.core.LocalPairingDiscoveryStatus
import com.sheen.adb.core.LocalPairingNotificationState
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.data.DeviceProfile
import com.sheen.adb.ui.SheenDimensions

@Composable
fun DevicesRoute(
    viewModel: DevicesViewModel,
    onOpenWirelessDebuggingSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var openingWirelessDebuggingSettings by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> openingWirelessDebuggingSettings = false
                Lifecycle.Event.ON_STOP -> if (!openingWirelessDebuggingSettings) viewModel.closePairing()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!openingWirelessDebuggingSettings) viewModel.closePairing()
        }
    }
    DevicesScreen(
        state = state,
        pairingState = pairingState,
        actions = viewModel,
        onOpenWirelessDebuggingSettings = {
            openingWirelessDebuggingSettings = true
            viewModel.onLocalWirelessSettingsOpened()
            onOpenWirelessDebuggingSettings()
        },
    )
}

@Composable
internal fun DevicesScreen(
    state: DevicesUiState,
    pairingState: DevicesPairingState,
    actions: DevicesViewModel,
    onOpenWirelessDebuggingSettings: () -> Unit = {},
) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions::enterLocalPairingMode) { Text("本机无线配对") }
            OutlinedButton(onClick = actions::prefillLocalhost) { Text("手动填写本机调试端口") }
        }
        PairingCard(state, pairingState, actions, onOpenWirelessDebuggingSettings)
        Text("最近设备", style = MaterialTheme.typography.titleLarge)
        if (state.profiles.isEmpty()) Text("暂无设备档案。应用不会自动扫描局域网。")
        state.profiles.forEach { profile -> ProfileCard(profile, actions) }
        OutlinedButton(onClick = actions::toggleDiagnostics) {
            Text(if (state.showDiagnostics) "收起脱敏诊断事件" else "查看脱敏诊断事件（${state.diagnosticEvents.size}）")
        }
        if (state.showDiagnostics) DiagnosticsCard(state.diagnosticEvents, actions::clearDiagnostics)
    }
    val pairingPresentation = pairingState.toPresentation()
    if (pairingPresentation.showSessionReplacementConfirmation) {
        AlertDialog(
            onDismissRequest = actions::dismissPairingSessionReplacement,
            title = { Text("断开当前设备？") },
            text = { Text(pairingPresentation.sessionReplacementText) },
            confirmButton = {
                TextButton(onClick = actions::confirmPairingSessionReplacement) { Text("断开并继续") }
            },
            dismissButton = {
                TextButton(onClick = actions::dismissPairingSessionReplacement) { Text("保留当前连接") }
            },
        )
    }
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
private fun PairingCard(
    state: DevicesUiState,
    pairingState: DevicesPairingState,
    actions: DevicesViewModel,
    onOpenWirelessDebuggingSettings: () -> Unit,
) {
    val presentation = pairingState.toPresentation()
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (pairingState.isLocalMode) "本机无线调试配对" else presentation.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (pairingState.isLocalMode) {
                    "先打开系统无线调试，再选择“使用配对码配对设备”。应用会自动查找本机配对端口。"
                } else {
                    presentation.guidance
                },
            )
            if (!pairingState.isLocalMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presentation.methodOptions.forEach { method ->
                        val label = when (method) {
                            PairingMethod.QR -> "二维码"
                            PairingMethod.SIX_DIGIT_CODE -> "6 位配对码"
                            PairingMethod.NONE -> return@forEach
                        }
                        if (pairingState.method == method) {
                            Button(
                                onClick = { actions.selectPairingMethod(method) },
                                enabled = !presentation.showCancel,
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { actions.selectPairingMethod(method) },
                                enabled = !presentation.showCancel,
                            ) { Text(label) }
                        }
                    }
                }
            }
            Text(
                if (pairingState.isLocalMode) pairingState.localStatusText() else presentation.statusText,
                color = MaterialTheme.colorScheme.primary,
            )
            if (pairingState.isLocalMode) {
                Text(pairingState.localNotificationText())
                if (pairingState.suggestNativeNotificationStyle) {
                    Text(
                        "当前系统通知样式可能不支持通知栏内输入。请改用系统原生通知样式，或直接在应用内输入配对码。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onOpenWirelessDebuggingSettings) { Text("打开系统无线调试设置") }
            }
            if (presentation.showQrMatrix) {
                pairingState.qrMatrix?.let {
                    QrMatrixImage(it, Modifier.align(Alignment.CenterHorizontally))
                }
            }
            if (presentation.showCodeInputs) {
                if (!pairingState.isLocalMode) {
                    OutlinedTextField(
                        state.pairingEndpointInput,
                        actions::updatePairingEndpoint,
                        label = { Text("IP:配对端口") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    state.pairingCode,
                    actions::updatePairingCode,
                    label = { Text("6 位配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (presentation.showStart) {
                    Button(onClick = actions::startSelectedPairing) { Text("开始配对") }
                }
                if (presentation.showCodeInputs) {
                    Button(onClick = actions::pair, enabled = presentation.submitCodeEnabled) { Text("提交配对码") }
                }
                if (presentation.showCancel) {
                    OutlinedButton(onClick = actions::onPairingPageLeft) { Text("取消") }
                }
                if (presentation.showRetry) {
                    Button(
                        onClick = if (pairingState.isLocalMode) {
                            actions::retryLocalPairingMode
                        } else {
                            actions::retryPairing
                        },
                    ) { Text("重新开始") }
                }
                if (presentation.showCodeFallback) {
                    Button(
                        onClick = {
                            actions.selectPairingMethod(PairingMethod.SIX_DIGIT_CODE)
                            actions.startSelectedPairing()
                        },
                    ) { Text("改用 6 位配对码") }
                }
            }
        }
    }
}

private fun DevicesPairingState.localStatusText(): String = when (localDiscoveryStatus) {
    LocalPairingDiscoveryStatus.IDLE -> "等待开始本机端口扫描"
    LocalPairingDiscoveryStatus.SEARCHING -> "正在扫描本机无线调试配对端口"
    LocalPairingDiscoveryStatus.FOUND -> "已发现本机配对端口，请输入系统显示的 6 位配对码"
    LocalPairingDiscoveryStatus.NOT_FOUND -> "暂未发现配对端口，请确认系统配对码对话框保持打开"
    LocalPairingDiscoveryStatus.AMBIGUOUS -> "发现多个本机配对端口，请在系统设置中保留当前配对窗口后重试"
    LocalPairingDiscoveryStatus.UNSUPPORTED -> "当前系统未提供可发现的本机无线调试配对服务"
    LocalPairingDiscoveryStatus.STOPPED -> "本机配对端口扫描已停止"
}

private fun DevicesPairingState.localNotificationText(): String = when (localNotificationState) {
    LocalPairingNotificationState.HIDDEN -> "应用内配对码输入始终可用；通知栏输入正在准备"
    LocalPairingNotificationState.PRIVATE_LOCKED -> "设备已锁定；通知不会显示或接收配对码，解锁后可继续"
    LocalPairingNotificationState.INPUT_READY -> "也可直接在通知栏输入 6 位配对码"
    LocalPairingNotificationState.INPUT_UNAVAILABLE -> "通知栏输入不可用，请在应用内输入配对码"
    LocalPairingNotificationState.RESULT -> "通知栏配对操作已结束"
}

@Composable
private fun QrMatrixImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .size(256.dp)
            .background(Color.White)
            .clearAndSetSemantics { },
    ) {
        val moduleSize = size.minDimension / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleSize, y * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
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
