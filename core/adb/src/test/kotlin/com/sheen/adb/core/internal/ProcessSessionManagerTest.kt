package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessSessionManagerTest {
    @Test
    fun `new refresh generation prevents older snapshot delivery`() = runBlocking {
        val client = ProcessClient()
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId

        val older = async(Dispatchers.Default) { manager.refreshProcesses(sessionId, 5.seconds) }
        assertTrue(client.firstCounter.await(2, TimeUnit.SECONDS))
        delay(100)
        val newer = async(Dispatchers.Default) { manager.refreshProcesses(sessionId, 5.seconds) }

        val olderResult = older.await()
        val newerResult = newer.await()
        assertTrue(olderResult !is AdbOperationResult.Success)
        assertTrue(newerResult is AdbOperationResult.Success)
        newerResult as AdbOperationResult.Success
        assertTrue(newerResult.value.isNotEmpty())
        assertTrue(newerResult.value.all { it.identity.sessionId == sessionId })
        assertTrue(newerResult.value.all { it.identity.observedGeneration == 2L })
    }

    @Test
    fun `timeout and cancellation close only process command substream`() = runBlocking {
        val timeoutClient = ProcessClient(blockCounters = true)
        val timeoutManager = connectedManager(timeoutClient)
        val timeoutSession = (timeoutManager.connectionState.value as AdbConnectionState.Connected).sessionId

        val timedOut = timeoutManager.refreshProcesses(timeoutSession, 50.milliseconds)
        assertTrue(timedOut is AdbOperationResult.Failure)
        assertTrue(timeoutClient.commandClosed.get())
        assertTrue(!timeoutClient.clientClosed.get())
        assertTrue(timeoutManager.connectionState.value is AdbConnectionState.Connected)

        val cancelClient = ProcessClient(blockCounters = true)
        val cancelManager = connectedManager(cancelClient)
        val cancelSession = (cancelManager.connectionState.value as AdbConnectionState.Connected).sessionId
        val operation = async(Dispatchers.Default) { cancelManager.refreshProcesses(cancelSession, 5.seconds) }
        assertTrue(cancelClient.counterStarted.await(2, TimeUnit.SECONDS))
        operation.cancel()
        operation.join()
        assertTrue(cancelClient.commandClosed.get())
        assertTrue(!cancelClient.clientClosed.get())
    }

    @Test
    fun `session switch rejects in flight process snapshot`() = runBlocking {
        val first = ProcessClient()
        val second = ProcessClient()
        val manager = DefaultAdbSessionManager(QueueFactory(first, second), Dispatchers.IO)
        manager.connect(AdbEndpoint("first.local", 37001))
        val firstSession = (manager.connectionState.value as AdbConnectionState.Connected).sessionId

        val refresh = async(Dispatchers.Default) { manager.refreshProcesses(firstSession, 5.seconds) }
        assertTrue(first.firstCounter.await(2, TimeUnit.SECONDS))
        manager.connect(AdbEndpoint("second.local", 37002))

        assertTrue(refresh.await() !is AdbOperationResult.Success)
        val current = manager.connectionState.value as AdbConnectionState.Connected
        assertTrue(current.sessionId != firstSession)
    }

    private suspend fun connectedManager(client: ProcessClient): DefaultAdbSessionManager {
        val manager = DefaultAdbSessionManager(SingleFactory(client), Dispatchers.IO)
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        return manager
    }

    private class ProcessClient(
        private val blockCounters: Boolean = false,
    ) : AdbProtocolClient {
        val commands = CopyOnWriteArrayList<String>()
        val commandClosed = AtomicBoolean(false)
        val clientClosed = AtomicBoolean(false)
        val firstCounter = CountDownLatch(1)
        val counterStarted = CountDownLatch(1)
        private val counterCalls = AtomicInteger(0)

        override fun execute(command: String): ProtocolShellResponse = scripted(command)

        override fun openShellCommand(command: String): ProtocolShellCommand = object : ProtocolShellCommand {
            override fun execute(): ProtocolShellResponse {
                if (command.startsWith("awk '/^cpu /")) {
                    counterStarted.countDown()
                    if (blockCounters) Thread.sleep(10_000)
                }
                return scripted(command)
            }

            override fun close() {
                commandClosed.set(true)
            }
        }

        private fun scripted(command: String): ProtocolShellResponse {
            commands += command
            val stdout = when {
                command == "am get-current-user" -> "0\n"
                command.startsWith("pm list packages -3 -U") -> "package:com.example.client uid:10123\n"
                command.startsWith("pm list packages -3 -d") -> ""
                command == AdbCommands.PROCESSES_EXTENDED ->
                    "USER PID PPID VSZ RSS S NAME\nu0_a123 101 1 1000 64 S com.example.client\n"
                command.startsWith("awk '/^cpu /") -> {
                    val call = counterCalls.incrementAndGet()
                    firstCounter.countDown()
                    if (call % 2 == 1) "total 1000\n101 900 100 20\n" else "total 1200\n101 900 130 30\n"
                }
                command.startsWith("for d in /proc/") -> "101 1536\n"
                command == AdbCommands.CORES -> "4\n"
                else -> "ok\n"
            }
            return ProtocolShellResponse(stdout, "", 0, streamsSeparated = true, wasTruncated = false)
        }

        override fun openShellStream(command: String): ProtocolShellStream = error("unused")
        override fun close() {
            clientClosed.set(true)
        }
    }

    private class SingleFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint) = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }

    private class QueueFactory(
        private vararg val clients: AdbProtocolClient,
    ) : AdbProtocolClientFactory {
        private val index = AtomicInteger()
        override fun open(endpoint: AdbEndpoint): AdbProtocolClient = clients[index.getAndIncrement()]
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }
}
