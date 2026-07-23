package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.ApplicationIconEncoding
import com.sheen.adb.core.ApplicationIconFallback
import com.sheen.adb.core.ApplicationMetadataStatus
import com.sheen.adb.core.ApplicationMutationResult
import com.sheen.adb.core.internal.applications.ApplicationMetadataParseFailure
import com.sheen.adb.core.internal.applications.ApplicationMetadataParseResult
import com.sheen.adb.core.internal.applications.ParsedApplicationIcon
import com.sheen.adb.core.internal.applications.ParsedApplicationIconKind
import com.sheen.adb.core.internal.applications.ParsedApplicationMetadata
import com.sheen.adb.core.internal.applications.RemoteApkReadFailure
import com.sheen.adb.core.internal.applications.RemoteApkReadResult
import com.sheen.adb.core.internal.applications.RemoteApkReader
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ApplicationMetadataSessionManagerTest {
    @Test
    fun `package snapshot returns before metadata and flow emits one owned update per package`() = runBlocking {
        val client = metadataClient(listOf("com.example.one", "com.example.two"))
        val requested = CopyOnWriteArrayList<String>()
        val reader = RemoteApkReader { request ->
            requested += request.packageName
            RemoteApkReadResult.Success(request.packageName.toByteArray(), "/data/app/redacted/base.apk")
        }
        val manager = connectedManager(client, reader, successfulParser())

        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value

        assertTrue(requested.isEmpty(), "metadata must not delay the package snapshot")
        val updates = manager.observeApplicationMetadata(snapshot.sessionId, listOf("zh-CN", "en-US")).toList()
        assertEquals(requested, snapshot.applications.map { it.packageName })
        assertEquals(updates.size, 2)
        updates.forEachIndexed { index, result ->
            assertTrue(result is AdbOperationResult.Success)
            val update = (result as AdbOperationResult.Success).value
            assertEquals(update.sessionId, snapshot.sessionId)
            assertEquals(update.userId, snapshot.userId)
            assertEquals(update.packageName, snapshot.applications[index].packageName)
            assertEquals(update.status, ApplicationMetadataStatus.AVAILABLE)
            assertEquals(update.displayName, snapshot.applications[index].packageName)
            assertEquals(update.icon?.encoding, ApplicationIconEncoding.PNG)
            assertEquals(update.icon?.fallback, ApplicationIconFallback.NONE)
            assertTrue(update.icon?.encodedBytes?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true)
        }
    }

    @Test
    fun `metadata failures are classified per package and one failure does not hide later packages`() = runBlocking {
        val packages = listOf(
            "com.example.large",
            "com.example.missing",
            "com.example.broken",
            "com.example.changed",
            "com.example.after",
        )
        val requested = CopyOnWriteArrayList<String>()
        val reader = RemoteApkReader { request ->
            requested += request.packageName
            when (request.packageName) {
                "com.example.large" -> RemoteApkReadResult.Failure(RemoteApkReadFailure.TOO_LARGE)
                "com.example.missing" -> RemoteApkReadResult.Failure(RemoteApkReadFailure.UNAVAILABLE)
                "com.example.changed" -> RemoteApkReadResult.Failure(RemoteApkReadFailure.SESSION_CHANGED)
                else -> RemoteApkReadResult.Success(request.packageName.toByteArray(), "/data/app/redacted/base.apk")
            }
        }
        val parser: (ByteArray, List<String>) -> ApplicationMetadataParseResult = { _, _ ->
            ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.PARSE_FAILED)
        }
        val manager = connectedManager(metadataClient(packages), reader, parser)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value

        val updates = manager.observeApplicationMetadata(snapshot.sessionId).toList()

        assertEquals(
            updates.map { (it as AdbOperationResult.Success).value.status },
            listOf(
                ApplicationMetadataStatus.TOO_LARGE,
                ApplicationMetadataStatus.UNAVAILABLE,
                ApplicationMetadataStatus.PARSE_FAILED,
                ApplicationMetadataStatus.SESSION_CHANGED,
                ApplicationMetadataStatus.SESSION_CHANGED,
            ),
        )
        assertEquals(requested, packages.take(4))
        assertEquals(updates.map { (it as AdbOperationResult.Success).value.packageName }, packages)
    }

    @Test
    fun `stale session is rejected before reader access`() = runBlocking {
        val requested = CopyOnWriteArrayList<String>()
        val reader = RemoteApkReader { request ->
            requested += request.packageName
            RemoteApkReadResult.Failure(RemoteApkReadFailure.UNAVAILABLE)
        }
        val manager = connectedManager(metadataClient(listOf("com.example.one")), reader, successfulParser())
        manager.listApplications()

        val results = manager.observeApplicationMetadata("stale-session").toList()

        assertEquals(results.size, 1)
        assertTrue(results.single() is AdbOperationResult.Failure)
        assertTrue((results.single() as AdbOperationResult.Failure).error is AdbError.ApplicationSessionInvalid)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `metadata enrichment does not change existing application mutation policy`() = runBlocking {
        val packageName = "com.example.client"
        val reader = RemoteApkReader { request ->
            RemoteApkReadResult.Success(request.packageName.toByteArray(), "/data/app/redacted/base.apk")
        }
        val manager = connectedManager(metadataClient(listOf(packageName)), reader, successfulParser())
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value
        manager.observeApplicationMetadata(snapshot.sessionId).toList()

        val result = manager.forceStopApplication(packageName, snapshot.sessionId)

        assertTrue(result is AdbOperationResult.Success)
        assertTrue((result as AdbOperationResult.Success).value is ApplicationMutationResult.RequestAccepted)
    }

    private suspend fun connectedManager(
        client: AdbProtocolClient,
        reader: RemoteApkReader,
        parser: (ByteArray, List<String>) -> ApplicationMetadataParseResult,
    ): DefaultAdbSessionManager {
        val manager = DefaultAdbSessionManager(
            clientFactory = SingleMetadataFactory(client),
            ioDispatcher = Dispatchers.IO,
            metadataReaderFactory = { _, _ -> reader },
            metadataParser = parser,
        )
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        return manager
    }

    private fun successfulParser(): (ByteArray, List<String>) -> ApplicationMetadataParseResult = { bytes, _ ->
        val packageName = bytes.toString(Charsets.UTF_8)
        ApplicationMetadataParseResult.Success(
            ParsedApplicationMetadata(
                packageName = packageName,
                displayName = packageName,
                icon = ParsedApplicationIcon(
                    encodedBytes = byteArrayOf(1, 2, 3, 4),
                    mimeType = "image/png",
                    width = 1,
                    height = 1,
                    kind = ParsedApplicationIconKind.RASTER,
                ),
            ),
        )
    }

    private fun metadataClient(packages: List<String>) = ScriptedMetadataClient { command ->
        when {
            command == "am get-current-user" -> response("0\n")
            command.startsWith("pm list packages -3 -d --user 0") -> response("")
            command.startsWith("pm list packages -3 -U --user 0") -> response(
                packages.mapIndexed { index, packageName -> "package:$packageName uid:${10_123 + index}" }
                    .joinToString(separator = "\n", postfix = "\n"),
            )
            command.startsWith("am force-stop --user 0 ") -> response("")
            else -> response("ok\n")
        }
    }

    private class ScriptedMetadataClient(
        private val script: (String) -> ProtocolShellResponse,
    ) : AdbProtocolClient {
        override fun execute(command: String): ProtocolShellResponse = script(command)
        override fun openShellStream(command: String): ProtocolShellStream = error("unused")
        override fun close() = Unit
    }

    private class SingleMetadataFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint): AdbProtocolClient = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }

    private companion object {
        fun response(stdout: String, stderr: String = "", exitCode: Int = 0) =
            ProtocolShellResponse(stdout, stderr, exitCode, streamsSeparated = true, wasTruncated = false)
    }
}
