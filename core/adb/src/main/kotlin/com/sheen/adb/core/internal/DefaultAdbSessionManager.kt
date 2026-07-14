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
import com.sheen.adb.core.DisconnectionReason
import com.sheen.adb.core.ShellResult
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
            closeActiveIfPresent()
            appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.STARTED, "ADB_CONNECT_STARTED", endpoint)
            mutableState.value = AdbConnectionState.Connecting(endpoint)
            var candidate: AdbProtocolClient? = null
            var adopted = false
            try {
                withTimeout(timeout) {
                    val probe = runInterruptible(ioDispatcher) {
                        val opened = clientFactory.open(endpoint)
                        candidate = opened
                        opened.execute(CONNECTION_PROBE)
                    }
                    if (probe.exitCode != 0) throw ProtocolProbeException()
                }
                val session = ActiveSession(UUID.randomUUID().toString(), endpoint, checkNotNull(candidate))
                active = session
                adopted = true
                mutableState.value = AdbConnectionState.Connected(endpoint, session.id)
                appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.SUCCEEDED, "ADB_CONNECT_SUCCEEDED", endpoint)
                AdbOperationResult.Success(Unit)
            } catch (error: TimeoutCancellationException) {
                failure(AdbError.Timeout(AdbOperationStage.CONNECT), endpoint, error)
            } catch (error: CancellationException) {
                mutableState.value = AdbConnectionState.Disconnected(DisconnectionReason.CONNECT_CANCELLED)
                appendDiagnostic(AdbOperationStage.CONNECT, AdbDiagnosticOutcome.CANCELLED, "ADB_CONNECT_CANCELLED", endpoint)
                AdbOperationResult.Cancelled
            } catch (error: Throwable) {
                val mapped = AdbExceptionMapper.map(error, AdbOperationStage.CONNECT)
                failure(mapped, endpoint, error)
            } finally {
                if (!adopted && candidate != null) {
                    runCatching { candidate.close() }
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
        closeActiveIfPresent()
        appendDiagnostic(AdbOperationStage.PAIR, AdbDiagnosticOutcome.STARTED, "ADB_PAIR_STARTED", pairingEndpoint)
        mutableState.value = AdbConnectionState.Pairing(pairingEndpoint)
        try {
            require(pairingCode.size == 6 && pairingCode.all(Char::isDigit))
            withTimeout(timeout) { clientFactory.pair(pairingEndpoint, pairingCode) }
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
                    ShellResult(response.stdout, response.stderr, response.exitCode, mark.elapsedNow()),
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

    override suspend fun disconnect(timeout: Duration): AdbOperationResult<Unit> = mutex.withLock {
        appendDiagnostic(AdbOperationStage.DISCONNECT, AdbDiagnosticOutcome.STARTED, "ADB_DISCONNECT_STARTED", active?.endpoint)
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
        }
    }

    override fun clearDiagnosticEvents() {
        mutableDiagnosticEvents.value = emptyList()
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

    private fun closeSession(session: ActiveSession) {
        if (active?.id == session.id) active = null
        runCatching { session.client.close() }
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

    private companion object {
        const val CONNECTION_PROBE = "echo sheen-session-ready"
        const val MAX_DIAGNOSTIC_EVENTS = 100
    }
}
