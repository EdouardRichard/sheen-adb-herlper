package com.sheen.adb.core.internal

import com.sheen.adb.core.VerifiedWirelessDeviceId
import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.WirelessNetworkKey
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceType
import com.sheen.adb.core.internal.discovery.WirelessDiscoveryReducer
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

class WirelessDiscoveryCoreTest {
    @Test
    fun `maps only the approved TLS DNS-SD service types`() {
        assertEquals(
            WirelessServiceType.fromDnsSdType("_adb-tls-pairing._tcp"),
            WirelessServiceType.PAIRING,
        )
        assertEquals(
            WirelessServiceType.fromDnsSdType("_adb-tls-connect._tcp"),
            WirelessServiceType.CONNECT,
        )
        assertNull(WirelessServiceType.fromDnsSdType("_adb._tcp"))
    }

    @Test
    fun `deduplicates repeated observations with the same network type and name`() {
        val reducer = WirelessDiscoveryReducer()
        val observation = observation(
            type = WirelessServiceType.PAIRING,
            name = "pairing-service-alpha",
        )

        val afterFirst = reducer.reduce(
            WirelessDiscoveryState(generation = GENERATION),
            WirelessDiscoveryEvent.ServiceObserved(GENERATION, observation),
        )
        val afterRepeated = reducer.reduce(
            afterFirst,
            WirelessDiscoveryEvent.ServiceObserved(GENERATION, observation),
        )

        assertEquals(afterRepeated.services, listOf(observation))
        assertEquals(afterRepeated.devices.size, 1)
    }

    @Test
    fun `merges pairing and connect observations only for the same verified identity`() {
        val reducer = WirelessDiscoveryReducer()
        val verifiedIdentity = VerifiedWirelessDeviceId("verified-device-alpha")

        val result = reduce(
            reducer,
            observation(
                type = WirelessServiceType.PAIRING,
                name = "pairing-service-alpha",
                verifiedDeviceId = verifiedIdentity,
            ),
            observation(
                type = WirelessServiceType.CONNECT,
                name = "connect-service-alpha",
                verifiedDeviceId = verifiedIdentity,
            ),
        )

        assertEquals(result.services.size, 2)
        assertEquals(result.devices.size, 1)
        assertEquals(
            result.devices.single().serviceTypes,
            setOf(WirelessServiceType.PAIRING, WirelessServiceType.CONNECT),
        )
    }

    @Test
    fun `keeps unknown identity pairing and connect observations separate even with matching endpoints`() {
        val sharedAddresses = setOf(WirelessAddress.Ipv4(192, 0, 2, 44))
        val reducer = WirelessDiscoveryReducer()

        val result = reduce(
            reducer,
            observation(
                type = WirelessServiceType.PAIRING,
                name = "pairing-service-alpha",
                addresses = sharedAddresses,
            ),
            observation(
                type = WirelessServiceType.CONNECT,
                name = "connect-service-alpha",
                addresses = sharedAddresses,
            ),
        )

        assertEquals(result.services.size, 2)
        assertEquals(result.devices.size, 2)
    }

    @Test
    fun `ignores observations delivered for an older discovery generation`() {
        val reducer = WirelessDiscoveryReducer()
        val current = WirelessDiscoveryState(generation = GENERATION)

        val result = reducer.reduce(
            current,
            WirelessDiscoveryEvent.ServiceObserved(
                generation = GENERATION - 1,
                observation = observation(
                    type = WirelessServiceType.CONNECT,
                    name = "late-service-alpha",
                ),
            ),
        )

        assertEquals(result, current)
    }

    @Test
    fun `preserves IPv4 and scoped IPv6 addresses as distinct value objects`() {
        val ipv4 = WirelessAddress.Ipv4(192, 0, 2, 44)
        val scopedIpv6 = WirelessAddress.Ipv6(
            segments = listOf(0x2001, 0x0db8, 0, 0, 0, 0, 0, 0x0044),
            scopeId = "scope-synthetic-alpha",
        )
        val sameIpv6WithOtherScope = scopedIpv6.copy(scopeId = "scope-synthetic-beta")
        val reducer = WirelessDiscoveryReducer()

        val result = reduce(
            reducer,
            observation(
                type = WirelessServiceType.CONNECT,
                name = "connect-service-ipv6-alpha",
                addresses = setOf(ipv4, scopedIpv6),
            ),
        )

        assertEquals(result.services.single().addresses, setOf(ipv4, scopedIpv6))
        assertNotEquals(scopedIpv6, sameIpv6WithOtherScope)
    }

    private fun reduce(
        reducer: WirelessDiscoveryReducer,
        vararg observations: WirelessServiceObservation,
    ): WirelessDiscoveryState = observations.fold(WirelessDiscoveryState(generation = GENERATION)) { state, observation ->
        reducer.reduce(state, WirelessDiscoveryEvent.ServiceObserved(GENERATION, observation))
    }

    private fun observation(
        type: WirelessServiceType,
        name: String,
        addresses: Set<WirelessAddress> = setOf(WirelessAddress.Ipv4(192, 0, 2, 44)),
        verifiedDeviceId: VerifiedWirelessDeviceId? = null,
    ): WirelessServiceObservation = WirelessServiceObservation(
        networkKey = WirelessNetworkKey("network-synthetic-alpha"),
        serviceType = type,
        serviceName = name,
        addresses = addresses,
        port = 42_001,
        verifiedDeviceId = verifiedDeviceId,
    )

    private companion object {
        const val GENERATION = 41L
    }
}
