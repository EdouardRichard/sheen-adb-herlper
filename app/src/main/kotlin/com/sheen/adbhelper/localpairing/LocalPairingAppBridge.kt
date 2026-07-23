package com.sheen.adbhelper.localpairing

import android.content.Context
import android.content.Intent
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.LocalPairingController
import com.sheen.adb.core.LocalPairingControllerState
import com.sheen.adb.core.LocalPairingNotificationCapability
import com.sheen.adb.core.LocalPairingNotificationDecision
import com.sheen.adb.core.LocalPairingWindow
import com.sheen.adb.core.LocalPairingWindowId
import com.sheen.adb.core.PairingAttemptId
import kotlinx.coroutines.flow.StateFlow

internal interface LocalPairingServiceLifecycle {
    fun start()
    fun stop()
}

internal class AndroidLocalPairingServiceLifecycle(
    context: Context,
) : LocalPairingServiceLifecycle {
    private val applicationContext = context.applicationContext
    private val serviceIntent: Intent
        get() = Intent(applicationContext, LocalPairingForegroundService::class.java)

    override fun start() {
        applicationContext.startForegroundService(serviceIntent)
    }

    override fun stop() {
        applicationContext.stopService(serviceIntent)
    }
}

internal class LocalPairingAppBridge(
    private val controller: LocalPairingController,
    private val serviceLifecycle: LocalPairingServiceLifecycle,
) {
    val state: StateFlow<LocalPairingControllerState>
        get() = controller.state

    fun start(
        attemptId: PairingAttemptId,
        windowId: LocalPairingWindowId,
    ): AdbOperationResult<LocalPairingWindow> = controller.start(attemptId, windowId).also { result ->
        if (result is AdbOperationResult.Success) serviceLifecycle.start()
    }

    fun onNotificationPermissionResult(
        granted: Boolean,
        deviceUnlocked: Boolean,
    ): LocalPairingNotificationDecision = controller.updateNotification(
        deviceUnlocked = deviceUnlocked,
        capability = if (granted) {
            LocalPairingNotificationCapability.AVAILABLE
        } else {
            LocalPairingNotificationCapability.PERMISSION_DENIED
        },
    )

    fun onInlineInputUnavailable(deviceUnlocked: Boolean): LocalPairingNotificationDecision =
        controller.updateNotification(
            deviceUnlocked = deviceUnlocked,
            capability = LocalPairingNotificationCapability.INLINE_INPUT_UNAVAILABLE,
        )

    fun stop(windowId: LocalPairingWindowId): AdbOperationResult<Unit> =
        controller.cancel(windowId).also { serviceLifecycle.stop() }

    fun hasActiveWindow(): Boolean = state.value.window != null
}
