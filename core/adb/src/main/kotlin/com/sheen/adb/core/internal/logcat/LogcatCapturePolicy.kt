package com.sheen.adb.core.internal.logcat

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal enum class LogcatCaptureMode { SNAPSHOT, CONTINUOUS }

internal enum class LogcatCaptureStopReason {
    SNAPSHOT_COMPLETE,
    USER_STOPPED,
    SIZE_LIMIT,
    TIME_LIMIT,
    CANCELLED,
    SESSION_CHANGED,
    DISCONNECTED,
    UNSUPPORTED,
}

internal data class LogcatCaptureLimits(
    val maxBytes: Long = 10L * 1024L * 1024L,
    val maxDuration: Duration = 10.minutes,
)

internal class LogcatCapturePolicy(
    val mode: LogcatCaptureMode,
    private val limits: LogcatCaptureLimits = LogcatCaptureLimits(),
    private val startedAtMillis: Long,
    private val startCursor: String?,
) {
    private var byteCount = 0L
    private var finished = false

    init {
        require(startedAtMillis >= 0L)
        if (mode == LogcatCaptureMode.CONTINUOUS) require(!startCursor.isNullOrBlank())
    }

    fun append(bytes: ByteArray, nowMillis: Long): ByteArray {
        if (finished || bytes.isEmpty()) return ByteArray(0)
        if (nowMillis - startedAtMillis >= limits.maxDuration.inWholeMilliseconds) {
            finished = true
            return ByteArray(0)
        }
        val remaining = limits.maxBytes - byteCount
        if (remaining <= 0L) {
            finished = true
            return ByteArray(0)
        }
        val accepted = validUtf8Prefix(bytes, minOf(remaining, bytes.size.toLong()).toInt())
        byteCount += accepted.size
        if (byteCount >= limits.maxBytes || accepted.size < bytes.size) finished = true
        return accepted
    }

    fun snapshotCompleted(): Boolean = mode == LogcatCaptureMode.SNAPSHOT
    fun isFinished(): Boolean = finished
    fun bytesCaptured(): Long = byteCount
    fun cursor(): String? = startCursor

    fun safeUtf8(bytes: ByteArray): String = bytes.toString(StandardCharsets.UTF_8)

    private fun validUtf8Prefix(bytes: ByteArray, limit: Int): ByteArray {
        var size = limit
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        while (size > 0) {
            try {
                decoder.decode(ByteBuffer.wrap(bytes, 0, size))
                return bytes.copyOf(size)
            } catch (_: Exception) {
                size--
                decoder.reset()
            }
        }
        return ByteArray(0)
    }
}
