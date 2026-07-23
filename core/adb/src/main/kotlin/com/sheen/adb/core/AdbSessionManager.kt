package com.sheen.adb.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ApplicationMetadataStatus {
    PENDING,
    AVAILABLE,
    UNAVAILABLE,
    TOO_LARGE,
    PARSE_FAILED,
    SESSION_CHANGED,
    TIMED_OUT,
}

enum class ApplicationIconEncoding { PNG, JPEG, WEBP }

enum class ApplicationIconFallback { NONE, ADAPTIVE_FOREGROUND }

class ApplicationIconPayload(
    val encoding: ApplicationIconEncoding,
    val width: Int,
    val height: Int,
    encodedBytes: ByteArray,
    val fallback: ApplicationIconFallback,
) {
    private val retainedBytes = encodedBytes.copyOf()

    val encodedBytes: ByteArray
        get() = retainedBytes.copyOf()
}

data class ApplicationMetadataUpdate(
    val sessionId: String,
    val userId: Int,
    val packageName: String,
    val displayName: String?,
    val icon: ApplicationIconPayload?,
    val status: ApplicationMetadataStatus,
    val evictedIconPackages: Set<String> = emptySet(),
)

internal data class WirelessDiscoverySourceRequest(
    val generation: Long,
    val mode: WirelessDiscoveryMode,
)

internal enum class WirelessDiscoverySourceFailure {
    NETWORK_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    RESOLUTION_FAILED,
    PLATFORM_FAILURE,
}

internal sealed interface WirelessDiscoverySourceStartResult {
    data object Started : WirelessDiscoverySourceStartResult

    data class Rejected(
        val failure: WirelessDiscoverySourceFailure,
    ) : WirelessDiscoverySourceStartResult
}

internal interface WirelessDiscoverySourceObserver {
    fun onEvent(event: WirelessDiscoveryEvent)

    fun onFailure(failure: WirelessDiscoverySourceFailure)
}

internal interface WirelessDiscoverySource : AutoCloseable {
    fun start(request: WirelessDiscoverySourceRequest): WirelessDiscoverySourceStartResult

    override fun close()
}

internal fun interface WirelessDiscoverySourceFactory {
    fun create(observer: WirelessDiscoverySourceObserver): WirelessDiscoverySource
}

enum class LocalPairingDiscoveryStatus {
    IDLE,
    SEARCHING,
    FOUND,
    NOT_FOUND,
    AMBIGUOUS,
    UNSUPPORTED,
    STOPPED,
}

data class LocalPairingControllerState(
    val window: LocalPairingWindow? = null,
    val discoveryStatus: LocalPairingDiscoveryStatus = LocalPairingDiscoveryStatus.IDLE,
    val notificationDecision: LocalPairingNotificationDecision? = null,
    val stopReason: LocalPairingStopReason? = null,
)

interface LocalPairingController {
    val state: StateFlow<LocalPairingControllerState>

    fun start(
        attemptId: PairingAttemptId,
        windowId: LocalPairingWindowId,
    ): AdbOperationResult<LocalPairingWindow>

    fun updateNotification(
        deviceUnlocked: Boolean,
        capability: LocalPairingNotificationCapability,
    ): LocalPairingNotificationDecision

    suspend fun submit(
        windowId: LocalPairingWindowId,
        secret: PairingSecret,
    ): AdbOperationResult<Unit>

    fun cancel(windowId: LocalPairingWindowId): AdbOperationResult<Unit>

    fun onSystemTimeout(windowId: LocalPairingWindowId): AdbOperationResult<Unit>
}

private object UnsupportedLocalPairingController : LocalPairingController {
    override val state: StateFlow<LocalPairingControllerState> = MutableStateFlow(LocalPairingControllerState())

    override fun start(
        attemptId: PairingAttemptId,
        windowId: LocalPairingWindowId,
    ): AdbOperationResult<LocalPairingWindow> = AdbOperationResult.Failure(AdbError.PairingUnsupported)

    override fun updateNotification(
        deviceUnlocked: Boolean,
        capability: LocalPairingNotificationCapability,
    ): LocalPairingNotificationDecision = LocalPairingNotificationDecision(
        state = LocalPairingNotificationState.INPUT_UNAVAILABLE,
        inputActionAvailable = false,
        submitAllowed = false,
        actionWindowId = null,
        applicationInputAvailable = true,
        suggestNativeNotificationStyle =
            capability == LocalPairingNotificationCapability.INLINE_INPUT_UNAVAILABLE,
    )

    override suspend fun submit(
        windowId: LocalPairingWindowId,
        secret: PairingSecret,
    ): AdbOperationResult<Unit> {
        secret.clear()
        return AdbOperationResult.Failure(AdbError.PairingUnsupported)
    }

    override fun cancel(windowId: LocalPairingWindowId): AdbOperationResult<Unit> =
        AdbOperationResult.Failure(AdbError.PairingUnsupported)

    override fun onSystemTimeout(windowId: LocalPairingWindowId): AdbOperationResult<Unit> =
        AdbOperationResult.Failure(AdbError.PairingUnsupported)
}

interface AdbSessionManager : AutoCloseable {
    val connectionState: StateFlow<AdbConnectionState>
    val diagnosticEvents: StateFlow<List<AdbDiagnosticEvent>>
    val localPairingController: LocalPairingController
        get() = UnsupportedLocalPairingController

    suspend fun acquireExclusiveOperation(
        kind: AdbExclusiveOperationKind,
        expectedSessionId: String,
    ): AdbOperationResult<ExclusiveAdbOperationLease>

    suspend fun connect(
        endpoint: AdbEndpoint,
        timeout: Duration = 15.seconds,
    ): AdbOperationResult<Unit>

    suspend fun pair(
        pairingEndpoint: AdbEndpoint,
        pairingCode: CharArray,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<Unit>

    suspend fun pairWithSecret(
        pairingEndpoint: AdbEndpoint,
        pairingSecret: PairingSecret,
        method: PairingMethod,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<Unit> {
        pairingSecret.clear()
        return AdbOperationResult.Failure(AdbError.PairingUnsupported)
    }

    suspend fun createQrPairingAttempt(
        attemptId: PairingAttemptId,
    ): AdbOperationResult<QrPairingMaterial> = AdbOperationResult.Failure(AdbError.PairingUnsupported)

    suspend fun pairQrObservation(
        attemptId: PairingAttemptId,
        observation: WirelessServiceObservation,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<Unit> = AdbOperationResult.Failure(AdbError.PairingUnsupported)

    suspend fun cancelQrPairing(
        attemptId: PairingAttemptId,
    ): AdbOperationResult<Unit> = AdbOperationResult.Failure(AdbError.PairingUnsupported)

    suspend fun executeShell(
        command: String,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<ShellResult>

    suspend fun loadDeviceOverview(timeout: Duration = 20.seconds): AdbOperationResult<DeviceOverview>

    suspend fun refreshDynamicMetrics(timeout: Duration = 10.seconds): AdbOperationResult<DynamicDeviceMetrics>

    suspend fun listProcesses(timeout: Duration = 15.seconds): AdbOperationResult<ProcessSnapshot>

    suspend fun loadProcessAnalysis(
        expectedSessionId: String,
        timeout: Duration = 15.seconds,
    ): AdbOperationResult<ProcessAnalysisSnapshot> = AdbOperationResult.Failure(
        AdbError.ApplicationSessionInvalid(AdbOperationStage.PROCESSES),
    )

    suspend fun listApplications(timeout: Duration = 15.seconds): AdbOperationResult<ApplicationSnapshot>

    fun observeApplicationMetadata(
        expectedSessionId: String,
        preferredLocaleTags: List<String> = emptyList(),
    ): Flow<AdbOperationResult<ApplicationMetadataUpdate>> = flowOf(
        AdbOperationResult.Failure(
            AdbError.ApplicationSessionInvalid(AdbOperationStage.APPLICATIONS_LIST),
        ),
    )

    fun observeWirelessServices(
        mode: WirelessDiscoveryMode,
        timeout: Duration,
    ): Flow<AdbOperationResult<WirelessDiscoveryState>> = flowOf(
        AdbOperationResult.Failure(AdbError.DiscoveryPlatformFailure),
    )

    suspend fun pairDiscoveredService(
        target: WirelessDiscoveryTarget,
        attemptId: PairingAttemptId,
        secret: PairingSecret,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<WirelessDiscoveryState> {
        secret.clear()
        return AdbOperationResult.Failure(AdbError.DiscoveryResolutionFailed)
    }

    suspend fun connectDiscoveredService(
        target: WirelessDiscoveryTarget,
        expectedPairingAttemptId: PairingAttemptId? = null,
        timeout: Duration = 15.seconds,
    ): AdbOperationResult<WirelessDiscoveryState> =
        AdbOperationResult.Failure(AdbError.DiscoveryResolutionFailed)

    suspend fun loadRemoteDirectory(
        path: String? = null,
        expectedSessionId: String,
        timeout: Duration = 5.seconds,
    ): AdbOperationResult<RemoteDirectorySnapshot>

    suspend fun pullRemoteFile(
        remoteFile: RemotePathEntry,
        destination: OutputStream,
        expectedSessionId: String,
        progress: (FileTransferProgress) -> Unit = {},
        externalLease: ExclusiveAdbOperationLease? = null,
    ): AdbOperationResult<RemoteFileTransferReceipt>

    suspend fun pushRemoteFile(
        source: InputStream,
        sourceSize: Long?,
        stagedRemotePath: String,
        expectedSessionId: String,
        progress: (FileTransferProgress) -> Unit = {},
        externalLease: ExclusiveAdbOperationLease? = null,
    ): AdbOperationResult<RemoteFileTransferReceipt>

    suspend fun prepareRemoteUpload(
        remoteDirectory: String,
        displayName: String,
        expectedSessionId: String,
    ): AdbOperationResult<RemoteUploadPlan>

    suspend fun commitRemoteUpload(
        plan: RemoteUploadPlan,
        conflictPolicy: RemoteFileConflictPolicy,
        expectedSessionId: String,
        externalLease: ExclusiveAdbOperationLease? = null,
    ): AdbOperationResult<RemoteUploadCommitReceipt>

    suspend fun cleanupRemoteStaging(
        stagedRemotePath: String,
        expectedSessionId: String,
        externalLease: ExclusiveAdbOperationLease? = null,
    ): AdbOperationResult<Unit>

    suspend fun forceStopApplication(
        packageName: String,
        expectedSessionId: String,
        timeout: Duration = 10.seconds,
    ): AdbOperationResult<ApplicationMutationResult>

    suspend fun setApplicationEnabled(
        packageName: String,
        enabled: Boolean,
        expectedSessionId: String,
        timeout: Duration = 15.seconds,
    ): AdbOperationResult<ApplicationMutationResult>

    fun streamLogcat(config: LogcatConfig): Flow<AdbOperationResult<LogcatLine>>

    fun streamStructuredLogcat(
        config: LogcatConfig,
        expectedSessionId: String,
        expectedProcessGeneration: Long,
    ): Flow<AdbOperationResult<StructuredLogcatRecord>> = flowOf(
        AdbOperationResult.Failure(AdbError.RemoteClosed(AdbOperationStage.LOGCAT)),
    )

    suspend fun disconnect(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    suspend fun clearHostIdentity(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    fun clearDiagnosticEvents()

    fun reportInvalidAddress(userMessage: String)

    override fun close()
}
