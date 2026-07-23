package com.sheen.adb.core

import java.util.Collections

data class WirelessObservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Observation identifier must not be blank." }
    }

    override fun toString(): String = "WirelessObservationId(redacted)"
}

data class WirelessNetworkKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Network identifier must not be blank." }
    }

    override fun toString(): String = "WirelessNetworkKey(redacted)"
}

data class VerifiedWirelessDeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Verified device identifier must not be blank." }
    }

    override fun toString(): String = "VerifiedWirelessDeviceId(redacted)"
}

class WirelessDiscoveryTarget(
    val generation: Long,
    val observationId: WirelessObservationId,
) {
    init {
        require(generation > 0L) { "Discovery target generation must be positive." }
    }

    override fun equals(other: Any?): Boolean =
        other is WirelessDiscoveryTarget &&
            generation == other.generation &&
            observationId == other.observationId

    override fun hashCode(): Int = 31 * generation.hashCode() + observationId.hashCode()

    override fun toString(): String = "WirelessDiscoveryTarget(generation=$generation, observationId=redacted)"
}

sealed interface WirelessAddress {
    data class Ipv4(
        val firstOctet: Int,
        val secondOctet: Int,
        val thirdOctet: Int,
        val fourthOctet: Int,
    ) : WirelessAddress {
        init {
            listOf(firstOctet, secondOctet, thirdOctet, fourthOctet).forEach {
                require(it in 0..255) { "IPv4 octets must be between 0 and 255." }
            }
        }

        override fun toString(): String = "WirelessAddress.Ipv4(redacted)"
    }

    class Ipv6(
        segments: List<Int>,
        val scopeId: String? = null,
    ) : WirelessAddress {
        val segments: List<Int> = immutableList(segments)

        init {
            require(segments.size == 8) { "IPv6 addresses must have exactly eight segments." }
            require(segments.all { it in 0..0xffff }) {
                "IPv6 segments must be between 0 and 65535."
            }
            require(scopeId == null || scopeId.isNotBlank()) { "IPv6 scope identifier must not be blank." }
        }

        fun copy(
            segments: List<Int> = this.segments,
            scopeId: String? = this.scopeId,
        ): Ipv6 = Ipv6(segments, scopeId)

        override fun equals(other: Any?): Boolean =
            other is Ipv6 && segments == other.segments && scopeId == other.scopeId

        override fun hashCode(): Int = 31 * segments.hashCode() + (scopeId?.hashCode() ?: 0)

        override fun toString(): String = "WirelessAddress.Ipv6(redacted)"
    }
}

enum class WirelessServiceType {
    PAIRING,
    CONNECT;

    companion object {
        fun fromDnsSdType(value: String): WirelessServiceType? = when (value) {
            "_adb-tls-pairing._tcp" -> PAIRING
            "_adb-tls-connect._tcp" -> CONNECT
            else -> null
        }
    }
}

enum class WirelessServiceStatus {
    DISCOVERED,
    RESOLVING,
    RESOLVED,
    LOST,
    UNREACHABLE,
    FAILED,
}

class WirelessServiceObservation(
    val observationId: WirelessObservationId,
    val serviceType: WirelessServiceType,
    val serviceName: String,
    addresses: List<WirelessAddress>,
    val port: Int,
    val status: WirelessServiceStatus,
    val verifiedDeviceId: VerifiedWirelessDeviceId? = null,
    val lastSeenAt: Long,
) {
    val addresses: List<WirelessAddress> = immutableList(addresses)

    init {
        require(serviceName.isNotBlank()) { "Service name must not be blank." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }
    }

    fun copy(
        observationId: WirelessObservationId = this.observationId,
        serviceType: WirelessServiceType = this.serviceType,
        serviceName: String = this.serviceName,
        addresses: List<WirelessAddress> = this.addresses,
        port: Int = this.port,
        status: WirelessServiceStatus = this.status,
        verifiedDeviceId: VerifiedWirelessDeviceId? = this.verifiedDeviceId,
        lastSeenAt: Long = this.lastSeenAt,
    ): WirelessServiceObservation = WirelessServiceObservation(
        observationId = observationId,
        serviceType = serviceType,
        serviceName = serviceName,
        addresses = addresses,
        port = port,
        status = status,
        verifiedDeviceId = verifiedDeviceId,
        lastSeenAt = lastSeenAt,
    )

    override fun equals(other: Any?): Boolean =
        other is WirelessServiceObservation &&
            observationId == other.observationId &&
            serviceType == other.serviceType &&
            serviceName == other.serviceName &&
            addresses == other.addresses &&
            port == other.port &&
            status == other.status &&
            verifiedDeviceId == other.verifiedDeviceId &&
            lastSeenAt == other.lastSeenAt

    override fun hashCode(): Int {
        var result = observationId.hashCode()
        result = 31 * result + serviceType.hashCode()
        result = 31 * result + serviceName.hashCode()
        result = 31 * result + addresses.hashCode()
        result = 31 * result + port
        result = 31 * result + status.hashCode()
        result = 31 * result + (verifiedDeviceId?.hashCode() ?: 0)
        result = 31 * result + lastSeenAt.hashCode()
        return result
    }

    override fun toString(): String = "WirelessServiceObservation(redacted)"
}

class WirelessDisplayDevice(
    val verifiedDeviceId: VerifiedWirelessDeviceId?,
    observations: List<WirelessServiceObservation>,
) {
    val observations: List<WirelessServiceObservation> = immutableList(observations)
    val serviceTypes: Set<WirelessServiceType> = immutableSet(this.observations.map { it.serviceType })

    init {
        require(this.observations.isNotEmpty()) { "A display device must contain an observation." }
        if (verifiedDeviceId == null) {
            require(this.observations.size == 1 && this.observations.single().verifiedDeviceId == null) {
                "An unknown display device must contain exactly one unknown observation."
            }
        } else {
            require(this.observations.all { it.verifiedDeviceId == verifiedDeviceId }) {
                "A verified display device must contain observations for one verified identity."
            }
        }
    }

    fun copy(
        verifiedDeviceId: VerifiedWirelessDeviceId? = this.verifiedDeviceId,
        observations: List<WirelessServiceObservation> = this.observations,
    ): WirelessDisplayDevice = WirelessDisplayDevice(verifiedDeviceId, observations)

    override fun equals(other: Any?): Boolean =
        other is WirelessDisplayDevice &&
            verifiedDeviceId == other.verifiedDeviceId &&
            observations == other.observations

    override fun hashCode(): Int = 31 * (verifiedDeviceId?.hashCode() ?: 0) + observations.hashCode()

    override fun toString(): String = "WirelessDisplayDevice(redacted)"
}

class WirelessDiscoveryState(
    val generation: Long,
    val networkKey: WirelessNetworkKey? = null,
    services: List<WirelessServiceObservation> = emptyList(),
) {
    val services: List<WirelessServiceObservation> = immutableList(services).also(::requireUniqueObservationIds)
    val devices: List<WirelessDisplayDevice> = immutableList(displayDevicesFor(this.services))

    fun copy(
        generation: Long = this.generation,
        networkKey: WirelessNetworkKey? = this.networkKey,
        services: List<WirelessServiceObservation> = this.services,
    ): WirelessDiscoveryState = WirelessDiscoveryState(generation, networkKey, services)

    override fun equals(other: Any?): Boolean =
        other is WirelessDiscoveryState &&
            generation == other.generation &&
            networkKey == other.networkKey &&
            services == other.services

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + (networkKey?.hashCode() ?: 0)
        result = 31 * result + services.hashCode()
        return result
    }

    override fun toString(): String =
        "WirelessDiscoveryState(generation=$generation, serviceCount=${services.size}, deviceCount=${devices.size})"
}

enum class WirelessDiscoveryMode {
    LAN_FOREGROUND,
    LOCAL_PAIRING,
}

enum class WirelessDiscoveryPhase {
    IDLE,
    STARTING,
    DISCOVERING,
    STOPPING,
    STOPPED,
    FAILED,
}

class WirelessDiscoverySession(
    val generation: Long,
    val mode: WirelessDiscoveryMode,
    val networkKey: WirelessNetworkKey,
    val startedAt: Long,
    val deadline: Long,
    val phase: WirelessDiscoveryPhase,
    services: List<WirelessServiceObservation> = emptyList(),
) {
    val services: List<WirelessServiceObservation> = immutableList(services).also(::requireUniqueObservationIds)

    init {
        require(deadline >= startedAt) { "Discovery deadline must not precede its start." }
    }

    fun copy(
        generation: Long = this.generation,
        mode: WirelessDiscoveryMode = this.mode,
        networkKey: WirelessNetworkKey = this.networkKey,
        startedAt: Long = this.startedAt,
        deadline: Long = this.deadline,
        phase: WirelessDiscoveryPhase = this.phase,
        services: List<WirelessServiceObservation> = this.services,
    ): WirelessDiscoverySession = WirelessDiscoverySession(
        generation = generation,
        mode = mode,
        networkKey = networkKey,
        startedAt = startedAt,
        deadline = deadline,
        phase = phase,
        services = services,
    )

    override fun equals(other: Any?): Boolean =
        other is WirelessDiscoverySession &&
            generation == other.generation &&
            mode == other.mode &&
            networkKey == other.networkKey &&
            startedAt == other.startedAt &&
            deadline == other.deadline &&
            phase == other.phase &&
            services == other.services

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + networkKey.hashCode()
        result = 31 * result + startedAt.hashCode()
        result = 31 * result + deadline.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + services.hashCode()
        return result
    }

    override fun toString(): String =
        "WirelessDiscoverySession(generation=$generation, phase=$phase, serviceCount=${services.size})"
}

sealed interface WirelessDiscoveryEvent {
    val generation: Long

    data class ServiceObserved(
        override val generation: Long,
        val observation: WirelessServiceObservation,
    ) : WirelessDiscoveryEvent {
        override fun toString(): String = "WirelessDiscoveryEvent.ServiceObserved(redacted)"
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun requireUniqueObservationIds(services: List<WirelessServiceObservation>) {
    require(services.map { it.observationId }.toSet().size == services.size) {
        "Discovery services must have unique observation identifiers."
    }
}

private fun displayDevicesFor(
    services: List<WirelessServiceObservation>,
): List<WirelessDisplayDevice> {
    val observationsByGroup = linkedMapOf<DisplayDeviceGroupKey, MutableList<WirelessServiceObservation>>()
    services.forEach { observation ->
        val groupKey = observation.verifiedDeviceId?.let(DisplayDeviceGroupKey::Verified)
            ?: DisplayDeviceGroupKey.Unknown(observation.observationId)
        observationsByGroup.getOrPut(groupKey) { mutableListOf() }.add(observation)
    }
    return observationsByGroup.map { (groupKey, observations) ->
        WirelessDisplayDevice(
            verifiedDeviceId = (groupKey as? DisplayDeviceGroupKey.Verified)?.value,
            observations = observations,
        )
    }
}

private sealed interface DisplayDeviceGroupKey {
    data class Verified(val value: VerifiedWirelessDeviceId) : DisplayDeviceGroupKey

    data class Unknown(val value: WirelessObservationId) : DisplayDeviceGroupKey
}
