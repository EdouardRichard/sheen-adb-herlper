package com.sheen.adb.feature.devices

import com.sheen.adb.core.WirelessDiscoveryTarget
import com.sheen.adb.core.WirelessServiceType

internal enum class DevicesDiscoveryPhase {
    IDLE,
    SCANNING,
    CONTENT,
    EMPTY,
    ERROR,
    CANCELLED,
}

internal enum class DevicesDiscoveryFailure {
    NETWORK_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    RESOLUTION_FAILED,
    PLATFORM_FAILURE,
    TIMED_OUT,
    SESSION_CHANGED,
}

internal enum class DevicesDiscoveryRelation {
    VERIFIED,
    UNKNOWN,
}

internal enum class DevicesDiscoveryReachability {
    RESOLVED,
    LOST,
    UNAVAILABLE,
}

internal data class DevicesDiscoveryItem(
    val serviceTypes: Set<WirelessServiceType>,
    val pairingTarget: WirelessDiscoveryTarget?,
    val connectTarget: WirelessDiscoveryTarget?,
    val endpointLabel: String,
    val relation: DevicesDiscoveryRelation,
    val reachability: DevicesDiscoveryReachability,
    val selectable: Boolean,
) {
    override fun toString(): String =
        "DevicesDiscoveryItem(serviceTypes=$serviceTypes, hasPairingTarget=${pairingTarget != null}, " +
            "hasConnectTarget=${connectTarget != null}, relation=$relation, reachability=$reachability, " +
            "selectable=$selectable)"
}

internal sealed interface DevicesDiscoverySelection {
    val target: WirelessDiscoveryTarget

    data class Pairing(override val target: WirelessDiscoveryTarget) : DevicesDiscoverySelection

    data class Connect(override val target: WirelessDiscoveryTarget) : DevicesDiscoverySelection
}

internal data class DevicesDiscoveryState(
    val phase: DevicesDiscoveryPhase = DevicesDiscoveryPhase.IDLE,
    val generation: Long = 0L,
    val items: List<DevicesDiscoveryItem> = emptyList(),
    val failure: DevicesDiscoveryFailure? = null,
    val pendingSelection: DevicesDiscoverySelection? = null,
) {
    override fun toString(): String =
        "DevicesDiscoveryState(phase=$phase, generation=$generation, itemCount=${items.size}, " +
            "failure=$failure, hasPendingSelection=${pendingSelection != null})"
}

internal sealed interface DevicesDiscoveryEvent {
    data class Start(val generation: Long) : DevicesDiscoveryEvent

    data class Snapshot(val value: com.sheen.adb.core.WirelessDiscoveryState) : DevicesDiscoveryEvent

    data class Failed(val failure: DevicesDiscoveryFailure) : DevicesDiscoveryEvent

    data object Cancelled : DevicesDiscoveryEvent
    data object UseManualAddress : DevicesDiscoveryEvent

    data class SelectPairing(val target: WirelessDiscoveryTarget) : DevicesDiscoveryEvent

    data class SelectConnect(val target: WirelessDiscoveryTarget) : DevicesDiscoveryEvent

    data object ConfirmSelection : DevicesDiscoveryEvent
    data object DismissSelection : DevicesDiscoveryEvent
}

internal sealed interface DevicesDiscoveryEffect {
    data object OpenManualAddress : DevicesDiscoveryEffect

    data class OpenCodePairing(val target: WirelessDiscoveryTarget) : DevicesDiscoveryEffect

    data class Connect(val target: WirelessDiscoveryTarget) : DevicesDiscoveryEffect
}

internal data class DevicesDiscoveryReduction(
    val state: DevicesDiscoveryState,
    val effects: List<DevicesDiscoveryEffect> = emptyList(),
)
