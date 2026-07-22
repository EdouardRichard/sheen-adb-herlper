package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbDiagnosticOutcome
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbExclusiveOperationKind
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbOperationStage
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.ApplicationField
import com.sheen.adb.core.ApplicationMutationResult
import com.sheen.adb.core.ApplicationSnapshot
import com.sheen.adb.core.DiagnosticRedactor
import com.sheen.adb.core.DeviceOverview
import com.sheen.adb.core.DynamicDeviceMetrics
import com.sheen.adb.core.DisconnectionReason
import com.sheen.adb.core.ExclusiveAdbOperationLease
import com.sheen.adb.core.FileTransferProgress
import com.sheen.adb.core.LogcatConfig
import com.sheen.adb.core.LogcatLine
import com.sheen.adb.core.ProcessSnapshot
import com.sheen.adb.core.RemoteApplication
import com.sheen.adb.core.RemoteApplicationEnabledState
import com.sheen.adb.core.RemoteDirectorySnapshot
import com.sheen.adb.core.RemoteDirectorySource
import com.sheen.adb.core.RemoteFileKind
import com.sheen.adb.core.RemoteLinkResolution
import com.sheen.adb.core.RemotePathEntry
import com.sheen.adb.core.RemoteFileTransferReceipt
import com.sheen.adb.core.RemoteFileConflictPolicy
import com.sheen.adb.core.RemoteUploadCommitReceipt
import com.sheen.adb.core.RemoteUploadPlan
import com.sheen.adb.core.ShellResult
import com.sheen.adb.core.ShellOutputMode
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoverySource
import com.sheen.adb.core.WirelessDiscoverySourceFactory
import com.sheen.adb.core.WirelessDiscoverySourceFailure
import com.sheen.adb.core.WirelessDiscoverySourceObserver
import com.sheen.adb.core.WirelessDiscoverySourceRequest
import com.sheen.adb.core.WirelessDiscoverySourceStartResult
import com.sheen.adb.core.WirelessDiscoveryState
import com.sheen.adb.core.internal.discovery.WirelessDiscoveryReducer
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class DefaultAdbSessionManager(
    private val clientFactory: AdbProtocolClientFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transferNoProgressTimeout: Duration = 30.seconds,
    private val transferCancellationGrace: Duration = 3.seconds,
    private val wirelessDiscoverySourceFactory: WirelessDiscoverySourceFactory? = null,
) : AdbSessionManager, Closeable {
    private data class ActiveSession(
        val id: String,
        val endpoint: AdbEndpoint,
        val client: AdbProtocolClient,
    )

    private data class ActiveExclusiveOperation(
        val token: String,
        val sessionId: String,
        val kind: AdbExclusiveOperationKind,
        val active: AtomicBoolean = AtomicBoolean(true),
    )

    private sealed interface WirelessDiscoverySignal {
        data class Event(val value: WirelessDiscoveryEvent) : WirelessDiscoverySignal

        data class Terminal(val error: AdbError) : WirelessDiscoverySignal
    }

    private sealed interface WirelessDiscoveryCallResult<out T> {
        data class Value<T>(val value: T) : WirelessDiscoveryCallResult<T>

        data class Cancelled(val cancellation: CancellationException) : WirelessDiscoveryCallResult<Nothing>

        data object Failed : WirelessDiscoveryCallResult<Nothing>
    }

    private class ActiveWirelessDiscovery(
        val generation: Long,
        val ownerSessionId: String?,
    ) {
        val signals = Channel<WirelessDiscoverySignal>(Channel.UNLIMITED)
        val terminalCompletion = CompletableDeferred<AdbError>()

        private val stateLock = Any()
        private val sourceLock = Any()
        private var terminal = false
        private var terminalError: AdbError? = null
        private var terminalPublished = false
        private var source: WirelessDiscoverySource? = null
        private var sourceAttachmentComplete = false
        private var sourceCloseState = SourceCloseState.OPEN

        fun isTerminal(): Boolean = synchronized(stateLock) { terminal }

        fun terminalError(): AdbError? = synchronized(stateLock) { terminalError }

        fun markTerminal(error: AdbError): Boolean = synchronized(stateLock) {
            if (terminal) return@synchronized false
            terminal = true
            terminalError = error
            true
        }

        fun markRetired(): Boolean = synchronized(stateLock) {
            if (terminal) return@synchronized false
            terminal = true
            true
        }

        fun attachSource(value: WirelessDiscoverySource) = synchronized(sourceLock) {
            check(!sourceAttachmentComplete) { "Wireless discovery source attachment already completed." }
            source = value
            sourceAttachmentComplete = true
        }

        fun markSourceUnavailable() = synchronized(sourceLock) {
            if (!sourceAttachmentComplete) sourceAttachmentComplete = true
        }

        fun closeSourceIfReady(): Boolean {
            var closeNow: WirelessDiscoverySource? = null
            synchronized(sourceLock) {
                if (!sourceAttachmentComplete) return false
                when (sourceCloseState) {
                    SourceCloseState.CLOSING -> return false
                    SourceCloseState.CLOSED -> return true
                    SourceCloseState.OPEN -> {
                        sourceCloseState = SourceCloseState.CLOSING
                        closeNow = source
                    }
                }
            }
            closeNow?.let { runCatching { it.close() } }
            synchronized(sourceLock) {
                sourceCloseState = SourceCloseState.CLOSED
            }
            return true
        }

        fun publishTerminal(error: AdbError) {
            val shouldPublish = synchronized(stateLock) {
                if (terminalPublished) false else {
                    terminalPublished = true
                    true
                }
            }
            if (!shouldPublish) return
            terminalCompletion.complete(error)
            signals.trySend(WirelessDiscoverySignal.Terminal(error))
            signals.close()
        }

        fun finishRetirement() {
            signals.close()
        }

        private enum class SourceCloseState {
            OPEN,
            CLOSING,
            CLOSED,
        }
    }

    private val mutex = Mutex()
    private val applicationMutex = Mutex()
    private val exclusiveOperationLock = Any()
    private val wirelessDiscoveryLock = Any()
    private val closed = AtomicBoolean(false)
    private val diagnosticSequence = AtomicLong(0)
    private val wirelessDiscoveryGeneration = AtomicLong(0)
    @Volatile
    private var active: ActiveSession? = null
    private var activeExclusiveOperation: ActiveExclusiveOperation? = null
    private var activeWirelessDiscovery: ActiveWirelessDiscovery? = null
    private var applicationSnapshot: ApplicationSnapshot? = null
    private val mutableState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected())
    override val connectionState: StateFlow<AdbConnectionState> = mutableState.asStateFlow()
    private val mutableDiagnosticEvents = MutableStateFlow<List<AdbDiagnosticEvent>>(emptyList())
    override val diagnosticEvents: StateFlow<List<AdbDiagnosticEvent>> = mutableDiagnosticEvents.asStateFlow()

    override suspend fun acquireExclusiveOperation(
        kind: AdbExclusiveOperationKind,
        expectedSessionId: String,
    ): AdbOperationResult<ExclusiveAdbOperationLease> = mutex.withLock {
        val session = active
        if (session == null || session.id != expectedSessionId) {
            return@withLock operationFailure(AdbError.SessionInvalid(kind), session?.endpoint, null)
        }

        val (acquired, conflictingKind) = synchronized(exclusiveOperationLock) {
            val current = activeExclusiveOperation?.takeIf { it.active.get() }
            if (current != null) {
                null to current.kind
            } else {
                ActiveExclusiveOperation(
                    token = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    kind = kind,
                ).also { activeExclusiveOperation = it } to null
            }
        }
        if (acquired == null) {
            return@withLock operationFailure(
                AdbError.OperationConflict(kind, checkNotNull(conflictingKind)),
                session.endpoint,
                null,
            )
        }
        AdbOperationResult.Success(ManagerExclusiveOperationLease(acquired))
    }

    override fun observeWirelessServices(
        mode: WirelessDiscoveryMode,
        timeout: Duration,
    ): Flow<AdbOperationResult<WirelessDiscoveryState>> = flow {
        val discovery = claimWirelessDiscovery()
        if (discovery == null) {
            val error = if (closed.get()) AdbError.DiscoveryManagerClosed else AdbError.DiscoveryConflict
            emit(operationFailure(error, null, null))
            return@flow
        }

        var state = WirelessDiscoveryState(generation = discovery.generation)
        val reducer = WirelessDiscoveryReducer()
        var pendingSourceCancellation: CancellationException? = null
        try {
            try {
                withTimeout(timeout) {
                    val factory = wirelessDiscoverySourceFactory
                    if (discovery.isTerminal()) {
                        discovery.markSourceUnavailable()
                        completePendingWirelessDiscovery(discovery)
                    } else if (factory == null) {
                        discovery.markSourceUnavailable()
                        terminateWirelessDiscovery(discovery, AdbError.DiscoveryPlatformFailure)
                    } else {
                        val observer = object : WirelessDiscoverySourceObserver {
                            override fun onEvent(event: WirelessDiscoveryEvent) {
                                publishWirelessDiscoveryEvent(discovery, event)
                            }

                            override fun onFailure(failure: WirelessDiscoverySourceFailure) {
                                terminateWirelessDiscovery(discovery, discoveryError(failure))
                            }
                        }
                        val sourceResult = try {
                            runInterruptible(ioDispatcher) {
                                captureWirelessDiscoveryCall {
                                    factory.create(observer).also(discovery::attachSource)
                                }
                            }
                        } catch (error: CancellationException) {
                            discovery.markSourceUnavailable()
                            completePendingWirelessDiscovery(discovery)
                            throw error
                        }
                        val source = when (sourceResult) {
                            is WirelessDiscoveryCallResult.Value -> sourceResult.value
                            is WirelessDiscoveryCallResult.Cancelled -> {
                                discovery.markSourceUnavailable()
                                pendingSourceCancellation = sourceResult.cancellation
                                return@withTimeout
                            }
                            WirelessDiscoveryCallResult.Failed -> {
                                discovery.markSourceUnavailable()
                                terminateWirelessDiscovery(discovery, AdbError.DiscoveryPlatformFailure)
                                null
                            }
                        }
                        if (source != null) {
                            completePendingWirelessDiscovery(discovery)
                            if (!discovery.isTerminal()) {
                                val startCallResult = try {
                                    runInterruptible(ioDispatcher) {
                                        captureWirelessDiscoveryCall {
                                            source.start(
                                                WirelessDiscoverySourceRequest(
                                                    generation = discovery.generation,
                                                    mode = mode,
                                                ),
                                            )
                                        }
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                }
                                val startResult = when (startCallResult) {
                                    is WirelessDiscoveryCallResult.Value -> startCallResult.value
                                    is WirelessDiscoveryCallResult.Cancelled -> {
                                        pendingSourceCancellation = startCallResult.cancellation
                                        return@withTimeout
                                    }
                                    WirelessDiscoveryCallResult.Failed -> {
                                        terminateWirelessDiscovery(discovery, AdbError.DiscoveryPlatformFailure)
                                        null
                                    }
                                }
                                when (startResult) {
                                    WirelessDiscoverySourceStartResult.Started -> Unit
                                    is WirelessDiscoverySourceStartResult.Rejected -> {
                                        terminateWirelessDiscovery(discovery, discoveryError(startResult.failure))
                                    }
                                    null -> Unit
                                }
                            }
                        }
                    }

                    if (discovery.isTerminal()) {
                        val error = discovery.terminalCompletion.await()
                        emit(operationFailure(error, null, null))
                        return@withTimeout
                    }

                    appendDiagnostic(
                        AdbOperationStage.DISCOVERY,
                        AdbDiagnosticOutcome.STARTED,
                        "ADB_DISCOVERY_STARTED",
                        null,
                    )
                    emit(AdbOperationResult.Success(state))
                    for (signal in discovery.signals) {
                        when (signal) {
                            is WirelessDiscoverySignal.Event -> {
                                state = reducer.reduce(state, signal.value)
                                emit(AdbOperationResult.Success(state))
                            }

                            is WirelessDiscoverySignal.Terminal -> {
                                emit(operationFailure(signal.error, null, null))
                                return@withTimeout
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                terminateWirelessDiscovery(discovery, AdbError.DiscoveryTimeout)
                val error = discovery.terminalCompletion.await()
                emit(operationFailure(error, null, null))
            }
            pendingSourceCancellation?.let { throw it }
        } catch (error: CancellationException) {
            appendDiagnostic(
                AdbOperationStage.DISCOVERY,
                AdbDiagnosticOutcome.CANCELLED,
                "ADB_DISCOVERY_CANCELLED",
                null,
            )
            throw error
        } finally {
            discovery.markSourceUnavailable()
            completePendingWirelessDiscovery(discovery)
            retireWirelessDiscovery(discovery)
            appendDiagnostic(
                AdbOperationStage.DISCOVERY,
                AdbDiagnosticOutcome.RESOURCE_CLOSED,
                "ADB_DISCOVERY_SOURCE_CLOSED",
                null,
            )
        }
    }

    override suspend fun loadRemoteDirectory(
        path: String?,
        expectedSessionId: String,
        timeout: Duration,
    ): AdbOperationResult<RemoteDirectorySnapshot> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId) {
            return operationFailure(AdbError.RemoteSessionInvalid, session?.endpoint, null)
        }
        return try {
            val (directory, listing) = if (path == null) {
                val userId = when (val user = currentUser(timeout)) {
                    is AdbOperationResult.Success -> user.value
                    else -> 0
                }
                resolveSharedStorage(session, userId, timeout)
            } else {
                if (!RemoteFileCapabilities.isValidAbsolutePath(path)) {
                    return operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
                }
                path to KadbRemoteFileProtocol.list(session.client, path, timeout)
            }
            try {
                RemoteFileCapabilities.requireDirectoryCapacity(listing.entries.size)
            } catch (_: RemoteDirectoryCapacityException) {
                return operationFailure(AdbError.RemoteDirectoryCapacityExceeded, session.endpoint, null)
            }
            val currentStat = runCatching {
                KadbRemoteFileProtocol.stat(session.client, directory, timeout)
            }.getOrNull()
            val entries = listing.entries.map { entry ->
                toRemotePathEntry(session, directory, entry, listing.version, currentStat, timeout)
            }.sortedWith(compareBy<RemotePathEntry>({ kindOrder(it.kind) }, { it.displayName.lowercase() }))
            if (mutex.withLock { active?.id } != expectedSessionId) {
                return operationFailure(AdbError.RemoteSessionInvalid, session.endpoint, null)
            }
            AdbOperationResult.Success(
                RemoteDirectorySnapshot(
                    sessionId = session.id,
                    directory = directory,
                    entries = entries,
                    sourceCapabilities = if (listing.version == ProtocolSyncVersion.V2) {
                        setOf(RemoteDirectorySource.LIST_V2, RemoteDirectorySource.STAT_V2)
                    } else {
                        setOf(RemoteDirectorySource.SYNC_V1_DEGRADED)
                    },
                    loadedAtMonotonicMillis = System.nanoTime() / 1_000_000,
                ),
            )
        } catch (error: TimeoutCancellationException) {
            operationFailure(AdbError.Timeout(AdbOperationStage.FILE_BROWSER), session.endpoint, error)
        } catch (_: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            operationFailure(remoteFileError(error), session.endpoint, error)
        }
    }

    override suspend fun pullRemoteFile(
        remoteFile: RemotePathEntry,
        destination: OutputStream,
        expectedSessionId: String,
        progress: (FileTransferProgress) -> Unit,
        externalLease: ExclusiveAdbOperationLease?,
    ): AdbOperationResult<RemoteFileTransferReceipt> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId) {
            return operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
        if (!RemoteFileCapabilities.isValidAbsolutePath(remoteFile.absolutePath) || !remoteFile.selectable) {
            return operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
        }
        val (lease, ownsLease) = when (val acquired = obtainFileTransferLease(expectedSessionId, externalLease)) {
            is AdbOperationResult.Success -> acquired.value
            is AdbOperationResult.Failure -> return acquired
            AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        val forcedSessionClose = AtomicBoolean(false)
        return try {
            val before = KadbRemoteFileProtocol.stat(session.client, remoteFile.absolutePath, FILE_PREPARE_TIMEOUT)
            val reliableMetadata = before.hasReliableTransferMetadata()
            val digestBefore = if (reliableMetadata) null else remoteDigest(session.client, remoteFile.absolutePath)
                ?: return operationFailure(AdbError.RemoteIntegrityUnavailable, session.endpoint, null)
            val transferred = KadbRemoteFileProtocol.receive(
                client = session.client,
                path = remoteFile.absolutePath,
                destination = destination,
                noProgressTimeout = transferNoProgressTimeout,
                cancellationGrace = transferCancellationGrace,
                onForcedSessionClose = {
                    forcedSessionClose.set(true)
                    runCatching { session.client.close() }
                },
            ) { bytes -> progress(FileTransferProgress(bytes, before.size.takeIf { it >= 0L })) }
            val after = KadbRemoteFileProtocol.stat(session.client, remoteFile.absolutePath, FILE_PREPARE_TIMEOUT)
            val stable = if (reliableMetadata) {
                before.sameTransferIdentity(after) && transferred == before.size
            } else {
                val digestAfter = remoteDigest(session.client, remoteFile.absolutePath)
                    ?: return operationFailure(AdbError.RemoteIntegrityUnavailable, session.endpoint, null)
                digestBefore == digestAfter && transferred == after.size
            }
            if (!stable) return operationFailure(AdbError.RemoteSourceChanged, session.endpoint, null)
            if (mutex.withLock { active?.id } != expectedSessionId || !lease.isActive) {
                return operationFailure(
                    AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                    session.endpoint,
                    null,
                )
            }
            AdbOperationResult.Success(RemoteFileTransferReceipt(session.id, transferred))
        } catch (error: ProtocolNoProgressTimeoutException) {
            operationFailure(AdbError.NoProgressTimeout, session.endpoint, error)
        } catch (_: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            operationFailure(fileTransferError(error), session.endpoint, error)
        } finally {
            if (forcedSessionClose.get()) {
                withContext(NonCancellable) { invalidateForcedTransferSession(session) }
            }
            if (ownsLease) lease.release()
        }
    }

    override suspend fun pushRemoteFile(
        source: InputStream,
        sourceSize: Long?,
        stagedRemotePath: String,
        expectedSessionId: String,
        progress: (FileTransferProgress) -> Unit,
        externalLease: ExclusiveAdbOperationLease?,
    ): AdbOperationResult<RemoteFileTransferReceipt> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId) {
            return operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
        if (!RemoteFileCapabilities.isValidAbsolutePath(stagedRemotePath)) {
            return operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
        }
        val (lease, ownsLease) = when (val acquired = obtainFileTransferLease(expectedSessionId, externalLease)) {
            is AdbOperationResult.Success -> acquired.value
            is AdbOperationResult.Failure -> return acquired
            AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        val forcedSessionClose = AtomicBoolean(false)
        return try {
            val transferred = KadbRemoteFileProtocol.send(
                client = session.client,
                path = stagedRemotePath,
                source = source,
                mode = DEFAULT_REMOTE_FILE_MODE,
                modifiedEpochMillis = System.currentTimeMillis(),
                noProgressTimeout = transferNoProgressTimeout,
                cancellationGrace = transferCancellationGrace,
                onForcedSessionClose = {
                    forcedSessionClose.set(true)
                    runCatching { session.client.close() }
                },
            ) { bytes -> progress(FileTransferProgress(bytes, sourceSize)) }
            val uploaded = KadbRemoteFileProtocol.stat(session.client, stagedRemotePath, FILE_PREPARE_TIMEOUT)
            if ((sourceSize != null && sourceSize != transferred) || uploaded.size != transferred) {
                return operationFailure(AdbError.RemoteSourceChanged, session.endpoint, null)
            }
            if (mutex.withLock { active?.id } != expectedSessionId || !lease.isActive) {
                return operationFailure(
                    AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                    session.endpoint,
                    null,
                )
            }
            AdbOperationResult.Success(RemoteFileTransferReceipt(session.id, transferred))
        } catch (error: ProtocolNoProgressTimeoutException) {
            operationFailure(AdbError.NoProgressTimeout, session.endpoint, error)
        } catch (_: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            operationFailure(fileTransferError(error), session.endpoint, error)
        } finally {
            if (forcedSessionClose.get()) {
                withContext(NonCancellable) { invalidateForcedTransferSession(session) }
            }
            if (ownsLease) lease.release()
        }
    }

    override suspend fun prepareRemoteUpload(
        remoteDirectory: String,
        displayName: String,
        expectedSessionId: String,
    ): AdbOperationResult<RemoteUploadPlan> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId) {
            return operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
        return try {
            val finalPath = RemoteFileCapabilities.safeChild(remoteDirectory, displayName)
            val listing = KadbRemoteFileProtocol.list(session.client, remoteDirectory, FILE_PREPARE_TIMEOUT)
            val names = listing.entries.mapTo(mutableSetOf()) { it.name }
            val stagedName = generateSequence {
                ".sheen-${UUID.randomUUID().toString().replace("-", "").take(16)}.part"
            }.first { it !in names }
            AdbOperationResult.Success(
                RemoteUploadPlan(
                    sessionId = session.id,
                    directory = remoteDirectory,
                    requestedName = displayName,
                    stagedPath = RemoteFileCapabilities.safeChild(remoteDirectory, stagedName),
                    finalPath = finalPath,
                    conflictExists = displayName in names,
                ),
            )
        } catch (error: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (_: IllegalArgumentException) {
            operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
        } catch (error: Throwable) {
            operationFailure(remoteFileError(error), session.endpoint, error)
        }
    }

    override suspend fun commitRemoteUpload(
        plan: RemoteUploadPlan,
        conflictPolicy: RemoteFileConflictPolicy,
        expectedSessionId: String,
        externalLease: ExclusiveAdbOperationLease?,
    ): AdbOperationResult<RemoteUploadCommitReceipt> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId || plan.sessionId != expectedSessionId) {
            return operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
        if (!isValidUploadPlan(plan)) {
            return operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
        }
        val (lease, ownsLease) = when (val acquired = obtainFileTransferLease(expectedSessionId, externalLease)) {
            is AdbOperationResult.Success -> acquired.value
            is AdbOperationResult.Failure -> return acquired
            AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        return try {
            val names = KadbRemoteFileProtocol.list(
                session.client,
                plan.directory,
                FILE_PREPARE_TIMEOUT,
            ).entries.mapTo(mutableSetOf()) { it.name }
            val targetExists = plan.requestedName in names
            if (targetExists && conflictPolicy == RemoteFileConflictPolicy.CANCEL) {
                return operationFailure(AdbError.RemoteConflict, session.endpoint, null)
            }
            val targetPath = when {
                targetExists && conflictPolicy == RemoteFileConflictPolicy.AUTO_RENAME -> {
                    val renamed = autoRenamedName(plan.requestedName, names)
                        ?: return operationFailure(AdbError.RemoteConflict, session.endpoint, null)
                    RemoteFileCapabilities.safeChild(plan.directory, renamed)
                }
                else -> plan.finalPath
            }
            val replaced = targetExists && conflictPolicy == RemoteFileConflictPolicy.OVERWRITE
            val committed = if (replaced) {
                commitRemoteOverwrite(session.client, plan, targetPath, names)
            } else {
                remoteMove(session.client, plan.stagedPath, targetPath)
            }
            if (!committed) return operationFailure(AdbError.RemoteCommitFailed, session.endpoint, null)
            if (mutex.withLock { active?.id } != expectedSessionId || !lease.isActive) {
                return operationFailure(
                    AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                    session.endpoint,
                    null,
                )
            }
            AdbOperationResult.Success(RemoteUploadCommitReceipt(session.id, targetPath, replaced))
        } catch (_: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            operationFailure(AdbError.RemoteCommitFailed, session.endpoint, error)
        } finally {
            if (ownsLease) lease.release()
        }
    }

    override suspend fun cleanupRemoteStaging(
        stagedRemotePath: String,
        expectedSessionId: String,
        externalLease: ExclusiveAdbOperationLease?,
    ): AdbOperationResult<Unit> {
        val session = mutex.withLock { active }
        if (session == null || session.id != expectedSessionId) {
            return operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
        if (!RemoteFileCapabilities.isValidAbsolutePath(stagedRemotePath) ||
            !stagedRemotePath.substringAfterLast('/').startsWith(".sheen-")
        ) {
            return operationFailure(AdbError.RemotePathInvalid, session.endpoint, null)
        }
        val leaseUse = when (val acquired = obtainFileTransferLease(expectedSessionId, externalLease)) {
            is AdbOperationResult.Success -> acquired.value
            is AdbOperationResult.Failure -> return acquired
            AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        return try {
            if (remoteDelete(session.client, stagedRemotePath)) {
                AdbOperationResult.Success(Unit)
            } else {
                operationFailure(AdbError.RemoteCleanupFailed, session.endpoint, null)
            }
        } catch (_: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            operationFailure(AdbError.RemoteCleanupFailed, session.endpoint, error)
        } finally {
            if (leaseUse.second) leaseUse.first.release()
        }
    }

    private suspend fun obtainFileTransferLease(
        expectedSessionId: String,
        externalLease: ExclusiveAdbOperationLease?,
    ): AdbOperationResult<Pair<ExclusiveAdbOperationLease, Boolean>> {
        if (externalLease == null) {
            return when (val acquired = acquireExclusiveOperation(
                AdbExclusiveOperationKind.FILE_TRANSFER,
                expectedSessionId,
            )) {
                is AdbOperationResult.Success -> AdbOperationResult.Success(acquired.value to true)
                is AdbOperationResult.Failure -> acquired
                AdbOperationResult.Cancelled -> AdbOperationResult.Cancelled
            }
        }
        val valid = externalLease.kind == AdbExclusiveOperationKind.FILE_TRANSFER &&
            externalLease.sessionId == expectedSessionId &&
            externalLease.isActive &&
            synchronized(exclusiveOperationLock) {
                val activeLease = activeExclusiveOperation
                activeLease?.token == externalLease.token && activeLease.sessionId == expectedSessionId
            }
        return if (valid) {
            AdbOperationResult.Success(externalLease to false)
        } else {
            val session = mutex.withLock { active }
            operationFailure(
                AdbError.SessionInvalid(AdbExclusiveOperationKind.FILE_TRANSFER),
                session?.endpoint,
                null,
            )
        }
    }

    private fun isValidUploadPlan(plan: RemoteUploadPlan): Boolean =
        RemoteFileCapabilities.isValidAbsolutePath(plan.directory) &&
            RemoteFileCapabilities.safeChild(plan.directory, plan.requestedName) == plan.finalPath &&
            plan.stagedPath.substringBeforeLast('/', "").ifEmpty { "/" } ==
            plan.directory.trimEnd('/').ifEmpty { "/" } &&
            plan.stagedPath.substringAfterLast('/').startsWith(".sheen-") &&
            plan.stagedPath.endsWith(".part")

    private fun autoRenamedName(requestedName: String, existing: Set<String>): String? {
        val dot = requestedName.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { requestedName.substring(0, it) } ?: requestedName
        val extension = dot?.let { requestedName.substring(it) }.orEmpty()
        return (1..MAX_AUTO_RENAME_ATTEMPTS)
            .asSequence()
            .map { "$stem ($it)$extension" }
            .firstOrNull { it !in existing }
    }

    private suspend fun commitRemoteOverwrite(
        client: AdbProtocolClient,
        plan: RemoteUploadPlan,
        targetPath: String,
        existing: Set<String>,
    ): Boolean {
        val backupName = generateSequence {
            ".sheen-${UUID.randomUUID().toString().replace("-", "").take(16)}.bak"
        }.first { it !in existing }
        val backupPath = RemoteFileCapabilities.safeChild(plan.directory, backupName)
        if (!remoteMove(client, targetPath, backupPath)) return false
        if (!remoteMove(client, plan.stagedPath, targetPath)) {
            remoteMove(client, backupPath, targetPath)
            return false
        }
        if (!remoteDelete(client, backupPath)) {
            remoteMove(client, backupPath, targetPath)
            return false
        }
        return true
    }

    private suspend fun remoteMove(client: AdbProtocolClient, source: String, target: String): Boolean =
        executeRemoteFileCommand(client, "mv ${shellQuote(source)} ${shellQuote(target)}").exitCode == 0

    private suspend fun remoteDelete(client: AdbProtocolClient, target: String): Boolean =
        executeRemoteFileCommand(client, "rm -f ${shellQuote(target)}").exitCode == 0

    private suspend fun executeRemoteFileCommand(
        client: AdbProtocolClient,
        command: String,
    ): ProtocolShellResponse = runInterruptible(ioDispatcher) { client.execute(command) }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun ProtocolRemoteStat.hasReliableTransferMetadata(): Boolean =
        size >= 0L && modifiedEpochSeconds > 0L

    private fun ProtocolRemoteStat.sameTransferIdentity(other: ProtocolRemoteStat): Boolean =
        size == other.size &&
            modifiedEpochSeconds == other.modifiedEpochSeconds &&
            (deviceId == null || other.deviceId == null || deviceId == other.deviceId) &&
            (inode == null || other.inode == null || inode == other.inode)

    private suspend fun remoteDigest(client: AdbProtocolClient, path: String): String? {
        val escaped = path.replace("'", "'\\''")
        val commands = listOf(
            "toybox sha256sum -- '$escaped'",
            "sha256sum -- '$escaped'",
        )
        val response = commands.firstNotNullOfOrNull { command ->
            executeRemoteFileCommand(client, command).takeIf { it.exitCode == 0 }
        } ?: return null
        return response.stdout.trim().substringBefore(' ').takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
    }

    private fun fileTransferError(error: Throwable): AdbError {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val messages = causes.mapNotNull { it.message?.lowercase() }
        return when {
            causes.any { it is ProtocolLocalSourceException } -> AdbError.LocalFileReadFailed
            causes.any { it is ProtocolLocalDestinationException } -> AdbError.LocalFileWriteFailed
            messages.any { "permission denied" in it || "access denied" in it } ->
                AdbError.RemoteFilePermissionDenied
            messages.any { "no such file" in it || "not found" in it } ->
                AdbError.RemoteFilePathNotFound
            causes.any { it is java.io.EOFException || it.javaClass.simpleName.contains("StreamClosed", true) } ->
                AdbError.RemoteFileStreamClosed
            else -> AdbExceptionMapper.map(error, AdbOperationStage.FILE_TRANSFER)
        }
    }

    private suspend fun resolveSharedStorage(
        session: ActiveSession,
        userId: Int,
        timeout: Duration,
    ): Pair<String, ProtocolDirectoryListing> {
        for (candidate in RemoteFileCapabilities.sharedStorageCandidates(userId)) {
            val listing = try {
                KadbRemoteFileProtocol.list(session.client, candidate, timeout)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (listing != null) return candidate to listing
        }
        throw IOException("No shared storage path")
    }

    private suspend fun toRemotePathEntry(
        session: ActiveSession,
        directory: String,
        entry: ProtocolRemoteEntry,
        version: ProtocolSyncVersion,
        currentDirectoryStat: ProtocolRemoteStat?,
        timeout: Duration,
    ): RemotePathEntry {
        val kind = fileKind(entry.mode)
        val child = RemoteFileCapabilities.safeChild(directory, entry.name)
        var resolution = if (kind == RemoteFileKind.SYMLINK) RemoteLinkResolution.UNSUPPORTED else RemoteLinkResolution.NOT_A_LINK
        var targetKind: RemoteFileKind? = null
        if (kind == RemoteFileKind.SYMLINK && version == ProtocolSyncVersion.V2) {
            try {
                val target = KadbRemoteFileProtocol.stat(session.client, child, timeout)
                targetKind = fileKind(target.mode)
                resolution = if (
                    currentDirectoryStat?.deviceId != null && currentDirectoryStat.inode != null &&
                    target.deviceId == currentDirectoryStat.deviceId && target.inode == currentDirectoryStat.inode
                ) RemoteLinkResolution.LOOP else RemoteLinkResolution.VERIFIED
            } catch (error: Throwable) {
                resolution = when {
                    error.message.orEmpty().contains("permission", ignoreCase = true) -> RemoteLinkResolution.PERMISSION_DENIED
                    else -> RemoteLinkResolution.MISSING
                }
            }
        }
        return RemotePathEntry(
            absolutePath = child,
            displayName = entry.name,
            kind = kind,
            sizeBytes = entry.size.takeIf { kind == RemoteFileKind.FILE },
            modifiedEpochSeconds = entry.modifiedEpochSeconds,
            mode = entry.mode,
            deviceId = entry.deviceId,
            inode = entry.inode,
            linkResolution = resolution,
            targetKind = targetKind,
        )
    }

    private fun fileKind(mode: Int): RemoteFileKind = when (mode and 0xF000) {
        0x4000 -> RemoteFileKind.DIRECTORY
        0x8000 -> RemoteFileKind.FILE
        0xA000 -> RemoteFileKind.SYMLINK
        else -> RemoteFileKind.OTHER
    }

    private fun kindOrder(kind: RemoteFileKind): Int = when (kind) {
        RemoteFileKind.DIRECTORY -> 0
        RemoteFileKind.SYMLINK -> 1
        RemoteFileKind.FILE -> 2
        RemoteFileKind.OTHER -> 3
    }

    private fun remoteFileError(error: Throwable): AdbError = when {
        error.message.orEmpty().contains("permission", ignoreCase = true) -> AdbError.RemotePermissionDenied
        error.message.orEmpty().contains("not found", ignoreCase = true) -> AdbError.RemotePathNotFound
        else -> AdbExceptionMapper.map(error, AdbOperationStage.FILE_BROWSER)
    }

    override suspend fun connect(endpoint: AdbEndpoint, timeout: Duration): AdbOperationResult<Unit> =
        mutex.withLock {
            if (closed.get()) return@withLock failure(AdbError.Unknown(AdbOperationStage.CONNECT), endpoint, null)
            terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
            runInterruptible(ioDispatcher) { closeActiveIfPresent() }
            appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.STARTED, "ADB_CONNECT_STARTED", endpoint)
            mutableState.value = AdbConnectionState.Connecting(endpoint)
            var candidate: AdbProtocolClient? = null
            var adopted = false
            var awaitingAuthorization = false
            try {
                withTimeout(timeout) {
                    var ready = false
                    while (!ready) {
                        try {
                            val probe = runInterruptible(ioDispatcher) {
                                val opened = clientFactory.open(endpoint)
                                candidate = opened
                                opened.execute(CONNECTION_PROBE)
                            }
                            if (probe.exitCode != 0) throw ProtocolProbeException()
                            ready = true
                        } catch (error: Throwable) {
                            if (!isLegacyAuthorization(error)) throw error
                            withContext(NonCancellable + ioDispatcher) { runCatching { candidate?.close() } }
                            candidate = null
                            awaitingAuthorization = true
                            mutableState.value = AdbConnectionState.AwaitingAuthorization(endpoint)
                            appendDiagnostic(
                                AdbOperationStage.AUTHENTICATE,
                                AdbDiagnosticOutcome.STARTED,
                                "ADB_AWAITING_RSA_AUTHORIZATION",
                                endpoint,
                            )
                            delay(AUTHORIZATION_RETRY_MILLIS)
                        }
                    }
                }
                val session = ActiveSession(UUID.randomUUID().toString(), endpoint, checkNotNull(candidate))
                active = session
                adopted = true
                mutableState.value = AdbConnectionState.Connected(endpoint, session.id)
                appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.SUCCEEDED, "ADB_CONNECT_SUCCEEDED", endpoint)
                AdbOperationResult.Success(Unit)
            } catch (error: TimeoutCancellationException) {
                if (awaitingAuthorization) {
                    failure(AdbError.DeviceRejected(AdbOperationStage.AUTHENTICATE), endpoint, error)
                } else {
                    failure(AdbError.Timeout(AdbOperationStage.CONNECT), endpoint, error)
                }
            } catch (error: CancellationException) {
                mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.CONNECT_CANCELLED)
                appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.CANCELLED, "ADB_CONNECT_CANCELLED", endpoint)
                AdbOperationResult.Cancelled
            } catch (error: Throwable) {
                val mapped = AdbExceptionMapper.map(error, AdbOperationStage.CONNECT)
                failure(mapped, endpoint, error)
            } finally {
                if (!adopted && candidate != null) {
                    withContext(NonCancellable + ioDispatcher) { runCatching { candidate.close() } }
                    appendDiagnostic(
                        AdbOperationStage.CONNECT,
                        AdbDiagnosticOutcome.RESOURCE_CLOSED,
                        "ADB_CANDIDATE_CLOSED",
                        endpoint,
                    )
                }
            }
        }

    override suspend fun pair(
        pairingEndpoint: AdbEndpoint,
        pairingCode: CharArray,
        timeout: Duration,
    ): AdbOperationResult<Unit> = mutex.withLock {
        if (closed.get()) {
            pairingCode.fill('\u0000')
            return@withLock failure(AdbError.Unknown(AdbOperationStage.PAIR), pairingEndpoint, null)
        }
        terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
        runInterruptible(ioDispatcher) { closeActiveIfPresent() }
        appendDiagnostic(AdbOperationStage.PAIR, AdbDiagnosticOutcome.STARTED, "ADB_PAIR_STARTED", pairingEndpoint)
        mutableState.value = AdbConnectionState.Pairing(pairingEndpoint)
        try {
            require(pairingCode.size == 6 && pairingCode.all(Char::isDigit))
            withTimeout(timeout) { withContext(ioDispatcher) { clientFactory.pair(pairingEndpoint, pairingCode) } }
            mutableState.value = AdbConnectionState.Disconnected()
            appendDiagnostic(AdbOperationStage.PAIR, AdbDiagnosticOutcome.SUCCEEDED, "ADB_PAIR_SUCCEEDED", pairingEndpoint)
            AdbOperationResult.Success(Unit)
        } catch (error: TimeoutCancellationException) {
            failure(AdbError.Timeout(AdbOperationStage.PAIR), pairingEndpoint, error)
        } catch (error: CancellationException) {
            mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.PAIR_CANCELLED)
            appendDiagnostic(AdbOperationStage.PAIR, AdbDiagnosticOutcome.CANCELLED, "ADB_PAIR_CANCELLED", pairingEndpoint)
            AdbOperationResult.Cancelled
        } catch (error: IllegalArgumentException) {
            failure(AdbError.DeviceRejected(AdbOperationStage.PAIR), pairingEndpoint, error)
        } catch (error: Throwable) {
            val mapped = AdbExceptionMapper.map(error, AdbOperationStage.PAIR)
            failure(mapped, pairingEndpoint, error)
        } finally {
            pairingCode.fill('\u0000')
        }
    }

    override suspend fun executeShell(command: String, timeout: Duration): AdbOperationResult<ShellResult> =
        mutex.withLock {
            val session = active ?: return@withLock failure(
                AdbError.RemoteClosed(AdbOperationStage.SHELL),
                null,
                null,
            )
            appendDiagnostic(AdbOperationStage.SHELL, AdbDiagnosticOutcome.STARTED, "ADB_SHELL_STARTED", session.endpoint)
            val mark = TimeSource.Monotonic.markNow()
            var commandStream: ProtocolShellCommand? = null
            try {
                val response = withTimeout(timeout) {
                    runInterruptible(ioDispatcher) {
                        session.client.openShellCommand(command).also { commandStream = it }.execute()
                    }
                }
                if (active?.id != session.id) {
                    return@withLock operationFailure(
                        AdbError.RemoteClosed(AdbOperationStage.SHELL),
                        session.endpoint,
                        null,
                    )
                }
                AdbOperationResult.Success(
                    ShellResult(
                        response.stdout,
                        response.stderr,
                        response.exitCode,
                        mark.elapsedNow(),
                        if (response.streamsSeparated) ShellOutputMode.SEPARATED else ShellOutputMode.MERGED,
                        response.wasTruncated,
                    ),
                ).also {
                    appendDiagnostic(
                        AdbOperationStage.SHELL,
                        AdbDiagnosticOutcome.SUCCEEDED,
                        "ADB_SHELL_SUCCEEDED",
                        session.endpoint,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                operationFailure(AdbError.Timeout(AdbOperationStage.SHELL), session.endpoint, error)
            } catch (error: CancellationException) {
                appendDiagnostic(AdbOperationStage.SHELL, AdbDiagnosticOutcome.CANCELLED, "ADB_SHELL_CANCELLED", session.endpoint)
                AdbOperationResult.Cancelled
            } catch (error: Throwable) {
                val mapped = AdbExceptionMapper.map(error, AdbOperationStage.SHELL)
                operationFailure(mapped, session.endpoint, error)
            } finally {
                withContext(NonCancellable + ioDispatcher) { runCatching { commandStream?.close() } }
                if (commandStream != null) {
                    appendDiagnostic(
                        AdbOperationStage.SHELL,
                        AdbDiagnosticOutcome.RESOURCE_CLOSED,
                        "ADB_SHELL_STREAM_CLOSED",
                        session.endpoint,
                    )
                }
            }
        }

    override suspend fun loadDeviceOverview(timeout: Duration): AdbOperationResult<DeviceOverview> {
        val outputs = mutableListOf<String>()
        for (command in listOf(
            AdbCommands.PROPERTIES,
            AdbCommands.MEMORY,
            AdbCommands.STORAGE,
            AdbCommands.BATTERY,
            AdbCommands.UPTIME,
            AdbCommands.CORES,
            AdbCommands.NETWORK,
        )) {
            when (val result = executeShell(command, timeout)) {
                is AdbOperationResult.Success -> outputs += result.value.stdout
                is AdbOperationResult.Failure -> return result
                AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
            }
        }
        return AdbOperationResult.Success(
            AdbCapabilityParsers.overview(
                propertiesText = outputs[0],
                memoryText = outputs[1],
                storageText = outputs[2],
                batteryText = outputs[3],
                uptimeText = outputs[4],
                coresText = outputs[5],
                networkText = outputs[6],
            ),
        )
    }

    override suspend fun refreshDynamicMetrics(timeout: Duration): AdbOperationResult<DynamicDeviceMetrics> {
        val outputs = mutableListOf<String>()
        for (command in listOf(AdbCommands.MEMORY, AdbCommands.BATTERY, AdbCommands.UPTIME)) {
            when (val result = executeShell(command, timeout)) {
                is AdbOperationResult.Success -> outputs += result.value.stdout
                is AdbOperationResult.Failure -> return result
                AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
            }
        }
        return AdbOperationResult.Success(AdbCapabilityParsers.dynamic(outputs[0], outputs[1], outputs[2]))
    }

    override suspend fun listProcesses(timeout: Duration): AdbOperationResult<ProcessSnapshot> {
        val primary = executeShell(AdbCommands.PROCESSES_EXTENDED, timeout)
        if (primary is AdbOperationResult.Success && primary.value.exitCode == 0) {
            val parsed = AdbCapabilityParsers.processes(primary.value.stdout)
            if (parsed.processes.isNotEmpty()) return AdbOperationResult.Success(parsed)
        } else if (primary is AdbOperationResult.Failure) {
            return primary
        } else if (primary is AdbOperationResult.Cancelled) {
            return AdbOperationResult.Cancelled
        }
        return when (val fallback = executeShell(AdbCommands.PROCESSES_FALLBACK, timeout)) {
            is AdbOperationResult.Success -> AdbOperationResult.Success(AdbCapabilityParsers.processes(fallback.value.stdout))
            is AdbOperationResult.Failure -> fallback
            AdbOperationResult.Cancelled -> AdbOperationResult.Cancelled
        }
    }

    override suspend fun listApplications(timeout: Duration): AdbOperationResult<ApplicationSnapshot> =
        applicationMutex.withLock {
            val session = mutex.withLock { active } ?: return@withLock applicationFailure(
                AdbError.ApplicationSessionInvalid(AdbOperationStage.APPLICATIONS_LIST),
                null,
            )
            appendDiagnostic(
                AdbOperationStage.APPLICATIONS_LIST,
                AdbDiagnosticOutcome.STARTED,
                "ADB_APP_LIST_STARTED",
                session.endpoint,
            )
            try {
                withTimeout(timeout) { loadApplicationSnapshot(session, timeout, remember = true) }
            } catch (error: TimeoutCancellationException) {
                applicationFailure(AdbError.Timeout(AdbOperationStage.APPLICATIONS_LIST), session.endpoint)
            } catch (error: CancellationException) {
                appendDiagnostic(
                    AdbOperationStage.APPLICATIONS_LIST,
                    AdbDiagnosticOutcome.CANCELLED,
                    "ADB_APP_LIST_CANCELLED",
                    session.endpoint,
                )
                AdbOperationResult.Cancelled
            }
        }

    override suspend fun forceStopApplication(
        packageName: String,
        expectedSessionId: String,
        timeout: Duration,
    ): AdbOperationResult<ApplicationMutationResult> = applicationMutex.withLock {
        mutateApplication(
            packageName = packageName,
            expectedSessionId = expectedSessionId,
            timeout = timeout,
            stage = AdbOperationStage.APPLICATION_FORCE_STOP,
            enabled = null,
        )
    }

    override suspend fun setApplicationEnabled(
        packageName: String,
        enabled: Boolean,
        expectedSessionId: String,
        timeout: Duration,
    ): AdbOperationResult<ApplicationMutationResult> = applicationMutex.withLock {
        mutateApplication(
            packageName = packageName,
            expectedSessionId = expectedSessionId,
            timeout = timeout,
            stage = AdbOperationStage.APPLICATION_SET_ENABLED,
            enabled = enabled,
        )
    }

    private suspend fun mutateApplication(
        packageName: String,
        expectedSessionId: String,
        timeout: Duration,
        stage: AdbOperationStage,
        enabled: Boolean?,
    ): AdbOperationResult<ApplicationMutationResult> {
        val session = mutex.withLock { active }
            ?: return applicationFailure(AdbError.ApplicationSessionInvalid(stage), null)
        if (session.id != expectedSessionId) {
            return applicationFailure(AdbError.ApplicationSessionInvalid(stage), session.endpoint)
        }
        val remembered = applicationSnapshot
        val rememberedTarget = remembered?.takeIf { it.sessionId == expectedSessionId }
            ?.applications?.singleOrNull { it.packageName == packageName }
        if (!isAllowedApplicationTarget(session, rememberedTarget, packageName, enabled != null)) {
            return applicationFailure(AdbError.ApplicationTargetNotAllowed(stage), session.endpoint)
        }

        appendDiagnostic(stage, AdbDiagnosticOutcome.STARTED, "ADB_APP_MUTATION_STARTED", session.endpoint)
        var dispatched = false
        return try {
            withTimeout(timeout) {
                val fresh = when (val loaded = loadApplicationSnapshot(session, timeout, remember = true)) {
                    is AdbOperationResult.Success -> loaded.value
                    is AdbOperationResult.Failure -> return@withTimeout loaded
                    AdbOperationResult.Cancelled -> return@withTimeout AdbOperationResult.Cancelled
                }
                val freshTarget = fresh.applications.singleOrNull { it.packageName == packageName }
                    ?: return@withTimeout applicationFailure(AdbError.ApplicationPackageNotFound(stage), session.endpoint)
                if (!isAllowedApplicationTarget(session, freshTarget, packageName, enabled != null)) {
                    return@withTimeout applicationFailure(AdbError.ApplicationTargetNotAllowed(stage), session.endpoint)
                }
                if (mutex.withLock { active?.id } != expectedSessionId) {
                    return@withTimeout applicationFailure(AdbError.ApplicationSessionInvalid(stage), session.endpoint)
                }

                val command = if (enabled == null) {
                    ApplicationCommands.forceStop(fresh.userId, packageName)
                } else {
                    ApplicationCommands.setEnabled(fresh.userId, packageName, enabled)
                }
                dispatched = true
                when (val commandResult = executeShell(command, timeout)) {
                    is AdbOperationResult.Success -> {
                        val rejection = ApplicationParsers.rejectedOutput(
                            commandResult.value.stdout,
                            commandResult.value.stderr,
                            commandResult.value.exitCode,
                        )
                        if (rejection != null) {
                            return@withTimeout when (rejection) {
                                ApplicationCommandRejection.PACKAGE_NOT_FOUND -> applicationFailure(
                                    AdbError.ApplicationPackageNotFound(stage),
                                    session.endpoint,
                                )
                                ApplicationCommandRejection.POLICY -> applicationFailure(
                                    AdbError.ApplicationPolicyRejected(stage),
                                    session.endpoint,
                                )
                            }
                        }
                    }
                    is AdbOperationResult.Failure -> {
                        if (active?.id == session.id) withContext(NonCancellable) { closeSession(session) }
                        return@withTimeout unknownMutation(session, stage)
                    }
                    AdbOperationResult.Cancelled -> {
                        if (active?.id == session.id) withContext(NonCancellable) { closeSession(session) }
                        return@withTimeout unknownMutation(session, stage)
                    }
                }

                if (enabled == null) {
                    appendDiagnostic(stage, AdbDiagnosticOutcome.SUCCEEDED, "ADB_APP_FORCE_STOP_ACCEPTED", session.endpoint)
                    return@withTimeout AdbOperationResult.Success(ApplicationMutationResult.RequestAccepted(session.id))
                }

                val verifiedSnapshot = when (val loaded = loadApplicationSnapshot(session, timeout, remember = true)) {
                    is AdbOperationResult.Success -> loaded.value
                    is AdbOperationResult.Failure -> return@withTimeout unknownMutation(session, stage)
                    AdbOperationResult.Cancelled -> return@withTimeout unknownMutation(session, stage)
                }
                val verifiedTarget = verifiedSnapshot.applications.singleOrNull { it.packageName == packageName }
                    ?: return@withTimeout applicationFailure(
                        AdbError.ApplicationPackageNotFound(AdbOperationStage.APPLICATION_VERIFY),
                        session.endpoint,
                    )
                val expectedState = if (enabled) RemoteApplicationEnabledState.ENABLED else RemoteApplicationEnabledState.DISABLED
                if (verifiedTarget.enabledState != expectedState) {
                    return@withTimeout applicationFailure(AdbError.ApplicationStateVerifyFailed, session.endpoint)
                }
                appendDiagnostic(
                    AdbOperationStage.APPLICATION_VERIFY,
                    AdbDiagnosticOutcome.SUCCEEDED,
                    "ADB_APP_STATE_VERIFIED",
                    session.endpoint,
                )
                AdbOperationResult.Success(ApplicationMutationResult.Verified(session.id, verifiedTarget))
            }
        } catch (error: TimeoutCancellationException) {
            if (dispatched) {
                if (active?.id == session.id) withContext(NonCancellable) { closeSession(session) }
                unknownMutation(session, stage)
            }
            else applicationFailure(AdbError.Timeout(stage), session.endpoint)
        } catch (error: CancellationException) {
            if (dispatched) {
                if (active?.id == session.id) withContext(NonCancellable) { closeSession(session) }
                unknownMutation(session, stage)
            } else AdbOperationResult.Cancelled
        }
    }

    private suspend fun loadApplicationSnapshot(
        session: ActiveSession,
        timeout: Duration,
        remember: Boolean,
    ): AdbOperationResult<ApplicationSnapshot> {
        val userId = when (val current = currentUser(timeout)) {
            is AdbOperationResult.Success -> current.value
            is AdbOperationResult.Failure -> return current
            AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        if (mutex.withLock { active?.id } != session.id) {
            return applicationFailure(
                AdbError.ApplicationSessionInvalid(AdbOperationStage.APPLICATIONS_LIST),
                session.endpoint,
            )
        }
        val all = when (val parsed = queryPackageNames(userId, disabledOnly = false, timeout)) {
            is PackageQueryResult.Names -> parsed.names
            PackageQueryResult.Empty -> linkedSetOf()
            PackageQueryResult.CapacityExceeded -> return applicationFailure(
                AdbError.ApplicationListCapacityExceeded,
                session.endpoint,
            )
            PackageQueryResult.Unsupported -> return applicationFailure(
                AdbError.ApplicationListUnsupported,
                session.endpoint,
            )
            is PackageQueryResult.OperationFailure -> return parsed.result
            PackageQueryResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        var degradedReason: String? = OPTIONAL_FIELDS_UNAVAILABLE_REASON
        val disabled = when (val parsed = queryPackageNames(userId, disabledOnly = true, timeout)) {
            is PackageQueryResult.Names -> parsed.names
            PackageQueryResult.Empty -> linkedSetOf()
            PackageQueryResult.CapacityExceeded -> return applicationFailure(
                AdbError.ApplicationListCapacityExceeded,
                session.endpoint,
            )
            PackageQueryResult.Unsupported -> null.also {
                degradedReason = "$OPTIONAL_FIELDS_UNAVAILABLE_REASON；ROM 未提供可靠启用状态，相关操作已禁用"
            }
            is PackageQueryResult.OperationFailure -> return parsed.result
            PackageQueryResult.Cancelled -> return AdbOperationResult.Cancelled
        }
        val applications = all.map { packageName ->
            RemoteApplication(
                packageName = packageName,
                userId = userId,
                enabledState = when {
                    disabled == null -> RemoteApplicationEnabledState.UNKNOWN
                    packageName in disabled -> RemoteApplicationEnabledState.DISABLED
                    else -> RemoteApplicationEnabledState.ENABLED
                },
                isSystem = false,
            )
        }
        val snapshot = ApplicationSnapshot(
            sessionId = session.id,
            userId = userId,
            applications = applications,
            unavailableFields = ApplicationField.entries.toSet(),
            degradedReason = degradedReason,
        )
        if (remember && mutex.withLock { active?.id } == session.id) applicationSnapshot = snapshot
        appendDiagnostic(
            AdbOperationStage.APPLICATIONS_LIST,
            AdbDiagnosticOutcome.SUCCEEDED,
            "ADB_APP_LIST_SUCCEEDED",
            session.endpoint,
        )
        return AdbOperationResult.Success(snapshot)
    }

    private suspend fun currentUser(timeout: Duration): AdbOperationResult<Int> {
        for (command in listOf(ApplicationCommands.CURRENT_USER, ApplicationCommands.CURRENT_USER_FALLBACK)) {
            when (val result = executeShell(command, timeout)) {
                is AdbOperationResult.Success -> if (result.value.exitCode == 0) {
                    ApplicationParsers.currentUser(result.value.stdout)?.let { return AdbOperationResult.Success(it) }
                }
                is AdbOperationResult.Failure -> return restagedFailure(result, AdbOperationStage.APPLICATIONS_LIST)
                AdbOperationResult.Cancelled -> return AdbOperationResult.Cancelled
            }
        }
        return applicationFailure(AdbError.ApplicationCurrentUserUnavailable, mutex.withLock { active?.endpoint })
    }

    private suspend fun queryPackageNames(
        userId: Int,
        disabledOnly: Boolean,
        timeout: Duration,
    ): PackageQueryResult {
        repeat(2) { index ->
            val command = if (disabledOnly) {
                ApplicationCommands.listDisabledThirdParty(userId, fallback = index == 1)
            } else {
                ApplicationCommands.listThirdParty(userId, fallback = index == 1)
            }
            when (val result = executeShell(command, timeout)) {
                is AdbOperationResult.Success -> if (result.value.exitCode == 0) {
                    when (val parsed = ApplicationParsers.packageNames(result.value.stdout)) {
                        is PackageNamesParse.Success -> return PackageQueryResult.Names(parsed.names)
                        PackageNamesParse.Empty -> return PackageQueryResult.Empty
                        PackageNamesParse.CapacityExceeded -> return PackageQueryResult.CapacityExceeded
                        PackageNamesParse.Malformed -> Unit
                    }
                }
                is AdbOperationResult.Failure -> return PackageQueryResult.OperationFailure(
                    restagedFailure(result, AdbOperationStage.APPLICATIONS_LIST),
                )
                AdbOperationResult.Cancelled -> return PackageQueryResult.Cancelled
            }
        }
        return PackageQueryResult.Unsupported
    }

    private fun isAllowedApplicationTarget(
        session: ActiveSession,
        target: RemoteApplication?,
        packageName: String,
        stateMutation: Boolean,
    ): Boolean {
        if (!ApplicationParsers.isValidPackageName(packageName)) return false
        if (target == null || target.packageName != packageName || target.isSystem || target.userId < 0) return false
        if (target.enabledState == RemoteApplicationEnabledState.UNKNOWN) return false
        if (stateMutation && target.enabledState == RemoteApplicationEnabledState.UNKNOWN) return false
        val local = session.endpoint.host.equals("127.0.0.1", true) ||
            session.endpoint.host.equals("::1", true) || session.endpoint.host.equals("localhost", true)
        return !(local && packageName == SELF_PACKAGE_NAME)
    }

    private fun unknownMutation(
        session: ActiveSession,
        stage: AdbOperationStage,
    ): AdbOperationResult.Success<ApplicationMutationResult> {
        val reason = AdbError.ApplicationOutcomeUnknown(stage)
        val state = mutableState.value
        if (active?.id != session.id) {
            mutableState.value = if (state is AdbConnectionState.Error) {
                state.copy(error = reason)
            } else {
                AdbConnectionState.Error(
                    reason,
                    "code=${reason.technicalCode}; target=${session.endpoint.redacted()}",
                )
            }
        }
        appendDiagnostic(stage, AdbDiagnosticOutcome.FAILED, reason.technicalCode, session.endpoint)
        return AdbOperationResult.Success(ApplicationMutationResult.OutcomeUnknown(session.id, reason))
    }

    private fun applicationFailure(
        error: AdbError,
        endpoint: AdbEndpoint?,
    ): AdbOperationResult.Failure {
        appendDiagnostic(error.stage, AdbDiagnosticOutcome.FAILED, error.technicalCode, endpoint)
        return AdbOperationResult.Failure(error)
    }

    private fun restagedFailure(
        failure: AdbOperationResult.Failure,
        stage: AdbOperationStage,
    ): AdbOperationResult.Failure {
        val error = when (failure.error) {
            is AdbError.NetworkUnreachable -> AdbError.NetworkUnreachable(stage)
            is AdbError.Timeout -> AdbError.Timeout(stage)
            is AdbError.AuthenticationFailed -> AdbError.AuthenticationFailed(stage)
            is AdbError.DeviceRejected -> AdbError.DeviceRejected(stage)
            is AdbError.ProtocolIncompatible -> AdbError.ProtocolIncompatible(stage)
            is AdbError.RemoteClosed -> AdbError.RemoteClosed(stage)
            is AdbError.Unknown -> AdbError.Unknown(stage)
            else -> failure.error
        }
        val state = mutableState.value
        if (state is AdbConnectionState.Error) mutableState.value = state.copy(error = error)
        return AdbOperationResult.Failure(error)
    }

    private sealed interface PackageQueryResult {
        data class Names(val names: LinkedHashSet<String>) : PackageQueryResult
        data class OperationFailure(val result: AdbOperationResult.Failure) : PackageQueryResult
        data object Empty : PackageQueryResult
        data object Unsupported : PackageQueryResult
        data object CapacityExceeded : PackageQueryResult
        data object Cancelled : PackageQueryResult
    }

    override fun streamLogcat(config: LogcatConfig): Flow<AdbOperationResult<LogcatLine>> = flow {
        val session = mutex.withLock { active }
        if (session == null) {
            emit(AdbOperationResult.Failure(AdbError.RemoteClosed(AdbOperationStage.LOGCAT)))
            return@flow
        }
        appendDiagnostic(AdbOperationStage.LOGCAT, AdbDiagnosticOutcome.STARTED, "ADB_LOGCAT_STARTED", session.endpoint)
        var stream: ProtocolShellStream? = null
        val stdout = LineChunkDecoder()
        val stderr = LineChunkDecoder()
        try {
            val openedStream = runInterruptible(ioDispatcher) {
                session.client.openShellStream(AdbCommands.logcat(config))
            }
            stream = openedStream
            var finished = false
            while (!finished) {
                when (val packet = runInterruptible(ioDispatcher) { openedStream.read() }) {
                    is ProtocolShellPacket.StandardOutput -> stdout.append(packet.bytes).forEach {
                        emit(AdbOperationResult.Success(LogcatLine(it)))
                    }
                    is ProtocolShellPacket.StandardError -> stderr.append(packet.bytes).forEach {
                        emit(AdbOperationResult.Success(LogcatLine(it, fromStandardError = true)))
                    }
                    is ProtocolShellPacket.Exit -> {
                        emit(
                            operationFailure(
                                AdbError.CommandStreamClosed(AdbOperationStage.LOGCAT),
                                session.endpoint,
                                null,
                            ),
                        )
                        finished = true
                    }
                }
            }
            stdout.finish().forEach { emit(AdbOperationResult.Success(LogcatLine(it))) }
            stderr.finish().forEach { emit(AdbOperationResult.Success(LogcatLine(it, true))) }
        } catch (error: CancellationException) {
            appendDiagnostic(AdbOperationStage.LOGCAT, AdbDiagnosticOutcome.CANCELLED, "ADB_LOGCAT_CANCELLED", session.endpoint)
            throw error
        } catch (error: Throwable) {
            val mapped = AdbExceptionMapper.map(error, AdbOperationStage.LOGCAT)
            emit(operationFailure(mapped, session.endpoint, error))
        } finally {
            withContext(NonCancellable + ioDispatcher) { runCatching { stream?.close() } }
            appendDiagnostic(
                AdbOperationStage.LOGCAT,
                AdbDiagnosticOutcome.RESOURCE_CLOSED,
                "ADB_LOGCAT_STREAM_CLOSED",
                session.endpoint,
            )
        }
    }

    private suspend fun claimWirelessDiscovery(): ActiveWirelessDiscovery? = mutex.withLock {
        synchronized(wirelessDiscoveryLock) {
            if (closed.get() || activeWirelessDiscovery != null) return@synchronized null
            ActiveWirelessDiscovery(
                generation = wirelessDiscoveryGeneration.incrementAndGet(),
                ownerSessionId = active?.id,
            ).also { activeWirelessDiscovery = it }
        }
    }

    private fun publishWirelessDiscoveryEvent(
        discovery: ActiveWirelessDiscovery,
        event: WirelessDiscoveryEvent,
    ) {
        val ownerChanged = synchronized(wirelessDiscoveryLock) {
            activeWirelessDiscovery === discovery && active?.id != discovery.ownerSessionId
        }
        if (ownerChanged) {
            terminateWirelessDiscovery(discovery, AdbError.DiscoverySessionChanged)
            return
        }
        val accepted = synchronized(wirelessDiscoveryLock) {
            activeWirelessDiscovery === discovery &&
                !discovery.isTerminal() &&
                event.generation == discovery.generation
        }
        if (accepted) discovery.signals.trySend(WirelessDiscoverySignal.Event(event))
    }

    private fun terminateWirelessDiscovery(
        discovery: ActiveWirelessDiscovery,
        error: AdbError,
    ): Boolean {
        val terminated = synchronized(wirelessDiscoveryLock) {
            activeWirelessDiscovery === discovery && discovery.markTerminal(error)
        }
        if (terminated || discovery.terminalError() != null) {
            completePendingWirelessDiscovery(discovery)
        }
        return terminated
    }

    private fun terminateActiveWirelessDiscovery(error: AdbError) {
        val discovery = synchronized(wirelessDiscoveryLock) {
            val current = activeWirelessDiscovery ?: return@synchronized null
            current.markTerminal(error)
            current
        }
        discovery?.let(::completePendingWirelessDiscovery)
    }

    private fun completePendingWirelessDiscovery(discovery: ActiveWirelessDiscovery): Boolean {
        val error = discovery.terminalError() ?: return false
        if (!discovery.closeSourceIfReady()) return false
        synchronized(wirelessDiscoveryLock) {
            if (activeWirelessDiscovery === discovery) activeWirelessDiscovery = null
        }
        discovery.publishTerminal(error)
        return true
    }

    private fun retireWirelessDiscovery(discovery: ActiveWirelessDiscovery) {
        if (discovery.terminalError() != null) {
            completePendingWirelessDiscovery(discovery)
            return
        }
        val ownsRetirement = synchronized(wirelessDiscoveryLock) {
            activeWirelessDiscovery === discovery && discovery.markRetired()
        }
        if (!ownsRetirement) return
        if (discovery.closeSourceIfReady()) {
            synchronized(wirelessDiscoveryLock) {
                if (activeWirelessDiscovery === discovery) activeWirelessDiscovery = null
            }
            discovery.finishRetirement()
        }
    }

    private fun <T> captureWirelessDiscoveryCall(block: () -> T): WirelessDiscoveryCallResult<T> = try {
        WirelessDiscoveryCallResult.Value(block())
    } catch (error: InterruptedException) {
        throw error
    } catch (error: CancellationException) {
        WirelessDiscoveryCallResult.Cancelled(error)
    } catch (_: Throwable) {
        WirelessDiscoveryCallResult.Failed
    }

    private fun discoveryError(failure: WirelessDiscoverySourceFailure): AdbError = when (failure) {
        WirelessDiscoverySourceFailure.NETWORK_UNAVAILABLE -> AdbError.DiscoveryNetworkUnavailable
        WirelessDiscoverySourceFailure.PERMISSION_UNAVAILABLE -> AdbError.DiscoveryPermissionUnavailable
        WirelessDiscoverySourceFailure.RESOLUTION_FAILED -> AdbError.DiscoveryResolutionFailed
        WirelessDiscoverySourceFailure.PLATFORM_FAILURE -> AdbError.DiscoveryPlatformFailure
    }

    override suspend fun disconnect(timeout: Duration): AdbOperationResult<Unit> = mutex.withLock {
        terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
        val sessionToClose = active
        appendDiagnostic(AdbOperationStage.DISCONNECT, AdbDiagnosticOutcome.STARTED, "ADB_DISCONNECT_STARTED", sessionToClose?.endpoint)
        mutableState.value = AdbConnectionState.Disconnecting
        return@withLock try {
            withTimeout(timeout) { runInterruptible(ioDispatcher) { closeActiveIfPresent() } }
            mutableState.value = AdbConnectionState.Disconnected()
            appendDiagnostic(AdbOperationStage.DISCONNECT, AdbDiagnosticOutcome.SUCCEEDED, "ADB_DISCONNECT_SUCCEEDED", null)
            AdbOperationResult.Success(Unit)
        } catch (error: TimeoutCancellationException) {
            active = null
            applicationSnapshot = null
            failure(AdbError.Timeout(AdbOperationStage.DISCONNECT), null, error)
        } catch (error: CancellationException) {
            active = null
            applicationSnapshot = null
            mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.DISCONNECT_CANCELLED)
            appendDiagnostic(AdbOperationStage.DISCONNECT, AdbDiagnosticOutcome.CANCELLED, "ADB_DISCONNECT_CANCELLED", null)
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            active = null
            applicationSnapshot = null
            failure(AdbExceptionMapper.map(error, AdbOperationStage.DISCONNECT), null, error)
        } finally {
            if (sessionToClose != null) {
                invalidateExclusiveOperation(sessionToClose.id)
                if (active?.id == sessionToClose.id) active = null
                withContext(NonCancellable + ioDispatcher) { runCatching { sessionToClose.client.close() } }
            }
        }
    }

    override suspend fun clearHostIdentity(timeout: Duration): AdbOperationResult<Unit> = mutex.withLock {
        terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
        return@withLock try {
            withTimeout(timeout) {
                runInterruptible(ioDispatcher) {
                    closeActiveIfPresent()
                    clientFactory.clearIdentity()
                }
            }
            mutableState.value = AdbConnectionState.Disconnected()
            AdbOperationResult.Success(Unit)
        } catch (error: TimeoutCancellationException) {
            failure(AdbError.Timeout(AdbOperationStage.AUTHENTICATE), null, error)
        } catch (error: CancellationException) {
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            failure(AdbExceptionMapper.map(error, AdbOperationStage.AUTHENTICATE), null, error)
        }
    }

    override fun clearDiagnosticEvents() {
        mutableDiagnosticEvents.value = emptyList()
    }

    override fun reportInvalidAddress(userMessage: String) {
        failure(AdbError.InvalidAddress(userMessage.take(200)), null, null)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            terminateActiveWirelessDiscovery(AdbError.DiscoveryManagerClosed)
            val session = active
            active = null
            applicationSnapshot = null
            invalidateExclusiveOperation(session?.id)
            runCatching { session?.client?.close() }
            if (session != null) {
                appendDiagnostic(
                    AdbOperationStage.DISCONNECT,
                    AdbDiagnosticOutcome.RESOURCE_CLOSED,
                    "ADB_SESSION_CLOSED",
                    session.endpoint,
                )
            }
            mutableState.value = AdbConnectionState.Disconnected()
        }
    }

    private fun closeActiveIfPresent() {
        val session = active ?: return
        active = null
        applicationSnapshot = null
        invalidateExclusiveOperation(session.id)
        try {
            session.client.close()
        } finally {
            appendDiagnostic(
                AdbOperationStage.DISCONNECT,
                AdbDiagnosticOutcome.RESOURCE_CLOSED,
                "ADB_SESSION_CLOSED",
                session.endpoint,
            )
        }
    }

    private suspend fun closeSession(session: ActiveSession) {
        if (active?.id == session.id) {
            active = null
            applicationSnapshot = null
            terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
        }
        invalidateExclusiveOperation(session.id)
        withContext(NonCancellable + ioDispatcher) { runCatching { session.client.close() } }
        appendDiagnostic(
            AdbOperationStage.DISCONNECT,
            AdbDiagnosticOutcome.RESOURCE_CLOSED,
            "ADB_SESSION_CLOSED",
            session.endpoint,
        )
    }

    private suspend fun invalidateForcedTransferSession(session: ActiveSession) = mutex.withLock {
        if (active?.id != session.id) return@withLock
        active = null
        applicationSnapshot = null
        terminateActiveWirelessDiscovery(AdbError.DiscoverySessionChanged)
        invalidateExclusiveOperation(session.id)
        mutableState.value = AdbConnectionState.Disconnected()
        appendDiagnostic(
            AdbOperationStage.FILE_TRANSFER,
            AdbDiagnosticOutcome.RESOURCE_CLOSED,
            "ADB_TRANSFER_SESSION_FORCE_CLOSED",
            session.endpoint,
        )
    }

    private fun invalidateExclusiveOperation(sessionId: String?) {
        synchronized(exclusiveOperationLock) {
            val operation = activeExclusiveOperation ?: return
            if (sessionId == null || operation.sessionId == sessionId) {
                operation.active.set(false)
                activeExclusiveOperation = null
            }
        }
    }

    private inner class ManagerExclusiveOperationLease(
        private val operation: ActiveExclusiveOperation,
    ) : ExclusiveAdbOperationLease {
        override val token: String get() = operation.token
        override val sessionId: String get() = operation.sessionId
        override val kind: AdbExclusiveOperationKind get() = operation.kind

        override val isActive: Boolean
            get() = operation.active.get() && synchronized(exclusiveOperationLock) {
                activeExclusiveOperation === operation
            }

        override fun release() {
            if (!operation.active.compareAndSet(true, false)) return
            synchronized(exclusiveOperationLock) {
                if (activeExclusiveOperation === operation) activeExclusiveOperation = null
            }
        }
    }

    private fun failure(error: AdbError, endpoint: AdbEndpoint?, cause: Throwable?): AdbOperationResult.Failure {
        val details = cause?.let { AdbExceptionMapper.safeTechnicalDetails(it, endpoint) }
            ?: "code=${error.technicalCode}; target=${endpoint?.redacted() ?: "<无目标>"}"
        mutableState.value = AdbConnectionState.Error(error, DiagnosticRedactor.redact(details))
        appendDiagnostic(error.stage, AdbDiagnosticOutcome.FAILED, error.technicalCode, endpoint, cause)
        return AdbOperationResult.Failure(error)
    }

    private fun operationFailure(
        error: AdbError,
        endpoint: AdbEndpoint?,
        cause: Throwable?,
    ): AdbOperationResult.Failure {
        appendDiagnostic(error.stage, AdbDiagnosticOutcome.FAILED, error.technicalCode, endpoint, cause)
        return AdbOperationResult.Failure(error)
    }

    private fun appendDiagnostic(
        stage: AdbOperationStage,
        outcome: AdbDiagnosticOutcome,
        technicalCode: String,
        endpoint: AdbEndpoint?,
        cause: Throwable? = null,
    ) {
        val event = AdbDiagnosticEvent(
            sequence = diagnosticSequence.incrementAndGet(),
            stage = stage,
            outcome = outcome,
            technicalCode = technicalCode,
            redactedTarget = endpoint?.redacted() ?: "<无目标>",
            causeType = cause?.javaClass?.simpleName?.ifBlank { "Throwable" },
        )
        mutableDiagnosticEvents.update { current -> (current + event).takeLast(MAX_DIAGNOSTIC_EVENTS) }
    }

    private class ProtocolProbeException : Exception()

    private fun isLegacyAuthorization(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it.javaClass.simpleName == "AdbAuthException" }

    private class LineChunkDecoder {
        private var pending = ""

        fun append(bytes: ByteArray): List<String> {
            val combined = pending + bytes.toString(Charsets.UTF_8)
            val parts = combined.split('\n')
            pending = parts.last()
            return parts.dropLast(1).map { it.removeSuffix("\r") }
        }

        fun finish(): List<String> = pending.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty().also { pending = "" }
    }

    private companion object {
        const val CONNECTION_PROBE = "echo sheen-session-ready"
        const val MAX_DIAGNOSTIC_EVENTS = 100
        const val AUTHORIZATION_RETRY_MILLIS = 1_000L
        const val SELF_PACKAGE_NAME = "com.sheen.adbhelper"
        const val OPTIONAL_FIELDS_UNAVAILABLE_REASON =
            "设备基础包列表可用；版本号、版本名和安装器字段未通过跨 ROM 可靠性验证，已明确省略"
        val FILE_PREPARE_TIMEOUT = 5.seconds
        const val DEFAULT_REMOTE_FILE_MODE = 0x81A4
        const val MAX_AUTO_RENAME_ATTEMPTS = 1_000
    }
}
