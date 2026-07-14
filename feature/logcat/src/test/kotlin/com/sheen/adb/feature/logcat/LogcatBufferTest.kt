package com.sheen.adb.feature.logcat

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatBufferTest {
    @Test
    fun `enforces line limit by dropping oldest`() {
        val buffer = LogcatBuffer(maxLines = 3, maxBytes = 100)
        repeat(5) { buffer.add("line-$it") }
        assertEquals(buffer.snapshot(), listOf("line-2", "line-3", "line-4"))
        assertTrue(buffer.droppedOldest)
    }

    @Test
    fun `enforces utf8 byte limit`() {
        val buffer = LogcatBuffer(maxLines = 100, maxBytes = 8)
        buffer.add("中文中文")
        assertEquals(buffer.snapshot().single(), "中文")
        assertTrue(buffer.droppedOldest)
    }
}
