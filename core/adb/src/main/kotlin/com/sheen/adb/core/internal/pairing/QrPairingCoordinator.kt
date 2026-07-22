package com.sheen.adb.core.internal.pairing

import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingAttemptPhase
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import java.security.SecureRandom

internal class QrPairingCoordinator(
    private val clock: MonotonicClock,
    private val secureRandom: SecureRandom,
) : AutoCloseable {
    private val lock = Any()
    private val usedAttemptIds = mutableSetOf<PairingAttemptId>()
    private var current: ActiveQrAttempt? = null
    private var closed = false

    fun start(attemptId: PairingAttemptId): QrPairingMaterial = synchronized(lock) {
        check(!closed) { "QR pairing coordinator is closed." }
        current?.let(::expireIfDeadlineReached)
        check(attemptId !in usedAttemptIds) { "QR pairing attempt ID was already used." }
        check(current?.phase?.isTerminal() != false) { "A QR pairing attempt is already active." }

        val serviceInstance = SERVICE_PREFIX + randomChars(SERVICE_SUFFIX_LENGTH, SERVICE_ALPHABET).concatToString()
        val password = randomChars(PASSWORD_LENGTH, PASSWORD_ALPHABET)
        val now = clock.nowMillis()
        val deadline = if (now > Long.MAX_VALUE - DEFAULT_TTL_MILLIS) Long.MAX_VALUE else now + DEFAULT_TTL_MILLIS
        val material = QrPairingMaterial(
            attemptId = attemptId,
            serviceInstance = serviceInstance,
            deadlineMillis = deadline,
            password = password,
        )
        usedAttemptIds += attemptId
        current = ActiveQrAttempt(
            attemptId = attemptId,
            material = material,
            phase = PairingAttemptPhase.WAITING_FOR_TARGET,
        )
        material
    }

    fun match(
        attemptId: PairingAttemptId,
        observation: WirelessServiceObservation,
    ): QrPairingMatch? = synchronized(lock) {
        val attempt = current?.takeIf { it.attemptId == attemptId } ?: return@synchronized null
        if (attempt.phase.isTerminal()) return@synchronized null
        if (expireIfDeadlineReached(attempt)) return@synchronized null
        if (attempt.phase != PairingAttemptPhase.WAITING_FOR_TARGET ||
            observation.serviceType != WirelessServiceType.PAIRING ||
            observation.status != WirelessServiceStatus.RESOLVED ||
            observation.serviceName != attempt.material.serviceInstance
        ) {
            return@synchronized null
        }

        attempt.phase = PairingAttemptPhase.PAIRING
        QrPairingMatch(
            attemptId = attemptId,
            observationId = observation.observationId,
            secret = attempt.material.pairingSecret,
        )
    }

    fun complete(
        attemptId: PairingAttemptId,
        phase: PairingAttemptPhase,
    ): Boolean = synchronized(lock) {
        val attempt = current?.takeIf { it.attemptId == attemptId } ?: return@synchronized false
        if (attempt.phase.isTerminal()) return@synchronized false
        if (phase == PairingAttemptPhase.EXPIRED) {
            finish(attempt, phase)
            return@synchronized true
        }
        if (expireIfDeadlineReached(attempt) || !attempt.acceptsCompletion(phase)) return@synchronized false
        finish(attempt, phase)
        true
    }

    fun state(attemptId: PairingAttemptId): PairingAttemptPhase? = synchronized(lock) {
        val attempt = current?.takeIf { it.attemptId == attemptId } ?: return@synchronized null
        expireIfDeadlineReached(attempt)
        attempt.phase
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        current?.material?.invalidate()
        current = null
        usedAttemptIds.clear()
        closed = true
    }

    private fun finish(attempt: ActiveQrAttempt, phase: PairingAttemptPhase) {
        attempt.phase = phase
        attempt.material.invalidate()
    }

    private fun expireIfDeadlineReached(attempt: ActiveQrAttempt): Boolean {
        if (attempt.phase.isTerminal() || clock.nowMillis() < attempt.material.deadlineMillis) return false
        finish(attempt, PairingAttemptPhase.EXPIRED)
        return true
    }

    private fun randomChars(length: Int, alphabet: String): CharArray {
        val output = CharArray(length)
        val randomBytes = ByteArray(length.coerceAtLeast(1))
        val acceptanceLimit = 256 - (256 % alphabet.length)
        var outputIndex = 0
        try {
            while (outputIndex < length) {
                secureRandom.nextBytes(randomBytes)
                for (byte in randomBytes) {
                    val value = byte.toInt() and 0xff
                    if (value >= acceptanceLimit) continue
                    output[outputIndex++] = alphabet[value % alphabet.length]
                    if (outputIndex == length) break
                }
            }
            return output
        } finally {
            randomBytes.fill(0)
        }
    }

    private fun PairingAttemptPhase.isTerminal(): Boolean = this in TERMINAL_PHASES

    private fun ActiveQrAttempt.acceptsCompletion(phase: PairingAttemptPhase): Boolean = when (phase) {
        PairingAttemptPhase.SUCCEEDED,
        -> this.phase == PairingAttemptPhase.PAIRING

        PairingAttemptPhase.FAILED,
        PairingAttemptPhase.CANCELLED,
        PairingAttemptPhase.UNSUPPORTED,
        -> true

        else -> false
    }

    private class ActiveQrAttempt(
        val attemptId: PairingAttemptId,
        val material: QrPairingMaterial,
        var phase: PairingAttemptPhase,
    )

    private companion object {
        const val DEFAULT_TTL_MILLIS = 120_000L
        const val SERVICE_PREFIX = "studio-"
        const val SERVICE_SUFFIX_LENGTH = 10
        const val PASSWORD_LENGTH = 12
        const val SERVICE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-"
        const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val EXPLICIT_TERMINAL_PHASES = setOf(
            PairingAttemptPhase.SUCCEEDED,
            PairingAttemptPhase.CANCELLED,
            PairingAttemptPhase.FAILED,
            PairingAttemptPhase.UNSUPPORTED,
        )
        val TERMINAL_PHASES = EXPLICIT_TERMINAL_PHASES + PairingAttemptPhase.EXPIRED
    }
}

internal class QrPairingMaterial(
    val attemptId: PairingAttemptId,
    serviceInstance: String,
    val deadlineMillis: Long,
    private val password: CharArray,
) {
    private val lock = Any()
    private val pairingSecretReference = PairingSecret(password)
    private var active = true
    private var serviceInstanceReference: String? = serviceInstance
    private var payloadReference: String? = buildPayload(serviceInstance, password)

    val serviceInstance: String?
        get() = synchronized(lock) { serviceInstanceReference }

    val payload: String?
        get() = synchronized(lock) { payloadReference }

    val pairingSecret: PairingSecret
        get() = synchronized(lock) {
            check(active) { "QR pairing material is no longer active." }
            pairingSecretReference
        }

    internal fun invalidate() = synchronized(lock) {
        if (!active) return@synchronized
        active = false
        pairingSecretReference.clear()
        serviceInstanceReference = null
        payloadReference = null
    }

    override fun toString(): String = "QrPairingMaterial(redacted)"

    private companion object {
        fun buildPayload(serviceInstance: String, password: CharArray): String =
            "WIFI:T:ADB;S:$serviceInstance;P:${password.concatToString()};;"
    }
}

internal class QrPairingMatch(
    val attemptId: PairingAttemptId,
    val observationId: WirelessObservationId,
    val secret: PairingSecret,
) {
    override fun toString(): String = "QrPairingMatch(redacted)"
}
