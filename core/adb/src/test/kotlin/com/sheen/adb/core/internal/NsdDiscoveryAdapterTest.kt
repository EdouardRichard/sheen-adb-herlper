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
import com.sheen.adb.core.internal.discovery.NsdPlatformFailure
import com.sheen.adb.core.internal.discovery.NsdPlatformOperationException
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

    @DataProvider(name = "legacyTerminalScenarios")
    fun legacyTerminalScenarios(): Array<Array<Any>> = legacyApiLevels().flatMap { api ->
        LegacyTerminal.entries.map { terminal -> arrayOf(api.single(), terminal) }
    }.toTypedArray()

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

    @Test(dataProvider = "legacyTerminalScenarios")
    fun `legacy stop cancellation and close each release both discoveries resolves lock and timeout while late callbacks stay inert`(
        apiLevel: Int,
        terminal: LegacyTerminal,
    ) {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 310L + apiLevel, apiLevel = apiLevel))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource
        val lock = fixture.platform.multicastLocks.single()

        invokeLegacyTerminal(terminal, fixture)
        invokeLegacyTerminal(terminal, fixture)

        pending.assertAllReleased(timeout = timeout, multicastLock = lock)
        assertTrue(fixture.platform.networkChanges.isEmpty())
        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 2)
    }

    @Test
    fun `API 33 binds discovery resolution and its network callback to the current network and rejects an unavailable network`() {
        val network = NsdNetworkRef("network-synthetic-alpha")
        val bound = fixture()

        assertEquals(bound.adapter.start(request(generation = 330L, apiLevel = 33, network = network)), NsdDiscoveryStartResult.Started)
        assertEquals(bound.platform.discoveries.map { it.network }.toSet(), setOf(network))
        assertEquals(bound.platform.networkChanges.map { it.network }.toSet(), setOf(network))
        assertTrue(bound.platform.multicastLocks.isEmpty())

        bound.platform.discoveryFor(WirelessServiceType.CONNECT).callbacks.onServiceFound(CONNECT_SERVICE)
        assertEquals(bound.platform.resolves.single().network, network)

        listOf(33, 34).forEach { apiLevel ->
            val unavailable = fixture()
            val result = unavailable.adapter.start(request(generation = 331L + apiLevel, apiLevel = apiLevel, network = null))

            assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.NETWORK_UNAVAILABLE))
            assertTrue(unavailable.platform.discoveries.isEmpty())
            assertTrue(unavailable.platform.networkChanges.isEmpty())
            assertTrue(unavailable.platform.multicastLocks.isEmpty())
        }
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
        assertTrue(fixture.platform.multicastLocks.isEmpty())
        assertEquals(fixture.observedServices.single().addresses, ADDRESSES)
        assertEquals(fixture.observedServices.single().status, WirelessServiceStatus.RESOLVED)
    }

    @Test
    fun `timeout cleans both discovery registrations both pending resolves multicast lock and timeout handle exactly once`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 400L, apiLevel = 30))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource
        val lock = fixture.platform.multicastLocks.single()

        assertEquals(fixture.scheduler.delays, listOf(NsdDiscoveryPolicy.DEFAULT_LAN_DISCOVERY_CUTOFF_MILLIS))
        fixture.scheduler.runNext()
        fixture.scheduler.runNext()
        pending.assertAllReleased(timeout = timeout, multicastLock = lock)

        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 2)
    }

    @Test
    fun `stop cancellation and close unregister both API 33 and 34 discovery registrations resolves network callback and timeout handle exactly once`() {
        listOf(33, 34).forEach { apiLevel ->
            listOf<TerminalAction>(
                TerminalAction { it.adapter.stop() },
                TerminalAction { it.adapter.cancel() },
                TerminalAction { it.adapter.close() },
            ).forEach { terminalAction ->
                val fixture = fixture()
                fixture.adapter.start(request(generation = 500L + apiLevel, apiLevel = apiLevel, network = NETWORK_GAMMA))
                val pending = fixture.openPendingResolves()
                val timeout = fixture.scheduler.scheduled.single().resource
                val networkChange = fixture.platform.networkChanges.single()
                assertTrue(fixture.platform.multicastLocks.isEmpty())

                terminalAction(fixture)
                terminalAction(fixture)

                pending.assertAllReleased(timeout = timeout, networkChange = networkChange)
                pending.sendLateCallbacks()
                assertTrue(fixture.observedServices.isEmpty())
            }
        }
    }

    @Test
    fun `API 33 network change callback cleans both registrations resolves and timeout without direct adapter invocation`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 600L, apiLevel = 33, network = NETWORK_GAMMA))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource
        val networkChange = fixture.platform.networkChanges.single()
        assertTrue(fixture.platform.multicastLocks.isEmpty())

        networkChange.callbacks.onNetworkChanged(NsdNetworkRef("network-synthetic-replacement"))
        networkChange.callbacks.onNetworkChanged(NsdNetworkRef("network-synthetic-replacement"))

        pending.assertAllReleased(timeout = timeout, networkChange = networkChange)
        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
    }

    @Test
    fun `discovery failure after both pending resolves releases every acquired resource and notifies only a safe structured failure`() {
        listOf(30, 33).forEach { apiLevel ->
            val fixture = fixture()
            fixture.adapter.start(
                request(
                    generation = 650L + apiLevel,
                    apiLevel = apiLevel,
                    network = if (apiLevel >= 33) NETWORK_GAMMA else null,
                ),
            )
            val pending = fixture.openPendingResolves()
            val timeout = fixture.scheduler.scheduled.single().resource
            val lock = fixture.platform.multicastLocks.singleOrNull()
            val networkChange = fixture.platform.networkChanges.singleOrNull()

            pending.pairingDiscovery.callbacks.onDiscoveryFailure(NsdPlatformFailure.DISCOVERY_FAILED)
            pending.pairingDiscovery.callbacks.onDiscoveryFailure(NsdPlatformFailure.DISCOVERY_FAILED)

            pending.assertAllReleased(timeout, multicastLock = lock, networkChange = networkChange)
            if (apiLevel >= 33) assertTrue(fixture.platform.multicastLocks.isEmpty())
            assertEquals(fixture.failures, listOf(NsdDiscoveryFailure.PLATFORM_DISCOVERY_FAILED))
            pending.sendLateCallbacks()
            assertTrue(fixture.observedServices.isEmpty())
        }
    }

    @Test
    fun `resolve failure releases both registrations all pending resolves and reports only a safe structured failure`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 660L, apiLevel = 30))
        val pending = fixture.openPendingResolves()
        val timeout = fixture.scheduler.scheduled.single().resource
        val lock = fixture.platform.multicastLocks.single()

        pending.pairingResolve.callbacks.onResolveFailure(NsdPlatformFailure.RESOLVE_FAILED)
        pending.pairingResolve.callbacks.onResolveFailure(NsdPlatformFailure.RESOLVE_FAILED)

        pending.assertAllReleased(timeout = timeout, multicastLock = lock)
        assertEquals(fixture.failures, listOf(NsdDiscoveryFailure.PLATFORM_RESOLVE_FAILED))
        pending.sendLateCallbacks()
        assertTrue(fixture.observedServices.isEmpty())
    }

    @Test
    fun `resolve gateway exception after another resolve is pending is contained cleans every acquired resource and reports only a safe failure`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 665L, apiLevel = 30))
        val timeout = fixture.scheduler.scheduled.single().resource
        val lock = fixture.platform.multicastLocks.single()
        val pairingDiscovery = fixture.platform.discoveryFor(WirelessServiceType.PAIRING)
        val connectDiscovery = fixture.platform.discoveryFor(WirelessServiceType.CONNECT)
        pairingDiscovery.callbacks.onServiceFound(PAIRING_SERVICE)
        val pairingResolve = fixture.platform.resolves.single()
        fixture.platform.throwOnResolveService = CONNECT_SERVICE

        connectDiscovery.callbacks.onServiceFound(CONNECT_SERVICE)

        assertEquals(fixture.platform.resolves.size, 1)
        assertExactlyOnce(pairingDiscovery.resource)
        assertExactlyOnce(connectDiscovery.resource)
        assertExactlyOnce(pairingResolve.resource)
        assertExactlyOnce(timeout)
        assertExactlyOnce(lock)
        assertEquals(fixture.failures, listOf(NsdDiscoveryFailure.PLATFORM_OPERATION_FAILED))
        pairingResolve.callbacks.onResolved(resolvedService(PAIRING_SERVICE, ADDRESSES))
        assertTrue(fixture.observedServices.isEmpty())
    }

    @Test
    fun `second discovery operation exception is mapped to a safe start rejection and deterministically cleans acquired resources`() {
        val fixture = fixture().also {
            it.platform.throwOnDiscoverCall = 2
        }

        val result = fixture.adapter.start(request(generation = 670L, apiLevel = 30))

        assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.PLATFORM_OPERATION_FAILED))
        assertAllActuallyAcquiredResourcesReleased(fixture)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `multicast lock acquisition exception is contained as a safe start rejection`() {
        val fixture = fixture().also { it.platform.throwOnAcquireMulticastLock = true }

        val result = fixture.adapter.start(request(generation = 675L, apiLevel = 30))

        assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.PLATFORM_OPERATION_FAILED))
        assertAllActuallyAcquiredResourcesReleased(fixture)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `scheduler exception is contained and releases every resource actually acquired before it safely`() {
        val fixture = fixture().also { it.scheduler.throwOnSchedule = true }

        val result = fixture.adapter.start(request(generation = 676L, apiLevel = 30))

        assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.PLATFORM_OPERATION_FAILED))
        assertAllActuallyAcquiredResourcesReleased(fixture)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `API 33 network callback registration exception is contained and releases every resource acquired before it`() {
        val fixture = fixture().also { it.platform.throwOnNetworkChangeRegistration = true }

        val result = fixture.adapter.start(request(generation = 677L, apiLevel = 33, network = NETWORK_GAMMA))

        assertEquals(result, NsdDiscoveryStartResult.Rejected(NsdDiscoveryFailure.PLATFORM_OPERATION_FAILED))
        assertAllActuallyAcquiredResourcesReleased(fixture)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `restart invalidates generation N callbacks and accepts only generation N plus one callbacks`() {
        val fixture = fixture()
        fixture.adapter.start(request(generation = 700L, apiLevel = 33, network = NETWORK_GAMMA))
        val oldPending = fixture.openPendingResolves()
        val oldTimeout = fixture.scheduler.scheduled.single().resource
        val oldNetworkChange = fixture.platform.networkChanges.single()
        assertTrue(fixture.platform.multicastLocks.isEmpty())

        fixture.adapter.start(request(generation = 701L, apiLevel = 33, network = NETWORK_DELTA))
        oldPending.assertAllReleased(timeout = oldTimeout, networkChange = oldNetworkChange)
        oldPending.sendLateCallbacks()

        assertTrue(fixture.observedServices.isEmpty())
        assertEquals(fixture.platform.resolves.size, 2)
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
        val failures = mutableListOf<NsdDiscoveryFailure>()
        return Fixture(
            adapter = AndroidNsdDiscoveryAdapter(
                platform = platform,
                policy = NsdDiscoveryPolicy(),
                scheduler = scheduler,
                observer = object : NsdDiscoveryObserver {
                    override fun onEvent(event: WirelessDiscoveryEvent) {
                        events += event
                    }

                    override fun onFailure(failure: NsdDiscoveryFailure) {
                        failures += failure
                    }
                },
            ),
            platform = platform,
            scheduler = scheduler,
            events = events,
            failures = failures,
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

    private fun assertAllActuallyAcquiredResourcesReleased(fixture: Fixture) {
        val resources = buildList {
            addAll(fixture.platform.multicastLocks)
            addAll(fixture.platform.discoveries.map { it.resource })
            addAll(fixture.platform.resolves.map { it.resource })
            addAll(fixture.platform.networkChanges.map { it.resource })
            addAll(fixture.scheduler.scheduled.map { it.resource })
        }
        resources.forEach(::assertExactlyOnce)
    }

    private fun invokeLegacyTerminal(terminal: LegacyTerminal, fixture: Fixture) {
        when (terminal) {
            LegacyTerminal.STOP -> fixture.adapter.stop()
            LegacyTerminal.CANCEL -> fixture.adapter.cancel()
            LegacyTerminal.CLOSE -> fixture.adapter.close()
        }
    }

    private data class Fixture(
        val adapter: AndroidNsdDiscoveryAdapter,
        val platform: FakePlatform,
        val scheduler: FakeScheduler,
        val events: MutableList<WirelessDiscoveryEvent>,
        val failures: MutableList<NsdDiscoveryFailure>,
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
        var throwOnAcquireMulticastLock = false
        var throwOnDiscoverCall: Int? = null
        var throwOnResolveService: NsdServiceRef? = null
        var throwOnNetworkChangeRegistration = false

        override fun acquireMulticastLock(): NsdPlatformResource {
            if (throwOnAcquireMulticastLock) {
                throw NsdPlatformOperationException(NsdPlatformFailure.OPERATION_FAILED)
            }
            return FakeResource().also(multicastLocks::add)
        }

        override fun discover(
            serviceType: String,
            network: NsdNetworkRef?,
            callbacks: NsdDiscoveryCallbacks,
        ): NsdPlatformResource {
            val callNumber = discoveries.size + 1
            if (callNumber == throwOnDiscoverCall) {
                throw NsdPlatformOperationException(NsdPlatformFailure.OPERATION_FAILED)
            }
            return FakeResource().also { resource ->
                discoveries += DiscoverCall(serviceType, network, callbacks, resource)
            }
        }

        override fun resolve(
            service: NsdServiceRef,
            network: NsdNetworkRef?,
            callbacks: NsdResolveCallbacks,
        ): NsdPlatformResource {
            if (service == throwOnResolveService) {
                throw NsdPlatformOperationException(NsdPlatformFailure.OPERATION_FAILED)
            }
            return FakeResource().also { resource ->
                resolves += ResolveCall(service, network, callbacks, resource)
            }
        }

        override fun registerNetworkChangeCallback(
            network: NsdNetworkRef,
            callbacks: NsdNetworkChangeCallbacks,
        ): NsdPlatformResource {
            if (throwOnNetworkChangeRegistration) {
                throw NsdPlatformOperationException(NsdPlatformFailure.OPERATION_FAILED)
            }
            return FakeResource().also { resource ->
                networkChanges += NetworkChangeCall(network, callbacks, resource)
            }
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
        var throwOnSchedule = false

        override fun schedule(delayMillis: Long, action: () -> Unit): NsdPlatformResource {
            if (throwOnSchedule) {
                throw NsdPlatformOperationException(NsdPlatformFailure.OPERATION_FAILED)
            }
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
        fun assertAllReleased(
            timeout: FakeResource,
            multicastLock: FakeResource? = null,
            networkChange: NetworkChangeCall? = null,
        ) {
            listOf(pairingDiscovery.resource, connectDiscovery.resource).forEach {
                assertEquals(it.cancelCalls, 1)
            }
            listOf(pairingResolve.resource, connectResolve.resource).forEach {
                assertEquals(it.cancelCalls, 1)
            }
            assertEquals(timeout.cancelCalls, 1)
            multicastLock?.let { assertEquals(it.cancelCalls, 1) }
            networkChange?.let { assertEquals(it.resource.cancelCalls, 1) }
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

    enum class LegacyTerminal {
        STOP,
        CANCEL,
        CLOSE;
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
