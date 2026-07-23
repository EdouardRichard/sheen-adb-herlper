package com.sheen.adbhelper.localpairing

import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.LocalPairingController
import com.sheen.adb.core.LocalPairingControllerState
import com.sheen.adb.core.LocalPairingDiscoveryStatus
import com.sheen.adb.core.LocalPairingNotificationCapability
import com.sheen.adb.core.LocalPairingNotificationDecision
import com.sheen.adb.core.LocalPairingNotificationState
import com.sheen.adb.core.LocalPairingWindow
import com.sheen.adb.core.LocalPairingWindowId
import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingSecret
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

internal class LocalPairingAppBridgeTest {
    @Test
    fun `one accepted window starts one service against the same core controller`() {
        val controller = FakeController()
        val service = FakeServiceLifecycle()
        val bridge = LocalPairingAppBridge(controller, service)

        val started = bridge.start(ATTEMPT_ID, WINDOW_ID)
        val duplicate = bridge.start(PairingAttemptId.of("attempt-other"), LocalPairingWindowId.of("window-other"))

        assertTrue(started is AdbOperationResult.Success<*>)
        assertTrue(duplicate is AdbOperationResult.Failure)
        assertEquals(service.startCalls, 1)
        assertEquals(controller.startCalls, 2)
        assertTrue(bridge.state === controller.state)
    }

    @Test
    fun `notification permission denial keeps application input and does not cancel the window`() {
        val controller = FakeController()
        val service = FakeServiceLifecycle()
        val bridge = LocalPairingAppBridge(controller, service)
        bridge.start(ATTEMPT_ID, WINDOW_ID)

        val decision = bridge.onNotificationPermissionResult(
            granted = false,
            deviceUnlocked = true,
        )

        assertEquals(controller.lastCapability, LocalPairingNotificationCapability.PERMISSION_DENIED)
        assertTrue(decision.applicationInputAvailable)
        assertEquals(controller.cancelCalls, 0)
        assertEquals(service.stopCalls, 0)
    }

    @Test
    fun `notification cleared or OEM inline failure maps to unavailable with native style advice`() {
        val controller = FakeController()
        val bridge = LocalPairingAppBridge(controller, FakeServiceLifecycle())
        bridge.start(ATTEMPT_ID, WINDOW_ID)

        val decision = bridge.onInlineInputUnavailable(deviceUnlocked = true)

        assertEquals(controller.lastCapability, LocalPairingNotificationCapability.INLINE_INPUT_UNAVAILABLE)
        assertTrue(decision.applicationInputAvailable)
        assertTrue(decision.suggestNativeNotificationStyle)
        assertEquals(controller.cancelCalls, 0)
    }

    @Test
    fun `explicit stop cancels the exact window and stops the service`() {
        val controller = FakeController()
        val service = FakeServiceLifecycle()
        val bridge = LocalPairingAppBridge(controller, service)
        bridge.start(ATTEMPT_ID, WINDOW_ID)

        val stopped = bridge.stop(WINDOW_ID)

        assertTrue(stopped is AdbOperationResult.Success<*>)
        assertEquals(controller.cancelledWindowIds, listOf(WINDOW_ID))
        assertEquals(service.stopCalls, 1)
    }

    @Test
    fun `new process bridge does not restore or restart an old window`() {
        val firstController = FakeController()
        val firstService = FakeServiceLifecycle()
        LocalPairingAppBridge(firstController, firstService).start(ATTEMPT_ID, WINDOW_ID)

        val rebuiltController = FakeController()
        val rebuiltService = FakeServiceLifecycle()
        val rebuilt = LocalPairingAppBridge(rebuiltController, rebuiltService)

        assertEquals(rebuilt.state.value.discoveryStatus, LocalPairingDiscoveryStatus.IDLE)
        assertEquals(rebuiltController.startCalls, 0)
        assertEquals(rebuiltService.startCalls, 0)
        assertFalse(rebuilt.hasActiveWindow())
    }

    @Test
    fun `existing controller window synchronizes one service without creating another window`() {
        val controller = FakeController()
        val service = FakeServiceLifecycle()
        val bridge = LocalPairingAppBridge(controller, service)
        controller.publishExistingWindow(ATTEMPT_ID, WINDOW_ID)

        bridge.synchronizeService()
        bridge.synchronizeService()

        assertEquals(controller.startCalls, 0)
        assertEquals(service.startCalls, 1)
        assertEquals(service.stopCalls, 0)

        controller.publishStopped()
        bridge.synchronizeService()
        bridge.synchronizeService()

        assertEquals(service.startCalls, 1)
        assertEquals(service.stopCalls, 1)
        assertFalse(bridge.hasActiveWindow())
    }

    @Test
    fun `bridge and application assembly do not copy business deadline token or persistence state`() {
        val bridgeSource = String(
            Files.readAllBytes(
                Path.of("src/main/kotlin/com/sheen/adbhelper/localpairing/LocalPairingAppBridge.kt"),
            ),
        )
        val applicationSource = String(
            Files.readAllBytes(Path.of("src/main/kotlin/com/sheen/adbhelper/SheenApplication.kt")),
        )

        listOf("deadlineMillis", "EXTRA_PLATFORM_TOKEN", "SharedPreferences", "DataStore", "SavedState").forEach {
            assertFalse(bridgeSource.contains(it), "App bridge must not copy or persist $it")
        }
        assertTrue(bridgeSource.contains("applicationContext"))
        assertTrue(applicationSource.contains("localPairingBridge"))
        assertEquals(Regex("LocalPairingAppBridge\\(").findAll(applicationSource).count(), 1)
    }

    private class FakeServiceLifecycle : LocalPairingServiceLifecycle {
        var startCalls = 0
        var stopCalls = 0

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    private class FakeController : LocalPairingController {
        private val mutableState = MutableStateFlow(LocalPairingControllerState())
        override val state: StateFlow<LocalPairingControllerState> = mutableState
        var startCalls = 0
        var cancelCalls = 0
        var lastCapability: LocalPairingNotificationCapability? = null
        val cancelledWindowIds = mutableListOf<LocalPairingWindowId>()

        override fun start(
            attemptId: PairingAttemptId,
            windowId: LocalPairingWindowId,
        ): AdbOperationResult<LocalPairingWindow> {
            startCalls++
            if (mutableState.value.window != null) {
                return AdbOperationResult.Failure(AdbError.PairingSessionConflict)
            }
            val window = LocalPairingWindow(
                windowId = windowId,
                attemptId = attemptId,
                startedAtMillis = 0L,
                deadlineMillis = 120_000L,
            )
            mutableState.value = LocalPairingControllerState(
                window = window,
                discoveryStatus = LocalPairingDiscoveryStatus.SEARCHING,
            )
            return AdbOperationResult.Success(window)
        }

        override fun updateNotification(
            deviceUnlocked: Boolean,
            capability: LocalPairingNotificationCapability,
        ): LocalPairingNotificationDecision {
            lastCapability = capability
            val unavailable = capability != LocalPairingNotificationCapability.AVAILABLE
            return LocalPairingNotificationDecision(
                state = if (unavailable) {
                    LocalPairingNotificationState.INPUT_UNAVAILABLE
                } else {
                    LocalPairingNotificationState.INPUT_READY
                },
                inputActionAvailable = !unavailable,
                submitAllowed = !unavailable,
                actionWindowId = mutableState.value.window?.windowId.takeIf { !unavailable },
                applicationInputAvailable = true,
                suggestNativeNotificationStyle =
                    capability == LocalPairingNotificationCapability.INLINE_INPUT_UNAVAILABLE,
            )
        }

        override suspend fun submit(
            windowId: LocalPairingWindowId,
            secret: PairingSecret,
        ): AdbOperationResult<Unit> {
            secret.clear()
            return AdbOperationResult.Success(Unit)
        }

        override fun cancel(windowId: LocalPairingWindowId): AdbOperationResult<Unit> {
            cancelCalls++
            cancelledWindowIds += windowId
            mutableState.value = LocalPairingControllerState(
                discoveryStatus = LocalPairingDiscoveryStatus.STOPPED,
            )
            return AdbOperationResult.Success(Unit)
        }

        override fun onSystemTimeout(windowId: LocalPairingWindowId): AdbOperationResult<Unit> = cancel(windowId)

        fun publishExistingWindow(
            attemptId: PairingAttemptId,
            windowId: LocalPairingWindowId,
        ) {
            mutableState.value = LocalPairingControllerState(
                window = LocalPairingWindow(
                    windowId = windowId,
                    attemptId = attemptId,
                    startedAtMillis = 0L,
                    deadlineMillis = 120_000L,
                ),
                discoveryStatus = LocalPairingDiscoveryStatus.SEARCHING,
            )
        }

        fun publishStopped() {
            mutableState.value = LocalPairingControllerState(
                discoveryStatus = LocalPairingDiscoveryStatus.STOPPED,
            )
        }
    }

    private companion object {
        val ATTEMPT_ID = PairingAttemptId.of("attempt-synthetic")
        val WINDOW_ID = LocalPairingWindowId.of("window-synthetic")
    }
}
