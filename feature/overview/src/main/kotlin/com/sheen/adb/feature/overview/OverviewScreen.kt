package com.sheen.adb.feature.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sheen.adb.core.DeviceOverview
import com.sheen.adb.ui.SheenDimensions
import java.util.Locale

@Composable
fun OverviewRoute(viewModel: OverviewViewModel) {
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
    OverviewScreen(state, viewModel::refresh)
}

@Composable
fun OverviewScreen(state: OverviewUiState, onRefresh: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("设备概览", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onRefresh, enabled = state.isConnected && !state.isLoading) { Text("刷新") }
        }
        if (!state.isConnected) Text("请先连接设备")
        if (state.isLoading) CircularProgressIndicator()
        state.error?.let { Text("${it.userMessage} ${it.nextStep}", color = MaterialTheme.colorScheme.error) }
        state.overview?.let { OverviewCards(it) }
    }
}

@Composable
private fun OverviewCards(info: DeviceOverview) {
    Section("设备") {
        Field("品牌", info.brand); Field("制造商", info.manufacturer); Field("型号", info.model); Field("设备代号", info.deviceCode)
    }
    Section("系统") {
        Field("Android", info.androidVersion); Field("SDK", info.sdk); Field("构建版本", info.buildDisplay)
        Field("构建指纹", info.buildFingerprint); Field("安全补丁", info.securityPatch)
    }
    Section("硬件与资源") {
        Field("CPU ABI", info.cpuAbi); Field("可用核心", info.availableCores?.toString())
        Field("内存总量", formatBytes(info.memoryTotalBytes)); Field("内存可用", formatBytes(info.memoryAvailableBytes))
        Field("存储总量", formatBytes(info.storageTotalBytes)); Field("存储可用", formatBytes(info.storageAvailableBytes))
    }
    Section("运行状态") {
        Field("电量", info.batteryPercent?.let { "$it%" }); Field("充电状态", info.chargingState)
        Field("温度", info.temperatureCelsius?.let { String.format(Locale.ROOT, "%.1f °C", it) })
        Field("运行时间", info.uptimeSeconds?.let { "$it 秒" })
        Field("网络地址", info.networkAddresses.takeIf { it.isNotEmpty() }?.joinToString())
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value ?: "设备未提供")
    }
}

private fun formatBytes(bytes: Long?): String? = bytes?.let {
    when {
        it >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GiB", it / (1024.0 * 1024 * 1024))
        it >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", it / (1024.0 * 1024))
        else -> "$it B"
    }
}
