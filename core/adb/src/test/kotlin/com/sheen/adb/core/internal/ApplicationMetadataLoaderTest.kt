package com.sheen.adb.core.internal

import com.sheen.adb.core.internal.applications.ApplicationMetadataLoadStatus
import com.sheen.adb.core.internal.applications.ApplicationMetadataLoader
import com.sheen.adb.core.internal.applications.ApplicationMetadataParseResult
import com.sheen.adb.core.internal.applications.BoundedRemoteApkReader
import com.sheen.adb.core.internal.applications.MAX_APPLICATION_APK_BYTES
import com.sheen.adb.core.internal.applications.MAX_APPLICATION_ICON_BYTES
import com.sheen.adb.core.internal.applications.ParsedApplicationIcon
import com.sheen.adb.core.internal.applications.ParsedApplicationIconKind
import com.sheen.adb.core.internal.applications.ParsedApplicationMetadata
import com.sheen.adb.core.internal.applications.RemoteApkReadFailure
import com.sheen.adb.core.internal.applications.RemoteApkReadRequest
import com.sheen.adb.core.internal.applications.RemoteApkReadResult
import com.sheen.adb.core.internal.applications.RemoteApkReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ApplicationMetadataLoaderTest {
    @Test
    fun `reader resolves base path for current user and receives through bounded sync streams`() = runBlocking {
        val statSync = FakeMetadataSync(statSize = 4)
        val receiveSync = FakeMetadataSync(payload = byteArrayOf(1, 2, 3, 4))
        val client = FakeMetadataClient(
            shellResponse = ProtocolShellResponse(
                stdout = "package:/data/app/redacted/base.apk\npackage:/data/app/redacted/split_config.apk\n",
                stderr = "",
                exitCode = 0,
                streamsSeparated = true,
                wasTruncated = false,
            ),
            syncs = ArrayDeque(listOf(statSync, receiveSync)),
        )
        val reader = BoundedRemoteApkReader(client, sessionIsCurrent = { true }, ioDispatcher = Dispatchers.Default)

        val result = reader.read(RemoteApkReadRequest("com.example.fixture", 10, "session-a", 1.seconds))

        assertTrue(result is RemoteApkReadResult.Success)
        assertTrue((result as RemoteApkReadResult.Success).bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(result.remotePath, "/data/app/redacted/base.apk")
        assertEquals(client.commands, listOf("pm path --user 10 com.example.fixture"))
        assertEquals(statSync.statPaths, listOf("/data/app/redacted/base.apk"))
        assertEquals(receiveSync.receivePaths, listOf("/data/app/redacted/base.apk"))
        assertTrue(statSync.closed)
        assertTrue(receiveSync.closed)
    }

    @Test
    fun `reader rejects declared and streamed bytes over 32 MiB and always closes sync`() = runBlocking {
        val declaredTooLarge = FakeMetadataSync(statSize = MAX_APPLICATION_APK_BYTES.toLong() + 1)
        val declaredClient = basePathClient(declaredTooLarge)
        val reader = BoundedRemoteApkReader(declaredClient, { true }, Dispatchers.Default)

        val declared = reader.read(RemoteApkReadRequest("com.example.fixture", 0, "session-a", 1.seconds))

        assertFailure(declared, RemoteApkReadFailure.TOO_LARGE)
        assertTrue(declaredTooLarge.closed)

        val stat = FakeMetadataSync(statSize = 1)
        val streamedTooLarge = FakeMetadataSync(simulatedBytes = MAX_APPLICATION_APK_BYTES.toLong() + 1)
        val streamedClient = basePathClient(stat, streamedTooLarge)
        val streamedReader = BoundedRemoteApkReader(streamedClient, { true }, Dispatchers.Default)

        val streamed = streamedReader.read(RemoteApkReadRequest("com.example.fixture", 0, "session-a", 2.seconds))

        assertFailure(streamed, RemoteApkReadFailure.TOO_LARGE)
        assertTrue(streamedTooLarge.closed)
    }

    @Test
    fun `reader rejects split-only paths without opening sync`() = runBlocking {
        val client = FakeMetadataClient(
            shellResponse = shell("package:/data/app/redacted/split_config.en.apk\n"),
            syncs = ArrayDeque(),
        )
        val reader = BoundedRemoteApkReader(client, { true }, Dispatchers.Default)

        val result = reader.read(RemoteApkReadRequest("com.example.fixture", 0, "session-a", 1.seconds))

        assertFailure(result, RemoteApkReadFailure.SPLIT_ONLY)
        assertEquals(client.openSyncCount.get(), 0)
    }

    @Test
    fun `reader closes receive stream on timeout and caller cancellation`() = runBlocking {
        val timeoutStat = FakeMetadataSync(statSize = 1)
        val timeoutReceive = FakeMetadataSync(blockReceive = true)
        val timeoutReader = BoundedRemoteApkReader(
            basePathClient(timeoutStat, timeoutReceive),
            { true },
            Dispatchers.Default,
            cancellationGrace = 100.milliseconds,
        )

        val timeout = timeoutReader.read(
            RemoteApkReadRequest("com.example.fixture", 0, "session-a", 50.milliseconds),
        )

        assertFailure(timeout, RemoteApkReadFailure.TIMEOUT)
        assertTrue(timeoutReceive.closed)

        val cancelStat = FakeMetadataSync(statSize = 1)
        val cancelReceive = FakeMetadataSync(blockReceive = true)
        val cancelReader = BoundedRemoteApkReader(
            basePathClient(cancelStat, cancelReceive),
            { true },
            Dispatchers.Default,
            cancellationGrace = 100.milliseconds,
        )
        val operation = async(Dispatchers.Default) {
            cancelReader.read(RemoteApkReadRequest("com.example.fixture", 0, "session-a", 5.seconds))
        }
        assertTrue(cancelReceive.entered.await(1, TimeUnit.SECONDS))

        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertTrue(cancelReceive.closed)
    }

    @Test
    fun `reader rejects a session change during receive and closes stream`() = runBlocking {
        val current = AtomicBoolean(true)
        val stat = FakeMetadataSync(statSize = 4)
        val receive = FakeMetadataSync(payload = byteArrayOf(1, 2, 3, 4), beforePayload = { current.set(false) })
        val reader = BoundedRemoteApkReader(basePathClient(stat, receive), current::get, Dispatchers.Default)

        val result = reader.read(RemoteApkReadRequest("com.example.fixture", 0, "session-a", 1.seconds))

        assertFailure(result, RemoteApkReadFailure.SESSION_CHANGED)
        assertTrue(receive.closed)
    }

    @Test
    fun `loader reads sequentially continues after one package failure and respects batch budget`() = runBlocking {
        val activeReads = AtomicInteger(0)
        val maxActiveReads = AtomicInteger(0)
        var nowMillis = 0L
        val requests = CopyOnWriteArrayList<String>()
        val reader = RemoteApkReader { request ->
            val active = activeReads.incrementAndGet()
            maxActiveReads.updateAndGet { maxOf(it, active) }
            requests += request.packageName
            nowMillis += 4_000
            activeReads.decrementAndGet()
            if (request.packageName.endsWith("two")) {
                RemoteApkReadResult.Failure(RemoteApkReadFailure.UNAVAILABLE)
            } else {
                RemoteApkReadResult.Success(request.packageName.toByteArray(), "/data/app/redacted/base.apk")
            }
        }
        val parser = parserFromBytes(iconSize = 8)
        val loader = ApplicationMetadataLoader(
            reader = reader,
            parseMetadata = parser,
            nowMillis = { nowMillis },
            batchTimeout = 10.seconds,
        )

        val updates = loader.load(
            sessionId = "session-a",
            userId = 0,
            packageNames = listOf("com.example.one", "com.example.two", "com.example.three", "com.example.four"),
            preferredLocaleTags = listOf("en-US"),
        ).toList()

        assertEquals(maxActiveReads.get(), 1)
        assertEquals(requests, listOf("com.example.one", "com.example.two", "com.example.three"))
        assertEquals(
            updates.map { it.status },
            listOf(
                ApplicationMetadataLoadStatus.AVAILABLE,
                ApplicationMetadataLoadStatus.UNAVAILABLE,
                ApplicationMetadataLoadStatus.AVAILABLE,
                ApplicationMetadataLoadStatus.TIMED_OUT,
            ),
        )
    }

    @Test
    fun `loader enforces one MiB icon limit and sixteen MiB least recently used cache`() = runBlocking {
        val reader = RemoteApkReader { request ->
            RemoteApkReadResult.Success(request.packageName.toByteArray(), "/data/app/redacted/base.apk")
        }
        val oversizedLoader = ApplicationMetadataLoader(
            reader = reader,
            parseMetadata = parserFromBytes(MAX_APPLICATION_ICON_BYTES + 1),
        )

        val oversized = oversizedLoader.load(
            "session-a",
            0,
            listOf("com.example.oversized"),
            listOf("en-US"),
        ).toList().single()

        assertEquals(oversized.status, ApplicationMetadataLoadStatus.TOO_LARGE)
        assertEquals(oversized.metadata?.displayName, "com.example.oversized")
        assertEquals(oversized.metadata?.icon, null)

        val cacheLoader = ApplicationMetadataLoader(
            reader = reader,
            parseMetadata = parserFromBytes(MAX_APPLICATION_ICON_BYTES),
        )
        val packages = (0..16).map { "com.example.app$it" }
        val updates = cacheLoader.load("session-a", 0, packages, listOf("en-US")).toList()

        assertEquals(cacheLoader.cachedIconBytes(), 16L * 1024 * 1024)
        assertFalse(cacheLoader.hasCachedIcon("session-a", 0, packages.first()))
        assertTrue(cacheLoader.hasCachedIcon("session-a", 0, packages.last()))
        assertEquals(updates.last().evictedIconPackages, setOf(packages.first()))
    }

    private fun parserFromBytes(
        iconSize: Int,
    ): (ByteArray, List<String>) -> ApplicationMetadataParseResult = { bytes, _ ->
        val packageName = bytes.toString(Charsets.UTF_8)
        val icon = ParsedApplicationIcon(
            encodedBytes = ByteArray(iconSize),
            mimeType = "image/png",
            width = 1,
            height = 1,
            kind = ParsedApplicationIconKind.RASTER,
        )
        ApplicationMetadataParseResult.Success(
            ParsedApplicationMetadata(packageName, packageName, icon),
        )
    }

    private fun basePathClient(vararg syncs: FakeMetadataSync): FakeMetadataClient = FakeMetadataClient(
        shellResponse = shell("package:/data/app/redacted/base.apk\n"),
        syncs = ArrayDeque(syncs.toList()),
    )

    private fun shell(stdout: String) = ProtocolShellResponse(
        stdout = stdout,
        stderr = "",
        exitCode = 0,
        streamsSeparated = true,
        wasTruncated = false,
    )

    private fun assertFailure(result: RemoteApkReadResult, expected: RemoteApkReadFailure) {
        assertTrue(result is RemoteApkReadResult.Failure)
        assertEquals((result as RemoteApkReadResult.Failure).reason, expected)
    }

    private class FakeMetadataClient(
        private val shellResponse: ProtocolShellResponse,
        private val syncs: ArrayDeque<FakeMetadataSync>,
    ) : AdbProtocolClient {
        val commands = CopyOnWriteArrayList<String>()
        val openSyncCount = AtomicInteger(0)

        override fun execute(command: String): ProtocolShellResponse {
            commands += command
            return shellResponse
        }

        override fun openShellStream(command: String): ProtocolShellStream = error("unused")

        override fun openSync(): ProtocolSyncStream {
            openSyncCount.incrementAndGet()
            return syncs.removeFirst()
        }

        override fun close() = Unit
    }

    private class FakeMetadataSync(
        private val statSize: Long = 0,
        private val payload: ByteArray = ByteArray(0),
        private val simulatedBytes: Long? = null,
        private val blockReceive: Boolean = false,
        private val beforePayload: () -> Unit = {},
    ) : ProtocolSyncStream {
        override val version = ProtocolSyncVersion.V2
        val statPaths = CopyOnWriteArrayList<String>()
        val receivePaths = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        @Volatile var closed = false
            private set

        override fun list(path: String): List<ProtocolRemoteEntry> = emptyList()
        override fun lstat(path: String): ProtocolRemoteStat = stat(path)
        override fun stat(path: String): ProtocolRemoteStat {
            statPaths += path
            return ProtocolRemoteStat(0x81a4, statSize, 0, null, null)
        }

        override fun recv(path: String, sink: (ByteArray, Int, Int) -> Unit) {
            receivePaths += path
            entered.countDown()
            if (blockReceive) {
                while (!closed) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        if (closed) return
                    }
                }
                return
            }
            beforePayload()
            val total = simulatedBytes
            if (total != null) {
                val chunk = ByteArray(64 * 1024)
                var written = 0L
                while (written < total) {
                    val count = minOf(chunk.size.toLong(), total - written).toInt()
                    sink(chunk, 0, count)
                    written += count
                }
            } else if (payload.isNotEmpty()) {
                sink(payload, 0, payload.size)
            }
        }

        override fun close() {
            closed = true
        }
    }
}
