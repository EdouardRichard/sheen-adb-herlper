package com.sheen.adbhelper

import android.Manifest
import android.content.ActivityNotFoundException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.feature.apps.AppsRoute
import com.sheen.adb.feature.apps.AppsViewModel
import com.sheen.adb.feature.devices.DevicesRoute
import com.sheen.adb.feature.devices.DevicesViewModel
import com.sheen.adb.feature.files.FilesRoute
import com.sheen.adb.feature.files.FilesViewModel
import com.sheen.adb.feature.files.FileTaskSummaryBar
import com.sheen.adb.feature.files.taskSummary
import com.sheen.adb.feature.logcat.LogcatRoute
import com.sheen.adb.feature.logcat.LogcatViewModel
import com.sheen.adb.feature.overview.OverviewRoute
import com.sheen.adb.feature.overview.OverviewViewModel
import com.sheen.adb.feature.processes.ProcessesRoute
import com.sheen.adb.feature.processes.ProcessesViewModel
import com.sheen.adb.feature.shell.ShellRoute
import com.sheen.adb.feature.shell.ShellViewModel
import com.sheen.adb.feature.settings.SettingsRoute
import com.sheen.adb.feature.settings.SettingsViewModel
import com.sheen.adb.ui.SheenDimensions
import kotlinx.coroutines.launch

internal enum class Destination(val label: String, val requiresConnection: Boolean) {
    DEVICES("设备列表 / 首页", false),
    OVERVIEW("设备概览", true),
    FILES("文件浏览", true),
    APPS("应用管理", true),
    SHELL("Shell 终端", true),
    PROCESSES("进程监控", true),
    LOGCAT("Logcat", true),
    SETTINGS("设置与隐私", false),
}

@Composable
fun SheenApp(container: AppContainer, files: FilesViewModel) {
    val context = LocalContext.current
    val devices: DevicesViewModel = viewModel(factory = factory {
        DevicesViewModel(container.adbManager, container.deviceProfiles)
    })
    val overview: OverviewViewModel = viewModel(factory = factory { OverviewViewModel(container.adbManager) })
    val apps: AppsViewModel = viewModel(factory = factory { AppsViewModel(container.adbManager) })
    val shell: ShellViewModel = viewModel(factory = factory { ShellViewModel(container.adbManager) })
    val processes: ProcessesViewModel = viewModel(factory = factory { ProcessesViewModel(container.adbManager) })
    val logcat: LogcatViewModel = viewModel(factory = factory {
        LogcatViewModel(container.adbManager, container.textExporter)
    })
    val settings: SettingsViewModel = viewModel(factory = factory {
        SettingsViewModel(
            versionLabel = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            repository = container.deviceProfiles,
            manager = container.adbManager,
            temporaryDataCleaner = container.temporaryDataCleaner,
        )
    })
    val devicesState by devices.state.collectAsStateWithLifecycle()
    val localPairingControllerState by container.localPairingBridge.state.collectAsStateWithLifecycle()
    val filesState by files.state.collectAsStateWithLifecycle()
    val connected = devicesState.connectionState is AdbConnectionState.Connected
    var destination by rememberSaveable { mutableStateOf(Destination.DEVICES) }
    var handledNotificationPermissionGeneration by rememberSaveable { mutableStateOf(0L) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val available = granted && context.notificationsEnabled()
        container.localPairingBridge.onNotificationPermissionResult(
            granted = available,
            deviceUnlocked = context.isDeviceUnlocked(),
        )
        devices.onLocalNotificationPermissionResult(available)
    }

    LaunchedEffect(localPairingControllerState) {
        container.localPairingBridge.synchronizeService()
    }

    LaunchedEffect(devicesState.notificationPermissionRequestGeneration) {
        val requestGeneration = devicesState.notificationPermissionRequestGeneration
        if (requestGeneration == 0L || requestGeneration <= handledNotificationPermissionGeneration) {
            return@LaunchedEffect
        }
        handledNotificationPermissionGeneration = requestGeneration
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val available = context.notificationsEnabled()
            container.localPairingBridge.onNotificationPermissionResult(
                granted = available,
                deviceUnlocked = context.isDeviceUnlocked(),
            )
            devices.onLocalNotificationPermissionResult(available)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val openWirelessDebuggingSettings = {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    LaunchedEffect(connected) {
        destination = destinationAfterConnectionChange(destination, connected)
    }

    LaunchedEffect(devicesState.connectionState, devicesState.profiles) {
        val connection = devicesState.connectionState as? AdbConnectionState.Connected
        val displayName = connection?.let { connectedState ->
            devicesState.profiles.firstOrNull {
                it.host == connectedState.endpoint.host && it.debugPort == connectedState.endpoint.port
            }?.displayName
        }
        if (displayName != null) apps.setDeviceDisplayName(displayName)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 700.dp) {
            Row(Modifier.fillMaxSize()) {
                PermanentDrawerSheet(Modifier.width(SheenDimensions.expandedPaneWidth).fillMaxHeight()) {
                    DrawerContent(destination, connected, { destination = it }) {
                        devices.enterLocalPairingMode()
                        destination = Destination.DEVICES
                    }
                }
                AppScaffold(
                    destination,
                    devicesState.connectionState,
                    null,
                    fileTaskSummary = {
                        FileTaskSummaryBar(
                            summary = filesState.taskSummary,
                            showViewAction = destination != Destination.FILES,
                            onView = { destination = Destination.FILES },
                            onCancel = files::cancelActiveTask,
                        )
                    },
                ) {
                    DestinationContent(
                        destination,
                        devices,
                        overview,
                        files,
                        apps,
                        shell,
                        processes,
                        logcat,
                        settings,
                        openWirelessDebuggingSettings,
                    )
                }
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet {
                        DrawerContent(
                            destination,
                            connected,
                            onDestination = { destination = it; scope.launch { drawer.close() } },
                            onLocal = {
                                devices.enterLocalPairingMode()
                                destination = Destination.DEVICES
                                scope.launch { drawer.close() }
                            },
                        )
                    }
                },
            ) {
                AppScaffold(
                    destination,
                    devicesState.connectionState,
                    { scope.launch { drawer.open() } },
                    fileTaskSummary = {
                        FileTaskSummaryBar(
                            summary = filesState.taskSummary,
                            showViewAction = destination != Destination.FILES,
                            onView = { destination = Destination.FILES },
                            onCancel = files::cancelActiveTask,
                        )
                    },
                ) {
                    DestinationContent(
                        destination,
                        devices,
                        overview,
                        files,
                        apps,
                        shell,
                        processes,
                        logcat,
                        settings,
                        openWirelessDebuggingSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    selected: Destination,
    connected: Boolean,
    onDestination: (Destination) -> Unit,
    onLocal: () -> Unit,
) {
    Column(Modifier.padding(12.dp)) {
        Text("Sheen ADB 助手", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
        Destination.entries.forEach { destination ->
            val enabled = isMenuEnabled(destination.requiresConnection, connected)
            NavigationDrawerItem(
                label = {
                    Column {
                        Text(destination.label)
                        if (!enabled) Text("请先连接设备", style = MaterialTheme.typography.labelSmall)
                    }
                },
                selected = selected == destination,
                onClick = { if (enabled) onDestination(destination) },
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.38f)
                    .semantics {
                        contentDescription = destination.label
                        if (!enabled) disabled()
                    },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("本机无线配对") },
            selected = false,
            onClick = onLocal,
            modifier = Modifier.semantics { contentDescription = "本机无线调试配对" },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    destination: Destination,
    connection: AdbConnectionState,
    onMenu: (() -> Unit)?,
    fileTaskSummary: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(destination.label)
                        Text(connectionStatusName(connection), style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    if (onMenu != null) TextButton(
                        onClick = onMenu,
                        modifier = Modifier.semantics { contentDescription = "打开导航菜单" },
                    ) { Text("☰", style = MaterialTheme.typography.headlineSmall) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            fileTaskSummary()
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    devices: DevicesViewModel,
    overview: OverviewViewModel,
    files: FilesViewModel,
    apps: AppsViewModel,
    shell: ShellViewModel,
    processes: ProcessesViewModel,
    logcat: LogcatViewModel,
    settings: SettingsViewModel,
    onOpenWirelessDebuggingSettings: () -> Unit,
) {
    when (destination) {
        Destination.DEVICES -> DevicesRoute(devices, onOpenWirelessDebuggingSettings)
        Destination.OVERVIEW -> OverviewRoute(overview)
        Destination.FILES -> FilesRoute(files)
        Destination.APPS -> AppsRoute(apps)
        Destination.SHELL -> ShellRoute(shell)
        Destination.PROCESSES -> ProcessesRoute(processes)
        Destination.LOGCAT -> LogcatRoute(logcat)
        Destination.SETTINGS -> SettingsRoute(settings)
    }
}

internal fun isMenuEnabled(requiresConnection: Boolean, connected: Boolean): Boolean =
    connected || !requiresConnection

internal fun destinationAfterConnectionChange(current: Destination, connected: Boolean): Destination =
    if (!connected && current.requiresConnection) Destination.DEVICES else current

internal fun connectionStatusName(connection: AdbConnectionState): String = when (connection) {
    is AdbConnectionState.Disconnected -> "未连接"
    is AdbConnectionState.Connecting -> "连接中"
    is AdbConnectionState.AwaitingAuthorization -> "等待设备授权"
    is AdbConnectionState.Connected -> "已连接"
    is AdbConnectionState.Pairing -> "配对中"
    AdbConnectionState.Disconnecting -> "断开中"
    is AdbConnectionState.Error -> "错误"
}

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <R : ViewModel> create(modelClass: Class<R>): R = create() as R
    }

private fun Context.isDeviceUnlocked(): Boolean =
    !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

private fun Context.notificationsEnabled(): Boolean =
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
