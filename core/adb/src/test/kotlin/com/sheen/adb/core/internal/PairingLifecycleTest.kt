package com.sheen.adb.core.internal

import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingCommandRejection
import com.sheen.adb.core.PairingCommandResult
import com.sheen.adb.core.PairingFailure
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.internal.pairing.MonotonicClock
import com.sheen.adb.core.internal.pairing.PairingAction
import com.sheen.adb.core.internal.pairing.PairingLifecycle
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class PairingLifecycleTest {
    @Test
    fun `QR attempt prepares waits for target then succeeds with its supplied password`() {
        val password = "qr-password-synthetic".toCharArray()
        val action = FakePairingAction(password)
        val lifecycle = PairingLifecycle(FakeClock(nowMillis = 10), action)
        val attemptId = attemptId("qr")

        assertEquals(
            lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20).state.phase,
            PairingAttemptPhase.PREPARING,
        )
        assertEquals(lifecycle.awaitTarget(attemptId).state.phase, PairingAttemptPhase.WAITING_FOR_TARGET)
        val success = lifecycle.onTargetReady(attemptId)

        assertEquals(success.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertNull(success.rejection)
        assertEquals(action.calls, listOf(PairingCall(PairingMethod.QR, wasExpectedAndNonCleared = true)))
        assertCleared(password)
        assertTrue(action.expectedSourceIsCleared())
        val lateCode = "012345".toCharArray()
        val restartedSecret = "restart-secret-synthetic".toCharArray()
        val lateAwait = lifecycle.awaitTarget(attemptId)
        val lateTarget = lifecycle.onTargetReady(attemptId)
        val lateSubmit = lifecycle.submitCode(attemptId, lateCode)
        val restart = lifecycle.startQr(attemptId, PairingSecret(restartedSecret), deadlineMillis = 20)

        assertEquals(lateAwait.state, success.state)
        assertEquals(lateTarget.state, success.state)
        assertEquals(lateSubmit.state, success.state)
        assertEquals(lateSubmit.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertEquals(restart.state, success.state)
        assertEquals(restart.rejection, PairingCommandRejection.ATTEMPT_ID_REUSED)
        assertCleared(lateCode)
        assertCleared(restartedSecret)
        assertSafeRendering(success, lateSubmit, "attempt-synthetic-qr", "qr-password-synthetic")
    }

    @Test
    fun `six digit attempt is the code default waits for code and succeeds with its supplied code`() {
        val code = "012345".toCharArray()
        val action = FakePairingAction(code)
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val attemptId = attemptId("code")

        val waiting = lifecycle.startSixDigit(attemptId, deadlineMillis = 20)
        val success = lifecycle.submitCode(attemptId, code)

        assertEquals(waiting.state.method, PairingMethod.SIX_DIGIT_CODE)
        assertEquals(waiting.state.phase, PairingAttemptPhase.WAITING_FOR_CODE)
        assertEquals(success.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(action.calls, listOf(PairingCall(PairingMethod.SIX_DIGIT_CODE, wasExpectedAndNonCleared = true)))
        assertCleared(code)
        assertTrue(action.expectedSourceIsCleared())
    }

    @Test
    fun `invalid six digit input is safely rejected cleared and can be retried`() {
        listOf(
            "12345",
            "1234567",
            "12a456",
            "12 456",
            "１２３４５６",
            "١٢٣٤٥٦",
            "𝟙𝟚𝟛𝟜𝟝𝟞",
        ).forEachIndexed { index, candidate ->
            val action = FakePairingAction()
            val lifecycle = PairingLifecycle(FakeClock(), action)
            val attemptId = attemptId("invalid-$index")
            val invalid = candidate.toCharArray()
            lifecycle.startSixDigit(attemptId, deadlineMillis = 20)

            val rejected = lifecycle.submitCode(attemptId, invalid)

            assertEquals(rejected.state.phase, PairingAttemptPhase.WAITING_FOR_CODE)
            assertEquals(rejected.rejection, PairingCommandRejection.INVALID_CODE)
            assertTrue(action.calls.isEmpty())
            assertCleared(invalid)
        }

        val valid = "012345".toCharArray()
        val action = FakePairingAction(valid)
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val attemptId = attemptId("retry")
        lifecycle.startSixDigit(attemptId, deadlineMillis = 20)
        lifecycle.submitCode(attemptId, "12a456".toCharArray())

        val retried = lifecycle.submitCode(attemptId, valid)

        assertEquals(retried.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(action.calls, listOf(PairingCall(PairingMethod.SIX_DIGIT_CODE, wasExpectedAndNonCleared = true)))
        assertCleared(valid)
    }

    @Test
    fun `attempt identifiers are nonblank opaque and redacted`() {
        val token = "attempt-synthetic-redacted-token"
        val id = PairingAttemptId.of(token)
        val secret = PairingSecret("secret-synthetic".toCharArray())

        assertFalse(id.toString().contains(token))
        assertFalse(secret.toString().contains("secret-synthetic"))
        assertFalse(runCatching { PairingAttemptId.of("   ") }.isSuccess)
        assertTrue(
            PairingSecret::class.java.methods.none { method ->
                method.declaringClass == PairingSecret::class.java && method.returnType == CharArray::class.java
            },
        )
    }

    @Test
    fun `stale callbacks and code are rejected cleared and leave the active attempt able to succeed`() {
        val password = "qr-password-synthetic".toCharArray()
        val action = FakePairingAction(password)
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val activeId = attemptId("active")
        val staleId = attemptId("stale")
        val staleCode = "012345".toCharArray()
        lifecycle.startQr(activeId, PairingSecret(password), deadlineMillis = 20)
        val waiting = lifecycle.awaitTarget(activeId)

        val staleTarget = lifecycle.onTargetReady(staleId)
        val staleSubmit = lifecycle.submitCode(staleId, staleCode)
        val success = lifecycle.onTargetReady(activeId)

        assertEquals(staleTarget.state, waiting.state)
        assertEquals(staleTarget.rejection, PairingCommandRejection.STALE_ATTEMPT)
        assertEquals(staleSubmit.state, waiting.state)
        assertEquals(staleSubmit.rejection, PairingCommandRejection.STALE_ATTEMPT)
        assertCleared(staleCode)
        assertEquals(success.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertCleared(password)
    }

    @Test
    fun `attempt identifiers cannot be reused and an active attempt cannot be replaced`() {
        val lifecycle = PairingLifecycle(FakeClock(), FakePairingAction())
        val activeId = attemptId("first")
        val duplicateSecret = "duplicate-secret-synthetic".toCharArray()
        val replacementSecret = "replacement-secret-synthetic".toCharArray()
        val terminalDuplicateSecret = "terminal-duplicate-secret-synthetic".toCharArray()
        val secondId = attemptId("second")
        val secondSecret = "second-secret-synthetic".toCharArray()
        val started = lifecycle.startSixDigit(activeId, deadlineMillis = 20)

        val duplicate = lifecycle.startQr(activeId, PairingSecret(duplicateSecret), deadlineMillis = 20)
        val replacement = lifecycle.startQr(secondId, PairingSecret(replacementSecret), deadlineMillis = 20)
        val cancelled = lifecycle.cancel(activeId)
        val reusedAfterTerminal = lifecycle.startQr(activeId, PairingSecret(terminalDuplicateSecret), deadlineMillis = 20)
        val newAttempt = lifecycle.startQr(secondId, PairingSecret(secondSecret), deadlineMillis = 20)

        assertEquals(duplicate.state, started.state)
        assertEquals(duplicate.rejection, PairingCommandRejection.ATTEMPT_ID_REUSED)
        assertEquals(replacement.state, started.state)
        assertEquals(replacement.rejection, PairingCommandRejection.ACTIVE_ATTEMPT_EXISTS)
        assertEquals(cancelled.state.phase, PairingAttemptPhase.CANCELLED)
        assertEquals(reusedAfterTerminal.state, cancelled.state)
        assertEquals(reusedAfterTerminal.rejection, PairingCommandRejection.ATTEMPT_ID_REUSED)
        assertEquals(newAttempt.state.phase, PairingAttemptPhase.PREPARING)
        assertNull(newAttempt.rejection)
        assertCleared(duplicateSecret)
        assertCleared(replacementSecret)
        assertCleared(terminalDuplicateSecret)
        lifecycle.cancel(secondId)
        assertCleared(secondSecret)
    }

    @Test
    fun `old callbacks cannot affect a later unique attempt`() {
        val firstPassword = "first-secret-synthetic".toCharArray()
        val secondPassword = "second-secret-synthetic".toCharArray()
        val action = FakePairingAction(secondPassword)
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val firstId = attemptId("old")
        val secondId = attemptId("new")
        lifecycle.startQr(firstId, PairingSecret(firstPassword), deadlineMillis = 20)
        lifecycle.cancel(firstId)
        lifecycle.startQr(secondId, PairingSecret(secondPassword), deadlineMillis = 20)
        val waiting = lifecycle.awaitTarget(secondId)
        val oldTarget = lifecycle.onTargetReady(firstId)
        val oldCode = "012345".toCharArray()
        val oldSubmit = lifecycle.submitCode(firstId, oldCode)
        val success = lifecycle.onTargetReady(secondId)

        assertEquals(oldTarget.state, waiting.state)
        assertEquals(oldTarget.rejection, PairingCommandRejection.STALE_ATTEMPT)
        assertEquals(oldSubmit.state, waiting.state)
        assertEquals(oldSubmit.rejection, PairingCommandRejection.STALE_ATTEMPT)
        assertCleared(firstPassword)
        assertCleared(oldCode)
        assertEquals(success.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertCleared(secondPassword)
    }

    @Test
    fun `deadline one millisecond before permits QR and code actions while exact deadline does not`() {
        val qrBeforeDeadline = "qr-before-deadline".toCharArray()
        val qrBeforeAction = FakePairingAction(qrBeforeDeadline)
        val qrBeforeClock = FakeClock(nowMillis = 19)
        val qrBefore = PairingLifecycle(qrBeforeClock, qrBeforeAction)
        val qrBeforeId = attemptId("qr-before")
        qrBefore.startQr(qrBeforeId, PairingSecret(qrBeforeDeadline), deadlineMillis = 20)
        qrBefore.awaitTarget(qrBeforeId)

        val qrBeforeResult = qrBefore.onTargetReady(qrBeforeId)

        val codeBeforeDeadline = "012345".toCharArray()
        val codeBeforeAction = FakePairingAction(codeBeforeDeadline)
        val codeBefore = PairingLifecycle(FakeClock(nowMillis = 19), codeBeforeAction)
        val codeBeforeId = attemptId("code-before")
        codeBefore.startSixDigit(codeBeforeId, deadlineMillis = 20)

        val codeBeforeResult = codeBefore.submitCode(codeBeforeId, codeBeforeDeadline)

        val qrAtDeadline = "qr-at-deadline".toCharArray()
        val qrAtAction = FakePairingAction(qrAtDeadline)
        val qrAt = PairingLifecycle(FakeClock(nowMillis = 20), qrAtAction)
        val qrAtId = attemptId("qr-at")
        qrAt.startQr(qrAtId, PairingSecret(qrAtDeadline), deadlineMillis = 20)
        qrAt.awaitTarget(qrAtId)

        val qrAtResult = qrAt.onTargetReady(qrAtId)

        val codeAtDeadline = "012345".toCharArray()
        val codeAtAction = FakePairingAction(codeAtDeadline)
        val codeAt = PairingLifecycle(FakeClock(nowMillis = 20), codeAtAction)
        val codeAtId = attemptId("code-at")
        codeAt.startSixDigit(codeAtId, deadlineMillis = 20)

        val codeAtResult = codeAt.submitCode(codeAtId, codeAtDeadline)

        assertEquals(qrBeforeResult.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(codeBeforeResult.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(qrBeforeAction.calls, listOf(PairingCall(PairingMethod.QR, wasExpectedAndNonCleared = true)))
        assertEquals(codeBeforeAction.calls, listOf(PairingCall(PairingMethod.SIX_DIGIT_CODE, wasExpectedAndNonCleared = true)))
        assertEquals(qrAtResult.state.phase, PairingAttemptPhase.EXPIRED)
        assertEquals(codeAtResult.state.phase, PairingAttemptPhase.EXPIRED)
        assertTrue(qrAtAction.calls.isEmpty())
        assertTrue(codeAtAction.calls.isEmpty())
        assertCleared(qrBeforeDeadline)
        assertCleared(codeBeforeDeadline)
        assertCleared(qrAtDeadline)
        assertCleared(codeAtDeadline)
        assertTrue(qrBeforeAction.expectedSourceIsCleared())
        assertTrue(codeBeforeAction.expectedSourceIsCleared())
    }

    @Test
    fun `all terminal transitions are idempotent clear retained material and cannot revive`() {
        listOf<(PairingLifecycle, PairingAttemptId) -> PairingCommandResult>(
            { lifecycle, id -> lifecycle.cancel(id) },
            { lifecycle, id -> lifecycle.fail(id) },
            { lifecycle, id -> lifecycle.markUnsupported(id) },
        ).forEachIndexed { index, terminal ->
            val password = "terminal-secret-$index".toCharArray()
            val lifecycle = PairingLifecycle(FakeClock(), FakePairingAction(password))
            val attemptId = attemptId("terminal-$index")
            lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20)

            val terminalResult = terminal(lifecycle, attemptId)
            val repeated = terminal(lifecycle, attemptId)
            val lateTarget = lifecycle.onTargetReady(attemptId)
            val lateCode = "012345".toCharArray()
            val lateSubmit = lifecycle.submitCode(attemptId, lateCode)
            val restartedSecret = "restart-secret-$index".toCharArray()
            val restart = lifecycle.startQr(attemptId, PairingSecret(restartedSecret), deadlineMillis = 20)

            assertEquals(repeated.state, terminalResult.state)
            assertEquals(lateTarget.state, terminalResult.state)
            assertEquals(lateSubmit.state, terminalResult.state)
            assertEquals(lateSubmit.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
            assertEquals(restart.state, terminalResult.state)
            assertEquals(restart.rejection, PairingCommandRejection.ATTEMPT_ID_REUSED)
            assertCleared(password)
            assertCleared(lateCode)
            assertCleared(restartedSecret)
            assertSafeRendering(
                terminalResult,
                lateSubmit,
                "attempt-synthetic-terminal-$index",
                "terminal-secret-$index",
            )
        }
    }

    @Test
    fun `expiry action failure and terminal rendering clear material without exposing sensitive values`() {
        val token = "attempt-synthetic-render-token"
        val service = "service-synthetic"
        val endpoint = "192.0.2.44"
        val rawException = "raw-exception-synthetic"
        val password = "secret-synthetic-$service-$endpoint".toCharArray()
        val throwingAction = FakePairingAction(password, throwable = IllegalStateException(rawException))
        val lifecycle = PairingLifecycle(FakeClock(), throwingAction)
        val attemptId = PairingAttemptId.of(token)
        lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20)
        lifecycle.awaitTarget(attemptId)

        val failed = lifecycle.onTargetReady(attemptId)
        val lateCode = "012345".toCharArray()
        val terminalSubmit = lifecycle.submitCode(attemptId, lateCode)

        assertEquals(failed.state.phase, PairingAttemptPhase.FAILED)
        assertEquals(failed.state.failure, PairingFailure.ACTION_FAILED)
        assertEquals(throwingAction.calls, listOf(PairingCall(PairingMethod.QR, wasExpectedAndNonCleared = true)))
        assertEquals(terminalSubmit.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertCleared(password)
        assertCleared(lateCode)
        assertTrue(throwingAction.expectedSourceIsCleared())
        assertSafeRendering(failed, terminalSubmit, token, service, endpoint, rawException)

        val expiredPassword = "expiry-secret-synthetic".toCharArray()
        val expired = PairingLifecycle(FakeClock(nowMillis = 20), FakePairingAction(expiredPassword))
        val expiredId = attemptId("render-expired")
        expired.startQr(expiredId, PairingSecret(expiredPassword), deadlineMillis = 20)
        expired.awaitTarget(expiredId)

        val expiry = expired.onTargetReady(expiredId)

        assertEquals(expiry.state.phase, PairingAttemptPhase.EXPIRED)
        assertCleared(expiredPassword)
        assertSafeRendering(expiry, expiry, "attempt-synthetic-render-expired", "expiry-secret-synthetic")
    }

    private fun assertSafeRendering(
        result: PairingCommandResult,
        terminalSubmit: PairingCommandResult,
        vararg forbidden: String,
    ) {
        val rendered = listOf(
            result.toString(),
            result.state.toString(),
            result.state.failure.toString(),
            terminalSubmit.toString(),
            terminalSubmit.state.toString(),
            terminalSubmit.rejection.toString(),
        )
        forbidden.forEach { value ->
            rendered.forEach { text -> assertFalse(text.contains(value)) }
        }
    }

    private fun attemptId(suffix: String): PairingAttemptId =
        PairingAttemptId.of("attempt-synthetic-$suffix")

    private fun assertCleared(value: CharArray) {
        assertTrue(value.all { it == '\u0000' })
    }

    private class FakeClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class FakePairingAction(
        private val expectedSecret: CharArray? = null,
        private val throwable: Throwable? = null,
    ) : PairingAction {
        val calls = mutableListOf<PairingCall>()

        override fun pair(method: PairingMethod, secret: CharArray) {
            calls += PairingCall(
                method = method,
                wasExpectedAndNonCleared = secret === expectedSecret && secret.isNotEmpty() && secret.all { it != '\u0000' },
            )
            throwable?.let { throw it }
        }

        fun expectedSourceIsCleared(): Boolean = expectedSecret?.all { it == '\u0000' } ?: true
    }

    private data class PairingCall(
        val method: PairingMethod,
        val wasExpectedAndNonCleared: Boolean,
    )
}
