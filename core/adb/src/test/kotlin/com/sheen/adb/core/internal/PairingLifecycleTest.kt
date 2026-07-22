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
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
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
        val clock = FakeClock(nowMillis = 10)
        val lifecycle = PairingLifecycle(clock, action)
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
        val lateSubmit = assertTerminalStateIsStable(lifecycle, attemptId, clock, success)
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
        val retryInvalid = "12a456".toCharArray()
        lifecycle.startSixDigit(attemptId, deadlineMillis = 20)
        lifecycle.submitCode(attemptId, retryInvalid)

        val retried = lifecycle.submitCode(attemptId, valid)

        assertEquals(retried.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(action.calls, listOf(PairingCall(PairingMethod.SIX_DIGIT_CODE, wasExpectedAndNonCleared = true)))
        assertCleared(retryInvalid)
        assertCleared(valid)
    }

    @Test
    fun `attempt identifiers are nonblank opaque and redacted`() {
        val token = "attempt-synthetic-redacted-token"
        val id = PairingAttemptId.of(token)
        val secretChars = "secret-synthetic".toCharArray()
        val secret = PairingSecret(secretChars)
        val lifecycle = PairingLifecycle(FakeClock(), FakePairingAction())
        lifecycle.startQr(id, secret, deadlineMillis = 20)
        lifecycle.cancel(id)

        assertFalse(id.toString().contains(token))
        assertFalse(secret.toString().contains("secret-synthetic"))
        assertFalse(runCatching { PairingAttemptId.of("   ") }.isSuccess)
        assertCleared(secretChars)
        assertOpaquePublicSurface(PairingAttemptId::class.java, setOf("equals", "hashCode", "toString"))
        assertOpaquePublicSurface(PairingSecret::class.java, setOf("clear", "equals", "hashCode", "toString"))
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
        listOf(
            ExpectedTerminal(PairingAttemptPhase.CANCELLED, PairingFailure.CANCELLED) { lifecycle, id -> lifecycle.cancel(id) },
            ExpectedTerminal(PairingAttemptPhase.FAILED, PairingFailure.EXPLICIT_FAILURE) { lifecycle, id -> lifecycle.fail(id) },
            ExpectedTerminal(PairingAttemptPhase.UNSUPPORTED, PairingFailure.UNSUPPORTED) { lifecycle, id -> lifecycle.markUnsupported(id) },
        ).forEachIndexed { index, terminal ->
            val password = "terminal-secret-$index".toCharArray()
            val clock = FakeClock()
            val lifecycle = PairingLifecycle(clock, FakePairingAction(password))
            val attemptId = attemptId("terminal-$index")
            lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20)

            val terminalResult = terminal.transition(lifecycle, attemptId)
            val repeated = terminal.transition(lifecycle, attemptId)
            val lateSubmit = assertTerminalStateIsStable(lifecycle, attemptId, clock, terminalResult)

            assertEquals(repeated.state, terminalResult.state)
            assertEquals(terminalResult.state.phase, terminal.phase)
            assertEquals(terminalResult.state.failure, terminal.failure)
            assertCleared(password)
            assertSafeRendering(
                terminalResult,
                lateSubmit,
                "attempt-synthetic-terminal-$index",
                "terminal-secret-$index",
            )
        }
    }

    @Test
    fun `explicit expiry at the deadline is idempotent clears material and cannot revive`() {
        val clock = FakeClock(nowMillis = 19)
        val password = "expiry-secret-synthetic".toCharArray()
        val action = FakePairingAction(password)
        val lifecycle = PairingLifecycle(clock, action)
        val attemptId = attemptId("explicit-expiry")
        lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20)
        lifecycle.awaitTarget(attemptId)
        clock.nowMillis = 20

        val expired = lifecycle.expire(attemptId)
        val repeated = lifecycle.expire(attemptId)
        val lateSubmit = assertTerminalStateIsStable(lifecycle, attemptId, clock, expired)

        assertEquals(expired.state.phase, PairingAttemptPhase.EXPIRED)
        assertEquals(expired.state.failure, PairingFailure.EXPIRED)
        assertEquals(repeated.state, expired.state)
        assertTrue(action.calls.isEmpty())
        assertCleared(password)
        assertSafeRendering(expired, lateSubmit, "attempt-synthetic-explicit-expiry", "expiry-secret-synthetic")
    }

    @Test
    fun `expiry action failure and terminal rendering clear material without exposing sensitive values`() {
        val token = "attempt-synthetic-render-token"
        val service = "service-synthetic"
        val endpoint = "192.0.2.44"
        val rawException = "raw-exception-synthetic"
        val password = "secret-synthetic-$service-$endpoint".toCharArray()
        val throwingAction = FakePairingAction(password, throwable = IllegalStateException(rawException))
        val clock = FakeClock()
        val lifecycle = PairingLifecycle(clock, throwingAction)
        val attemptId = PairingAttemptId.of(token)
        lifecycle.startQr(attemptId, PairingSecret(password), deadlineMillis = 20)
        lifecycle.awaitTarget(attemptId)

        val failed = lifecycle.onTargetReady(attemptId)
        val terminalSubmit = assertTerminalStateIsStable(lifecycle, attemptId, clock, failed)

        assertEquals(failed.state.phase, PairingAttemptPhase.FAILED)
        assertEquals(failed.state.failure, PairingFailure.ACTION_FAILED)
        assertEquals(throwingAction.calls, listOf(PairingCall(PairingMethod.QR, wasExpectedAndNonCleared = true)))
        assertCleared(password)
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

    @Test
    fun `QR action cancellation is rethrown clears retained secret and atomically cancels attempt`() {
        val password = "qr-cancel-secret-synthetic".toCharArray()
        val action = FakePairingAction(
            expectedSecret = password,
            throwable = CancellationException("qr-cancellation-synthetic"),
        )
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val cancelledId = attemptId("qr-cancelled")
        lifecycle.startQr(cancelledId, PairingSecret(password), deadlineMillis = 20)
        lifecycle.awaitTarget(cancelledId)

        val thrown = runCatching { lifecycle.onTargetReady(cancelledId) }.exceptionOrNull()
        val cancelled = lifecycle.awaitTarget(cancelledId)
        val nextId = attemptId("after-qr-cancel")
        val next = lifecycle.startSixDigit(nextId, deadlineMillis = 20)

        assertTrue(thrown is CancellationException)
        assertCleared(password)
        assertTrue(action.expectedSourceIsCleared())
        assertEquals(cancelled.state.phase, PairingAttemptPhase.CANCELLED)
        assertEquals(cancelled.state.failure, PairingFailure.CANCELLED)
        assertEquals(cancelled.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertEquals(next.state.phase, PairingAttemptPhase.WAITING_FOR_CODE)
        assertNull(next.rejection)
        lifecycle.cancel(nextId)
    }

    @Test
    fun `code action cancellation is rethrown clears supplied code and atomically cancels attempt`() {
        val code = "012345".toCharArray()
        val action = FakePairingAction(
            expectedSecret = code,
            throwable = CancellationException("code-cancellation-synthetic"),
        )
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val cancelledId = attemptId("code-cancelled")
        lifecycle.startSixDigit(cancelledId, deadlineMillis = 20)

        val thrown = runCatching { lifecycle.submitCode(cancelledId, code) }.exceptionOrNull()
        val cancelled = lifecycle.awaitTarget(cancelledId)
        val nextPassword = "next-qr-secret-synthetic".toCharArray()
        val nextId = attemptId("after-code-cancel")
        val next = lifecycle.startQr(nextId, PairingSecret(nextPassword), deadlineMillis = 20)

        assertTrue(thrown is CancellationException)
        assertCleared(code)
        assertTrue(action.expectedSourceIsCleared())
        assertEquals(cancelled.state.phase, PairingAttemptPhase.CANCELLED)
        assertEquals(cancelled.state.failure, PairingFailure.CANCELLED)
        assertEquals(cancelled.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertEquals(next.state.phase, PairingAttemptPhase.PREPARING)
        assertNull(next.rejection)
        lifecycle.cancel(nextId)
        assertCleared(nextPassword)
    }

    @Test
    fun `commands without a current attempt return safe idle rejection and clear submitted code`() {
        val token = "attempt-synthetic-no-active"
        val code = "012345".toCharArray()
        val lifecycle = PairingLifecycle(FakeClock(), FakePairingAction())
        val attemptId = PairingAttemptId.of(token)
        val results = listOf(
            lifecycle.awaitTarget(attemptId),
            lifecycle.onTargetReady(attemptId),
            lifecycle.cancel(attemptId),
            lifecycle.fail(attemptId),
            lifecycle.markUnsupported(attemptId),
            lifecycle.expire(attemptId),
            lifecycle.submitCode(attemptId, code),
        )

        results.forEach(::assertNoActiveAttempt)
        assertCleared(code)
        val rendered = results.joinToString()
        assertFalse(rendered.contains(token))
        assertFalse(rendered.contains("012345"))
    }

    @Test
    fun `early expiry is rejected without changing QR or code attempts and both can still succeed`() {
        val qrPassword = "qr-early-expiry-secret".toCharArray()
        val qrAction = FakePairingAction(qrPassword)
        val qrLifecycle = PairingLifecycle(FakeClock(nowMillis = 19), qrAction)
        val qrId = attemptId("qr-early-expiry")
        qrLifecycle.startQr(qrId, PairingSecret(qrPassword), deadlineMillis = 20)
        val qrWaiting = qrLifecycle.awaitTarget(qrId)

        val qrEarly = qrLifecycle.expire(qrId)
        val qrSuccess = qrLifecycle.onTargetReady(qrId)

        val code = "012345".toCharArray()
        val codeAction = FakePairingAction(code)
        val codeLifecycle = PairingLifecycle(FakeClock(nowMillis = 19), codeAction)
        val codeId = attemptId("code-early-expiry")
        val codeWaiting = codeLifecycle.startSixDigit(codeId, deadlineMillis = 20)

        val codeEarly = codeLifecycle.expire(codeId)
        val codeSuccess = codeLifecycle.submitCode(codeId, code)

        assertEquals(qrEarly.state, qrWaiting.state)
        assertEquals(qrEarly.rejection, PairingCommandRejection.NOT_EXPIRED)
        assertEquals(qrSuccess.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertEquals(codeEarly.state, codeWaiting.state)
        assertEquals(codeEarly.rejection, PairingCommandRejection.NOT_EXPIRED)
        assertEquals(codeSuccess.state.phase, PairingAttemptPhase.SUCCEEDED)
        assertCleared(qrPassword)
        assertCleared(code)
    }

    @Test
    fun `attempt started at its exact deadline is immediately expired and clears QR secret without action`() {
        val password = "exact-start-secret-synthetic".toCharArray()
        val action = FakePairingAction(password)
        val lifecycle = PairingLifecycle(FakeClock(nowMillis = 20), action)

        val result = lifecycle.startQr(
            attemptId = attemptId("exact-start"),
            password = PairingSecret(password),
            deadlineMillis = 20,
        )

        assertEquals(result.state.phase, PairingAttemptPhase.EXPIRED)
        assertEquals(result.state.failure, PairingFailure.EXPIRED)
        assertNull(result.rejection)
        assertTrue(action.calls.isEmpty())
        assertCleared(password)
    }

    @Test
    fun `close is idempotent clears active material and identifiers and permanently rejects later commands`() {
        val token = "attempt-synthetic-close-active"
        val password = "close-active-secret-synthetic".toCharArray()
        val action = FakePairingAction(password)
        val lifecycle = PairingLifecycle(FakeClock(), action)
        val activeId = PairingAttemptId.of(token)
        lifecycle.startQr(activeId, PairingSecret(password), deadlineMillis = 20)
        lifecycle.awaitTarget(activeId)

        lifecycle.close()
        lifecycle.close()

        assertCleared(password)
        assertTrue(action.calls.isEmpty())
        assertInstanceCollectionsCleared(lifecycle)

        val lateCode = "012345".toCharArray()
        val callbackResults = listOf(
            lifecycle.awaitTarget(activeId),
            lifecycle.onTargetReady(activeId),
            lifecycle.cancel(activeId),
            lifecycle.fail(activeId),
            lifecycle.markUnsupported(activeId),
            lifecycle.expire(activeId),
            lifecycle.submitCode(activeId, lateCode),
        )
        val reusedSecret = "close-reused-secret-synthetic".toCharArray()
        val newSecret = "close-new-secret-synthetic".toCharArray()
        val startResults = listOf(
            lifecycle.startQr(activeId, PairingSecret(reusedSecret), deadlineMillis = 20),
            lifecycle.startQr(attemptId("close-new"), PairingSecret(newSecret), deadlineMillis = 20),
        )

        (callbackResults + startResults).forEach(::assertClosedIdle)
        assertCleared(lateCode)
        assertCleared(reusedSecret)
        assertCleared(newSecret)
        val rendered = (callbackResults + startResults).joinToString()
        listOf(token, "012345", "close-active-secret", "close-reused-secret", "close-new-secret").forEach {
            assertFalse(rendered.contains(it))
        }
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

    private fun assertOpaquePublicSurface(type: Class<*>, allowedNames: Set<String>) {
        assertTrue(
            type.declaredMethods
                .filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        !method.isSynthetic &&
                        '$' !in method.name
                }
                .all { method -> method.name in allowedNames },
        )
    }

    private fun assertNoActiveAttempt(result: PairingCommandResult) {
        assertEquals(result.state.phase, PairingAttemptPhase.IDLE)
        assertEquals(result.state.failure, PairingFailure.NO_ACTIVE_ATTEMPT)
        assertEquals(result.rejection, PairingCommandRejection.NO_ACTIVE_ATTEMPT)
    }

    private fun assertClosedIdle(result: PairingCommandResult) {
        assertEquals(result.state.phase, PairingAttemptPhase.IDLE)
        assertEquals(result.state.failure, PairingFailure.NO_ACTIVE_ATTEMPT)
        assertEquals(result.rejection, PairingCommandRejection.CLOSED)
    }

    private fun assertInstanceCollectionsCleared(lifecycle: PairingLifecycle) {
        val instanceCollections = lifecycle.javaClass.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && Collection::class.java.isAssignableFrom(field.type)
        }
        assertTrue(instanceCollections.isNotEmpty())
        instanceCollections.forEach { field ->
            field.isAccessible = true
            assertTrue((field.get(lifecycle) as Collection<*>).isEmpty())
        }
    }

    private fun assertTerminalStateIsStable(
        lifecycle: PairingLifecycle,
        attemptId: PairingAttemptId,
        clock: FakeClock,
        expected: PairingCommandResult,
    ): PairingCommandResult {
        clock.nowMillis = 20
        listOf(
            lifecycle.cancel(attemptId),
            lifecycle.fail(attemptId),
            lifecycle.markUnsupported(attemptId),
            lifecycle.expire(attemptId),
        ).forEach { result ->
            assertEquals(result.state, expected.state)
            assertEquals(result.state.failure, expected.state.failure)
        }

        val lateAwait = lifecycle.awaitTarget(attemptId)
        val lateTarget = lifecycle.onTargetReady(attemptId)
        val lateCode = "012345".toCharArray()
        val lateSubmit = lifecycle.submitCode(attemptId, lateCode)
        val restartedSecret = "restart-secret-synthetic".toCharArray()
        val restart = lifecycle.startQr(attemptId, PairingSecret(restartedSecret), deadlineMillis = 20)

        assertEquals(lateAwait.state, expected.state)
        assertEquals(lateAwait.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertEquals(lateTarget.state, expected.state)
        assertEquals(lateSubmit.state, expected.state)
        assertEquals(lateSubmit.rejection, PairingCommandRejection.TERMINAL_ATTEMPT)
        assertEquals(restart.state, expected.state)
        assertEquals(restart.rejection, PairingCommandRejection.ATTEMPT_ID_REUSED)
        assertCleared(lateCode)
        assertCleared(restartedSecret)
        return lateSubmit
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

    private data class ExpectedTerminal(
        val phase: PairingAttemptPhase,
        val failure: PairingFailure,
        val transition: (PairingLifecycle, PairingAttemptId) -> PairingCommandResult,
    )
}
