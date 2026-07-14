package com.sheen.adb.feature.shell

import com.sheen.adb.core.ShellOutputMode
import java.nio.charset.StandardCharsets

enum class ShellEntryStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, DISCONNECTED }

data class ShellEntry(
    val sequence: Long,
    val command: String,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val outputMode: ShellOutputMode = ShellOutputMode.SEPARATED,
    val status: ShellEntryStatus = ShellEntryStatus.RUNNING,
    val wasTruncated: Boolean = false,
)

class ShellTranscriptBuffer(private val maxOutputBytes: Int = 1024 * 1024) {
    private val entries = mutableListOf<ShellEntry>()
    var droppedOldestOutput: Boolean = false
        private set

    fun snapshot(): List<ShellEntry> = entries.toList()

    fun add(entry: ShellEntry) {
        if (entry.wasTruncated) droppedOldestOutput = true
        entries += trimSingle(entry)
        enforceLimit()
    }

    fun replace(sequence: Long, entry: ShellEntry) {
        if (entry.wasTruncated) droppedOldestOutput = true
        val index = entries.indexOfFirst { it.sequence == sequence }
        if (index >= 0) entries[index] = trimSingle(entry) else entries += trimSingle(entry)
        enforceLimit()
    }

    fun clear() {
        entries.clear()
        droppedOldestOutput = false
    }

    private fun enforceLimit() {
        while (outputBytes() > maxOutputBytes && entries.size > 1) {
            entries.removeAt(0)
            droppedOldestOutput = true
        }
        if (entries.size == 1 && outputBytes() > maxOutputBytes) {
            entries[0] = trimSingle(entries[0])
            droppedOldestOutput = true
        }
    }

    private fun trimSingle(entry: ShellEntry): ShellEntry {
        val stderrBytes = entry.stderr.toByteArray(StandardCharsets.UTF_8)
        val stdoutBudget = (maxOutputBytes - minOf(stderrBytes.size, maxOutputBytes)).coerceAtLeast(0)
        val stdout = tailUtf8(entry.stdout, stdoutBudget)
        val remaining = (maxOutputBytes - stdout.toByteArray(StandardCharsets.UTF_8).size).coerceAtLeast(0)
        val stderr = tailUtf8(entry.stderr, remaining)
        if (stdout != entry.stdout || stderr != entry.stderr) droppedOldestOutput = true
        return entry.copy(stdout = stdout, stderr = stderr)
    }

    private fun outputBytes(): Int = entries.sumOf {
        it.stdout.toByteArray(StandardCharsets.UTF_8).size + it.stderr.toByteArray(StandardCharsets.UTF_8).size
    }

    private fun tailUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var start = value.length
        var bytes = 0
        while (start > 0) {
            val previous = value.offsetByCodePoints(start, -1)
            val charBytes = value.substring(previous, start).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            start = previous
        }
        return value.substring(start)
    }
}
