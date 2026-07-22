package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbOperationStage
import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoverySource
import com.sheen.adb.core.WirelessDiscoverySourceFactory
import com.sheen.adb.core.WirelessDiscoverySourceFailure
import com.sheen.adb.core.WirelessDiscoverySourceObserver
import com.sheen.adb.core.WirelessDiscoverySourceRequest
import com.sheen.adb.core.WirelessDiscoverySourceStartResult
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WirelessSessionManagerContractTest {
    @Test
    fun `discovery belongs to its generation and adb session and rejects late callbacks`() = runBlocking {
        val sourceFactory = FakeWirelessDiscoverySourceFactory()
        val manager = manager(
            sourceFactory = sourceFactory,
            clients = arrayOf(FakeProtocolClient(), FakeProtocolClient()),
        )
        assertTrue(manager.connect(AdbEndpoint("synthetic-session-a.invalid", 41001)) is AdbOperationResult.Success)

        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val firstCollection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val firstState = nextSuccess(emissions)
        val firstSource = sourceFactory.awaitSource(0)
        assertEquals(firstSource.request?.generation, firstState.generation)

        firstSource.emit(observedEvent(firstState.generation, "observation-current"))
        assertEquals(nextSuccess(emissions).services.single().observationId, WirelessObservationId("observation-current"))

        firstSource.emit(observedEvent(firstState.generation - 1, "observation-stale"))
        assertNull(withTimeoutOrNull(100.milliseconds) { emissions.receive() })

        assertTrue(manager.connect(AdbEndpoint("synthetic-session-b.invalid", 41002)) is AdbOperationResult.Success)
        assertDiscoveryFailure(next(emissions), "ADB_DISCOVERY_SESSION_CHANGED")
        withTimeout(2.seconds) { firstCollection.join() }
        assertEquals(firstSource.closeCalls.get(), 1)

        firstSource.emit(observedEvent(firstState.generation, "observation-late"))
        val secondCollection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val secondState = nextSuccess(emissions)
        sourceFactory.awaitSource(1)
        assertTrue(secondState.generation > firstState.generation)
        assertTrue(secondState.services.isEmpty())

        secondCollection.cancelAndJoin()
        manager.close()
    }

    @Test
    fun `collector cancellation closes the active discovery source`() = runBlocking {
        val sourceFactory = FakeWirelessDiscoverySourceFactory()
        val manager = manager(sourceFactory)
        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val collection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }

        nextSuccess(emissions)
        val source = sourceFactory.awaitSource(0)
        collection.cancelAndJoin()

        awaitCondition { source.closeCalls.get() == 1 }
        assertEquals(source.closeCalls.get(), 1)
        manager.close()
    }

    @Test
    fun `discovery has a finite timeout with a structured terminal error`() = runBlocking {
        val sourceFactory = FakeWirelessDiscoverySourceFactory()
        val manager = manager(sourceFactory)

        val results = withTimeout(2.seconds) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 75.milliseconds).toList()
        }

        assertTrue(results.first() is AdbOperationResult.Success)
        assertDiscoveryFailure(results.last(), "ADB_DISCOVERY_TIMEOUT")
        assertEquals(sourceFactory.awaitSource(0).closeCalls.get(), 1)
        manager.close()
    }

    @Test
    fun `timeout bounds a source factory that has not returned`() = runBlocking {
        val createGate = BlockingGate()
        val sourceFactory = FakeWirelessDiscoverySourceFactory(createGate = createGate)
        val manager = manager(sourceFactory)
        val collection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 75.milliseconds).toList()
        }
        var results: List<AdbOperationResult<WirelessDiscoveryState>>? = null
        var sourceCountWhileBlocked = -1

        try {
            assertTrue(createGate.entered.await(2, TimeUnit.SECONDS))
            results = withTimeoutOrNull(200.milliseconds) { collection.await() }
            sourceCountWhileBlocked = sourceFactory.sources.size
        } finally {
            createGate.release.countDown()
            if (collection.isActive) withTimeout(2.seconds) { collection.cancelAndJoin() }
            manager.close()
        }

        assertEquals(sourceCountWhileBlocked, 0)
        assertEquals(results?.size, 1)
        assertDiscoveryFailure(checkNotNull(results).single(), "ADB_DISCOVERY_TIMEOUT")
    }

    @Test
    fun `timeout bounds source start and closes the attached source exactly once`() = runBlocking {
        val startGate = BlockingGate()
        val sourceFactory = FakeWirelessDiscoverySourceFactory(startGate = startGate)
        val manager = manager(sourceFactory)
        val collection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 75.milliseconds).toList()
        }
        val source = sourceFactory.awaitSource(0)
        var results: List<AdbOperationResult<WirelessDiscoveryState>>? = null

        try {
            assertTrue(startGate.entered.await(2, TimeUnit.SECONDS))
            results = withTimeoutOrNull(200.milliseconds) { collection.await() }
        } finally {
            startGate.release.countDown()
            if (collection.isActive) withTimeout(2.seconds) { collection.cancelAndJoin() }
            manager.close()
        }

        assertEquals(results?.size, 1)
        assertDiscoveryFailure(checkNotNull(results).single(), "ADB_DISCOVERY_TIMEOUT")
        assertEquals(source.closeCalls.get(), 1)
    }

    @Test
    fun `cancellation from factory create or source start is propagated without platform remapping`() = runBlocking {
        val createCancellation = CancellationException("synthetic-create-cancelled")
        val createFactory = FakeWirelessDiscoverySourceFactory(createFailure = createCancellation)
        val createManager = manager(createFactory)
        var caughtFromCreate: CancellationException? = null
        try {
            withTimeout(2.seconds) {
                createManager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds).toList()
            }
        } catch (error: CancellationException) {
            caughtFromCreate = error
        } finally {
            createManager.close()
        }

        val startCancellation = CancellationException("synthetic-start-cancelled")
        val startFactory = FakeWirelessDiscoverySourceFactory(startFailure = startCancellation)
        val startManager = manager(startFactory)
        var caughtFromStart: CancellationException? = null
        try {
            withTimeout(2.seconds) {
                startManager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds).toList()
            }
        } catch (error: CancellationException) {
            caughtFromStart = error
        } finally {
            startManager.close()
        }
        val startSource = startFactory.awaitSource(0)

        assertTrue(caughtFromCreate === createCancellation)
        assertEquals(createFactory.sources.size, 0)
        assertTrue(caughtFromStart === startCancellation)
        assertEquals(startSource.closeCalls.get(), 1)
    }

    @Test
    fun `source failures are mapped to discovery errors without platform or endpoint details`() = runBlocking {
        val expectedCodes = listOf(
            WirelessDiscoverySourceFailure.NETWORK_UNAVAILABLE to "ADB_DISCOVERY_NETWORK_UNAVAILABLE",
            WirelessDiscoverySourceFailure.PERMISSION_UNAVAILABLE to "ADB_DISCOVERY_PERMISSION_UNAVAILABLE",
            WirelessDiscoverySourceFailure.RESOLUTION_FAILED to "ADB_DISCOVERY_RESOLUTION_FAILED",
            WirelessDiscoverySourceFailure.PLATFORM_FAILURE to "ADB_DISCOVERY_PLATFORM_FAILURE",
        )

        expectedCodes.forEach { (sourceFailure, expectedCode) ->
            val sourceFactory = FakeWirelessDiscoverySourceFactory()
            val manager = manager(sourceFactory)
            val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
            val collection = async(Dispatchers.Default) {
                manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                    .collect(emissions::send)
            }

            nextSuccess(emissions)
            val source = sourceFactory.awaitSource(0)
            source.fail(sourceFailure)

            val failure = next(emissions)
            assertDiscoveryFailure(failure, expectedCode)
            val rendered = (failure as AdbOperationResult.Failure).error.toString()
            assertTrue("synthetic-session" !in rendered)
            assertTrue("NsdManager" !in rendered)
            withTimeout(2.seconds) { collection.join() }
            assertEquals(source.closeCalls.get(), 1)
            manager.close()
        }
    }

    @Test
    fun `synchronous source start rejections fail without publishing discovery state or hanging`() = runBlocking {
        val expectedCodes = listOf(
            WirelessDiscoverySourceFailure.NETWORK_UNAVAILABLE to "ADB_DISCOVERY_NETWORK_UNAVAILABLE",
            WirelessDiscoverySourceFailure.PERMISSION_UNAVAILABLE to "ADB_DISCOVERY_PERMISSION_UNAVAILABLE",
            WirelessDiscoverySourceFailure.RESOLUTION_FAILED to "ADB_DISCOVERY_RESOLUTION_FAILED",
            WirelessDiscoverySourceFailure.PLATFORM_FAILURE to "ADB_DISCOVERY_PLATFORM_FAILURE",
        )

        expectedCodes.forEach { (sourceFailure, expectedCode) ->
            val sourceFactory = FakeWirelessDiscoverySourceFactory(
                startResult = WirelessDiscoverySourceStartResult.Rejected(sourceFailure),
            )
            val manager = manager(sourceFactory)

            val results = withTimeout(2.seconds) {
                manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds).toList()
            }

            assertEquals(results.size, 1)
            assertDiscoveryFailure(results.single(), expectedCode)
            val source = sourceFactory.awaitSource(0)
            assertEquals(source.closeCalls.get(), 1)
            assertEquals(sourceFactory.sources.size, 1)
            manager.close()
            assertEquals(source.closeCalls.get(), 1)
        }
    }

    @Test
    fun `a concurrent discovery is rejected without creating another source`() = runBlocking {
        val sourceFactory = FakeWirelessDiscoverySourceFactory()
        val manager = manager(sourceFactory)
        val firstEmissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val firstCollection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(firstEmissions::send)
        }
        nextSuccess(firstEmissions)
        sourceFactory.awaitSource(0)

        val rejected = withTimeout(2.seconds) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LOCAL_PAIRING, 5.seconds).toList()
        }

        assertEquals(rejected.size, 1)
        assertDiscoveryFailure(rejected.single(), "ADB_DISCOVERY_CONFLICT")
        assertEquals(sourceFactory.sources.size, 1)
        firstCollection.cancelAndJoin()
        manager.close()
    }

    @Test
    fun `discovery remains conflicting until a terminal source close finishes`() = runBlocking {
        val closeGate = BlockingGate()
        val sourceFactory = FakeWirelessDiscoverySourceFactory(closeGate = closeGate)
        val manager = manager(sourceFactory)
        val firstCollection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds).toList()
        }
        val firstSource = sourceFactory.awaitSource(0)
        awaitCondition { firstSource.request != null }
        val failureTrigger = async(Dispatchers.Default) {
            firstSource.fail(WirelessDiscoverySourceFailure.PLATFORM_FAILURE)
        }
        assertTrue(closeGate.entered.await(2, TimeUnit.SECONDS))

        val secondCollection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LOCAL_PAIRING, 5.seconds).toList()
        }
        var secondResults: List<AdbOperationResult<WirelessDiscoveryState>>? = null
        var sourceCountBeforeCloseRelease = -1
        var firstResults: List<AdbOperationResult<WirelessDiscoveryState>>? = null
        try {
            secondResults = withTimeoutOrNull(200.milliseconds) { secondCollection.await() }
            sourceCountBeforeCloseRelease = sourceFactory.sources.size
        } finally {
            closeGate.release.countDown()
            withTimeout(2.seconds) { failureTrigger.await() }
            firstResults = withTimeout(2.seconds) { firstCollection.await() }
            if (secondCollection.isActive) withTimeout(2.seconds) { secondCollection.cancelAndJoin() }
            manager.close()
        }

        assertEquals(secondResults?.size, 1)
        assertDiscoveryFailure(checkNotNull(secondResults).single(), "ADB_DISCOVERY_CONFLICT")
        assertEquals(sourceCountBeforeCloseRelease, 1)
        assertTrue(checkNotNull(firstResults).last() is AdbOperationResult.Failure)
        assertEquals(firstSource.closeCalls.get(), 1)
    }

    @Test
    fun `manager close terminalizes discovery closes source and discards late callback`() = runBlocking {
        val sourceFactory = FakeWirelessDiscoverySourceFactory()
        val manager = manager(sourceFactory)
        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val collection = async(Dispatchers.Default) {
            manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val initial = nextSuccess(emissions)
        val source = sourceFactory.awaitSource(0)

        manager.close()

        assertDiscoveryFailure(next(emissions), "ADB_DISCOVERY_MANAGER_CLOSED")
        withTimeout(2.seconds) { collection.join() }
        assertEquals(source.closeCalls.get(), 1)
        source.emit(observedEvent(initial.generation, "observation-after-close"))
        assertNull(withTimeoutOrNull(100.milliseconds) { emissions.receive() })
    }

    private fun manager(
        sourceFactory: FakeWirelessDiscoverySourceFactory,
        clients: Array<FakeProtocolClient> = arrayOf(FakeProtocolClient()),
    ): DefaultAdbSessionManager = DefaultAdbSessionManager(
        clientFactory = QueueProtocolClientFactory(*clients),
        ioDispatcher = Dispatchers.IO,
        wirelessDiscoverySourceFactory = sourceFactory,
    )

    private suspend fun next(
        emissions: Channel<AdbOperationResult<WirelessDiscoveryState>>,
    ): AdbOperationResult<WirelessDiscoveryState> = withTimeout(2.seconds) { emissions.receive() }

    private suspend fun nextSuccess(
        emissions: Channel<AdbOperationResult<WirelessDiscoveryState>>,
    ): WirelessDiscoveryState {
        val result = next(emissions)
        assertTrue(result is AdbOperationResult.Success)
        return (result as AdbOperationResult.Success).value
    }

    private fun assertDiscoveryFailure(result: AdbOperationResult<WirelessDiscoveryState>, expectedCode: String) {
        assertTrue(result is AdbOperationResult.Failure)
        val error = (result as AdbOperationResult.Failure).error
        assertEquals(error.stage, AdbOperationStage.DISCOVERY)
        assertEquals(error.technicalCode, expectedCode)
        assertTrue(error.userMessage.isNotBlank())
        assertTrue(error.nextStep.isNotBlank())
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2.seconds) {
            while (!condition()) yield()
        }
    }

    private fun observedEvent(generation: Long, observationId: String): WirelessDiscoveryEvent =
        WirelessDiscoveryEvent.ServiceObserved(
            generation = generation,
            observation = WirelessServiceObservation(
                observationId = WirelessObservationId(observationId),
                serviceType = WirelessServiceType.CONNECT,
                serviceName = "synthetic-connect-service",
                addresses = listOf(WirelessAddress.Ipv4(192, 0, 2, 80)),
                port = 41080,
                status = WirelessServiceStatus.RESOLVED,
                lastSeenAt = 1L,
            ),
        )

    private class FakeWirelessDiscoverySourceFactory(
        private val startResult: WirelessDiscoverySourceStartResult = WirelessDiscoverySourceStartResult.Started,
        private val createGate: BlockingGate? = null,
        private val startGate: BlockingGate? = null,
        private val closeGate: BlockingGate? = null,
        private val createFailure: Throwable? = null,
        private val startFailure: Throwable? = null,
    ) : WirelessDiscoverySourceFactory {
        val sources = CopyOnWriteArrayList<FakeWirelessDiscoverySource>()

        override fun create(observer: WirelessDiscoverySourceObserver): WirelessDiscoverySource {
            createGate?.awaitRelease()
            createFailure?.let { throw it }
            return FakeWirelessDiscoverySource(
                observer = observer,
                startResult = startResult,
                startGate = startGate,
                closeGate = closeGate,
                startFailure = startFailure,
            ).also(sources::add)
        }

        suspend fun awaitSource(index: Int): FakeWirelessDiscoverySource {
            withTimeout(2.seconds) {
                while (sources.size <= index) yield()
            }
            return sources[index]
        }
    }

    private class FakeWirelessDiscoverySource(
        private val observer: WirelessDiscoverySourceObserver,
        private val startResult: WirelessDiscoverySourceStartResult,
        private val startGate: BlockingGate?,
        private val closeGate: BlockingGate?,
        private val startFailure: Throwable?,
    ) : WirelessDiscoverySource {
        @Volatile
        var request: WirelessDiscoverySourceRequest? = null
            private set
        val closeCalls = AtomicInteger(0)

        override fun start(request: WirelessDiscoverySourceRequest): WirelessDiscoverySourceStartResult {
            this.request = request
            startGate?.awaitRelease()
            startFailure?.let { throw it }
            return startResult
        }

        fun emit(event: WirelessDiscoveryEvent) {
            observer.onEvent(event)
        }

        fun fail(failure: WirelessDiscoverySourceFailure) {
            observer.onFailure(failure)
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closeGate?.awaitRelease()
        }
    }

    private class BlockingGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun awaitRelease() {
            entered.countDown()
            release.await()
        }
    }

    private class FakeProtocolClient : AdbProtocolClient {
        override fun execute(command: String): ProtocolShellResponse =
            ProtocolShellResponse("synthetic-ok\n", "", 0, streamsSeparated = true, wasTruncated = false)

        override fun openShellStream(command: String): ProtocolShellStream = object : ProtocolShellStream {
            override fun read(): ProtocolShellPacket = ProtocolShellPacket.Exit(0)
            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private class QueueProtocolClientFactory(
        vararg clients: FakeProtocolClient,
    ) : AdbProtocolClientFactory {
        private val clients = ArrayDeque(clients.toList())

        override fun open(endpoint: AdbEndpoint): AdbProtocolClient = clients.removeFirst()

        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit

        override fun clearIdentity() = Unit
    }
}
