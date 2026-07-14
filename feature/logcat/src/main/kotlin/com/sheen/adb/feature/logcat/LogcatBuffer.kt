package com.sheen.adb.feature.logcat

import java.nio.charset.StandardCharsets

class LogcatBuffer(
    private val maxLines: Int = 10_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0
    var droppedOldest: Boolean = false
        private set

    fun add(line: String) {
        var accepted = line
        val lineBytes = byteSize(accepted)
        if (lineBytes > maxBytes) {
            accepted = tailUtf8(accepted, (maxBytes - 1).coerceAtLeast(0))
            droppedOldest = true
        }
        lines.addLast(accepted)
        bytes += byteSize(accepted)
        while (lines.size > maxLines || bytes > maxBytes) {
            bytes -= byteSize(lines.removeFirst())
            droppedOldest = true
        }
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() {
        lines.clear()
        bytes = 0
        droppedOldest = false
    }

    private fun byteSize(value: String) = value.toByteArray(StandardCharsets.UTF_8).size + 1

    private fun tailUtf8(value: String, limit: Int): String {
        var start = value.length
        var used = 0
        while (start > 0) {
            val previous = value.offsetByCodePoints(start, -1)
            val count = value.substring(previous, start).toByteArray(StandardCharsets.UTF_8).size
            if (used + count > limit) break
            used += count
            start = previous
        }
        return value.substring(start)
    }
}
