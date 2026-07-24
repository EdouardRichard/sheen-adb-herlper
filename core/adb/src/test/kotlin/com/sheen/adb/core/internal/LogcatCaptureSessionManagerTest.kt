package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbExclusiveOperationKind
import com.sheen.adb.core.AdbOperationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatCaptureSessionManagerTest {
    @Test
    fun `logcat owns exclusive lease and releases it on cancellation while session survives`() = runBlocking {
        val client = Client()
        val manager = DefaultAdbSessionManager(SingleFactory(client), Dispatchers.IO)
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId

        val capture = async(Dispatchers.Default) {
            manager.streamLogcat(com.sheen.adb.core.LogcatConfig()).collect()
        }
        assertTrue(client.started.await(2, TimeUnit.SECONDS))
        val conflict = manager.acquireExclusiveOperation(AdbExclusiveOperationKind.FILE_TRANSFER, sessionId)
        assertTrue(conflict is AdbOperationResult.Failure)
        capture.cancelAndJoin()

        val released = manager.acquireExclusiveOperation(AdbExclusiveOperationKind.FILE_TRANSFER, sessionId)
        assertTrue(released is AdbOperationResult.Success, "released=$released")
        (released as AdbOperationResult.Success).value.release()
        assertTrue(manager.connectionState.value is AdbConnectionState.Connected)
    }

    private class Client : AdbProtocolClient {
        val started = CountDownLatch(1)
        override fun execute(command: String) =
            ProtocolShellResponse("ready\n", "", 0, streamsSeparated = true, wasTruncated = false)
        override fun openShellStream(command: String): ProtocolShellStream = object : ProtocolShellStream {
            override fun read(): ProtocolShellPacket {
                started.countDown()
                Thread.sleep(10_000)
                return ProtocolShellPacket.Exit(0)
            }
            override fun close() = Unit
        }
        override fun close() = Unit
    }

    private class SingleFactory(private val client: Client) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint) = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }
}
