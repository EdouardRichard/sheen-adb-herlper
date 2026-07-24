package com.sheen.adb.core.internal.logcat

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

class LogcatCapturePolicyTest {
    @Test
    fun `snapshot is one shot and continuous requires a device start cursor`() {
        val snapshot = LogcatCapturePolicy(LogcatCaptureMode.SNAPSHOT, startedAtMillis = 0L, startCursor = null)
        assertTrue(snapshot.snapshotCompleted())
        assertEquals(snapshot.append("hello".encodeToByteArray(), 1L).decodeToString(), "hello")

        val continuous = LogcatCapturePolicy(
            LogcatCaptureMode.CONTINUOUS,
            startedAtMillis = 0L,
            startCursor = "cursor-1",
        )
        assertFalse(continuous.snapshotCompleted())
        assertEquals(continuous.cursor(), "cursor-1")
    }

    @Test
    fun `size and duration limits truncate without splitting utf8 code points`() {
        val policy = LogcatCapturePolicy(
            LogcatCaptureMode.CONTINUOUS,
            limits = LogcatCaptureLimits(maxBytes = 7L, maxDuration = 10.milliseconds),
            startedAtMillis = 0L,
            startCursor = "cursor",
        )
        val bytes = "甲乙丙".encodeToByteArray()
        val accepted = policy.append(bytes, 1L)
        assertTrue(accepted.size <= 7)
        assertTrue(policy.bytesCaptured() <= 7)
        assertEquals(policy.safeUtf8(accepted), accepted.decodeToString())
        assertTrue(policy.append("x".encodeToByteArray(), 11L).isEmpty())
        assertTrue(policy.isFinished())
    }

    @Test
    fun `continuous capture rejects missing cursor instead of clearing device logs`() {
        var rejected = false
        try {
            LogcatCapturePolicy(LogcatCaptureMode.CONTINUOUS, startedAtMillis = 0L, startCursor = null)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
