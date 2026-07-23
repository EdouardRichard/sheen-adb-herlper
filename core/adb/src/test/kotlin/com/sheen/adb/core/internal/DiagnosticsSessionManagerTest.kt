package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.LogcatConfig
import com.sheen.adb.core.ProcessApplicationAssociation
import com.sheen.adb.core.StructuredLogcatKind
import com.sheen.adb.core.StructuredLogcatLevel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class DiagnosticsSessionManagerTest {
    @Test
    fun `manager returns session generation owned process analysis and structured logcat`() = runBlocking {
        val stream = PacketStream(
            ProtocolShellPacket.StandardOutput(
                "07-23 10:11:12.345  10123  2345  6789 I FixtureTag: synthetic message\n".toByteArray(),
            ),
            ProtocolShellPacket.StandardOutput("synthetic unknown\n".toByteArray()),
            ProtocolShellPacket.Exit(0),
        )
        val client = DiagnosticsClient(stream)
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId

        val analysis = manager.loadProcessAnalysis(sessionId) as AdbOperationResult.Success

        assertEquals(analysis.value.sessionId, sessionId)
        assertTrue(analysis.value.generation > 0)
        assertEquals(analysis.value.entries.single().process.pid, 2345)
        assertEquals(
            analysis.value.entries.single().applicationAssociation,
            ProcessApplicationAssociation.Verified("com.example.fixture"),
        )

        val records = manager.streamStructuredLogcat(
            config = LogcatConfig(),
            expectedSessionId = sessionId,
            expectedProcessGeneration = analysis.value.generation,
        ).toList()
        assertEquals(records.size, 2)
        val parsed = (records.first() as AdbOperationResult.Success).value
        assertEquals(parsed.sessionId, sessionId)
        assertEquals(parsed.snapshotGeneration, analysis.value.generation)
        assertEquals(parsed.kind, StructuredLogcatKind.PARSED)
        assertEquals(parsed.level, StructuredLogcatLevel.INFO)
        assertEquals(parsed.processName, "fixture.worker")
        assertEquals(parsed.applicationAssociation, ProcessApplicationAssociation.Verified("com.example.fixture"))
        val unparsed = (records.last() as AdbOperationResult.Success).value
        assertEquals(unparsed.kind, StructuredLogcatKind.UNPARSED)
        assertEquals(unparsed.rawText, "synthetic unknown")
    }

    @Test
    fun `stale session and stale process generation are rejected without guessed association`() = runBlocking {
        val client = DiagnosticsClient(PacketStream(ProtocolShellPacket.Exit(0)))
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val analysis = (manager.loadProcessAnalysis(sessionId) as AdbOperationResult.Success).value

        val staleSession = manager.loadProcessAnalysis("stale-session")
        assertTrue(staleSession is AdbOperationResult.Failure)
        assertTrue((staleSession as AdbOperationResult.Failure).error is AdbError.ApplicationSessionInvalid)

        val staleStream = manager.streamStructuredLogcat(
            LogcatConfig(),
            sessionId,
            analysis.generation - 1,
        ).toList()
        assertEquals(staleStream.size, 1)
        assertTrue(staleStream.single() is AdbOperationResult.Failure)
    }

    @Test
    fun `cancelling structured stream closes only stream and disconnect rejects old delivery`() = runBlocking {
        val blocking = BlockingDiagnosticStream()
        val client = DiagnosticsClient(blocking)
        val manager = connectedManager(client)
        val sessionId = (manager.connectionState.value as AdbConnectionState.Connected).sessionId
        val analysis = (manager.loadProcessAnalysis(sessionId) as AdbOperationResult.Success).value
        val collection = async(Dispatchers.Default) {
            manager.streamStructuredLogcat(LogcatConfig(), sessionId, analysis.generation).toList()
        }
        assertTrue(blocking.started.await(1, TimeUnit.SECONDS))

        collection.cancelAndJoin()

        assertTrue(blocking.closed.get())
        assertTrue(!client.closed.get())
        assertTrue(manager.connectionState.value is AdbConnectionState.Connected)

        val disconnecting = BlockingDiagnosticStream()
        val disconnectClient = DiagnosticsClient(disconnecting)
        val disconnectManager = connectedManager(disconnectClient)
        val disconnectSession = (disconnectManager.connectionState.value as AdbConnectionState.Connected).sessionId
        val disconnectAnalysis = (disconnectManager.loadProcessAnalysis(disconnectSession) as AdbOperationResult.Success).value
        val oldFlow = async(Dispatchers.Default) {
            disconnectManager.streamStructuredLogcat(
                LogcatConfig(),
                disconnectSession,
                disconnectAnalysis.generation,
            ).toList()
        }
        assertTrue(disconnecting.started.await(1, TimeUnit.SECONDS))
        assertTrue(disconnectManager.disconnect() is AdbOperationResult.Success)
        oldFlow.join()
        assertTrue(disconnecting.closed.get())
        assertTrue(disconnectClient.closed.get())
        assertTrue(disconnectManager.connectionState.value is AdbConnectionState.Disconnected)
    }

    private suspend fun connectedManager(client: DiagnosticsClient): DefaultAdbSessionManager {
        val manager = DefaultAdbSessionManager(DiagnosticsFactory(client), Dispatchers.IO)
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        return manager
    }

    private class DiagnosticsClient(
        private val stream: ProtocolShellStream,
    ) : AdbProtocolClient {
        val closed = AtomicBoolean(false)

        override fun execute(command: String): ProtocolShellResponse = when (command) {
            "am get-current-user" -> response("0\n")
            "pm list packages -3 -U --user 0" -> response("package:com.example.fixture uid:10123\n")
            "pm list packages -3 -d --user 0" -> response("")
            AdbCommands.PROCESSES_EXTENDED -> response(
                "USER PID PPID VSZ RSS S NAME\n" +
                    "u0_a123 2345 1 1000 42 S fixture.worker\n",
            )
            else -> response("ok\n")
        }

        override fun openShellStream(command: String): ProtocolShellStream = stream

        override fun close() {
            closed.set(true)
            (stream as? BlockingDiagnosticStream)?.release()
        }
    }

    private class PacketStream(vararg packets: ProtocolShellPacket) : ProtocolShellStream {
        private val queue = ArrayDeque(packets.toList())
        override fun read(): ProtocolShellPacket = queue.removeFirst()
        override fun close() = Unit
    }

    private class BlockingDiagnosticStream : ProtocolShellStream {
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        override fun read(): ProtocolShellPacket {
            started.countDown()
            released.await(10, TimeUnit.SECONDS)
            throw ProtocolCommandStreamException()
        }

        fun release() = released.countDown()

        override fun close() {
            closed.set(true)
            released.countDown()
        }
    }

    private class DiagnosticsFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint): AdbProtocolClient = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }

    private companion object {
        fun response(stdout: String) = ProtocolShellResponse(stdout, "", 0, true, false)
    }
}
