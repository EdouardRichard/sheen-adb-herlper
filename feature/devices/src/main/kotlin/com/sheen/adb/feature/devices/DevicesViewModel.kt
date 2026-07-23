package com.sheen.adb.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbEndpointParser
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.EndpointParseResult
import com.sheen.adb.core.LocalPairingControllerState
import com.sheen.adb.core.LocalPairingStopReason
import com.sheen.adb.core.LocalPairingWindowId
import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.QrPairingMaterial
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoveryTarget
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import com.sheen.adb.data.DeviceProfile
import com.sheen.adb.data.DeviceProfileRepository
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

data class DevicesUiState(
    val endpointInput: String = "",
    val pairingEndpointInput: String = "",
    val pairingCode: String = "",
    val showPairing: Boolean = false,
    val showDiagnostics: Boolean = false,
    val inputError: String? = null,
    val notice: String? = null,
    val connectionState: AdbConnectionState = AdbConnectionState.Disconnected(),
    val profiles: List<DeviceProfile> = emptyList(),
    val diagnosticEvents: List<AdbDiagnosticEvent> = emptyList(),
    val pendingDeleteProfile: DeviceProfile? = null,
    val pendingRenameProfile: DeviceProfile? = null,
    val renameInput: String = "",
    val notificationPermissionRequestGeneration: Long = 0L,
    val awaitingDiscoverySessionReplacement: Boolean = false,
)

class DevicesViewModel(
    private val manager: AdbSessionManager,
    private val repository: DeviceProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private var pairingReducer = DevicesPairingReducer()
    private var qrEncoder = QrMatrixEncoder()
    private var pairingAttemptIdFactory: () -> PairingAttemptId = {
        PairingAttemptId.of(UUID.randomUUID().toString())
    }
    private var localPairingWindowIdFactory: () -> LocalPairingWindowId = {
        LocalPairingWindowId.of(UUID.randomUUID().toString())
    }
    private val discoveryReducer = DevicesDiscoveryReducer()

    internal constructor(
        manager: AdbSessionManager,
        repository: DeviceProfileRepository,
        clock: Clock,
        pairingReducer: DevicesPairingReducer,
        qrEncoder: QrMatrixEncoder,
        pairingAttemptIdFactory: () -> PairingAttemptId,
        localPairingWindowIdFactory: () -> LocalPairingWindowId = {
            LocalPairingWindowId.of(UUID.randomUUID().toString())
        },
    ) : this(manager, repository, clock) {
        this.pairingReducer = pairingReducer
        this.qrEncoder = qrEncoder
        this.pairingAttemptIdFactory = pairingAttemptIdFactory
        this.localPairingWindowIdFactory = localPairingWindowIdFactory
    }

    private val mutableState = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = mutableState.asStateFlow()
    private val mutablePairingState = MutableStateFlow(DevicesPairingState())
    internal val pairingState: StateFlow<DevicesPairingState> = mutablePairingState.asStateFlow()
    private val mutableDiscoveryState = MutableStateFlow(DevicesDiscoveryState())
    internal val discoveryState: StateFlow<DevicesDiscoveryState> = mutableDiscoveryState.asStateFlow()
    private var operation: Job? = null
    private var operationGeneration = 0L
    private var activeQrAttemptId: PairingAttemptId? = null
    private var activeLocalWindowId: LocalPairingWindowId? = null
    private var localPairingGeneration = 0L
    private var localSubmission: Job? = null
    private var discoveryJob: Job? = null
    private var discoveryCollectionGeneration = 0L
    private var discoveredPairingTarget: WirelessDiscoveryTarget? = null
    private var discoveredPairingAttemptId: PairingAttemptId? = null
    private var lastSuccessfulDiscoveredPairingAttemptId: PairingAttemptId? = null
    private var pendingDiscoveryConnectTarget: WirelessDiscoveryTarget? = null

    init {
        viewModelScope.launch {
            manager.connectionState.collect { connection ->
                mutableState.update { it.copy(connectionState = connection) }
                val pairingWasActive = activeQrAttemptId != null
                reducePairing(
                    DevicesPairingEvent.SessionAvailabilityChanged(
                        hasActiveSession = connection is AdbConnectionState.Connected,
                    ),
                )
                if (pairingWasActive && connection is AdbConnectionState.Connected) {
                    cancelPairingOperation(markCancelled = true)
                }
            }
        }
        viewModelScope.launch {
            manager.diagnosticEvents.collect { events -> mutableState.update { it.copy(diagnosticEvents = events) } }
        }
        viewModelScope.launch {
            repository.profiles.collect { profiles -> mutableState.update { it.copy(profiles = profiles) } }
        }
        viewModelScope.launch {
            manager.localPairingController.state.collect(::handleLocalPairingControllerState)
        }
    }

    fun updateEndpoint(value: String) = mutableState.update { it.copy(endpointInput = value, inputError = null, notice = null) }

    fun prefillLocalhost() = mutableState.update {
        it.copy(endpointInput = "127.0.0.1:", inputError = null, notice = "请填写无线调试主页面显示的当前调试端口")
    }

    fun onDiscoveryForeground() {
        if (discoveryJob?.isActive == true) return
        startLanDiscovery()
    }

    fun refreshDiscovery() = startLanDiscovery()

    fun onDiscoveryBackground() = stopLanDiscovery(markCancelled = true)

    fun cancelDiscovery() = stopLanDiscovery(markCancelled = true)

    fun useManualDiscoveryAddress() {
        reduceDiscovery(DevicesDiscoveryEvent.UseManualAddress)
    }

    internal fun selectDiscoveryPairing(target: WirelessDiscoveryTarget) {
        reduceDiscovery(DevicesDiscoveryEvent.SelectPairing(target))
    }

    internal fun selectDiscoveryConnect(target: WirelessDiscoveryTarget) {
        reduceDiscovery(DevicesDiscoveryEvent.SelectConnect(target))
    }

    internal fun confirmDiscoverySelection() {
        reduceDiscovery(DevicesDiscoveryEvent.ConfirmSelection)
    }

    internal fun dismissDiscoverySelection() {
        reduceDiscovery(DevicesDiscoveryEvent.DismissSelection)
    }

    fun confirmDiscoverySessionReplacement() {
        val target = pendingDiscoveryConnectTarget ?: return
        mutableState.update { it.copy(awaitingDiscoverySessionReplacement = false) }
        startOperation { generation ->
            when (val disconnected = manager.disconnect()) {
                is AdbOperationResult.Success -> if (generation == operationGeneration) {
                    connectDiscoveredTarget(target, generation)
                }
                is AdbOperationResult.Failure -> mutableState.update {
                    it.copy(notice = disconnected.error.userMessage)
                }
                AdbOperationResult.Cancelled -> Unit
            }
        }
    }

    fun dismissDiscoverySessionReplacement() {
        pendingDiscoveryConnectTarget = null
        mutableState.update { it.copy(awaitingDiscoverySessionReplacement = false) }
    }

    fun connect() {
        val endpoint = parse(mutableState.value.endpointInput) ?: return
        connect(endpoint)
    }

    fun reconnect(profile: DeviceProfile) {
        val raw = if (':' in profile.host) "[${profile.host}]:${profile.debugPort}" else "${profile.host}:${profile.debugPort}"
        mutableState.update { it.copy(endpointInput = raw, inputError = null, notice = null) }
        connect(AdbEndpoint(profile.host, profile.debugPort))
    }

    private fun connect(endpoint: AdbEndpoint) = startOperation { generation ->
        mutableState.update { it.copy(showPairing = false, pairingCode = "", notice = null) }
        val result = manager.connect(endpoint)
        if (result is AdbOperationResult.Success && generation == operationGeneration) {
            repository.recordSuccessfulConnection(
                host = endpoint.host,
                port = endpoint.port,
                suggestedName = if (endpoint.host == "127.0.0.1") "本机设备" else endpoint.host,
                isLocal = endpoint.host == "127.0.0.1" || endpoint.host == "::1",
                identityReference = HOST_IDENTITY_REFERENCE,
                nowEpochMillis = clock.millis(),
            )
            mutableState.update { it.copy(notice = "连接成功") }
        }
    }

    fun cancelCurrentOperation() {
        cancelPairingOperation(markCancelled = hasNonTerminalPairing())
    }

    fun openPairing() {
        val error = (mutableState.value.connectionState as? AdbConnectionState.Error)?.error ?: return
        if (!error.allowsPairingFallback) return
        val host = (AdbEndpointParser.parse(mutableState.value.endpointInput) as? EndpointParseResult.Valid)?.endpoint?.host
        val prefix = host?.let { if (':' in it) "[$it]:" else "$it:" }.orEmpty()
        mutableState.update {
            it.copy(showPairing = true, pairingEndpointInput = prefix, pairingCode = "", inputError = null, notice = null)
        }
        selectPairingMethod(PairingMethod.SIX_DIGIT_CODE)
        startSelectedPairing()
    }

    fun closePairing() {
        onPairingPageLeft()
        discoveredPairingTarget = null
        discoveredPairingAttemptId = null
        mutableState.update {
            it.copy(showPairing = false, pairingCode = "", inputError = null)
        }
    }

    fun enterLocalPairingMode() {
        mutableState.update {
            it.copy(showPairing = true, pairingCode = "", inputError = null, notice = null)
        }
        reducePairing(DevicesPairingEvent.EnterLocalMode)
    }

    fun retryLocalPairingMode() {
        clearPairingCode()
        reducePairing(DevicesPairingEvent.RetryLocalMode)
    }

    fun onLocalNotificationPermissionResult(granted: Boolean) {
        reducePairing(DevicesPairingEvent.NotificationPermissionResult(granted), handleEffects = false)
    }

    fun onLocalWirelessSettingsOpened() {
        reducePairing(DevicesPairingEvent.LocalPageLeft(openingWirelessSettings = true))
    }

    fun updatePairingEndpoint(value: String) = mutableState.update {
        it.copy(pairingEndpointInput = value, inputError = null)
    }

    fun updatePairingCode(value: String) {
        val sanitized = value.filter { it in '0'..'9' }.take(SIX_DIGIT_CODE_LENGTH)
        mutableState.update { it.copy(pairingCode = sanitized, inputError = null) }
        reducePairing(DevicesPairingEvent.CodeChanged(sanitized))
    }

    fun pair() {
        if (mutablePairingState.value.isLocalMode) {
            submitLocalPairingCode()
            return
        }
        if (discoveredPairingTarget != null) {
            submitDiscoveredPairingCode()
            return
        }
        val current = mutableState.value
        val endpoint = parse(current.pairingEndpointInput) ?: return
        if (current.pairingCode.length != SIX_DIGIT_CODE_LENGTH) {
            mutableState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        val reduction = reducePairing(DevicesPairingEvent.SubmitCode, handleEffects = false)
        clearPairingCode()
        val submit = reduction.effects.singleOrNull() as? DevicesPairingEffect.SubmitCode
        if (submit == null) {
            mutableState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        startCodePairing(endpoint, submit)
    }

    internal fun selectPairingMethod(method: PairingMethod) {
        if (method != mutablePairingState.value.method && hasNonTerminalPairing()) {
            cancelPairingOperation(markCancelled = false)
        }
        reducePairing(DevicesPairingEvent.SelectMethod(method))
        mutableState.update { it.copy(pairingCode = "", inputError = null, notice = null) }
    }

    internal fun startSelectedPairing() {
        reducePairing(DevicesPairingEvent.StartRequested)
    }

    internal fun retryPairing() {
        val method = mutablePairingState.value.method
        if (method == PairingMethod.NONE) return
        cancelPairingOperation(markCancelled = false)
        reducePairing(DevicesPairingEvent.SelectMethod(method), handleEffects = false)
        reducePairing(DevicesPairingEvent.StartRequested)
    }

    internal fun onPairingPageLeft() {
        if (mutablePairingState.value.isLocalMode) {
            reducePairing(DevicesPairingEvent.LocalPageLeft(openingWirelessSettings = false))
            return
        }
        cancelPairingOperation(markCancelled = hasNonTerminalPairing())
    }

    fun submitLocalPairingCode() {
        val windowId = activeLocalWindowId
        if (windowId == null) {
            clearPairingCode()
            mutableState.update { it.copy(inputError = "本机配对窗口不可用，请重试") }
            return
        }
        val reduction = reducePairing(DevicesPairingEvent.SubmitCode, handleEffects = false)
        clearPairingCode()
        val submit = reduction.effects.singleOrNull() as? DevicesPairingEffect.SubmitCode
        if (submit == null) {
            mutableState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        val generation = localPairingGeneration
        localSubmission?.cancel()
        localSubmission = viewModelScope.launch {
            try {
                when (val result = manager.localPairingController.submit(windowId, submit.secret)) {
                    is AdbOperationResult.Success -> if (isCurrentLocalWindow(generation, windowId)) {
                        reducePairing(DevicesPairingEvent.Succeeded, handleEffects = false)
                    }
                    is AdbOperationResult.Failure -> if (isCurrentLocalWindow(generation, windowId)) {
                        reducePairing(terminalPairingEvent(result.error), handleEffects = false)
                    }
                    AdbOperationResult.Cancelled -> if (isCurrentLocalWindow(generation, windowId)) {
                        reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
                    }
                }
            } finally {
                submit.secret.clear()
                clearPairingCode()
            }
        }
    }

    internal fun confirmPairingSessionReplacement() {
        reducePairing(DevicesPairingEvent.ConfirmSessionReplacement)
    }

    internal fun dismissPairingSessionReplacement() {
        reducePairing(DevicesPairingEvent.DismissSessionReplacement)
    }

    fun disconnect() {
        cancelPairingOperation(markCancelled = hasNonTerminalPairing())
        startOperation { _ ->
            manager.disconnect()
            mutableState.update { it.copy(pairingCode = "", showPairing = false, notice = "已断开连接") }
        }
    }

    fun toggleDiagnostics() = mutableState.update { it.copy(showDiagnostics = !it.showDiagnostics) }
    fun clearDiagnostics() = manager.clearDiagnosticEvents()

    fun requestRename(profile: DeviceProfile) = mutableState.update {
        it.copy(pendingRenameProfile = profile, renameInput = profile.displayName)
    }

    fun updateRename(value: String) = mutableState.update { it.copy(renameInput = value.take(80)) }

    fun confirmRename() {
        val profile = mutableState.value.pendingRenameProfile ?: return
        val name = mutableState.value.renameInput
        viewModelScope.launch {
            repository.rename(profile.id, name)
            mutableState.update { it.copy(pendingRenameProfile = null, renameInput = "") }
        }
    }

    fun dismissRename() = mutableState.update { it.copy(pendingRenameProfile = null, renameInput = "") }

    fun requestDelete(profile: DeviceProfile) = mutableState.update { it.copy(pendingDeleteProfile = profile) }
    fun dismissDelete() = mutableState.update { it.copy(pendingDeleteProfile = null) }

    fun confirmDelete() {
        val profile = mutableState.value.pendingDeleteProfile ?: return
        viewModelScope.launch {
            repository.delete(profile.id)
            val remaining = repository.profiles.first()
            if (remaining.none { it.identityReference == profile.identityReference }) manager.clearHostIdentity()
            mutableState.update { it.copy(pendingDeleteProfile = null, notice = "设备档案及关联身份引用已删除") }
        }
    }

    private fun startCodePairing(
        endpoint: AdbEndpoint,
        effect: DevicesPairingEffect.SubmitCode,
    ) = startOperation { generation ->
        try {
            val result = manager.pairWithSecret(
                pairingEndpoint = endpoint,
                pairingSecret = effect.secret,
                method = PairingMethod.SIX_DIGIT_CODE,
            )
            if (generation != operationGeneration) return@startOperation
            when (result) {
                is AdbOperationResult.Success -> {
                    reducePairing(DevicesPairingEvent.Succeeded, handleEffects = false)
                    mutableState.update {
                        it.copy(
                            showPairing = false,
                            pairingCode = "",
                            notice = "配对成功。请填写无线调试主页面的调试端口后连接。",
                        )
                    }
                }
                is AdbOperationResult.Failure -> reducePairing(
                    terminalPairingEvent(result.error),
                    handleEffects = false,
                )
                AdbOperationResult.Cancelled -> reducePairing(
                    DevicesPairingEvent.Cancelled,
                    handleEffects = false,
                )
            }
        } finally {
            effect.secret.clear()
            clearPairingCode()
        }
    }

    private fun reducePairing(
        event: DevicesPairingEvent,
        handleEffects: Boolean = true,
    ): DevicesPairingReduction {
        val reduction = pairingReducer.reduce(mutablePairingState.value, event)
        mutablePairingState.value = reduction.state
        if (handleEffects) reduction.effects.forEach(::handlePairingEffect)
        return reduction
    }

    private fun handlePairingEffect(effect: DevicesPairingEffect) {
        when (effect) {
            is DevicesPairingEffect.Begin -> when (effect.method) {
                PairingMethod.QR -> startOperation(::runQrPairing)
                PairingMethod.SIX_DIGIT_CODE, PairingMethod.NONE -> Unit
            }
            is DevicesPairingEffect.DisconnectSessionAndBegin -> startOperation { generation ->
                when (val result = manager.disconnect()) {
                    is AdbOperationResult.Success -> {
                        if (generation != operationGeneration) return@startOperation
                        reducePairing(
                            DevicesPairingEvent.SessionAvailabilityChanged(hasActiveSession = false),
                            handleEffects = false,
                        )
                        if (effect.method == PairingMethod.QR) runQrPairing(generation)
                    }
                    is AdbOperationResult.Failure -> reducePairing(
                        terminalPairingEvent(result.error),
                        handleEffects = false,
                    )
                    AdbOperationResult.Cancelled -> reducePairing(
                        DevicesPairingEvent.Cancelled,
                        handleEffects = false,
                    )
                }
            }
            DevicesPairingEffect.CancelCurrent -> cancelPairingOperation(markCancelled = false)
            is DevicesPairingEffect.SubmitCode -> effect.secret.clear()
            DevicesPairingEffect.StartLocalWindow -> startLocalPairingWindow()
            DevicesPairingEffect.RequestNotificationPermission -> mutableState.update {
                it.copy(notificationPermissionRequestGeneration = it.notificationPermissionRequestGeneration + 1L)
            }
            DevicesPairingEffect.KeepLocalWindow -> Unit
            DevicesPairingEffect.StopLocalWindow -> stopLocalPairingWindow()
        }
    }

    private fun startLanDiscovery() {
        val previous = discoveryJob
        val collectionGeneration = ++discoveryCollectionGeneration
        mutableDiscoveryState.value = DevicesDiscoveryState(phase = DevicesDiscoveryPhase.SCANNING)
        discoveryJob = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                if (collectionGeneration != discoveryCollectionGeneration) return@launch
                var coreGeneration: Long? = null
                manager.observeWirelessServices(
                    mode = WirelessDiscoveryMode.LAN_FOREGROUND,
                    timeout = LAN_DISCOVERY_TIMEOUT,
                ).collect { result ->
                    if (collectionGeneration != discoveryCollectionGeneration) return@collect
                    when (result) {
                        is AdbOperationResult.Success -> {
                            if (coreGeneration == null) {
                                coreGeneration = result.value.generation
                                reduceDiscovery(
                                    DevicesDiscoveryEvent.Start(result.value.generation),
                                    handleEffects = false,
                                )
                            }
                            reduceDiscovery(
                                DevicesDiscoveryEvent.Snapshot(result.value),
                                handleEffects = false,
                            )
                        }
                        is AdbOperationResult.Failure -> reduceDiscovery(
                            DevicesDiscoveryEvent.Failed(discoveryFailure(result.error)),
                            handleEffects = false,
                        )
                        AdbOperationResult.Cancelled -> reduceDiscovery(
                            DevicesDiscoveryEvent.Cancelled,
                            handleEffects = false,
                        )
                    }
                }
            } catch (_: CancellationException) {
                // Explicit lifecycle cancellation owns the visible cancelled state.
            } finally {
                if (collectionGeneration == discoveryCollectionGeneration) discoveryJob = null
            }
        }
    }

    private fun stopLanDiscovery(markCancelled: Boolean) {
        discoveryCollectionGeneration++
        discoveryJob?.cancel()
        discoveryJob = null
        if (markCancelled) {
            reduceDiscovery(DevicesDiscoveryEvent.Cancelled, handleEffects = false)
        }
    }

    private fun reduceDiscovery(
        event: DevicesDiscoveryEvent,
        handleEffects: Boolean = true,
    ): DevicesDiscoveryReduction {
        val reduction = discoveryReducer.reduce(mutableDiscoveryState.value, event)
        mutableDiscoveryState.value = reduction.state
        if (handleEffects) reduction.effects.forEach(::handleDiscoveryEffect)
        return reduction
    }

    private fun handleDiscoveryEffect(effect: DevicesDiscoveryEffect) {
        when (effect) {
            DevicesDiscoveryEffect.OpenManualAddress -> prefillLocalhost()
            is DevicesDiscoveryEffect.OpenCodePairing -> {
                val attemptId = pairingAttemptIdFactory()
                discoveredPairingTarget = effect.target
                discoveredPairingAttemptId = attemptId
                mutableState.update {
                    it.copy(showPairing = true, pairingCode = "", inputError = null, notice = null)
                }
                reducePairing(
                    DevicesPairingEvent.SelectMethod(PairingMethod.SIX_DIGIT_CODE),
                    handleEffects = false,
                )
                reducePairing(DevicesPairingEvent.StartRequested, handleEffects = false)
            }
            is DevicesDiscoveryEffect.Connect -> {
                pendingDiscoveryConnectTarget = effect.target
                if (mutableState.value.connectionState is AdbConnectionState.Connected) {
                    mutableState.update { it.copy(awaitingDiscoverySessionReplacement = true) }
                } else {
                    startOperation { generation -> connectDiscoveredTarget(effect.target, generation) }
                }
            }
        }
    }

    private fun submitDiscoveredPairingCode() {
        val target = discoveredPairingTarget
        val attemptId = discoveredPairingAttemptId
        if (target == null || attemptId == null) {
            clearPairingCode()
            return
        }
        val reduction = reducePairing(DevicesPairingEvent.SubmitCode, handleEffects = false)
        clearPairingCode()
        val submit = reduction.effects.singleOrNull() as? DevicesPairingEffect.SubmitCode
        if (submit == null) {
            mutableState.update { it.copy(inputError = "配对码必须是 6 位数字") }
            return
        }
        startOperation { generation ->
            try {
                when (val result = manager.pairDiscoveredService(target, attemptId, submit.secret)) {
                    is AdbOperationResult.Success -> if (generation == operationGeneration) {
                        lastSuccessfulDiscoveredPairingAttemptId = attemptId
                        discoveredPairingTarget = null
                        discoveredPairingAttemptId = null
                        reducePairing(DevicesPairingEvent.Succeeded, handleEffects = false)
                        mutableState.update {
                            it.copy(
                                showPairing = false,
                                pairingCode = "",
                                notice = "配对成功，请刷新并选择连接服务。",
                            )
                        }
                    }
                    is AdbOperationResult.Failure -> if (generation == operationGeneration) {
                        reducePairing(terminalPairingEvent(result.error), handleEffects = false)
                    }
                    AdbOperationResult.Cancelled -> if (generation == operationGeneration) {
                        reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
                    }
                }
            } finally {
                submit.secret.clear()
                clearPairingCode()
            }
        }
    }

    private suspend fun connectDiscoveredTarget(
        target: WirelessDiscoveryTarget,
        generation: Long,
    ) {
        when (
            val result = manager.connectDiscoveredService(
                target = target,
                expectedPairingAttemptId = lastSuccessfulDiscoveredPairingAttemptId,
            )
        ) {
            is AdbOperationResult.Success -> if (generation == operationGeneration) {
                pendingDiscoveryConnectTarget = null
                reduceDiscovery(DevicesDiscoveryEvent.Snapshot(result.value), handleEffects = false)
                mutableState.update {
                    it.copy(awaitingDiscoverySessionReplacement = false, notice = "连接成功")
                }
            }
            is AdbOperationResult.Failure -> if (generation == operationGeneration) {
                mutableState.update {
                    it.copy(
                        awaitingDiscoverySessionReplacement = false,
                        inputError = result.error.userMessage,
                    )
                }
            }
            AdbOperationResult.Cancelled -> Unit
        }
    }

    private fun discoveryFailure(error: AdbError): DevicesDiscoveryFailure = when (error) {
        AdbError.DiscoveryNetworkUnavailable -> DevicesDiscoveryFailure.NETWORK_UNAVAILABLE
        AdbError.DiscoveryPermissionUnavailable -> DevicesDiscoveryFailure.PERMISSION_UNAVAILABLE
        AdbError.DiscoveryResolutionFailed -> DevicesDiscoveryFailure.RESOLUTION_FAILED
        AdbError.DiscoveryTimeout, is AdbError.Timeout -> DevicesDiscoveryFailure.TIMED_OUT
        AdbError.DiscoverySessionChanged -> DevicesDiscoveryFailure.SESSION_CHANGED
        else -> DevicesDiscoveryFailure.PLATFORM_FAILURE
    }

    private fun startLocalPairingWindow() {
        activeLocalWindowId?.let(manager.localPairingController::cancel)
        localSubmission?.cancel()
        localSubmission = null
        val generation = ++localPairingGeneration
        val attemptId = pairingAttemptIdFactory()
        val windowId = localPairingWindowIdFactory()
        activeLocalWindowId = windowId
        when (val result = manager.localPairingController.start(attemptId, windowId)) {
            is AdbOperationResult.Success -> Unit
            is AdbOperationResult.Failure -> if (isCurrentLocalWindow(generation, windowId)) {
                activeLocalWindowId = null
                reducePairing(terminalPairingEvent(result.error), handleEffects = false)
            }
            AdbOperationResult.Cancelled -> if (isCurrentLocalWindow(generation, windowId)) {
                activeLocalWindowId = null
                reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
            }
        }
    }

    private fun stopLocalPairingWindow() {
        val windowId = activeLocalWindowId
        activeLocalWindowId = null
        localPairingGeneration++
        localSubmission?.cancel()
        localSubmission = null
        if (windowId != null) manager.localPairingController.cancel(windowId)
    }

    private fun handleLocalPairingControllerState(controllerState: LocalPairingControllerState) {
        val activeWindowId = activeLocalWindowId ?: return
        val window = controllerState.window ?: return
        if (window.windowId != activeWindowId) return
        reducePairing(
            DevicesPairingEvent.LocalDiscoveryChanged(controllerState.discoveryStatus),
            handleEffects = false,
        )
        controllerState.notificationDecision?.let { decision ->
            reducePairing(
                DevicesPairingEvent.LocalNotificationChanged(
                    state = decision.state,
                    suggestNativeNotificationStyle = decision.suggestNativeNotificationStyle,
                ),
                handleEffects = false,
            )
        }
        controllerState.stopReason?.let { reason ->
            val event = when (reason) {
                LocalPairingStopReason.SUCCEEDED -> DevicesPairingEvent.Succeeded
                LocalPairingStopReason.CANCELLED -> DevicesPairingEvent.Cancelled
                LocalPairingStopReason.DEADLINE_REACHED,
                LocalPairingStopReason.SYSTEM_TIMEOUT,
                -> DevicesPairingEvent.Expired
                LocalPairingStopReason.SERVICE_LOST,
                LocalPairingStopReason.SESSION_CHANGED,
                LocalPairingStopReason.FAILED,
                -> DevicesPairingEvent.Failed
            }
            activeLocalWindowId = null
            localPairingGeneration++
            reducePairing(event, handleEffects = false)
        }
    }

    private fun isCurrentLocalWindow(
        generation: Long,
        windowId: LocalPairingWindowId,
    ): Boolean = generation == localPairingGeneration && activeLocalWindowId == windowId

    private suspend fun runQrPairing(generation: Long) {
        var attemptId: PairingAttemptId? = null
        var material: QrPairingMaterial? = null
        try {
            if (generation != operationGeneration) return
            attemptId = pairingAttemptIdFactory()
            when (val created = manager.createQrPairingAttempt(attemptId)) {
                is AdbOperationResult.Success -> material = created.value
                is AdbOperationResult.Failure -> {
                    if (generation == operationGeneration) {
                        reducePairing(terminalPairingEvent(created.error), handleEffects = false)
                    }
                    return
                }
                AdbOperationResult.Cancelled -> {
                    if (generation == operationGeneration) {
                        reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
                    }
                    return
                }
            }
            if (generation != operationGeneration) return
            if (material.attemptId != attemptId) {
                reducePairing(DevicesPairingEvent.Failed, handleEffects = false)
                return
            }
            activeQrAttemptId = attemptId
            val payload = material.payload
            if (payload == null) {
                reducePairing(DevicesPairingEvent.Expired, handleEffects = false)
                return
            }
            val matrix = qrEncoder.encode(payload)
            if (generation != operationGeneration || material.payload == null) return
            reducePairing(DevicesPairingEvent.QrPrepared(matrix), handleEffects = false)

            val submittedObservations = mutableSetOf<com.sheen.adb.core.WirelessObservationId>()
            manager.observeWirelessServices(
                mode = WirelessDiscoveryMode.LOCAL_PAIRING,
                timeout = QR_PAIRING_TIMEOUT,
            ).first { discoveryResult ->
                if (generation != operationGeneration) throw CancellationException()
                when (discoveryResult) {
                    is AdbOperationResult.Success -> {
                        var terminal = false
                        discoveryResult.value.services
                            .filter {
                                it.serviceType == WirelessServiceType.PAIRING &&
                                    it.status == WirelessServiceStatus.RESOLVED &&
                                    submittedObservations.add(it.observationId)
                            }
                            .forEach { observation ->
                                if (!terminal) {
                                    terminal = handleQrObservationResult(
                                        generation = generation,
                                        material = material,
                                        result = manager.pairQrObservation(attemptId, observation),
                                    )
                                }
                            }
                        terminal
                    }
                    is AdbOperationResult.Failure -> {
                        reducePairing(terminalPairingEvent(discoveryResult.error), handleEffects = false)
                        true
                    }
                    AdbOperationResult.Cancelled -> {
                        reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
                        true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (generation == operationGeneration) {
                reducePairing(DevicesPairingEvent.Failed, handleEffects = false)
            }
        } finally {
            if (attemptId != null && material?.payload != null) {
                withContext(NonCancellable) { manager.cancelQrPairing(attemptId) }
            }
            if (activeQrAttemptId == attemptId) {
                activeQrAttemptId = null
            }
        }
    }

    private fun handleQrObservationResult(
        generation: Long,
        material: QrPairingMaterial,
        result: AdbOperationResult<Unit>,
    ): Boolean {
        if (generation != operationGeneration) return true
        return when (result) {
            is AdbOperationResult.Success -> {
                reducePairing(DevicesPairingEvent.PairingStarted, handleEffects = false)
                reducePairing(DevicesPairingEvent.Succeeded, handleEffects = false)
                true
            }
            is AdbOperationResult.Failure -> {
                if (material.payload != null) {
                    false
                } else {
                    reducePairing(terminalPairingEvent(result.error), handleEffects = false)
                    true
                }
            }
            AdbOperationResult.Cancelled -> {
                reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
                true
            }
        }
    }

    private fun terminalPairingEvent(error: AdbError): DevicesPairingEvent = when (error) {
        AdbError.DiscoveryTimeout, is AdbError.Timeout -> DevicesPairingEvent.Expired
        AdbError.PairingUnsupported,
        AdbError.DiscoveryPermissionUnavailable,
        AdbError.DiscoveryPlatformFailure,
        -> DevicesPairingEvent.Unsupported
        else -> DevicesPairingEvent.Failed
    }

    private fun cancelPairingOperation(markCancelled: Boolean) {
        if (markCancelled) reducePairing(DevicesPairingEvent.Cancelled, handleEffects = false)
        operationGeneration++
        operation?.cancel()
        clearPairingCode()
    }

    private fun hasNonTerminalPairing(): Boolean {
        val pairing = mutablePairingState.value
        return pairing.method != PairingMethod.NONE &&
            (pairing.phase !in TERMINAL_PAIRING_PHASES) &&
            (pairing.phase != PairingAttemptPhase.IDLE || pairing.awaitingSessionReplacementConfirmation)
    }

    private fun startOperation(block: suspend (Long) -> Unit) {
        val previous = operation
        previous?.cancel()
        val generation = ++operationGeneration
        operation = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                if (generation != operationGeneration) return@launch
                block(generation)
            } finally {
                if (generation == operationGeneration) operation = null
            }
        }
    }

    private fun parse(raw: String): AdbEndpoint? = when (val result = AdbEndpointParser.parse(raw)) {
        is EndpointParseResult.Valid -> result.endpoint
        is EndpointParseResult.Invalid -> {
            manager.reportInvalidAddress(result.reason)
            mutableState.update { it.copy(inputError = result.reason) }
            null
        }
    }

    private fun clearPairingCode() = mutableState.update { it.copy(pairingCode = "") }

    override fun onCleared() {
        stopLanDiscovery(markCancelled = false)
        stopLocalPairingWindow()
        cancelPairingOperation(markCancelled = hasNonTerminalPairing())
        super.onCleared()
    }

    companion object {
        const val HOST_IDENTITY_REFERENCE = "android-keystore-host-v1"
        private const val SIX_DIGIT_CODE_LENGTH = 6
        private val QR_PAIRING_TIMEOUT = 120.seconds
        private val LAN_DISCOVERY_TIMEOUT = 10.seconds
        private val TERMINAL_PAIRING_PHASES = setOf(
            PairingAttemptPhase.SUCCEEDED,
            PairingAttemptPhase.CANCELLED,
            PairingAttemptPhase.EXPIRED,
            PairingAttemptPhase.FAILED,
            PairingAttemptPhase.UNSUPPORTED,
        )
    }
}
