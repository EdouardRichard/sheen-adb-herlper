package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbDiagnosticOutcome
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbOperationResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultAdbSessionManagerTest {
    @Test
    fun `connect validates protocol then executes command and disconnects`() = runBlocking {
        val client = FakeClient()
        val manager = DefaultAdbSessionManager(FakeFactory(client), Dispatchers.IO)
        val endpoint = AdbEndpoint("device.local", 42111)

        assertTrue(manager.connect(endpoint) is AdbOperationResult.Success)
        assertTrue(manager.connectionState.value is AdbConnectionState.Connected)

        val shell = manager.executeShell("test-command")
        assertTrue(shell is AdbOperationResult.Success)
        shell as AdbOperationResult.Success
        assertEquals("ok\n", shell.value.stdout)

        assertTrue(manager.disconnect() is AdbOperationResult.Success)
        assertTrue(client.closed.get())
        assertEquals(AdbConnectionState.Disconnected(), manager.connectionState.value)
    }

    @Test
    fun `new connection closes the previous single active session`() = runBlocking {
        val first = FakeClient()
        val second = FakeClient()
        val manager = DefaultAdbSessionManager(QueueFactory(first, second), Dispatchers.IO)

        manager.connect(AdbEndpoint("first.local", 40001))
        manager.connect(AdbEndpoint("second.local", 40002))

        assertTrue(first.closed.get())
        assertTrue(!second.closed.get())
        val state = manager.connectionState.value as AdbConnectionState.Connected
        assertEquals("second.local", state.endpoint.host)
    }

    @Test
    fun `timeout and cancellation close candidate resources`() = runBlocking {
        val timeoutClient = FakeClient(block = true)
        val timeoutManager = DefaultAdbSessionManager(FakeFactory(timeoutClient), Dispatchers.IO)
        val endpoint = AdbEndpoint("timeout.local", 40003)

        val result = timeoutManager.connect(endpoint, 50.milliseconds)
        assertTrue(result is AdbOperationResult.Failure)
        assertTrue(timeoutClient.closed.get())

        val cancelClient = FakeClient(block = true)
        val cancelManager = DefaultAdbSessionManager(FakeFactory(cancelClient), Dispatchers.IO)
        val operation = async(Dispatchers.Default) { cancelManager.connect(endpoint, 5.seconds) }
        while (!cancelClient.started.get()) Thread.yield()
        operation.cancel()
        operation.join()
        assertTrue(cancelClient.closed.get())
        val cancelledState = cancelManager.connectionState.value as AdbConnectionState.Disconnected
        assertEquals(com.sheen.adb.core.DisconnectionReason.CONNECT_CANCELLED, cancelledState.reason)
    }

    @Test
    fun `diagnostics are bounded cleared and never contain endpoint or command output`() = runBlocking {
        val client = FakeClient()
        val manager = DefaultAdbSessionManager(FakeFactory(client), Dispatchers.IO)
        val endpoint = AdbEndpoint("sensitive-device.local", 40123)

        manager.connect(endpoint)
        manager.executeShell("secret-command")
        repeat(60) { manager.disconnect() }

        val events = manager.diagnosticEvents.value
        assertEquals(100, events.size)
        assertTrue(events.any { it.outcome == AdbDiagnosticOutcome.SUCCEEDED })
        val rendered = events.joinToString()
        assertTrue("sensitive-device.local" !in rendered)
        assertTrue("secret-command" !in rendered)
        assertTrue("ok" !in rendered)

        manager.clearDiagnosticEvents()
        assertTrue(manager.diagnosticEvents.value.isEmpty())
    }

    private class FakeClient(private val block: Boolean = false) : AdbProtocolClient {
        val closed = AtomicBoolean(false)
        val started = AtomicBoolean(false)

        override fun execute(command: String): ProtocolShellResponse {
            started.set(true)
            if (block) Thread.sleep(10_000)
            return ProtocolShellResponse("ok\n", "", 0)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private open class FakeFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint) = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
    }

    private class QueueFactory(vararg clients: AdbProtocolClient) : AdbProtocolClientFactory {
        private val queue = ArrayDeque(clients.toList())
        override fun open(endpoint: AdbEndpoint): AdbProtocolClient = queue.removeFirst()
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
    }
}
