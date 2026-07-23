package com.sheen.adbhelper.localpairing

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.sheen.adb.core.LocalPairingController
import com.sheen.adb.core.LocalPairingControllerState
import com.sheen.adb.core.LocalPairingNotificationCapability
import com.sheen.adb.core.LocalPairingNotificationDecision
import com.sheen.adb.core.LocalPairingNotificationState
import com.sheen.adb.core.LocalPairingWindowId
import com.sheen.adb.core.PairingSecret
import com.sheen.adbhelper.SheenApplication
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal interface LocalPairingPlatformFacade {
    fun isDeviceLocked(): Boolean
    fun areNotificationsEnabled(): Boolean
    fun isInlineInputSupported(): Boolean
}

internal data class LocalPairingPlatformPlan(
    val visibilityPrivate: Boolean,
    val localOnly: Boolean,
    val showRemoteInput: Boolean,
    val submitActionAvailable: Boolean,
    val pendingIntentExplicit: Boolean,
    val pendingIntentOneShot: Boolean,
    val pendingIntentMutable: Boolean,
    val authenticationRequired: Boolean,
    val registerUserPresentReceiver: Boolean,
    val stopService: Boolean,
    val applicationInputAvailable: Boolean,
    val suggestNativeNotificationStyle: Boolean,
    val startForegroundWithinMillis: Long,
)

internal class LocalPairingPlatformPolicy(
    private val facade: LocalPairingPlatformFacade,
) {
    fun notificationPlan(
        coreDecision: LocalPairingNotificationDecision,
        apiLevel: Int,
        windowActive: Boolean,
    ): LocalPairingPlatformPlan {
        val terminal = !windowActive || coreDecision.stopReason != null ||
            coreDecision.state == LocalPairingNotificationState.RESULT
        val locked = facade.isDeviceLocked()
        val notificationsEnabled = facade.areNotificationsEnabled()
        val inlineInputSupported = facade.isInlineInputSupported()
        val inputReady = !terminal && !locked && notificationsEnabled && inlineInputSupported &&
            coreDecision.state == LocalPairingNotificationState.INPUT_READY &&
            coreDecision.inputActionAvailable && coreDecision.submitAllowed
        return LocalPairingPlatformPlan(
            visibilityPrivate = true,
            localOnly = true,
            showRemoteInput = inputReady,
            submitActionAvailable = inputReady,
            pendingIntentExplicit = inputReady,
            pendingIntentOneShot = inputReady,
            pendingIntentMutable = inputReady,
            authenticationRequired = inputReady && apiLevel >= 31,
            registerUserPresentReceiver = !terminal && locked,
            stopService = terminal,
            applicationInputAvailable = !terminal && coreDecision.applicationInputAvailable,
            suggestNativeNotificationStyle = !terminal &&
                (coreDecision.suggestNativeNotificationStyle ||
                    (notificationsEnabled && !inlineInputSupported)),
            startForegroundWithinMillis = START_FOREGROUND_BUDGET_MILLIS,
        )
    }

    fun validateRemoteInput(
        windowActive: Boolean,
        tokenMatches: Boolean,
        beforeDeadline: Boolean,
        code: CharArray,
    ): PairingSecret? {
        val valid = windowActive && tokenMatches && beforeDeadline && !facade.isDeviceLocked() &&
            code.size == SIX_DIGIT_CODE_LENGTH && code.all { it in '0'..'9' }
        if (!valid) {
            code.fill('\u0000')
            return null
        }
        return PairingSecret(code)
    }

    private companion object {
        const val START_FOREGROUND_BUDGET_MILLIS = 5_000L
        const val SIX_DIGIT_CODE_LENGTH = 6
    }
}

class LocalPairingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: LocalPairingController by lazy(LazyThreadSafetyMode.NONE) {
        (application as SheenApplication).container.adbManager.localPairingController
    }
    private val notificationManager: NotificationManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(NotificationManager::class.java)
    }
    private val keyguardManager: KeyguardManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(KeyguardManager::class.java)
    }
    private val platformFacade = object : LocalPairingPlatformFacade {
        override fun isDeviceLocked(): Boolean = keyguardManager.isDeviceLocked

        override fun areNotificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()

        override fun isInlineInputSupported(): Boolean = !notificationCleared
    }
    private val platformPolicy by lazy(LazyThreadSafetyMode.NONE) { LocalPairingPlatformPolicy(platformFacade) }

    private var activePlatformToken: String? = null
    private var activeWindowId: LocalPairingWindowId? = null
    private var notificationCleared = false
    private var userPresentReceiverRegistered = false
    private var foregroundStarted = false

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) render(controller.state.value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            controller.state.collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundImmediately()
        when (intent?.action) {
            ACTION_SUBMIT_CODE -> handleRemoteInput(intent)
            ACTION_NOTIFICATION_CLEARED -> {
                notificationCleared = true
                render(controller.state.value)
            }
            else -> render(controller.state.value)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        stopForSystemTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForSystemTimeout(startId)
    }

    override fun onDestroy() {
        controller.state.value.window?.windowId?.let(controller::cancel)
        unregisterUserPresentReceiver()
        notificationManager.cancel(NOTIFICATION_ID)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundImmediately() {
        if (foregroundStarted) return
        val notification = baseNotification("正在准备本机无线配对", includeInputAction = false).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun render(state: LocalPairingControllerState) {
        val window = state.window
        if (window == null) {
            if (state.stopReason != null || foregroundStarted) stopAndRemoveNotification()
            return
        }
        ensurePlatformToken(window.windowId)
        val capability = when {
            !notificationManager.areNotificationsEnabled() -> {
                LocalPairingNotificationCapability.NOTIFICATIONS_DISABLED
            }
            notificationCleared -> LocalPairingNotificationCapability.INLINE_INPUT_UNAVAILABLE
            else -> LocalPairingNotificationCapability.AVAILABLE
        }
        val decision = controller.updateNotification(
            deviceUnlocked = !keyguardManager.isDeviceLocked,
            capability = capability,
        )
        val plan = platformPolicy.notificationPlan(decision, Build.VERSION.SDK_INT, windowActive = true)
        if (plan.stopService) {
            stopAndRemoveNotification()
            return
        }
        if (plan.registerUserPresentReceiver) registerUserPresentReceiver() else unregisterUserPresentReceiver()
        val text = when {
            plan.showRemoteInput -> "已发现本机配对端口，可在此输入 6 位配对码"
            plan.suggestNativeNotificationStyle -> "通知内输入不可用，请回到应用输入；可尝试系统原生通知样式"
            keyguardManager.isDeviceLocked -> "本机无线配对进行中，解锁后可继续输入"
            else -> "正在等待本机无线调试配对端口"
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            baseNotification(text, includeInputAction = plan.showRemoteInput).build(),
        )
    }

    private fun baseNotification(
        text: String,
        includeInputAction: Boolean,
    ): Notification.Builder {
        val publicVersion = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("本机无线配对")
            .setContentText("本机配对正在进行")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("本机无线配对")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setDeleteIntent(notificationClearedIntent())
            .also { builder -> if (includeInputAction) builder.addAction(remoteInputAction()) }
    }

    private fun remoteInputAction(): Notification.Action {
        val token = checkNotNull(activePlatformToken)
        val intent = Intent(this, LocalPairingForegroundService::class.java)
            .setAction(ACTION_SUBMIT_CODE)
            .putExtra(EXTRA_PLATFORM_TOKEN, token)
        val pendingIntent = PendingIntent.getService(
            this,
            token.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("6 位配对码")
            .build()
        val actionBuilder = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_send),
            "提交配对码",
            pendingIntent,
        ).addRemoteInput(remoteInput)
        if (Build.VERSION.SDK_INT >= 31) actionBuilder.setAuthenticationRequired(true)
        return actionBuilder.build()
    }

    private fun notificationClearedIntent(): PendingIntent {
        val intent = Intent(this, LocalPairingForegroundService::class.java)
            .setAction(ACTION_NOTIFICATION_CLEARED)
        return PendingIntent.getService(
            this,
            NOTIFICATION_CLEARED_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun handleRemoteInput(intent: Intent) {
        val window = controller.state.value.window
        val submittedToken = intent.getStringExtra(EXTRA_PLATFORM_TOKEN)
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.toString()
            ?.toCharArray()
            ?: CharArray(0)
        val secret = platformPolicy.validateRemoteInput(
            windowActive = window != null,
            tokenMatches = submittedToken != null && submittedToken == activePlatformToken,
            beforeDeadline = window != null && monotonicNowMillis() < window.deadlineMillis,
            code = input,
        ) ?: return
        val activeWindow = window ?: run {
            secret.clear()
            return
        }
        serviceScope.launch {
            controller.submit(activeWindow.windowId, secret)
        }
    }

    private fun ensurePlatformToken(windowId: LocalPairingWindowId) {
        if (activeWindowId == windowId && activePlatformToken != null) return
        activeWindowId = windowId
        activePlatformToken = UUID.randomUUID().toString()
        notificationCleared = false
    }

    private fun registerUserPresentReceiver() {
        if (userPresentReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(userPresentReceiver, filter)
        }
        userPresentReceiverRegistered = true
    }

    private fun unregisterUserPresentReceiver() {
        if (!userPresentReceiverRegistered) return
        runCatching { unregisterReceiver(userPresentReceiver) }
        userPresentReceiverRegistered = false
    }

    private fun stopForSystemTimeout(startId: Int) {
        controller.state.value.window?.windowId?.let(controller::onSystemTimeout)
        stopAndRemoveNotification(startId)
    }

    private fun stopAndRemoveNotification(startId: Int? = null) {
        unregisterUserPresentReceiver()
        activePlatformToken = null
        activeWindowId = null
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本机无线配对",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "仅在最长两分钟的本机无线调试配对期间显示"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
    }

    private fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val CHANNEL_ID = "local_pairing"
        const val NOTIFICATION_ID = 4102
        const val NOTIFICATION_CLEARED_REQUEST_CODE = 4103
        const val ACTION_SUBMIT_CODE = "com.sheen.adbhelper.localpairing.SUBMIT_CODE"
        const val ACTION_NOTIFICATION_CLEARED = "com.sheen.adbhelper.localpairing.NOTIFICATION_CLEARED"
        const val EXTRA_PLATFORM_TOKEN = "platform_token"
        const val REMOTE_INPUT_KEY = "pairing_code"
    }
}
