package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.ProcessTerminationOutcome
import com.sheen.adb.core.ProcessTerminationRequest
import com.sheen.adb.core.ProcessTerminationScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.seconds

class ProcessTerminationSessionManagerTest {
    @Test
    fun `single process sends one sigterm only after identity revalidation and verifies exit`() = runBlocking {
        val client = TerminationClient()
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val target = snapshot(manager, sessionId).first()

        val result = manager.terminateProcess(
            ProcessTerminationRequest("single-1", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = true),
            10.seconds,
        ) as AdbOperationResult.Success

        assertEquals(result.value.outcome, ProcessTerminationOutcome.TERMINATED)
        assertEquals(client.commands.count { it == "kill -TERM 101" }, 1)
        assertTrue(client.commands.none { "SIGKILL" in it || "kill -9" in it })
    }

    @Test
    fun `pid reuse is rejected before sigterm`() = runBlocking {
        val client = TerminationClient()
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val target = snapshot(manager, sessionId).first()
        client.startTime.set(999)

        val result = manager.terminateProcess(
            ProcessTerminationRequest("single-reused", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = true),
        ) as AdbOperationResult.Success

        assertEquals(result.value.outcome, ProcessTerminationOutcome.IDENTITY_CHANGED)
        assertTrue(client.commands.none { it.startsWith("kill ") })
    }

    @Test
    fun `whole application uses one force stop and rejects changed confirmed set`() = runBlocking {
        val client = TerminationClient(includeSecondary = true)
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val entries = snapshot(manager, sessionId)
        val target = entries.first()

        val result = manager.terminateProcess(
            ProcessTerminationRequest(
                requestId = "whole-1",
                sessionId = sessionId,
                scope = ProcessTerminationScope.WHOLE_APPLICATION_FORCE_STOP,
                targetProcess = target.identity,
                targetPackage = "com.example.client",
                confirmedProcessSet = entries.map { it.identity }.toSet(),
                riskAcknowledged = true,
                forceStopImpactAcknowledged = true,
            ),
        ) as AdbOperationResult.Success
        assertEquals(result.value.outcome, ProcessTerminationOutcome.TERMINATED)
        assertEquals(client.commands.count { it == "am force-stop --user 0 com.example.client" }, 1)
        assertTrue(client.commands.none { it.startsWith("kill ") })

        val changedClient = TerminationClient()
        val changedManager = connectedManager(changedClient)
        val changedSession = (changedManager.connectionState.value as AdbConnectionState.Connected).sessionId
        val confirmed = snapshot(changedManager, changedSession)
        changedClient.includeSecondary.set(true)
        val rejected = changedManager.terminateProcess(
            ProcessTerminationRequest(
                "whole-changed",
                changedSession,
                ProcessTerminationScope.WHOLE_APPLICATION_FORCE_STOP,
                confirmed.first().identity,
                "com.example.client",
                confirmed.map { it.identity }.toSet(),
                riskAcknowledged = true,
                forceStopImpactAcknowledged = true,
            ),
        ) as AdbOperationResult.Success
        assertEquals(rejected.value.outcome, ProcessTerminationOutcome.IDENTITY_CHANGED)
        assertTrue(changedClient.commands.none { it.startsWith("am force-stop") })
    }

    @Test
    fun `request sent is not success when final snapshot still contains target`() = runBlocking {
        val client = TerminationClient(ignoreTermination = true)
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val target = snapshot(manager, sessionId).first()

        val result = manager.terminateProcess(
            ProcessTerminationRequest("single-unknown", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = true),
        ) as AdbOperationResult.Success

        assertEquals(result.value.outcome, ProcessTerminationOutcome.UNKNOWN)
    }

    @Test
    fun `pre dispatch outcomes and contract classifications remain distinct`() = runBlocking {
        assertEquals(
            ProcessTerminationOutcome.entries.toSet(),
            setOf(
                ProcessTerminationOutcome.TERMINATED,
                ProcessTerminationOutcome.PARTIAL,
                ProcessTerminationOutcome.ALREADY_EXITED,
                ProcessTerminationOutcome.POLICY_REJECTED,
                ProcessTerminationOutcome.UNSUPPORTED,
                ProcessTerminationOutcome.IDENTITY_CHANGED,
                ProcessTerminationOutcome.UNKNOWN,
                ProcessTerminationOutcome.CANCELLED,
                ProcessTerminationOutcome.TIMED_OUT,
                ProcessTerminationOutcome.DISCONNECTED,
            ),
        )
        val client = TerminationClient()
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val target = snapshot(manager, sessionId).first()

        val unconfirmed = manager.terminateProcess(
            ProcessTerminationRequest("not-confirmed", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = false),
        ) as AdbOperationResult.Success
        assertEquals(unconfirmed.value.outcome, ProcessTerminationOutcome.POLICY_REJECTED)

        client.exitProcesses()
        val exited = manager.terminateProcess(
            ProcessTerminationRequest("already-exited", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = true),
        ) as AdbOperationResult.Success
        assertEquals(exited.value.outcome, ProcessTerminationOutcome.ALREADY_EXITED)

        manager.disconnect()
        val disconnected = manager.terminateProcess(
            ProcessTerminationRequest("disconnected", sessionId, ProcessTerminationScope.SINGLE_PROCESS, target.identity, riskAcknowledged = true),
        ) as AdbOperationResult.Success
        assertEquals(disconnected.value.outcome, ProcessTerminationOutcome.DISCONNECTED)
    }

    private suspend fun snapshot(manager: DefaultAdbSessionManager, sessionId: String) =
        (manager.refreshProcesses(sessionId) as AdbOperationResult.Success).value

    private suspend fun connectedManager(client: TerminationClient): DefaultAdbSessionManager {
        val manager = DefaultAdbSessionManager(SingleFactory(client), Dispatchers.IO)
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        return manager
    }

    private class TerminationClient(
        includeSecondary: Boolean = false,
        private val ignoreTermination: Boolean = false,
    ) : AdbProtocolClient {
        val commands = CopyOnWriteArrayList<String>()
        val startTime = AtomicInteger(900)
        val includeSecondary = AtomicBoolean(includeSecondary)
        private val alive = AtomicBoolean(true)
        private val counter = AtomicInteger()

        override fun execute(command: String): ProtocolShellResponse {
            commands += command
            val stdout = when {
                command == "am get-current-user" -> "0\n"
                command.startsWith("pm list packages -3 -U") -> "package:com.example.client uid:10123\n"
                command.startsWith("pm list packages -3 -d") -> ""
                command == AdbCommands.PROCESSES_EXTENDED -> processRows()
                command == AdbCommands.CORES -> "4\n"
                command == AdbCommands.PROCESS_COUNTERS -> counters()
                command == AdbCommands.PROCESS_PSS -> if (alive.get()) "101 1536\n" else ""
                command == "kill -TERM 101" -> "".also { if (!ignoreTermination) alive.set(false) }
                command == "am force-stop --user 0 com.example.client" -> "".also {
                    if (!ignoreTermination) alive.set(false)
                }
                else -> "ok\n"
            }
            return ProtocolShellResponse(stdout, "", 0, streamsSeparated = true, wasTruncated = false)
        }

        private fun processRows(): String = buildString {
            append("USER PID PPID VSZ RSS S NAME\n")
            if (alive.get()) append("u0_a123 101 1 1000 64 S com.example.client\n")
            if (alive.get() && includeSecondary.get()) {
                append("u0_a123 102 1 1000 64 S com.example.client:worker\n")
            }
        }

        private fun counters(): String {
            val phase = counter.incrementAndGet()
            val total = phase * 200L
            return buildString {
                append("total ").append(total).append('\n')
                if (alive.get()) {
                    append("101 ").append(startTime.get()).append(' ').append(phase * 10).append(" 5\n")
                    if (includeSecondary.get()) append("102 901 ").append(phase * 8).append(" 4\n")
                }
            }
        }

        fun exitProcesses() {
            alive.set(false)
        }

        override fun openShellStream(command: String): ProtocolShellStream = error("unused")
        override fun close() = Unit
    }

    private class SingleFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint) = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }
}
