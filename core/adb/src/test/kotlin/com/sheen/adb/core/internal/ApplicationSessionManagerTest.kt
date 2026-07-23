package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.ApplicationMutationResult
import com.sheen.adb.core.RemoteApplicationEnabledState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.io.EOFException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

class ApplicationSessionManagerTest {
    @Test
    fun `lists only current user third party packages and keeps optional fields degraded`() = runBlocking {
        val client = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("10\n")
                "pm list packages -3 -U --user 10" -> response(
                    "package:com.example.one uid:1010123\npackage:com.example.two uid:1010124\n",
                )
                "pm list packages -3 -d --user 10" -> response("package:com.example.two\n")
                else -> response("ok\n")
            }
        }
        val manager = connectedManager(client)

        val result = manager.listApplications() as AdbOperationResult.Success
        assertEquals(result.value.userId, 10)
        assertEquals(result.value.applications.map { it.packageName }, listOf("com.example.one", "com.example.two"))
        assertEquals(result.value.applications.map { it.androidUid }, listOf(1_010_123, 1_010_124))
        assertEquals(result.value.applications.map { it.enabledState }, listOf(RemoteApplicationEnabledState.ENABLED, RemoteApplicationEnabledState.DISABLED))
        assertTrue(result.value.applications.none { it.isSystem })
        assertTrue(result.value.unavailableFields.isNotEmpty())
        assertTrue(client.commands.none { "--user" !in it && ("list packages" in it || "force-stop" in it || "disable" in it || "enable" in it) })
    }

    @Test
    fun `force stop requires snapshot membership and returns accepted for same session`() = runBlocking {
        val client = standardClient()
        val manager = connectedManager(client)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value

        val rejected = manager.forceStopApplication("com.invalid;target", snapshot.sessionId)
        assertTrue(rejected is AdbOperationResult.Failure)
        assertTrue((rejected as AdbOperationResult.Failure).error is AdbError.ApplicationTargetNotAllowed)

        val accepted = manager.forceStopApplication("com.example.client", snapshot.sessionId)
        assertTrue(accepted is AdbOperationResult.Success)
        assertTrue((accepted as AdbOperationResult.Success).value is ApplicationMutationResult.RequestAccepted)
    }

    @Test
    fun `disable verifies post state and rejects stale session`() = runBlocking {
        var disabled = false
        val client = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response("package:com.example.client uid:10123\n")
                "pm list packages -3 -d --user 0" -> response(if (disabled) "package:com.example.client\n" else "")
                "pm disable-user --user 0 com.example.client" -> response("Package com.example.client new state: disabled-user\n").also { disabled = true }
                else -> response("ok\n")
            }
        }
        val manager = connectedManager(client)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value

        val result = manager.setApplicationEnabled("com.example.client", false, snapshot.sessionId)
        assertTrue(result is AdbOperationResult.Success)
        val verified = (result as AdbOperationResult.Success).value as ApplicationMutationResult.Verified
        assertEquals(verified.application.enabledState, RemoteApplicationEnabledState.DISABLED)

        val stale = manager.setApplicationEnabled("com.example.client", true, "old-session")
        assertTrue(stale is AdbOperationResult.Failure)
        assertTrue((stale as AdbOperationResult.Failure).error is AdbError.ApplicationSessionInvalid)
    }

    @Test
    fun `policy rejection package disappearance and verify mismatch are distinct`() = runBlocking {
        val policyClient = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response("package:com.example.client uid:10123\n")
                "pm list packages -3 -d --user 0" -> response("")
                "pm disable-user --user 0 com.example.client" -> response("Security exception", exitCode = 1)
                else -> response("ok\n")
            }
        }
        val policyManager = connectedManager(policyClient)
        val policySnapshot = (policyManager.listApplications() as AdbOperationResult.Success).value
        val policy = policyManager.setApplicationEnabled("com.example.client", false, policySnapshot.sessionId)
        assertTrue((policy as AdbOperationResult.Failure).error is AdbError.ApplicationPolicyRejected)

        var listCount = 0
        val vanishedClient = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response(
                    if (listCount++ == 0) "package:com.example.client uid:10123\n" else "",
                )
                "pm list packages -3 -d --user 0" -> response("")
                else -> response("ok\n")
            }
        }
        val vanishedManager = connectedManager(vanishedClient)
        val vanishedSnapshot = (vanishedManager.listApplications() as AdbOperationResult.Success).value
        val vanished = vanishedManager.setApplicationEnabled("com.example.client", false, vanishedSnapshot.sessionId)
        assertTrue((vanished as AdbOperationResult.Failure).error is AdbError.ApplicationPackageNotFound)
    }

    @Test
    fun `mutation timeout after dispatch returns outcome unknown and closes session`() = runBlocking {
        val client = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response("package:com.example.client uid:10123\n")
                "pm list packages -3 -d --user 0" -> response("")
                "am force-stop --user 0 com.example.client" -> {
                    Thread.sleep(10_000)
                    response("")
                }
                else -> response("ok\n")
            }
        }
        val manager = connectedManager(client)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value

        val result = manager.forceStopApplication("com.example.client", snapshot.sessionId, 30.milliseconds)
        val unknown = (result as AdbOperationResult.Success).value as ApplicationMutationResult.OutcomeUnknown
        assertTrue(unknown.reason is AdbError.ApplicationOutcomeUnknown)
        assertTrue(client.closed.get())
    }

    @Test
    fun `empty list current user failure and unsupported ROM stay distinct`() = runBlocking {
        val emptyManager = connectedManager(ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0", "pm list packages -3 -d --user 0" -> response("")
                else -> response("ok\n")
            }
        })
        assertTrue((emptyManager.listApplications() as AdbOperationResult.Success).value.applications.isEmpty())

        val userManager = connectedManager(ScriptedClient { command ->
            when (command) {
                "am get-current-user", "cmd activity get-current-user" -> response("not available")
                else -> response("ok\n")
            }
        })
        assertTrue((userManager.listApplications() as AdbOperationResult.Failure).error is AdbError.ApplicationCurrentUserUnavailable)

        val unsupportedManager = connectedManager(ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0", "cmd package list packages -3 -U --user 0" -> response("vendor noise")
                else -> response("ok\n")
            }
        })
        assertTrue((unsupportedManager.listApplications() as AdbOperationResult.Failure).error is AdbError.ApplicationListUnsupported)
    }

    @Test
    fun `local self target is rejected and verify mismatch is not success`() = runBlocking {
        val localClient = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response("package:com.sheen.adbhelper uid:10123\n")
                "pm list packages -3 -d --user 0" -> response("")
                else -> response("ok\n")
            }
        }
        val localManager = DefaultAdbSessionManager(SingleFactory(localClient), Dispatchers.IO)
        localManager.connect(AdbEndpoint("127.0.0.1", 37001))
        val localSnapshot = (localManager.listApplications() as AdbOperationResult.Success).value
        val localResult = localManager.forceStopApplication("com.sheen.adbhelper", localSnapshot.sessionId)
        assertTrue((localResult as AdbOperationResult.Failure).error is AdbError.ApplicationTargetNotAllowed)

        val mismatchClient = standardClient()
        val mismatchManager = connectedManager(mismatchClient)
        val mismatchSnapshot = (mismatchManager.listApplications() as AdbOperationResult.Success).value
        val mismatch = mismatchManager.setApplicationEnabled("com.example.client", false, mismatchSnapshot.sessionId)
        assertTrue((mismatch as AdbOperationResult.Failure).error is AdbError.ApplicationStateVerifyFailed)
    }

    @Test
    fun `remote disconnect after mutation dispatch is outcome unknown and diagnostics stay sanitized`() = runBlocking {
        val client = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("12\n")
                "pm list packages -3 -U --user 12" -> response("package:com.sensitive.client uid:1210123\n")
                "pm list packages -3 -d --user 12" -> response("")
                "am force-stop --user 12 com.sensitive.client" -> throw EOFException("sensitive output")
                else -> response("ok\n")
            }
        }
        val manager = connectedManager(client)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value
        val result = manager.forceStopApplication("com.sensitive.client", snapshot.sessionId)
        assertTrue((result as AdbOperationResult.Success).value is ApplicationMutationResult.OutcomeUnknown)
        val rendered = manager.diagnosticEvents.value.joinToString()
        assertTrue("com.sensitive.client" !in rendered)
        assertTrue("force-stop" !in rendered)
        assertTrue("sensitive output" !in rendered)
    }

    @Test
    fun `cancelling dispatched mutation closes resources and clears active session`() = runBlocking {
        val started = AtomicBoolean(false)
        val client = ScriptedClient { command ->
            when (command) {
                "am get-current-user" -> response("0\n")
                "pm list packages -3 -U --user 0" -> response("package:com.example.client uid:10123\n")
                "pm list packages -3 -d --user 0" -> response("")
                "am force-stop --user 0 com.example.client" -> {
                    started.set(true)
                    Thread.sleep(10_000)
                    response("")
                }
                else -> response("ok\n")
            }
        }
        val manager = connectedManager(client)
        val snapshot = (manager.listApplications() as AdbOperationResult.Success).value
        val mutation = async(Dispatchers.Default) {
            manager.forceStopApplication("com.example.client", snapshot.sessionId)
        }
        while (!started.get()) Thread.yield()
        mutation.cancel()
        mutation.join()
        assertTrue(client.closed.get())
        assertTrue(
            manager.connectionState.value !is AdbConnectionState.Connected,
            "state=${manager.connectionState.value}",
        )
    }

    private suspend fun connectedManager(client: ScriptedClient): DefaultAdbSessionManager {
        val manager = DefaultAdbSessionManager(SingleFactory(client), Dispatchers.IO)
        assertTrue(manager.connect(AdbEndpoint("device.local", 37001)) is AdbOperationResult.Success)
        return manager
    }

    private fun standardClient() = ScriptedClient { command ->
        when (command) {
            "am get-current-user" -> response("0\n")
            "pm list packages -3 -U --user 0" -> response("package:com.example.client uid:10123\n")
            "pm list packages -3 -d --user 0" -> response("")
            else -> response("ok\n")
        }
    }

    private class ScriptedClient(
        private val script: (String) -> ProtocolShellResponse,
    ) : AdbProtocolClient {
        val commands = CopyOnWriteArrayList<String>()
        val closed = AtomicBoolean(false)
        override fun execute(command: String): ProtocolShellResponse {
            commands += command
            return script(command)
        }
        override fun openShellStream(command: String): ProtocolShellStream = error("unused")
        override fun close() { closed.set(true) }
    }

    private class SingleFactory(private val client: AdbProtocolClient) : AdbProtocolClientFactory {
        override fun open(endpoint: AdbEndpoint) = client
        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) = Unit
        override fun clearIdentity() = Unit
    }

    companion object {
        private fun response(stdout: String, stderr: String = "", exitCode: Int = 0) =
            ProtocolShellResponse(stdout, stderr, exitCode, streamsSeparated = true, wasTruncated = false)
    }
}
