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
import com.sheen.adb.core.internal.discovery.NsdNetworkRef
import com.sheen.adb.core.internal.discovery.NsdPlatformResource
import com.sheen.adb.core.internal.discovery.NsdResolvedService
import com.sheen.adb.core.internal.discovery.NsdResolveCallbacks
import com.sheen.adb.core.internal.discovery.NsdScheduler
import com.sheen.adb.core.internal.discovery.NsdServiceRef
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class NsdDiscoveryAdapterTest {
    @Test
    fun `API 30 policy holds a multicast lock for discovery and releases it exactly once`() {
        val fixture = fixture()

        assertEquals(fixture.adapter.start(request(apiLevel = 30)), NsdDiscoveryStartResult.Started)
        assertEquals(fixture.platform.multicastLocks.size, 1)
        assertFalse(fixture.platform.multicastLocks.single().isCancelled)

        fixture.adapter.stop()
        fixture.adapter.stop()
        fixture.adapter.close()

        assertEquals(fixture.platform.multicastLocks.single().cancelCalls, 1)
        assertEquals(fixture.platform.discoveries.map { it.serviceType }.toSet(), APPROVED_SERVICE_TYPES)
        assertEquals(fixture.platform.discoveries.count { it.network != null }, 0)
    }

    @Test
    fun `API 33 binds discovery and resolution to the current network and rejects an unavailable network`() {
        val network = NsdNetworkRef("network-synthetic-alpha")
        val bound = fixture()

        assertEquals(bound.adapter.start(request(apiLevel = 33, network = network)), NsdDiscoveryStartResult.Started)
        assertEquals(bound.platform.discoveries.map { it.network }.toSet(), setOf(network))

        bound.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        assertEquals(bound.platform.resolves.single().network, network)

        val unavailable = fixture()
        val result = unavailable.adapter.start(request(apiLevel = 33, network = null))

        assertEquals(
            result,
            NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.NETWORK_UNAVAILABLE),
        )
        assertTrue(unavailable.platform.discoveries.isEmpty())
        assertTrue(unavailable.platform.multicastLocks.isEmpty())
    }

    @Test
    fun `API 34 publishes every resolved address while API 33 keeps its single-address compatibility result`() {
        val allAddresses = listOf(
            WirelessAddress.Ipv4(192, 0, 2, 44),
            WirelessAddress.Ipv6(
                segments = listOf(0x2001, 0x0db8, 0, 0, 0, 0, 0, 0x0044),
                scopeId = "scope-synthetic-alpha",
            ),
        )
        val resolved = NsdResolvedService(
            service = CONNECT_SERVICE,
            port = 42_001,
            primaryAddress = allAddresses.first(),
            allAddresses = allAddresses,
        )

        val api34 = fixture()
        api34.adapter.start(request(apiLevel = 34, network = NsdNetworkRef("network-synthetic-beta")))
        api34.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        api34.platform.resolves.single().callbacks.onResolved(resolved)

        assertEquals(api34.observedServices.single().addresses, allAddresses)
        assertEquals(api34.observedServices.single().status, WirelessServiceStatus.RESOLVED)

        val api33 = fixture()
        api33.adapter.start(request(apiLevel = 33, network = NsdNetworkRef("network-synthetic-gamma")))
        api33.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        api33.platform.resolves.single().callbacks.onResolved(resolved)

        assertEquals(api33.observedServices.single().addresses, listOf(allAddresses.first()))
    }

    @Test
    fun `default LAN cutoff is ten seconds and timeout invalidates the generation cleans resources and rejects late callbacks`() {
        val fixture = fixture()

        fixture.adapter.start(request(apiLevel = 30))
        val discovery = fixture.platform.discoveryFor(WirelessServiceType.PAIRING)
        discovery.callbacks.onServiceFound(PAIRING_SERVICE)
        val resolve = fixture.platform.resolves.single()

        assertEquals(fixture.scheduler.delays, listOf(NsdDiscoveryPolicy.DEFAULT_LAN_DISCOVERY_CUTOFF_MILLIS))
        fixture.scheduler.runNext()

        assertTrue(discovery.resource.isCancelled)
        assertTrue(resolve.resource.isCancelled)
        assertTrue(fixture.platform.multicastLocks.single().isCancelled)

        resolve.callbacks.onResolved(resolvedPairingService())
        discovery.callbacks.onServiceFound(PAIRING_SERVICE)

        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 1)
    }

    @Test
    fun `every terminal path unregisters callbacks cancels resolves releases the legacy lock once and ignores late callbacks`() {
        listOf<TerminalAction>(
            { adapter -> adapter.stop() },
            { adapter -> adapter.cancel() },
            { adapter -> adapter.onNetworkChanged(NsdNetworkRef("network-synthetic-replacement")) },
            { adapter -> adapter.close() },
        ).forEach { terminalAction ->
            val fixture = fixture()
            fixture.adapter.start(request(apiLevel = 30))
            val discovery = fixture.platform.discoveryFor(WirelessServiceType.PAIRING)
            discovery.callbacks.onServiceFound(PAIRING_SERVICE)
            val resolve = fixture.platform.resolves.single()

            terminalAction(fixture.adapter)
            terminalAction(fixture.adapter)
            resolve.callbacks.onResolved(resolvedPairingService())
            discovery.callbacks.onServiceFound(PAIRING_SERVICE)

            assertEquals(discovery.resource.cancelCalls, 1)
            assertEquals(resolve.resource.cancelCalls, 1)
            assertEquals(fixture.platform.multicastLocks.single().cancelCalls, 1)
            assertTrue(fixture.observedServices.isEmpty())
            assertEquals(fixture.platform.resolves.size, 1)
        }
    }

    @Test
    fun `registers only the approved ADB TLS DNS-SD service types`() {
        val fixture = fixture()

        fixture.adapter.start(request(apiLevel = 34, network = NsdNetworkRef("network-synthetic-delta")))

        assertEquals(fixture.platform.discoveries.map { it.serviceType }.toSet(), APPROVED_SERVICE_TYPES)
        assertFalse(fixture.platform.discoveries.any { it.serviceType == "_adb._tcp" })
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
        apiLevel: Int,
        network: NsdNetworkRef? = null,
    ): NsdDiscoveryRequest = NsdDiscoveryRequest(apiLevel = apiLevel, currentNetwork = network)

    private fun resolvedPairingService(): NsdResolvedService = NsdResolvedService(
        service = PAIRING_SERVICE,
        port = 42_001,
        primaryAddress = WirelessAddress.Ipv4(192, 0, 2, 45),
        allAddresses = listOf(WirelessAddress.Ipv4(192, 0, 2, 45)),
    )

    private data class Fixture(
        val adapter: AndroidNsdDiscoveryAdapter,
        val platform: FakePlatform,
        val scheduler: FakeScheduler,
        val events: MutableList<WirelessDiscoveryEvent>,
    ) {
        val observedServices get() = events.filterIsInstance<WirelessDiscoveryEvent.ServiceObserved>()
            .map { it.observation }
    }

    private class FakePlatform : NsdDiscoveryPlatformGateway {
        val multicastLocks = mutableListOf<FakeResource>()
        val discoveries = mutableListOf<DiscoverCall>()
        val resolves = mutableListOf<ResolveCall>()

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

        fun discoveryFor(type: WirelessServiceType): DiscoverCall = discoveries.single {
            it.serviceType == when (type) {
                WirelessServiceType.PAIRING -> "_adb-tls-pairing._tcp"
                WirelessServiceType.CONNECT -> "_adb-tls-connect._tcp"
            }
        }
    }

    private class FakeScheduler : NsdScheduler {
        val delays = mutableListOf<Long>()
        private val scheduled = mutableListOf<ScheduledCall>()

        override fun schedule(delayMillis: Long, action: () -> Unit): NsdPlatformResource {
            delays += delayMillis
            return FakeResource().also { resource -> scheduled += ScheduledCall(resource, action) }
        }

        fun runNext() {
            val next = scheduled.removeFirst()
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

    private data class ScheduledCall(
        val resource: FakeResource,
        val action: () -> Unit,
    )

    private fun interface TerminalAction {
        operator fun invoke(adapter: AndroidNsdDiscoveryAdapter)
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
        val APPROVED_SERVICE_TYPES = setOf(
            "_adb-tls-pairing._tcp",
            "_adb-tls-connect._tcp",
        )
    }
}
