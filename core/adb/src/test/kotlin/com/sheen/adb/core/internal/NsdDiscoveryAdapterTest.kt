package com.sheen.adb.core.internal

import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import com.sheen.adb.core.internal.discovery.AndroidNsdDiscoveryAdapter
import com.sheen.adb.core.internal.discovery.NsdDiscoveryCallbacks
import com.sheen.adb.core.internal.discovery.NsdDiscoveryFailure
import com.sheen.adb.core.internal.discovery.NsdDiscoveryObserver
import com.sheen.adb.core.internal.discovery.NsdDiscoveryPlatformGateway
import com.sheen.adb.core.internal.discovery.NsdDiscoveryPolicy
import com.sheen.adb.core.internal.discovery.NsdDiscoveryRequest
import com.sheen.adb.core.internal.discovery.NsdDiscoveryStartResult
import com.sheen.adb.core.internal.discovery.NsdNetworkChangeCallbacks
import com.sheen.adb.core.internal.discovery.NsdNetworkRef
import com.sheen.adb.core.internal.discovery.NsdPlatformResource
import com.sheen.adb.core.internal.discovery.NsdResolvedService
import com.sheen.adb.core.internal.discovery.NsdResolveCallbacks
import com.sheen.adb.core.internal.discovery.NsdScheduler
import com.sheen.adb.core.internal.discovery.NsdServiceRef
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.DataProvider
import org.testng.annotations.Test

class NsdDiscoveryAdapterTest {
    @DataProvider(name = "legacyApiLevels")
    fun legacyApiLevels(): Array<Array<Any>> = arrayOf(
        arrayOf(30),
        arrayOf(31),
        arrayOf(32),
    )

    @Test(dataProvider = "legacyApiLevels")
    fun `API 30 through 32 acquire release multicast lock and publish only the primary resolved address`(
        apiLevel: Int,
    ) {
        val fixture = fixture()
        val resolved = resolvedService(CONNECT_SERVICE, ADDRESSES)

        assertEquals(fixture.adapter.start(request(generation = 300L + apiLevel, apiLevel = apiLevel)), NsdDiscoveryStartResult.Started)
        fixture.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        fixture.platform.resolves.single().callbacks.onResolved(resolved)

        assertEquals(fixture.platform.multicastLocks.size, 1)
        assertEquals(fixture.platform.networkChanges, emptyList<NetworkChangeCall>())
        assertEquals(fixture.observedServices.single().addresses, listOf(ADDRESSES.first()))

        fixture.adapter.stop()
        fixture.adapter.stop()

        assertExactlyOnce(fixture.platform.multicastLocks.single())
        assertExactlyOnce(fixture.scheduler.scheduled.single().resource)
    }

    @Test
    fun `API 33 binds discovery resolution and its network callback to the current network and rejects an unavailable network`() {
        val network = NsdNetworkRef("network-synthetic-alpha")
        val bound = fixture()

        assertEquals(bound.adapter.start(request(generation = 330L, apiLevel = 33, network = network)), NsdDiscoveryStartResult.Started)
        assertEquals(bound.platform.discoveries.map { it.network }.toSet(), setOf(network))
        assertEquals(bound.platform.networkChanges.map { it.network }.toSet(), setOf(network))

        bound.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        assertEquals(bound.platform.resolves.single().network, network)

        val unavailable = fixture()
        val result = unavailable.adapter.start(request(generation = 331L, apiLevel = 33, network = null))

        assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.NETWORK_UNAVAILABLE))
        assertTrue(unavailable.platform.discoveries.isEmpty())
        assertTrue(unavailable.platform.networkChanges.isEmpty())
        assertTrue(unavailable.platform.multicastLocks.isEmpty())
    }

    @Test
    fun `API 34 binds discovery and resolution to the current network and publishes every resolved address`() {
        val network = NsdNetworkRef("network-synthetic-beta")
        val fixture = fixture()

        fixture.adapter.start(request(generation = 340L, apiLevel = 34, network = network))
        fixture.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        fixture.platform.resolves.single().callbacks.onResolved(resolvedService(CONNECT_SERVICE, ADDRESSES))

        assertEquals(fixture.platform.discoveries.map { it.network }.toSet(), setOf(network))
        assertEquals(fixture.platform.resolves.single().network, network)
        assertEquals(fixture.platform.networkChanges.map { it.network }.toSet(), setOf(network))
        assertEquals(fixture.observedServices.single().addresses, ADDRESSES)
        assertEquals(fixture.observedServices.single().status, WirelessServiceStatus.RESOLVED)
    }

    @Test
    fun `timeout cleans both discovery registrations both pending resolves multicast lock and timeout handle exactly once`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 400L, apiLevel = 30))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource

        assertEquals(fixture.scheduler.delays, listOf(NsdDiscoveryPolicy.DEFAULT_LAN_DISCOVERY_CUTOFF_MILLIS))
        fixture.scheduler.runNext()
        fixture.scheduler.runNext()
        pending.assertAllReleased(fixture, expectNetworkCallback = false)
        assertExactlyOnce(timeout)

        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 2)
    }

    @Test
    fun `stop cancellation and close unregister both API 33 discovery registrations resolves network callback and timeout handle exactly once`() {
        listOf<TerminalAction>(
            TerminalAction { it.adapter.stop() },
            TerminalAction { it.adapter.cancel() },
            TerminalAction { it.adapter.close() },
        ).forEach { terminalAction ->
            val fixture = fixture()
            fixture.adapter.start(request(generation = 500L, apiLevel = 33, network = NETWORK_GAMMA))
            val pending = fixture.openPendingResolves()
            val timeout = fixture.scheduler.scheduled.single().resource

            terminalAction(fixture)
            terminalAction(fixture)

            pending.assertAllReleased(fixture, expectNetworkCallback = true)
            assertExactlyOnce(timeout)
            pending.sendLateCallbacks()
            assertTrue(fixture.observedServices.isEmpty())
        }
    }

    @Test
    fun `API 33 network change callback cleans both registrations resolves and timeout without direct adapter invocation`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 600L, apiLevel = 33, network = NETWORK_GAMMA))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource

        fixture.platform.networkChanges.single().callbacks.onNetworkChanged(NsdNetworkRef("network-synthetic-replacement"))
        fixture.platform.networkChanges.single().callbacks.onNetworkChanged(NsdNetworkRef("network-synthetic-replacement"))

        pending.assertAllReleased(fixture, expectNetworkCallback = true)
        assertExactlyOnce(timeout)
        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
    }

    @Test
    fun `restart invalidates generation N callbacks and accepts only generation N plus one callbacks`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 700L, apiLevel = 33, network = NETWORK_GAMMA))
        val oldDiscovery = fixture.platform.discoveryFor(WirelessServiceType.PAIRING)
        oldDiscovery.callbacks.onServiceFound(PAIRING_SERVICE)
        val oldResolve = fixture.platform.resolves.single()

        fixture.adapter.start(request(generation = 701L, apiLevel = 33, network = NETWORK_DELTA))
        oldDiscovery.callbacks.onServiceFound(PAIRING_SERVICE)
        oldResolve.callbacks.onResolved(resolvedService(PAIRING_SERVICE, ADDRESSES))

        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 1)
        val newDiscovery = fixture.platform.discoveries.last { it.serviceType == "_adb-tls-connect._tcp" }
        newDiscovery.callbacks.onServiceFound(CONNECT_SERVICE)
        fixture.platform.resolves.last().callbacks.onResolved(resolvedService(CONNECT_SERVICE, ADDRESSES))

        assertEquals(fixture.observedServices.single().serviceType, WirelessServiceType.CONNECT)
        assertEquals(fixture.events.single().generation, 701L)
    }

    @Test
    fun `registers only the approved ADB TLS DNS-SD service types and redacts platform reference values`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 800L, apiLevel = 34, network = NETWORK_DELTA))

        assertEquals(fixture.platform.discoveries.map { it.serviceType }.toSet(), APPROVED_SERVICE_TYPES)
        assertFalse(fixture.platform.discoveries.any { it.serviceType == "_adb._tcp" })
        assertFalse(PAIRING_SERVICE.toString().contains(PAIRING_SERVICE.serviceName))
        assertFalse(NETWORK_DELTA.toString().contains("network-synthetic-delta"))
    }

    private fun fixture(): Fixture {
        val platform = FakePlatform()
        val scheduler = FakeScheduler()
        val events = mutableListOf<WirelessDiscoveryEvent>()
        return Fixture(
            adapter = AndroidNsdDiscoveryAdapter(
                platform = platform,
                policy = NsdDiscoveryPolicy(),
                scheduler = scheduler,
                observer = NsdDiscoveryObserver { events += it },
            ),
            platform = platform,
            scheduler = scheduler,
            events = events,
        )
    }

    private fun request(
        generation: Long,
        apiLevel: Int,
        network: NsdNetworkRef? = null,
    ): NsdDiscoveryRequest = NsdDiscoveryRequest(
        generation = generation,
        apiLevel = apiLevel,
        currentNetwork = network,
    )

    private fun resolvedService(
        service: NsdServiceRef,
        addresses: List<WirelessAddress>,
    ): NsdResolvedService = NsdResolvedService(
        service = service,
        port = 42_001,
        primaryAddress = addresses.first(),
        allAddresses = addresses,
    )

    private fun assertExactlyOnce(resource: FakeResource) {
        assertEquals(resource.cancelCalls, 1)
    }

    private data class Fixture(
        val adapter: AndroidNsdDiscoveryAdapter,
        val platform: FakePlatform,
        val scheduler: FakeScheduler,
        val events: MutableList<WirelessDiscoveryEvent>,
    ) {
        val observedServices get() = events.filterIsInstance<WirelessDiscoveryEvent.ServiceObserved>()
            .map { it.observation }

        fun openPendingResolves(): PendingResolves {
            val pairing = platform.discoveryFor(WirelessServiceType.PAIRING)
            val connect = platform.discoveryFor(WirelessServiceType.CONNECT)
            pairing.callbacks.onServiceFound(NsdDiscoveryAdapterTest.PAIRING_SERVICE)
            connect.callbacks.onServiceFound(NsdDiscoveryAdapterTest.CONNECT_SERVICE)
            return PendingResolves(
                pairingDiscovery = pairing,
                connectDiscovery = connect,
                pairingResolve = platform.resolveFor(NsdDiscoveryAdapterTest.PAIRING_SERVICE),
                connectResolve = platform.resolveFor(NsdDiscoveryAdapterTest.CONNECT_SERVICE),
            )
        }
    }

    private class FakePlatform : NsdDiscoveryPlatformGateway {
        val multicastLocks = mutableListOf<FakeResource>()
        val discoveries = mutableListOf<DiscoverCall>()
        val resolves = mutableListOf<ResolveCall>()
        val networkChanges = mutableListOf<NetworkChangeCall>()

        override fun acquireMulticastLock(): NsdPlatformResource = FakeResource().also(multicastLocks::add)

        override fun discover(
            serviceType: String,
            network: NsdNetworkRef?,
            callbacks: NsdDiscoveryCallbacks,
        ): NsdPlatformResource = FakeResource().also { resource ->
            discoveries += DiscoverCall(serviceType, network, callbacks, resource)
        }

        override fun resolve(
            service: NsdServiceRef,
            network: NsdNetworkRef?,
            callbacks: NsdResolveCallbacks,
        ): NsdPlatformResource = FakeResource().also { resource ->
            resolves += ResolveCall(service, network, callbacks, resource)
        }

        override fun registerNetworkChangeCallback(
            network: NsdNetworkRef,
            callbacks: NsdNetworkChangeCallbacks,
        ): NsdPlatformResource = FakeResource().also { resource ->
            networkChanges += NetworkChangeCall(network, callbacks, resource)
        }

        fun discoveryFor(type: WirelessServiceType): DiscoverCall = discoveries.single {
            it.serviceType == when (type) {
                WirelessServiceType.PAIRING -> "_adb-tls-pairing._tcp"
                WirelessServiceType.CONNECT -> "_adb-tls-connect._tcp"
            }
        }

        fun resolveFor(service: NsdServiceRef): ResolveCall = resolves.single { it.service == service }
    }

    private class FakeScheduler : NsdScheduler {
        val delays = mutableListOf<Long>()
        val scheduled = mutableListOf<ScheduledCall>()

        override fun schedule(delayMillis: Long, action: () -> Unit): NsdPlatformResource {
            delays += delayMillis
            return FakeResource().also { resource -> scheduled += ScheduledCall(resource, action) }
        }

        fun runNext() {
            val next = scheduled.firstOrNull { !it.hasRun } ?: return
            next.hasRun = true
            if (!next.resource.isCancelled) next.action()
        }
    }

    private class FakeResource : NsdPlatformResource {
        var cancelCalls = 0
            private set

        val isCancelled get() = cancelCalls > 0

        override fun cancel() {
            cancelCalls++
        }
    }

    private data class DiscoverCall(
        val serviceType: String,
        val network: NsdNetworkRef?,
        val callbacks: NsdDiscoveryCallbacks,
        val resource: FakeResource,
    )

    private data class ResolveCall(
        val service: NsdServiceRef,
        val network: NsdNetworkRef?,
        val callbacks: NsdResolveCallbacks,
        val resource: FakeResource,
    )

    private data class NetworkChangeCall(
        val network: NsdNetworkRef,
        val callbacks: NsdNetworkChangeCallbacks,
        val resource: FakeResource,
    )

    private data class ScheduledCall(
        val resource: FakeResource,
        val action: () -> Unit,
        var hasRun: Boolean = false,
    )

    private data class PendingResolves(
        val pairingDiscovery: DiscoverCall,
        val connectDiscovery: DiscoverCall,
        val pairingResolve: ResolveCall,
        val connectResolve: ResolveCall,
    ) {
        fun assertAllReleased(fixture: Fixture, expectNetworkCallback: Boolean) {
            listOf(pairingDiscovery.resource, connectDiscovery.resource).forEach {
                assertEquals(it.cancelCalls, 1)
            }
            listOf(pairingResolve.resource, connectResolve.resource).forEach {
                assertEquals(it.cancelCalls, 1)
            }
            if (expectNetworkCallback) {
                assertEquals(fixture.platform.networkChanges.size, 1)
                assertEquals(fixture.platform.networkChanges.single().resource.cancelCalls, 1)
            } else {
                assertTrue(fixture.platform.networkChanges.isEmpty())
            }
            if (fixture.platform.multicastLocks.isNotEmpty()) {
                assertEquals(fixture.platform.multicastLocks.single().cancelCalls, 1)
            }
        }

        fun sendLateCallbacks() {
            pairingDiscovery.callbacks.onServiceFound(NsdDiscoveryAdapterTest.PAIRING_SERVICE)
            connectDiscovery.callbacks.onServiceFound(NsdDiscoveryAdapterTest.CONNECT_SERVICE)
            pairingResolve.callbacks.onResolved(
                NsdResolvedService(
                    service = NsdDiscoveryAdapterTest.PAIRING_SERVICE,
                    port = 42_001,
                    primaryAddress = NsdDiscoveryAdapterTest.ADDRESSES.first(),
                    allAddresses = NsdDiscoveryAdapterTest.ADDRESSES,
                ),
            )
            connectResolve.callbacks.onResolved(
                NsdResolvedService(
                    service = NsdDiscoveryAdapterTest.CONNECT_SERVICE,
                    port = 42_001,
                    primaryAddress = NsdDiscoveryAdapterTest.ADDRESSES.first(),
                    allAddresses = NsdDiscoveryAdapterTest.ADDRESSES,
                ),
            )
        }
    }

    private fun interface TerminalAction {
        operator fun invoke(fixture: Fixture)
    }

    private companion object {
        val PAIRING_SERVICE = NsdServiceRef(
            serviceType = "_adb-tls-pairing._tcp",
            serviceName = "pairing-service-synthetic-alpha",
        )
        val CONNECT_SERVICE = NsdServiceRef(
            serviceType = "_adb-tls-connect._tcp",
            serviceName = "connect-service-synthetic-alpha",
        )
        val NETWORK_GAMMA = NsdNetworkRef("network-synthetic-gamma")
        val NETWORK_DELTA = NsdNetworkRef("network-synthetic-delta")
        val ADDRESSES = listOf(
            WirelessAddress.Ipv4(192, 0, 2, 44),
            WirelessAddress.Ipv6(
                segments = listOf(0x2001, 0x0db8, 0, 0, 0, 0, 0, 0x0044),
                scopeId = "scope-synthetic-alpha",
            ),
        )
        val APPROVED_SERVICE_TYPES = setOf(
            "_adb-tls-pairing._tcp",
            "_adb-tls-connect._tcp",
        )
    }
}
