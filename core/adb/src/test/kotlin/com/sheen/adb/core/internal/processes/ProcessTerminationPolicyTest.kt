package com.sheen.adb.core.internal.processes

import com.sheen.adb.core.ProcessIdentity
import com.sheen.adb.core.ProcessTerminationScope
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessTerminationPolicyTest {
    @Test
    fun `single process rejects pid one system uid and missing start time`() {
        assertFalse(ProcessTerminationPolicy.allowsSingle(identity(pid = 1)))
        assertFalse(ProcessTerminationPolicy.allowsSingle(identity(uid = "9999")))
        assertFalse(ProcessTerminationPolicy.allowsSingle(identity(startTime = null)))
        assertTrue(ProcessTerminationPolicy.allowsSingle(identity()))
    }

    @Test
    fun `whole application requires package and safe consistent process identities`() {
        val safe = identity()
        assertFalse(ProcessTerminationPolicy.allowsWholeApplication(null, setOf(safe)))
        assertFalse(ProcessTerminationPolicy.allowsWholeApplication("com.example.app", emptySet()))
        assertFalse(
            ProcessTerminationPolicy.allowsWholeApplication(
                "com.example.app",
                setOf(safe, identity(pid = 102, uid = "1000")),
            ),
        )
        assertTrue(
            ProcessTerminationPolicy.allows(
                ProcessTerminationScope.WHOLE_APPLICATION_FORCE_STOP,
                safe,
                "com.example.app",
                setOf(safe, identity(pid = 102)),
            ),
        )
    }

    @Test
    fun `confirmation nonce is one shot and cancellation produces no authorization`() {
        val guard = ProcessTerminationPolicy.ConfirmationGuard()
        guard.issue("nonce-a")

        assertTrue(guard.consume("nonce-a"))
        assertFalse(guard.consume("nonce-a"))

        guard.issue("nonce-b")
        guard.cancel("nonce-b")
        assertFalse(guard.consume("nonce-b"))
    }

    private fun identity(
        pid: Int = 101,
        uid: String? = "u0_a123",
        startTime: Long? = 500L,
    ) = ProcessIdentity(
        sessionId = "session-a",
        pid = pid,
        startTimeTicks = startTime,
        uid = uid,
        processName = "com.example.app",
        observedGeneration = 1,
    )
}
