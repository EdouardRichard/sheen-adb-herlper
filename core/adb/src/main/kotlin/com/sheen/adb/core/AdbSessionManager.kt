package com.sheen.adb.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WirelessDiscoverySourceRequest(
    val generation: Long,
    val mode: WirelessDiscoveryMode,
)

enum class WirelessDiscoverySourceFailure {
    NETWORK_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    RESOLUTION_FAILED,
    PLATFORM_FAILURE,
}

sealed interface WirelessDiscoverySourceStartResult {
    data object Started : WirelessDiscoverySourceStartResult

    data class Rejected(
        val failure: WirelessDiscoverySourceFailure,
    ) : WirelessDiscoverySourceStartResult
}

interface WirelessDiscoverySourceObserver {
    fun onEvent(event: WirelessDiscoveryEvent)

    fun onFailure(failure: WirelessDiscoverySourceFailure)
}

interface WirelessDiscoverySource : AutoCloseable {
    fun start(request: WirelessDiscoverySourceRequest): WirelessDiscoverySourceStartResult

    override fun close()
}

fun interface WirelessDiscoverySourceFactory {
    fun create(observer: WirelessDiscoverySourceObserver): WirelessDiscoverySource
}

interface AdbSessionManager : AutoCloseable {
    val connectionState: StateFlow<AdbConnectionState>
    val diagnosticEvents: StateFlow<List<AdbDiagnosticEvent>>

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

    suspend fun executeShell(
        command: String,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<ShellResult>

    suspend fun loadDeviceOverview(timeout: Duration = 20.seconds): AdbOperationResult<DeviceOverview>

    suspend fun refreshDynamicMetrics(timeout: Duration = 10.seconds): AdbOperationResult<DynamicDeviceMetrics>

    suspend fun listProcesses(timeout: Duration = 15.seconds): AdbOperationResult<ProcessSnapshot>

    suspend fun listApplications(timeout: Duration = 15.seconds): AdbOperationResult<ApplicationSnapshot>

    fun observeWirelessServices(
        mode: WirelessDiscoveryMode,
        timeout: Duration,
    ): Flow<AdbOperationResult<WirelessDiscoveryState>> = flowOf(
        AdbOperationResult.Failure(AdbError.DiscoveryPlatformFailure),
    )

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

    suspend fun disconnect(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    suspend fun clearHostIdentity(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    fun clearDiagnosticEvents()

    fun reportInvalidAddress(userMessage: String)

    override fun close()
}
