package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticEvent
import com.sheen.adb.core.AdbDiagnosticOutcome
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.AdbOperationStage
import com.sheen.adb.core.AdbSessionManager
import com.sheen.adb.core.DiagnosticRedactor
import com.sheen.adb.core.DeviceOverview
import com.sheen.adb.core.DynamicDeviceMetrics
import com.sheen.adb.core.DisconnectionReason
import com.sheen.adb.core.LogcatConfig
import com.sheen.adb.core.LogcatLine
import com.sheen.adb.core.ProcessSnapshot
import com.sheen.adb.core.ShellResult
import com.sheen.adb.core.ShellOutputMode
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlin.time.TimeSource

internal class DefaultAdbSessionManager(
    private val clientFactory: AdbProtocolClientFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdbSessionManager, Closeable {
    private data class ActiveSession(
        val id: String,
        val endpoint: AdbEndpoint,
        val client: AdbProtocolClient,
    )

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val diagnosticSequence = AtomicLong(0)
    private var active: ActiveSession? = null
    private val mutableState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected())
    override val connectionState: StateFlow<AdbConnectionState> = mutableState.asStateFlow()
    private val mutableDiagnosticEvents = MutableStateFlow<List<AdbDiagnosticEvent>>(emptyList())
    override val diagnosticEvents: StateFlow<List<AdbDiagnosticEvent>> = mutableDiagnosticEvents.asStateFlow()

    override suspend fun connect(endpoint: AdbEndpoint, timeout: Duration): AdbOperationResult<Unit> =
        mutex.withLock {
            if (closed.get()) return@withLock failure(AdbError.Unknown(AdbOperationStage.CONNECT), endpoint, null)
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
            try {
                val response = withTimeout(timeout) {
                    runInterruptible(ioDispatcher) { session.client.execute(command) }
                }
                if (active?.id != session.id) {
                    return@withLock failure(AdbError.RemoteClosed(AdbOperationStage.SHELL), session.endpoint, null)
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
                closeSession(session)
                failure(AdbError.Timeout(AdbOperationStage.SHELL), session.endpoint, error)
            } catch (error: CancellationException) {
                closeSession(session)
                mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.SHELL_CANCELLED)
                appendDiagnostic(AdbOperationStage.SHELL, AdbDiagnosticOutcome.CANCELLED, "ADB_SHELL_CANCELLED", session.endpoint)
                AdbOperationResult.Cancelled
            } catch (error: Throwable) {
                closeSession(session)
                val mapped = AdbExceptionMapper.map(error, AdbOperationStage.SHELL)
                failure(mapped, session.endpoint, error)
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
                    is ProtocolShellPacket.Exit -> finished = true
                }
            }
            stdout.finish().forEach { emit(AdbOperationResult.Success(LogcatLine(it))) }
            stderr.finish().forEach { emit(AdbOperationResult.Success(LogcatLine(it, true))) }
            appendDiagnostic(AdbOperationStage.LOGCAT, AdbDiagnosticOutcome.SUCCEEDED, "ADB_LOGCAT_FINISHED", session.endpoint)
        } catch (error: CancellationException) {
            appendDiagnostic(AdbOperationStage.LOGCAT, AdbDiagnosticOutcome.CANCELLED, "ADB_LOGCAT_CANCELLED", session.endpoint)
            throw error
        } catch (error: Throwable) {
            val mapped = AdbExceptionMapper.map(error, AdbOperationStage.LOGCAT)
            closeSession(session)
            emit(failure(mapped, session.endpoint, error))
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

    override suspend fun disconnect(timeout: Duration): AdbOperationResult<Unit> = mutex.withLock {
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
            failure(AdbError.Timeout(AdbOperationStage.DISCONNECT), null, error)
        } catch (error: CancellationException) {
            active = null
            mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.DISCONNECT_CANCELLED)
            appendDiagnostic(AdbOperationStage.DISCONNECT, AdbDiagnosticOutcome.CANCELLED, "ADB_DISCONNECT_CANCELLED", null)
            AdbOperationResult.Cancelled
        } catch (error: Throwable) {
            active = null
            failure(AdbExceptionMapper.map(error, AdbOperationStage.DISCONNECT), null, error)
        } finally {
            if (sessionToClose != null) {
                if (active?.id == sessionToClose.id) active = null
                withContext(NonCancellable + ioDispatcher) { runCatching { sessionToClose.client.close() } }
            }
        }
    }

    override suspend fun clearHostIdentity(timeout: Duration): AdbOperationResult<Unit> = mutex.withLock {
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
            val session = active
            active = null
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
        if (active?.id == session.id) active = null
        withContext(NonCancellable + ioDispatcher) { runCatching { session.client.close() } }
        appendDiagnostic(
            AdbOperationStage.DISCONNECT,
            AdbDiagnosticOutcome.RESOURCE_CLOSED,
            "ADB_SESSION_CLOSED",
            session.endpoint,
        )
    }

    private fun failure(error: AdbError, endpoint: AdbEndpoint?, cause: Throwable?): AdbOperationResult.Failure {
        val details = cause?.let { AdbExceptionMapper.safeTechnicalDetails(it, endpoint) }
            ?: "code=${error.technicalCode}; target=${endpoint?.redacted() ?: "<无目标>"}"
        mutableState.value = AdbConnectionState.Error(error, DiagnosticRedactor.redact(details))
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
    }
}
