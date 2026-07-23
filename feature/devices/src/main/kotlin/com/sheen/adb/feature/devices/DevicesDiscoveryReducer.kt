package com.sheen.adb.feature.devices

import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.WirelessDiscoveryTarget
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType

internal class DevicesDiscoveryReducer {
    fun reduce(
        state: DevicesDiscoveryState,
        event: DevicesDiscoveryEvent,
    ): DevicesDiscoveryReduction = when (event) {
        is DevicesDiscoveryEvent.Start -> DevicesDiscoveryReduction(
            DevicesDiscoveryState(
                phase = DevicesDiscoveryPhase.SCANNING,
                generation = event.generation,
            ),
        )
        is DevicesDiscoveryEvent.Snapshot -> snapshot(state, event.value)
        is DevicesDiscoveryEvent.Failed -> DevicesDiscoveryReduction(
            state.copy(
                phase = DevicesDiscoveryPhase.ERROR,
                failure = event.failure,
                pendingSelection = null,
            ),
        )
        DevicesDiscoveryEvent.Cancelled -> DevicesDiscoveryReduction(
            state.copy(
                phase = DevicesDiscoveryPhase.CANCELLED,
                failure = null,
                pendingSelection = null,
            ),
        )
        DevicesDiscoveryEvent.UseManualAddress -> DevicesDiscoveryReduction(
            state,
            listOf(DevicesDiscoveryEffect.OpenManualAddress),
        )
        is DevicesDiscoveryEvent.SelectPairing -> select(
            state,
            DevicesDiscoverySelection.Pairing(event.target),
        )
        is DevicesDiscoveryEvent.SelectConnect -> select(
            state,
            DevicesDiscoverySelection.Connect(event.target),
        )
        DevicesDiscoveryEvent.ConfirmSelection -> confirm(state)
        DevicesDiscoveryEvent.DismissSelection -> DevicesDiscoveryReduction(
            state.copy(pendingSelection = null),
        )
    }

    private fun snapshot(
        state: DevicesDiscoveryState,
        snapshot: WirelessDiscoveryState,
    ): DevicesDiscoveryReduction {
        if (snapshot.generation != state.generation) return DevicesDiscoveryReduction(state)
        val items = snapshot.devices.map { device ->
            val observations = device.observations
            val pairing = observations.firstOrNull { it.serviceType == WirelessServiceType.PAIRING }
            val connect = observations.firstOrNull { it.serviceType == WirelessServiceType.CONNECT }
            val reachability = observations.reachability()
            val pairingTarget = pairing.resolvedTarget(snapshot.generation)
            val connectTarget = connect.resolvedTarget(snapshot.generation)
            val preferredEndpoint = connect ?: pairing ?: observations.first()
            DevicesDiscoveryItem(
                serviceTypes = device.serviceTypes,
                pairingTarget = pairingTarget,
                connectTarget = connectTarget,
                endpointLabel = preferredEndpoint.endpointLabel(),
                relation = if (device.verifiedDeviceId == null) {
                    DevicesDiscoveryRelation.UNKNOWN
                } else {
                    DevicesDiscoveryRelation.VERIFIED
                },
                reachability = reachability,
                selectable = pairingTarget != null || connectTarget != null,
            )
        }
        return DevicesDiscoveryReduction(
            state.copy(
                phase = if (items.isEmpty()) DevicesDiscoveryPhase.EMPTY else DevicesDiscoveryPhase.CONTENT,
                items = items,
                failure = null,
                pendingSelection = null,
            ),
        )
    }

    private fun select(
        state: DevicesDiscoveryState,
        selection: DevicesDiscoverySelection,
    ): DevicesDiscoveryReduction {
        val valid = state.items.any { item ->
            item.selectable && when (selection) {
                is DevicesDiscoverySelection.Pairing -> item.pairingTarget == selection.target
                is DevicesDiscoverySelection.Connect -> item.connectTarget == selection.target
            }
        }
        return if (valid) {
            DevicesDiscoveryReduction(state.copy(pendingSelection = selection))
        } else {
            DevicesDiscoveryReduction(state)
        }
    }

    private fun confirm(state: DevicesDiscoveryState): DevicesDiscoveryReduction {
        val selection = state.pendingSelection ?: return DevicesDiscoveryReduction(state)
        val effect = when (selection) {
            is DevicesDiscoverySelection.Pairing -> DevicesDiscoveryEffect.OpenCodePairing(selection.target)
            is DevicesDiscoverySelection.Connect -> DevicesDiscoveryEffect.Connect(selection.target)
        }
        return DevicesDiscoveryReduction(
            state.copy(pendingSelection = null),
            listOf(effect),
        )
    }

    private fun List<WirelessServiceObservation>.reachability(): DevicesDiscoveryReachability = when {
        any { it.status == WirelessServiceStatus.RESOLVED } -> DevicesDiscoveryReachability.RESOLVED
        any { it.status == WirelessServiceStatus.LOST } -> DevicesDiscoveryReachability.LOST
        else -> DevicesDiscoveryReachability.UNAVAILABLE
    }

    private fun WirelessServiceObservation?.resolvedTarget(generation: Long): WirelessDiscoveryTarget? =
        this?.takeIf { it.status == WirelessServiceStatus.RESOLVED && it.addresses.isNotEmpty() }
            ?.let { WirelessDiscoveryTarget(generation, it.observationId) }

    private fun WirelessServiceObservation.endpointLabel(): String {
        val family = when (addresses.firstOrNull()) {
            is WirelessAddress.Ipv4 -> "IPv4"
            is WirelessAddress.Ipv6 -> "IPv6"
            null -> "地址不可用"
        }
        return "$family · 端口 $port"
    }
}
