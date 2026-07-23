package com.sheen.adb.feature.devices

import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.LocalPairingDiscoveryStatus
import com.sheen.adb.core.LocalPairingNotificationState

internal class DevicesPairingReducer {
    fun reduce(
        state: DevicesPairingState,
        event: DevicesPairingEvent,
    ): DevicesPairingReduction {
        if (state.phase.isTerminal() && !event.isAllowedAfterTerminal()) {
            return DevicesPairingReduction(state)
        }
        return when (event) {
            is DevicesPairingEvent.SelectMethod -> selectMethod(state, event.method)
            DevicesPairingEvent.StartRequested -> start(state)
            is DevicesPairingEvent.QrPrepared -> qrPrepared(state, event.matrix)
            is DevicesPairingEvent.CodeChanged -> codeChanged(state, event.value)
            DevicesPairingEvent.SubmitCode -> submitCode(state)
            DevicesPairingEvent.PairingStarted -> pairingStarted(state)
            DevicesPairingEvent.Succeeded -> terminal(state, PairingAttemptPhase.SUCCEEDED, null)
            DevicesPairingEvent.Failed -> {
                terminal(state, PairingAttemptPhase.FAILED, DevicesPairingFailure.EXPLICIT_FAILURE)
            }
            DevicesPairingEvent.Cancelled -> terminal(
                state,
                PairingAttemptPhase.CANCELLED,
                DevicesPairingFailure.CANCELLED,
                DevicesPairingEffect.CancelCurrent,
            )
            DevicesPairingEvent.Expired -> terminal(
                state,
                PairingAttemptPhase.EXPIRED,
                DevicesPairingFailure.EXPIRED,
                DevicesPairingEffect.CancelCurrent,
            )
            DevicesPairingEvent.Unsupported -> terminal(
                state,
                PairingAttemptPhase.UNSUPPORTED,
                DevicesPairingFailure.UNSUPPORTED,
                DevicesPairingEffect.CancelCurrent,
                codeFallbackAvailable = state.method == PairingMethod.QR,
            )
            DevicesPairingEvent.UseCodeFallback -> useCodeFallback(state)
            is DevicesPairingEvent.SessionAvailabilityChanged -> DevicesPairingReduction(
                state.copy(
                    hasActiveSession = event.hasActiveSession,
                    awaitingSessionReplacementConfirmation =
                        state.awaitingSessionReplacementConfirmation && event.hasActiveSession,
                ),
            )
            DevicesPairingEvent.ConfirmSessionReplacement -> confirmSessionReplacement(state)
            DevicesPairingEvent.DismissSessionReplacement -> DevicesPairingReduction(
                state.copy(awaitingSessionReplacementConfirmation = false),
            )
            DevicesPairingEvent.EnterLocalMode -> enterLocalMode(state)
            is DevicesPairingEvent.LocalDiscoveryChanged -> localDiscoveryChanged(state, event.status)
            is DevicesPairingEvent.LocalNotificationChanged -> localNotificationChanged(
                state,
                event.state,
                event.suggestNativeNotificationStyle,
            )
            is DevicesPairingEvent.NotificationPermissionResult -> notificationPermissionResult(
                state,
                event.granted,
            )
            DevicesPairingEvent.RetryLocalMode -> retryLocalMode(state)
            is DevicesPairingEvent.LocalPageLeft -> localPageLeft(state, event.openingWirelessSettings)
        }
    }

    private fun selectMethod(
        state: DevicesPairingState,
        method: PairingMethod,
    ): DevicesPairingReduction {
        if (method == PairingMethod.NONE) return DevicesPairingReduction(DevicesPairingState())
        return DevicesPairingReduction(
            state.copy(
                method = method,
                phase = PairingAttemptPhase.IDLE,
                codeInput = "",
                qrMatrix = null,
                failure = null,
                codeFallbackAvailable = false,
                awaitingSessionReplacementConfirmation = false,
                isLocalMode = false,
                localDiscoveryStatus = LocalPairingDiscoveryStatus.IDLE,
                localNotificationState = LocalPairingNotificationState.HIDDEN,
                applicationInputAvailable = false,
                suggestNativeNotificationStyle = false,
                localWindowActive = false,
                requiresLocalTargetSelection = false,
            ),
        )
    }

    private fun enterLocalMode(state: DevicesPairingState): DevicesPairingReduction {
        val requestNotificationPermission = !state.notificationPermissionRequested
        return DevicesPairingReduction(
            state = state.copy(
                method = PairingMethod.SIX_DIGIT_CODE,
                phase = PairingAttemptPhase.WAITING_FOR_CODE,
                codeInput = "",
                qrMatrix = null,
                failure = null,
                codeFallbackAvailable = false,
                awaitingSessionReplacementConfirmation = false,
                isLocalMode = true,
                localDiscoveryStatus = LocalPairingDiscoveryStatus.SEARCHING,
                localNotificationState = LocalPairingNotificationState.HIDDEN,
                applicationInputAvailable = true,
                notificationPermissionRequested = true,
                suggestNativeNotificationStyle = false,
                localWindowActive = true,
                requiresLocalTargetSelection = false,
            ),
            effects = buildList {
                add(DevicesPairingEffect.StartLocalWindow)
                if (requestNotificationPermission) add(DevicesPairingEffect.RequestNotificationPermission)
            },
        )
    }

    private fun localDiscoveryChanged(
        state: DevicesPairingState,
        status: LocalPairingDiscoveryStatus,
    ): DevicesPairingReduction {
        if (!state.isLocalMode) return DevicesPairingReduction(state)
        return DevicesPairingReduction(
            state.copy(
                localDiscoveryStatus = status,
                applicationInputAvailable = true,
                requiresLocalTargetSelection = status == LocalPairingDiscoveryStatus.AMBIGUOUS,
            ),
        )
    }

    private fun localNotificationChanged(
        state: DevicesPairingState,
        notificationState: LocalPairingNotificationState,
        suggestNativeNotificationStyle: Boolean,
    ): DevicesPairingReduction {
        if (!state.isLocalMode) return DevicesPairingReduction(state)
        return DevicesPairingReduction(
            state.copy(
                localNotificationState = notificationState,
                applicationInputAvailable = true,
                suggestNativeNotificationStyle = suggestNativeNotificationStyle,
            ),
        )
    }

    private fun notificationPermissionResult(
        state: DevicesPairingState,
        granted: Boolean,
    ): DevicesPairingReduction {
        if (!state.isLocalMode) return DevicesPairingReduction(state)
        return DevicesPairingReduction(
            state.copy(
                notificationPermissionRequested = true,
                localNotificationState = if (granted) {
                    LocalPairingNotificationState.HIDDEN
                } else {
                    LocalPairingNotificationState.INPUT_UNAVAILABLE
                },
                applicationInputAvailable = true,
            ),
        )
    }

    private fun retryLocalMode(state: DevicesPairingState): DevicesPairingReduction {
        if (!state.isLocalMode) return DevicesPairingReduction(state)
        val requestNotificationPermission = !state.notificationPermissionRequested
        return DevicesPairingReduction(
            state = state.copy(
                method = PairingMethod.SIX_DIGIT_CODE,
                phase = PairingAttemptPhase.WAITING_FOR_CODE,
                codeInput = "",
                qrMatrix = null,
                failure = null,
                localDiscoveryStatus = LocalPairingDiscoveryStatus.SEARCHING,
                localNotificationState = LocalPairingNotificationState.HIDDEN,
                applicationInputAvailable = true,
                notificationPermissionRequested = true,
                suggestNativeNotificationStyle = false,
                localWindowActive = true,
                requiresLocalTargetSelection = false,
            ),
            effects = buildList {
                add(DevicesPairingEffect.StartLocalWindow)
                if (requestNotificationPermission) add(DevicesPairingEffect.RequestNotificationPermission)
            },
        )
    }

    private fun localPageLeft(
        state: DevicesPairingState,
        openingWirelessSettings: Boolean,
    ): DevicesPairingReduction {
        if (openingWirelessSettings && state.localWindowActive) {
            return DevicesPairingReduction(state, listOf(DevicesPairingEffect.KeepLocalWindow))
        }
        return DevicesPairingReduction(
            state = state.copy(
                phase = PairingAttemptPhase.CANCELLED,
                codeInput = "",
                qrMatrix = null,
                failure = DevicesPairingFailure.CANCELLED,
                localWindowActive = false,
                localNotificationState = LocalPairingNotificationState.HIDDEN,
                requiresLocalTargetSelection = false,
            ),
            effects = listOf(DevicesPairingEffect.StopLocalWindow),
        )
    }

    private fun start(state: DevicesPairingState): DevicesPairingReduction {
        if (state.method == PairingMethod.NONE) return DevicesPairingReduction(state)
        if (state.hasActiveSession) {
            return DevicesPairingReduction(state.copy(awaitingSessionReplacementConfirmation = true))
        }
        return begin(state, DevicesPairingEffect.Begin(state.method))
    }

    private fun begin(
        state: DevicesPairingState,
        effect: DevicesPairingEffect,
    ): DevicesPairingReduction = DevicesPairingReduction(
        state = state.copy(
            phase = state.method.beginPhase(),
            codeInput = "",
            qrMatrix = null,
            failure = null,
            codeFallbackAvailable = false,
            awaitingSessionReplacementConfirmation = false,
        ),
        effects = listOf(effect),
    )

    private fun qrPrepared(
        state: DevicesPairingState,
        matrix: QrMatrix,
    ): DevicesPairingReduction {
        if (state.method != PairingMethod.QR || state.phase != PairingAttemptPhase.PREPARING) {
            return DevicesPairingReduction(state)
        }
        return DevicesPairingReduction(
            state.copy(
                phase = PairingAttemptPhase.WAITING_FOR_TARGET,
                qrMatrix = matrix,
                failure = null,
            ),
        )
    }

    private fun codeChanged(
        state: DevicesPairingState,
        value: String,
    ): DevicesPairingReduction {
        if (state.method != PairingMethod.SIX_DIGIT_CODE || state.phase != PairingAttemptPhase.WAITING_FOR_CODE) {
            return DevicesPairingReduction(state)
        }
        return DevicesPairingReduction(
            state.copy(
                codeInput = value.filter { it in '0'..'9' }.take(SIX_DIGIT_CODE_LENGTH),
                failure = null,
            ),
        )
    }

    private fun submitCode(state: DevicesPairingState): DevicesPairingReduction {
        if (state.method != PairingMethod.SIX_DIGIT_CODE || state.phase != PairingAttemptPhase.WAITING_FOR_CODE) {
            return DevicesPairingReduction(state.copy(codeInput = ""))
        }
        val code = state.codeInput
        if (code.length != SIX_DIGIT_CODE_LENGTH || !code.all { it in '0'..'9' }) {
            return DevicesPairingReduction(
                state.copy(codeInput = "", failure = DevicesPairingFailure.INVALID_CODE),
            )
        }
        return DevicesPairingReduction(
            state = state.copy(
                phase = PairingAttemptPhase.PAIRING,
                codeInput = "",
                failure = null,
            ),
            effects = listOf(DevicesPairingEffect.SubmitCode(PairingSecret(code.toCharArray()))),
        )
    }

    private fun pairingStarted(state: DevicesPairingState): DevicesPairingReduction {
        if (state.method == PairingMethod.NONE) return DevicesPairingReduction(state)
        return DevicesPairingReduction(
            state.copy(phase = PairingAttemptPhase.PAIRING, codeInput = "", failure = null),
        )
    }

    private fun useCodeFallback(state: DevicesPairingState): DevicesPairingReduction {
        if (!state.codeFallbackAvailable || state.failure != DevicesPairingFailure.UNSUPPORTED) {
            return DevicesPairingReduction(state)
        }
        val codeState = state.copy(
            method = PairingMethod.SIX_DIGIT_CODE,
            phase = PairingAttemptPhase.IDLE,
            codeInput = "",
            qrMatrix = null,
            failure = null,
            codeFallbackAvailable = false,
        )
        if (codeState.hasActiveSession) {
            return DevicesPairingReduction(
                codeState.copy(awaitingSessionReplacementConfirmation = true),
            )
        }
        return begin(codeState, DevicesPairingEffect.Begin(PairingMethod.SIX_DIGIT_CODE))
    }

    private fun confirmSessionReplacement(state: DevicesPairingState): DevicesPairingReduction {
        if (!state.awaitingSessionReplacementConfirmation || !state.hasActiveSession || state.method == PairingMethod.NONE) {
            return DevicesPairingReduction(state)
        }
        return begin(state, DevicesPairingEffect.DisconnectSessionAndBegin(state.method))
    }

    private fun terminal(
        state: DevicesPairingState,
        phase: PairingAttemptPhase,
        failure: DevicesPairingFailure?,
        vararg effects: DevicesPairingEffect,
        codeFallbackAvailable: Boolean = false,
    ): DevicesPairingReduction = DevicesPairingReduction(
        state = state.copy(
            phase = phase,
            codeInput = "",
            qrMatrix = null,
            failure = failure,
            codeFallbackAvailable = codeFallbackAvailable,
            awaitingSessionReplacementConfirmation = false,
        ),
        effects = effects.toList(),
    )

    private fun PairingMethod.beginPhase(): PairingAttemptPhase = when (this) {
        PairingMethod.QR -> PairingAttemptPhase.PREPARING
        PairingMethod.SIX_DIGIT_CODE -> PairingAttemptPhase.WAITING_FOR_CODE
        PairingMethod.NONE -> PairingAttemptPhase.IDLE
    }

    private fun PairingAttemptPhase.isTerminal(): Boolean = this in TERMINAL_PHASES

    private fun DevicesPairingEvent.isAllowedAfterTerminal(): Boolean =
        this is DevicesPairingEvent.SelectMethod ||
            this == DevicesPairingEvent.UseCodeFallback ||
            this is DevicesPairingEvent.SessionAvailabilityChanged ||
            this == DevicesPairingEvent.EnterLocalMode ||
            this == DevicesPairingEvent.RetryLocalMode ||
            this is DevicesPairingEvent.LocalPageLeft

    private companion object {
        const val SIX_DIGIT_CODE_LENGTH = 6
        val TERMINAL_PHASES = setOf(
            PairingAttemptPhase.SUCCEEDED,
            PairingAttemptPhase.CANCELLED,
            PairingAttemptPhase.EXPIRED,
            PairingAttemptPhase.FAILED,
            PairingAttemptPhase.UNSUPPORTED,
        )
    }
}
