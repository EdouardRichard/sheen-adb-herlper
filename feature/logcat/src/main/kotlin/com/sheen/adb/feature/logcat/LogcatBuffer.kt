package com.sheen.adb.feature.logcat

import com.sheen.adb.core.ProcessApplicationAssociation
import com.sheen.adb.core.StructuredLogcatLevel
import com.sheen.adb.core.StructuredLogcatRecord
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

internal class LogcatWindow(
    private val buffer: LogcatBuffer = LogcatBuffer(),
    private val visibleLimit: Int = 100,
) {
    private var keyword: String = ""
    private var paused = false
    private val visibleLines = ArrayDeque<String>()

    init {
        require(visibleLimit > 0)
    }

    fun add(line: String) {
        buffer.add(line)
        if (!paused && matches(line)) {
            visibleLines.addLast(line)
            while (visibleLines.size > visibleLimit) visibleLines.removeFirst()
        }
    }

    fun updateKeyword(value: String) {
        keyword = value
        refresh()
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
        refresh()
    }

    fun clear() {
        buffer.clear()
        visibleLines.clear()
    }

    fun reset() {
        clear()
        keyword = ""
        paused = false
    }

    fun snapshot(): List<String> = visibleLines.toList()

    val droppedOldest: Boolean
        get() = buffer.droppedOldest

    private fun refresh() {
        val refreshed = buffer.snapshot()
            .asSequence()
            .filter(::matches)
            .toList()
            .takeLast(visibleLimit)
        visibleLines.clear()
        visibleLines.addAll(refreshed)
    }

    private fun matches(line: String): Boolean =
        keyword.trim().let { it.isEmpty() || line.contains(it, ignoreCase = true) }
}

data class LogcatAnalysisFilter(
    val levels: Set<StructuredLogcatLevel> = emptySet(),
    val tagQuery: String = "",
    val keyword: String = "",
    val pidQuery: String = "",
    val processQuery: String = "",
    val applicationQuery: String = "",
) {
    val activeCount: Int
        get() = listOf(
            levels.isNotEmpty(),
            tagQuery.isNotBlank(),
            keyword.isNotBlank(),
            pidQuery.isNotBlank(),
            processQuery.isNotBlank(),
            applicationQuery.isNotBlank(),
        ).count { it }
}

internal class LogcatAnalysisWindow(
    private val sessionId: String,
    private val processGeneration: Long,
    maxLines: Int = 10_000,
    maxBytes: Int = 4 * 1024 * 1024,
    private val visibleLimit: Int = 100,
) {
    private val buffer = StructuredLogcatBuffer(maxLines, maxBytes)
    private val visibleRecords = ArrayDeque<StructuredLogcatRecord>()
    var filter: LogcatAnalysisFilter = LogcatAnalysisFilter()
        private set
    private var paused = false

    init {
        require(visibleLimit > 0)
    }

    fun add(record: StructuredLogcatRecord): Boolean {
        if (record.sessionId != sessionId || record.snapshotGeneration != processGeneration) return false
        buffer.add(record)
        if (!paused && matches(record)) {
            visibleRecords.addLast(record)
            while (visibleRecords.size > visibleLimit) visibleRecords.removeFirst()
        }
        return true
    }

    fun updateFilter(value: LogcatAnalysisFilter) {
        filter = value
        if (!paused) refresh()
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
        refresh()
    }

    fun clear() {
        buffer.clear()
        visibleRecords.clear()
    }

    fun reset() {
        clear()
        filter = LogcatAnalysisFilter()
        paused = false
    }

    fun snapshot(): List<StructuredLogcatRecord> = visibleRecords.toList()

    fun visibleText(): String = visibleRecords.joinToString("\n") { it.rawText }

    val droppedOldest: Boolean
        get() = buffer.droppedOldest

    private fun refresh() {
        val refreshed = buffer.snapshot()
            .asSequence()
            .filter(::matches)
            .toList()
            .takeLast(visibleLimit)
        visibleRecords.clear()
        visibleRecords.addAll(refreshed)
    }

    private fun matches(record: StructuredLogcatRecord): Boolean {
        val tagNeedle = filter.tagQuery.trim()
        val keywordNeedle = filter.keyword.trim()
        val pidNeedle = filter.pidQuery.trim()
        val processNeedle = filter.processQuery.trim()
        val applicationNeedle = filter.applicationQuery.trim()
        return (filter.levels.isEmpty() || record.level in filter.levels) &&
            (tagNeedle.isEmpty() || record.tag?.contains(tagNeedle, ignoreCase = true) == true) &&
            (keywordNeedle.isEmpty() || record.rawText.contains(keywordNeedle, ignoreCase = true)) &&
            (pidNeedle.isEmpty() || record.pid?.toString()?.contains(pidNeedle) == true) &&
            (processNeedle.isEmpty() || record.processName?.contains(processNeedle, ignoreCase = true) == true) &&
            (applicationNeedle.isEmpty() || record.applicationAssociation.matchesVerified(applicationNeedle))
    }
}

private class StructuredLogcatBuffer(
    private val maxLines: Int,
    private val maxBytes: Int,
) {
    private val records = ArrayDeque<StructuredLogcatRecord>()
    private var bytes = 0
    var droppedOldest: Boolean = false
        private set

    init {
        require(maxLines > 0)
        require(maxBytes > 0)
    }

    fun add(record: StructuredLogcatRecord) {
        val accepted = if (byteSize(record.rawText) > maxBytes) {
            droppedOldest = true
            record.copy(
                rawText = tailUtf8(record.rawText, (maxBytes - 1).coerceAtLeast(0)),
                message = null,
            )
        } else {
            record
        }
        records.addLast(accepted)
        bytes += byteSize(accepted.rawText)
        while (records.size > maxLines || bytes > maxBytes) {
            bytes -= byteSize(records.removeFirst().rawText)
            droppedOldest = true
        }
    }

    fun snapshot(): List<StructuredLogcatRecord> = records.toList()

    fun clear() {
        records.clear()
        bytes = 0
        droppedOldest = false
    }

    private fun byteSize(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size + 1

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

private fun ProcessApplicationAssociation.matchesVerified(query: String): Boolean =
    this is ProcessApplicationAssociation.Verified && packageName.contains(query, ignoreCase = true)
