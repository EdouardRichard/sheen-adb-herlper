package com.sheen.adb.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.adb.ui.SheenDimensions

@Composable
fun SettingsRoute(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(SheenDimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SheenDimensions.itemSpacing),
    ) {
        Text("设置与隐私", style = MaterialTheme.typography.headlineSmall)
        InfoCard("应用版本", state.versionLabel)
        InfoCard(
            "纯本地隐私承诺",
            "无账号、后端、广告、统计、遥测或崩溃上报。设备档案仅保存在本机；Shell、进程与 Logcat 内容默认只在内存中。",
        )
        InfoCard(
            "风险与支持范围",
            "主控端 Android 11（API 30）及以上；优先支持 Android 11+ 标准无线调试。旧式 :5555 为尽力兼容。ADB Shell 命令可能改变设备数据。",
        )
        InfoCard(
            "开源与第三方许可证",
            "本项目：Apache-2.0；Kadb 2.1.1：Apache-2.0；AndroidX：Apache-2.0；Coroutines 1.10.2：Apache-2.0；Okio 3.17.0：Apache-2.0；Bouncy Castle 1.83：MIT；spake2-java 1.0.5：Apache-2.0；HiddenApiBypass 6.1：Apache-2.0。",
        )
        InfoCard(
            "无线调试与配对帮助",
            "先在被控端手动开启开发者选项和无线调试。首页始终先尝试调试端口；只有认证失败时才使用系统“使用配对码配对设备”中的配对端口和 6 位配对码。配对成功后回到无线调试主页面填写调试端口。",
        )
        Button(onClick = { if (!openSettings(context, Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))) viewModel.showManualSettingsPath() }) {
            Text("打开无线调试设置")
        }
        OutlinedButton(onClick = {
            if (!openSettings(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) viewModel.showManualSettingsPath()
        }) { Text("打开开发者选项") }
        state.settingsHelp?.let { Text(it) }
        OutlinedButton(onClick = viewModel::requestClear, enabled = !state.isClearing) { Text("清除所有本地数据") }
        if (state.isClearing) Text("正在清除并验证……")
        state.clearResult?.let { Text(it) }
    }
    if (state.showClearConfirmation) AlertDialog(
        onDismissRequest = viewModel::dismissClear,
        title = { Text("清除所有本地数据？") },
        text = { Text("将删除设备档案、ADB 主机身份、偏好和临时文件。之后可能需要重新配对，且无法撤销。") },
        confirmButton = { TextButton(onClick = viewModel::clearAll) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = viewModel::dismissClear) { Text("取消") } },
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}

private fun openSettings(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
