package com.sheen.adb.feature.devices

import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret

internal enum class DevicesPairingFailure {
    UNSUPPORTED,
    EXPIRED,
    INVALID_CODE,
    CANCELLED,
    EXPLICIT_FAILURE,
}

internal data class DevicesPairingState(
    val method: PairingMethod = PairingMethod.NONE,
    val phase: PairingAttemptPhase = PairingAttemptPhase.IDLE,
    val codeInput: String = "",
    val qrMatrix: QrMatrix? = null,
    val failure: DevicesPairingFailure? = null,
    val codeFallbackAvailable: Boolean = false,
    val hasActiveSession: Boolean = false,
    val awaitingSessionReplacementConfirmation: Boolean = false,
) {
    override fun toString(): String =
        "DevicesPairingState(method=$method, phase=$phase, codeLength=${codeInput.length}, " +
            "hasQrMatrix=${qrMatrix != null}, failure=$failure, codeFallbackAvailable=$codeFallbackAvailable, " +
            "hasActiveSession=$hasActiveSession, " +
            "awaitingSessionReplacementConfirmation=$awaitingSessionReplacementConfirmation)"
}

internal sealed interface DevicesPairingEvent {
    data class SelectMethod(val method: PairingMethod) : DevicesPairingEvent

    data object StartRequested : DevicesPairingEvent

    class QrPrepared(val matrix: QrMatrix) : DevicesPairingEvent {
        override fun toString(): String = "QrPrepared(redacted)"
    }

    class CodeChanged(val value: String) : DevicesPairingEvent {
        override fun toString(): String = "CodeChanged(redacted)"
    }

    data object SubmitCode : DevicesPairingEvent
    data object PairingStarted : DevicesPairingEvent
    data object Succeeded : DevicesPairingEvent
    data object Failed : DevicesPairingEvent
    data object Cancelled : DevicesPairingEvent
    data object Expired : DevicesPairingEvent
    data object Unsupported : DevicesPairingEvent
    data object UseCodeFallback : DevicesPairingEvent

    data class SessionAvailabilityChanged(
        val hasActiveSession: Boolean,
    ) : DevicesPairingEvent

    data object ConfirmSessionReplacement : DevicesPairingEvent
    data object DismissSessionReplacement : DevicesPairingEvent
}

internal sealed interface DevicesPairingEffect {
    data class Begin(val method: PairingMethod) : DevicesPairingEffect

    class SubmitCode(val secret: PairingSecret) : DevicesPairingEffect {
        override fun toString(): String = "SubmitCode(redacted)"
    }

    data class DisconnectSessionAndBegin(
        val method: PairingMethod,
    ) : DevicesPairingEffect

    data object CancelCurrent : DevicesPairingEffect
}

internal data class DevicesPairingReduction(
    val state: DevicesPairingState,
    val effects: List<DevicesPairingEffect> = emptyList(),
)
