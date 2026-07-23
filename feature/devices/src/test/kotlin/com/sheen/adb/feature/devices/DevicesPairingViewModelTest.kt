package com.sheen.adb.feature.devices

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.QrPairingMaterial
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import com.sheen.adb.data.DeviceProfileRepository
import java.lang.reflect.Proxy
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesPairingViewModelTest {
    @Test
    fun `QR manager flow reaches success without creating a connection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager()
            val material = FakeMaterial(PairingAttemptId.of("attempt-success"))
            val discovery = manager.enqueueQrAttempt(material)
            val viewModel = viewModel(manager, listOf(material.attemptId))

            viewModel.selectPairingMethod(PairingMethod.QR)
            viewModel.startSelectedPairing()
            runCurrent()

            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.WAITING_FOR_TARGET)
            assertTrue(viewModel.pairingState.value.qrMatrix != null)

            discovery.emit(AdbOperationResult.Success(discoveryState(1, resolvedObservation("observation-success"))))
            advanceUntilIdle()

            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.SUCCEEDED)
            assertNull(viewModel.pairingState.value.qrMatrix)
            assertNull(material.payload)
            assertEquals(manager.qrPairCalls, listOf(material.attemptId))
            assertEquals(manager.connectCalls, 0)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retry rejects a late result from the previous generation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager()
            val oldMaterial = FakeMaterial(PairingAttemptId.of("attempt-old"))
            val newMaterial = FakeMaterial(PairingAttemptId.of("attempt-new"))
            val oldDiscovery = manager.enqueueQrAttempt(oldMaterial)
            manager.enqueueQrAttempt(newMaterial)
            val viewModel = viewModel(manager, listOf(oldMaterial.attemptId, newMaterial.attemptId))

            viewModel.selectPairingMethod(PairingMethod.QR)
            viewModel.startSelectedPairing()
            runCurrent()
            viewModel.retryPairing()
            advanceUntilIdle()

            oldDiscovery.emit(AdbOperationResult.Success(discoveryState(1, resolvedObservation("observation-late"))))
            advanceUntilIdle()

            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.WAITING_FOR_TARGET)
            assertTrue(viewModel.pairingState.value.qrMatrix != null)
            assertTrue(oldMaterial.attemptId in manager.cancelledAttempts)
            assertNull(oldMaterial.payload)
            assertTrue(newMaterial.payload != null)
            assertTrue(manager.qrPairCalls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `leaving pairing cancels the active attempt and clears sensitive display state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager()
            val material = FakeMaterial(PairingAttemptId.of("attempt-leave"))
            manager.enqueueQrAttempt(material)
            val viewModel = viewModel(manager, listOf(material.attemptId))

            viewModel.selectPairingMethod(PairingMethod.QR)
            viewModel.startSelectedPairing()
            runCurrent()
            viewModel.onPairingPageLeft()
            advanceUntilIdle()

            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.CANCELLED)
            assertNull(viewModel.pairingState.value.qrMatrix)
            assertEquals(viewModel.pairingState.value.codeInput, "")
            assertTrue(material.attemptId in manager.cancelledAttempts)
            assertNull(material.payload)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retry cleans the old material before creating a fresh attempt`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager()
            val first = FakeMaterial(PairingAttemptId.of("attempt-first"))
            val second = FakeMaterial(PairingAttemptId.of("attempt-second"))
            manager.enqueueQrAttempt(first)
            manager.enqueueQrAttempt(second)
            val viewModel = viewModel(manager, listOf(first.attemptId, second.attemptId))

            viewModel.selectPairingMethod(PairingMethod.QR)
            viewModel.startSelectedPairing()
            runCurrent()
            viewModel.retryPairing()
            advanceUntilIdle()

            assertEquals(manager.createdAttempts, listOf(first.attemptId, second.attemptId))
            assertTrue(first.attemptId in manager.cancelledAttempts)
            assertNull(first.payload)
            assertTrue(second.payload != null)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `active session is preserved until explicit replacement confirmation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager(
                initialConnectionState = AdbConnectionState.Connected(
                    endpoint = AdbEndpoint("synthetic.invalid", 4711),
                    sessionId = "session-synthetic",
                ),
            )
            val material = FakeMaterial(PairingAttemptId.of("attempt-after-confirmation"))
            manager.enqueueQrAttempt(material)
            val viewModel = viewModel(manager, listOf(material.attemptId))
            runCurrent()

            viewModel.selectPairingMethod(PairingMethod.QR)
            viewModel.startSelectedPairing()
            runCurrent()

            assertTrue(viewModel.pairingState.value.awaitingSessionReplacementConfirmation)
            assertEquals(manager.disconnectCalls, 0)
            assertTrue(manager.createdAttempts.isEmpty())

            viewModel.confirmPairingSessionReplacement()
            advanceUntilIdle()

            assertEquals(manager.disconnectCalls, 1)
            assertEquals(manager.createdAttempts, listOf(material.attemptId))
            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.WAITING_FOR_TARGET)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `existing six digit entry still pairs and clears reducer input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = FakeManager()
            val viewModel = viewModel(manager, emptyList())

            viewModel.selectPairingMethod(PairingMethod.SIX_DIGIT_CODE)
            viewModel.startSelectedPairing()
            viewModel.updatePairingEndpoint("synthetic.invalid:4711")
            viewModel.updatePairingCode("0".repeat(6))
            viewModel.pair()
            advanceUntilIdle()

            assertEquals(viewModel.pairingState.value.phase, PairingAttemptPhase.SUCCEEDED)
            assertEquals(viewModel.pairingState.value.codeInput, "")
            assertEquals(viewModel.state.value.pairingCode, "")
            assertEquals(manager.codePairCalls, 1)
            assertFalse(viewModel.pairingState.value.hasActiveSession)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        manager: FakeManager,
        attemptIds: List<PairingAttemptId>,
    ): DevicesViewModel {
        val ids = ArrayDeque(attemptIds)
        return DevicesViewModel(
            manager = manager.instance,
            repository = fakeRepository(),
            clock = Clock.systemUTC(),
            pairingReducer = DevicesPairingReducer(),
            qrEncoder = QrMatrixEncoder(),
            pairingAttemptIdFactory = { ids.removeFirst() },
        )
    }

    private fun discoveryState(
        generation: Long,
        observation: WirelessServiceObservation,
    ): WirelessDiscoveryState = WirelessDiscoveryState(generation = generation, services = listOf(observation))

    private fun resolvedObservation(id: String): WirelessServiceObservation = WirelessServiceObservation(
        observationId = WirelessObservationId(id),
        serviceType = WirelessServiceType.PAIRING,
        serviceName = "synthetic-service",
        addresses = emptyList(),
        port = 4711,
        status = WirelessServiceStatus.RESOLVED,
        lastSeenAt = 1L,
    )

    private fun fakeRepository(): DeviceProfileRepository = Proxy.newProxyInstance(
        DeviceProfileRepository::class.java.classLoader,
        arrayOf(DeviceProfileRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getProfiles" -> flowOf(emptyList<Any>())
            "rename" -> false
            else -> null
        }
    } as DeviceProfileRepository

    private class FakeMaterial(
        override val attemptId: PairingAttemptId,
    ) : QrPairingMaterial {
        override val deadlineMillis: Long = Long.MAX_VALUE
        override var payload: String? = "synthetic-qr-payload"
            private set

        fun invalidate() {
            payload = null
        }
    }

    private class FakeManager(
        initialConnectionState: AdbConnectionState = AdbConnectionState.Disconnected(),
    ) {
        val connectionState = MutableStateFlow(initialConnectionState)
        val diagnostics = MutableStateFlow<List<AdbDiagnosticEvent>>(emptyList())
        val createdAttempts = mutableListOf<PairingAttemptId>()
        val cancelledAttempts = mutableListOf<PairingAttemptId>()
        val qrPairCalls = mutableListOf<PairingAttemptId>()
        var connectCalls = 0
        var disconnectCalls = 0
        var codePairCalls = 0

        private val queuedMaterials = ArrayDeque<FakeMaterial>()
        private val queuedDiscoveries = ArrayDeque<MutableSharedFlow<AdbOperationResult<WirelessDiscoveryState>>>()
        private val activeMaterials = mutableMapOf<PairingAttemptId, FakeMaterial>()

        val instance: AdbSessionManager = Proxy.newProxyInstance(
            AdbSessionManager::class.java.classLoader,
            arrayOf(AdbSessionManager::class.java),
        ) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "getConnectionState" -> connectionState
                "getDiagnosticEvents" -> diagnostics
                "createQrPairingAttempt" -> {
                    val attemptId = args!![0] as PairingAttemptId
                    val material = queuedMaterials.removeFirst()
                    assertEquals(material.attemptId, attemptId)
                    createdAttempts += attemptId
                    activeMaterials[attemptId] = material
                    AdbOperationResult.Success(material)
                }
                "observeWirelessServices" -> {
                    assertEquals(args!![0], WirelessDiscoveryMode.LOCAL_PAIRING)
                    queuedDiscoveries.removeFirst()
                }
                "pairQrObservation" -> {
                    val attemptId = args!![0] as PairingAttemptId
                    qrPairCalls += attemptId
                    activeMaterials.remove(attemptId)?.invalidate()
                    AdbOperationResult.Success(Unit)
                }
                "cancelQrPairing" -> {
                    val attemptId = args!![0] as PairingAttemptId
                    cancelledAttempts += attemptId
                    activeMaterials.remove(attemptId)?.invalidate()
                    AdbOperationResult.Success(Unit)
                }
                "pairWithSecret" -> {
                    codePairCalls++
                    (args!![1] as PairingSecret).clear()
                    AdbOperationResult.Success(Unit)
                }
                "connect" -> {
                    connectCalls++
                    AdbOperationResult.Success(Unit)
                }
                "disconnect" -> {
                    disconnectCalls++
                    connectionState.value = AdbConnectionState.Disconnected()
                    AdbOperationResult.Success(Unit)
                }
                "clearDiagnosticEvents", "reportInvalidAddress", "close" -> null
                "streamLogcat" -> flowOf(AdbOperationResult.Cancelled)
                else -> AdbOperationResult.Cancelled
            }
        } as AdbSessionManager

        fun enqueueQrAttempt(material: FakeMaterial): MutableSharedFlow<AdbOperationResult<WirelessDiscoveryState>> {
            val discovery = MutableSharedFlow<AdbOperationResult<WirelessDiscoveryState>>(extraBufferCapacity = 1)
            queuedMaterials += material
            queuedDiscoveries += discovery
            return discovery
        }
    }
}
