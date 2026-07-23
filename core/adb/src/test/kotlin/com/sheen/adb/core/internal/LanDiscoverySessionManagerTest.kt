package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoverySource
import com.sheen.adb.core.WirelessDiscoverySourceFactory
import com.sheen.adb.core.WirelessDiscoverySourceObserver
import com.sheen.adb.core.WirelessDiscoverySourceRequest
import com.sheen.adb.core.WirelessDiscoverySourceStartResult
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.WirelessDiscoveryTarget
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LanDiscoverySessionManagerTest {
    @Test
    fun `manager owned LAN window closes the source and never probes pairs or connects`() = runBlocking {
        val fixture = Fixture(lanWindowMillis = 75)

        val results = withTimeout(2.seconds) {
            fixture.manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds).toList()
        }

        assertTrue(results.first() is AdbOperationResult.Success)
        assertEquals((results.last() as AdbOperationResult.Failure).error.technicalCode, "ADB_DISCOVERY_TIMEOUT")
        assertEquals(fixture.sourceFactory.awaitSource(0).closeCalls.get(), 1)
        assertEquals(fixture.clientFactory.openCalls, 0)
        assertEquals(fixture.clientFactory.pairCalls, 0)
        fixture.close()
    }

    @Test
    fun `LAN state caps at fifteen services updates duplicates and preserves lost status`() = runBlocking {
        val fixture = Fixture()
        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val collection = async(Dispatchers.Default) {
            fixture.manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val initial = nextSuccess(emissions)
        val source = fixture.sourceFactory.awaitSource(0)
        var latest = initial

        repeat(16) { index ->
            source.emit(observed(initial.generation, "service-$index", index))
            latest = nextSuccess(emissions)
        }

        assertEquals(latest.services.size, 15)
        assertEquals(latest.services.map { it.observationId }.toSet().size, 15)
        assertEquals(latest.services.map { it.serviceType }.toSet(), WirelessServiceType.entries.toSet())

        val duplicate = observed(initial.generation, "service-0", 0)
        source.emit(
            duplicate.copy(
                observation = duplicate.observation.copy(status = WirelessServiceStatus.LOST),
            ),
        )
        latest = nextSuccess(emissions)
        assertEquals(
            latest.services.single { it.observationId == WirelessObservationId("service-0") }.status,
            WirelessServiceStatus.LOST,
        )
        assertEquals(fixture.clientFactory.openCalls, 0)
        assertEquals(fixture.clientFactory.pairCalls, 0)

        collection.cancelAndJoin()
        assertEquals(source.closeCalls.get(), 1)
        fixture.close()
    }

    @Test
    fun `selection revalidates generation and resolved status before pairing`() = runBlocking {
        val fixture = Fixture()
        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val collection = async(Dispatchers.Default) {
            fixture.manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val initial = nextSuccess(emissions)
        val source = fixture.sourceFactory.awaitSource(0)
        val resolved = observed(
            generation = initial.generation,
            id = "pairing-target",
            index = 1,
            type = WirelessServiceType.PAIRING,
        )
        source.emit(resolved)
        nextSuccess(emissions)
        source.emit(
            resolved.copy(
                observation = resolved.observation.copy(status = WirelessServiceStatus.LOST, lastSeenAt = 2L),
            ),
        )
        nextSuccess(emissions)
        val staleSecret = PairingSecret("0".repeat(6).toCharArray())

        val stale = fixture.manager.pairDiscoveredService(
            target = WirelessDiscoveryTarget(initial.generation, resolved.observation.observationId),
            attemptId = PairingAttemptId.of("attempt-stale"),
            secret = staleSecret,
        )

        assertTrue(stale is AdbOperationResult.Failure)
        assertTrue(staleSecret.withChars { chars -> chars.all { it == '\u0000' } })
        assertEquals(fixture.clientFactory.pairCalls, 0)
        val wrongGeneration = fixture.manager.connectDiscoveredService(
            target = WirelessDiscoveryTarget(initial.generation + 1, resolved.observation.observationId),
        )
        assertTrue(wrongGeneration is AdbOperationResult.Failure)
        assertEquals(fixture.clientFactory.openCalls, 0)

        collection.cancelAndJoin()
        fixture.close()
    }

    @Test
    fun `pair and connect require explicit selections and matching identity merges attempt observations`() = runBlocking {
        val fingerprint = byteArrayOf(1, 3, 3, 7)
        val fixture = Fixture(pairFingerprint = fingerprint, connectFingerprint = fingerprint)
        val attemptId = PairingAttemptId.of("attempt-matching")
        val pairing = fixture.discoverOne("pairing-selected", WirelessServiceType.PAIRING)

        val paired = fixture.manager.pairDiscoveredService(
            target = pairing.target,
            attemptId = attemptId,
            secret = PairingSecret("1".repeat(6).toCharArray()),
        )

        assertTrue(paired is AdbOperationResult.Success)
        assertEquals(fixture.clientFactory.pairCalls, 1)
        assertEquals(fixture.clientFactory.openCalls, 0)

        val connect = fixture.discoverOne("connect-selected", WirelessServiceType.CONNECT)
        val connected = fixture.manager.connectDiscoveredService(
            target = connect.target,
            expectedPairingAttemptId = attemptId,
        )

        assertTrue(connected is AdbOperationResult.Success)
        val merged = (connected as AdbOperationResult.Success).value
        assertEquals(fixture.clientFactory.openCalls, 1)
        assertEquals(merged.devices.size, 1)
        assertEquals(merged.devices.single().observations.map { it.serviceType }.toSet(), WirelessServiceType.entries.toSet())
        assertTrue(merged.devices.single().verifiedDeviceId != null)
        assertFalse(merged.toString().contains(fingerprint.joinToString()))
        fixture.close()
    }

    @Test
    fun `different verified fingerprints and unverified observations never merge`() = runBlocking {
        val fixture = Fixture(
            pairFingerprint = byteArrayOf(1, 2, 3),
            connectFingerprint = byteArrayOf(9, 8, 7),
        )
        val attemptId = PairingAttemptId.of("attempt-mismatch")
        val pairing = fixture.discoverOne("pairing-mismatch", WirelessServiceType.PAIRING)
        val paired = fixture.manager.pairDiscoveredService(
            target = pairing.target,
            attemptId = attemptId,
            secret = PairingSecret("2".repeat(6).toCharArray()),
        )
        assertTrue(paired is AdbOperationResult.Success)

        val connect = fixture.discoverOne("connect-mismatch", WirelessServiceType.CONNECT)
        val connected = fixture.manager.connectDiscoveredService(connect.target, attemptId)
        assertTrue(connected is AdbOperationResult.Success)
        val separate = (connected as AdbOperationResult.Success).value

        assertEquals(separate.devices.size, 2)
        val identities = separate.devices.mapNotNull { it.verifiedDeviceId }
        assertEquals(identities.size, 2)
        assertNotEquals(identities[0], identities[1])
        fixture.close()

        val unverified = Fixture()
        val unknownPairing = unverified.discoverOne("pairing-unverified", WirelessServiceType.PAIRING)
        val unknownAttempt = PairingAttemptId.of("attempt-unverified")
        assertTrue(
            unverified.manager.pairDiscoveredService(
                unknownPairing.target,
                unknownAttempt,
                PairingSecret("3".repeat(6).toCharArray()),
            ) is AdbOperationResult.Success,
        )
        val unknownConnect = unverified.discoverOne("connect-unverified", WirelessServiceType.CONNECT)
        val unknownResult = unverified.manager.connectDiscoveredService(unknownConnect.target, unknownAttempt)
        assertEquals((unknownResult as AdbOperationResult.Success).value.devices.size, 2)
        unverified.close()
    }

    @Test
    fun `collector cancellation models page leave or background and invalidates late events`() = runBlocking {
        val fixture = Fixture()
        val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
        val collection = async(Dispatchers.Default) {
            fixture.manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                .collect(emissions::send)
        }
        val initial = nextSuccess(emissions)
        val source = fixture.sourceFactory.awaitSource(0)

        collection.cancelAndJoin()
        source.emit(observed(initial.generation, "late", 1))

        assertEquals(source.closeCalls.get(), 1)
        assertTrue(emissions.isEmpty)
        fixture.close()
    }

    private suspend fun nextSuccess(
        emissions: Channel<AdbOperationResult<WirelessDiscoveryState>>,
    ): WirelessDiscoveryState {
        val result = withTimeout(2.seconds) { emissions.receive() }
        assertTrue(result is AdbOperationResult.Success)
        return (result as AdbOperationResult.Success).value
    }

    private fun observed(
        generation: Long,
        id: String,
        index: Int,
        type: WirelessServiceType = if (index % 2 == 0) WirelessServiceType.CONNECT else WirelessServiceType.PAIRING,
    ): WirelessDiscoveryEvent.ServiceObserved = WirelessDiscoveryEvent.ServiceObserved(
        generation = generation,
        observation = WirelessServiceObservation(
            observationId = WirelessObservationId(id),
            serviceType = type,
            serviceName = "synthetic-service-$index",
            addresses = listOf(WirelessAddress.Ipv4(192, 0, 2, index.coerceIn(1, 254))),
            port = 40_000 + index,
            status = WirelessServiceStatus.RESOLVED,
            lastSeenAt = 1L,
        ),
    )

    private inner class Fixture(
        pairFingerprint: ByteArray? = null,
        connectFingerprint: ByteArray? = null,
        lanWindowMillis: Long = 5_000L,
    ) {
        val sourceFactory = FakeSourceFactory()
        val clientFactory = FakeClientFactory(connectFingerprint)
        val manager = DefaultAdbSessionManager(
            clientFactory = clientFactory,
            ioDispatcher = Dispatchers.IO,
            wirelessDiscoverySourceFactory = sourceFactory,
            lanDiscoveryWindow = lanWindowMillis.milliseconds,
            pairingIdentityFingerprint = { pairFingerprint?.copyOf() },
            connectedIdentityFingerprint = { client -> (client as FakeClient).fingerprint?.copyOf() },
        )

        suspend fun discoverOne(
            id: String,
            type: WirelessServiceType,
        ): DiscoveredFixture {
            val emissions = Channel<AdbOperationResult<WirelessDiscoveryState>>(Channel.UNLIMITED)
            val collection = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
                manager.observeWirelessServices(WirelessDiscoveryMode.LAN_FOREGROUND, 5.seconds)
                    .collect(emissions::send)
            }
            val initial = nextSuccess(emissions)
            val source = sourceFactory.awaitSource(sourceFactory.sources.lastIndex)
            val event = observed(initial.generation, id, sourceFactory.sources.lastIndex + 1, type)
            source.emit(event)
            nextSuccess(emissions)
            return DiscoveredFixture(
                target = WirelessDiscoveryTarget(initial.generation, event.observation.observationId),
                collection = collection,
            )
        }

        fun close() = manager.close()
    }

    private data class DiscoveredFixture(
        val target: WirelessDiscoveryTarget,
        val collection: kotlinx.coroutines.Deferred<Unit>,
    )

    private class FakeSourceFactory : WirelessDiscoverySourceFactory {
        val sources = CopyOnWriteArrayList<FakeSource>()

        override fun create(observer: WirelessDiscoverySourceObserver): WirelessDiscoverySource =
            FakeSource(observer).also(sources::add)

        suspend fun awaitSource(index: Int): FakeSource {
            withTimeout(2.seconds) {
                while (sources.size <= index) kotlinx.coroutines.yield()
            }
            return sources[index]
        }
    }

    private class FakeSource(
        private val observer: WirelessDiscoverySourceObserver,
    ) : WirelessDiscoverySource {
        val closeCalls = AtomicInteger(0)
        var request: WirelessDiscoverySourceRequest? = null

        override fun start(request: WirelessDiscoverySourceRequest): WirelessDiscoverySourceStartResult {
            this.request = request
            return WirelessDiscoverySourceStartResult.Started
        }

        fun emit(event: WirelessDiscoveryEvent) = observer.onEvent(event)

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class FakeClientFactory(
        private val connectFingerprint: ByteArray?,
    ) : AdbProtocolClientFactory {
        var openCalls = 0
        var pairCalls = 0

        override fun open(endpoint: AdbEndpoint): AdbProtocolClient {
            openCalls++
            return FakeClient(connectFingerprint)
        }

        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) {
            pairCalls++
            pairingCode.fill('\u0000')
        }

        override fun clearIdentity() = Unit
    }

    private class FakeClient(
        val fingerprint: ByteArray?,
    ) : AdbProtocolClient {
        override fun execute(command: String): ProtocolShellResponse =
            ProtocolShellResponse("synthetic-ok\n", "", 0, streamsSeparated = true, wasTruncated = false)

        override fun openShellStream(command: String): ProtocolShellStream = object : ProtocolShellStream {
            override fun read(): ProtocolShellPacket = ProtocolShellPacket.Exit(0)
            override fun close() = Unit
        }

        override fun close() = Unit
    }
}
