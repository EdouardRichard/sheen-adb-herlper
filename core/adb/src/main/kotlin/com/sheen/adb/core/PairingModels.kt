package com.sheen.adb.core

class PairingAttemptId private constructor(
    private val token: String,
) {
    override fun equals(other: Any?): Boolean = other is PairingAttemptId && token == other.token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = "PairingAttemptId(redacted)"

    companion object {
        fun of(token: String): PairingAttemptId {
            require(token.isNotBlank()) { "Pairing attempt ID must not be blank." }
            return PairingAttemptId(token)
        }
    }
}

class PairingSecret(
    private val chars: CharArray,
) {
    fun clear() {
        chars.fill('\u0000')
    }

    internal fun <T> withChars(block: (CharArray) -> T): T = block(chars)

    override fun toString(): String = "PairingSecret(redacted)"
}

enum class PairingMethod {
    QR,
    SIX_DIGIT_CODE,
}

enum class PairingAttemptPhase {
    PREPARING,
    WAITING_FOR_TARGET,
    WAITING_FOR_CODE,
    PAIRING,
    SUCCEEDED,
    CANCELLED,
    EXPIRED,
    FAILED,
    UNSUPPORTED,
}

enum class PairingFailure {
    CANCELLED,
    EXPIRED,
    EXPLICIT_FAILURE,
    ACTION_FAILED,
    UNSUPPORTED,
}

enum class PairingCommandRejection {
    INVALID_CODE,
    STALE_ATTEMPT,
    TERMINAL_ATTEMPT,
    ATTEMPT_ID_REUSED,
    ACTIVE_ATTEMPT_EXISTS,
}

data class PairingAttemptState(
    val attemptId: PairingAttemptId,
    val method: PairingMethod,
    val phase: PairingAttemptPhase,
    val deadlineMillis: Long,
    val failure: PairingFailure? = null,
)

data class PairingCommandResult(
    val state: PairingAttemptState,
    val rejection: PairingCommandRejection? = null,
)
