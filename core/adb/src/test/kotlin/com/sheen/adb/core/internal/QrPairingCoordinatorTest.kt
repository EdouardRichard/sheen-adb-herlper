package com.sheen.adb.core.internal

import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import com.sheen.adb.core.internal.pairing.MonotonicClock
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class QrPairingCoordinatorTest {
    @Test
    fun `material uses deterministic secure entropy standard payload and two minute deadline`() {
        val clock = FakeClock(nowMillis = 1_000)
        val entropy = DeterministicSecureRandom()
        val coordinator = Harness(clock, entropy)
        val duplicateHarness = Harness(FakeClock(nowMillis = 1_000), DeterministicSecureRandom())
        val differentHarness = Harness(FakeClock(nowMillis = 1_000), DeterministicSecureRandom(offset = 97))
        try {
            val material = coordinator.start(attemptId("material"))
            val serviceInstance = coordinator.serviceInstance(material)
            val password = coordinator.passwordValue(material)

            assertTrue(serviceInstance.matches(Regex("studio-[A-Za-z0-9-]{10}")))
            assertEquals(password.length, 12)
            assertTrue(password.all { it in PASSWORD_ALPHABET })
            assertEquals(coordinator.payload(material), "WIFI:T:ADB;S:$serviceInstance;P:$password;;")
            assertEquals(coordinator.deadlineMillis(material), 121_000L)
            assertTrue(entropy.nextBytesCalls >= 2, "The coordinator must use the injected SecureRandom")

            val duplicateMaterial = duplicateHarness.start(attemptId("deterministic-copy"))
            assertEquals(duplicateHarness.serviceInstance(duplicateMaterial), serviceInstance)
            assertEquals(duplicateHarness.passwordValue(duplicateMaterial), password)

            val differentMaterial = differentHarness.start(attemptId("different-entropy"))
            assertFalse(
                differentHarness.serviceInstance(differentMaterial) == serviceInstance &&
                    differentHarness.passwordValue(differentMaterial) == password,
                "Changing the injected entropy bytes must change generated pairing material",
            )

            coordinator.close()
            assertNull(coordinator.payload(material))
        } finally {
            coordinator.close()
            duplicateHarness.close()
            differentHarness.close()
        }
    }

    @Test
    fun `only the exact live resolved pairing service is claimed once without connecting`() {
        val coordinator = Harness(FakeClock(nowMillis = 50), DeterministicSecureRandom())
        val attemptId = attemptId("match")
        val foreignAttempt = attemptId("foreign")
        try {
            val material = coordinator.start(attemptId)
            val expectedName = coordinator.serviceInstance(material)
            val expected = observation("expected", WirelessServiceType.PAIRING, expectedName, WirelessServiceStatus.RESOLVED)

            assertNull(coordinator.match(foreignAttempt, expected))
            assertNull(coordinator.state(foreignAttempt))
            assertFalse(coordinator.complete(foreignAttempt, PairingAttemptPhase.CANCELLED))
            assertNull(
                coordinator.match(
                    attemptId,
                    observation("wrong-name", WirelessServiceType.PAIRING, "studio-wrongname0", WirelessServiceStatus.RESOLVED),
                ),
            )
            assertNull(
                coordinator.match(
                    attemptId,
                    observation("wrong-type", WirelessServiceType.CONNECT, expectedName, WirelessServiceStatus.RESOLVED),
                ),
            )
            assertNull(
                coordinator.match(
                    attemptId,
                    observation("unresolved", WirelessServiceType.PAIRING, expectedName, WirelessServiceStatus.DISCOVERED),
                ),
            )

            val match = checkNotNull(coordinator.match(attemptId, expected))
            val password = coordinator.matchPassword(match)
            assertEquals(coordinator.matchObservationId(match), expected.observationId)
            assertEquals(coordinator.state(attemptId), PairingAttemptPhase.PAIRING)
            assertNull(coordinator.match(attemptId, expected), "A duplicate scan must not claim the target twice")
            assertEquals(
                coordinator.constructorParameterTypes(),
                listOf(MonotonicClock::class.java, SecureRandom::class.java),
                "The coordinator must not receive a Session or connection collaborator",
            )
            assertFalse(
                coordinator.coordinatorClass.declaredMethods.any { it.name.contains("connect", ignoreCase = true) },
                "The QR coordinator must only return pairing authorization, never connect",
            )

            assertTrue(coordinator.complete(attemptId, PairingAttemptPhase.SUCCEEDED))
            assertFalse(coordinator.complete(attemptId, PairingAttemptPhase.FAILED))
            assertEquals(coordinator.state(attemptId), PairingAttemptPhase.SUCCEEDED)
            assertNull(coordinator.payload(material))
            assertCleared(password)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `expiry rejects late and duplicate services and invalidates material`() {
        val clock = FakeClock(nowMillis = 500)
        val coordinator = Harness(clock, DeterministicSecureRandom())
        val attemptId = attemptId("expired")
        try {
            val material = coordinator.start(attemptId)
            val service = observation(
                "late",
                WirelessServiceType.PAIRING,
                coordinator.serviceInstance(material),
                WirelessServiceStatus.RESOLVED,
            )
            clock.nowMillis = coordinator.deadlineMillis(material)

            assertNull(coordinator.match(attemptId, service))
            assertNull(coordinator.match(attemptId, service))
            assertEquals(coordinator.state(attemptId), PairingAttemptPhase.EXPIRED)
            assertNull(coordinator.payload(material))
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `terminal attempt wins once and later attempts never reuse material`() {
        val coordinator = Harness(FakeClock(), DeterministicSecureRandom())
        val firstId = attemptId("first-result")
        try {
            val first = coordinator.start(firstId)
            val firstService = coordinator.serviceInstance(first)
            val firstPasswordValue = coordinator.passwordValue(first)

            assertTrue(coordinator.complete(firstId, PairingAttemptPhase.CANCELLED))
            assertFalse(coordinator.complete(firstId, PairingAttemptPhase.SUCCEEDED))
            assertEquals(coordinator.state(firstId), PairingAttemptPhase.CANCELLED)
            assertNull(coordinator.payload(first))

            val secondId = attemptId("second-result")
            val second = coordinator.start(secondId)
            val secondService = observation(
                "second",
                WirelessServiceType.PAIRING,
                coordinator.serviceInstance(second),
                WirelessServiceStatus.RESOLVED,
            )
            assertFalse(coordinator.serviceInstance(second) == firstService)
            assertFalse(coordinator.passwordValue(second) == firstPasswordValue)
            assertNull(coordinator.match(firstId, secondService))
            assertFalse(coordinator.complete(firstId, PairingAttemptPhase.CANCELLED))
            assertEquals(coordinator.state(secondId), PairingAttemptPhase.WAITING_FOR_TARGET)
            val secondMatch = checkNotNull(coordinator.match(secondId, secondService))
            val secondPassword = coordinator.matchPassword(secondMatch)

            coordinator.close()
            assertNull(coordinator.payload(second))
            assertCleared(secondPassword)
        } finally {
            coordinator.close()
        }
    }

    private fun observation(
        id: String,
        type: WirelessServiceType,
        serviceName: String,
        status: WirelessServiceStatus,
    ): WirelessServiceObservation = WirelessServiceObservation(
        observationId = WirelessObservationId("observation-synthetic-$id"),
        serviceType = type,
        serviceName = serviceName,
        addresses = emptyList(),
        port = 1,
        status = status,
        lastSeenAt = 1,
    )

    private fun attemptId(suffix: String): PairingAttemptId = PairingAttemptId.of("attempt-synthetic-$suffix")

    private fun assertCleared(value: CharArray) {
        assertTrue(value.all { it == '\u0000' })
    }

    private class Harness(
        clock: MonotonicClock,
        entropy: SecureRandom,
    ) : AutoCloseable {
        val coordinatorClass: Class<*> = Class.forName(COORDINATOR_CLASS)
        private val coordinator = coordinatorClass.getDeclaredConstructor(
            MonotonicClock::class.java,
            SecureRandom::class.java,
        ).run {
            isAccessible = true
            newInstance(clock, entropy)
        }

        fun start(attemptId: PairingAttemptId): Any = invoke("start", attemptId)!!

        fun match(attemptId: PairingAttemptId, observation: WirelessServiceObservation): Any? =
            invoke("match", attemptId, observation)

        fun complete(attemptId: PairingAttemptId, phase: PairingAttemptPhase): Boolean =
            invoke("complete", attemptId, phase) as Boolean

        fun state(attemptId: PairingAttemptId): PairingAttemptPhase? =
            invoke("state", attemptId) as PairingAttemptPhase?

        fun serviceInstance(material: Any): String = property(material, "getServiceInstance") as String

        fun deadlineMillis(material: Any): Long = property(material, "getDeadlineMillis") as Long

        fun payload(material: Any): String? = property(material, "getPayload") as String?

        fun passwordValue(material: Any): String = checkNotNull(payload(material))
            .substringAfter(";P:")
            .substringBefore(";;")

        fun matchPassword(match: Any): CharArray =
            (property(match, "getSecret") as PairingSecret).withChars { it }

        fun matchObservationId(match: Any): WirelessObservationId =
            property(match, "getObservationId") as WirelessObservationId

        fun constructorParameterTypes(): List<Class<*>> =
            coordinatorClass.declaredConstructors.single().parameterTypes.toList()

        override fun close() {
            invoke("close")
        }

        private fun property(target: Any, getter: String): Any? = target.javaClass.getDeclaredMethod(getter).run {
            isAccessible = true
            invoke(target)
        }

        private fun invoke(name: String, vararg arguments: Any): Any? {
            val method = coordinatorClass.declaredMethods.single {
                it.name == name && it.parameterCount == arguments.size
            }
            method.isAccessible = true
            return try {
                method.invoke(coordinator, *arguments)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private class FakeClock(
        var nowMillis: Long = 0,
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class DeterministicSecureRandom(
        private val offset: Int = 0,
    ) : SecureRandom() {
        var nextBytesCalls = 0
        private var next = offset

        override fun nextBytes(bytes: ByteArray) {
            nextBytesCalls += 1
            bytes.indices.forEach { index -> bytes[index] = (next++ and 0xff).toByte() }
        }
    }

    private companion object {
        const val COORDINATOR_CLASS = "com.sheen.adb.core.internal.pairing.QrPairingCoordinator"
        const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
