package com.sheen.adb.core.internal.diagnostics

import java.nio.charset.StandardCharsets

internal data class DiagnosticFilterCriteria(
    val levels: Set<StructuredLogcatLevel> = emptySet(),
    val tag: String = "",
    val keyword: String = "",
    val pid: String = "",
    val processName: String = "",
    val applicationPackage: String = "",
)

internal object DiagnosticFilter {
    fun matches(record: StructuredLogcatRecord, criteria: DiagnosticFilterCriteria): Boolean {
        if (criteria.levels.isNotEmpty() && record.level !in criteria.levels) return false
        if (!record.tag.containsIfPresent(criteria.tag)) return false
        if (!record.rawText.containsIfPresent(criteria.keyword)) return false
        if (!record.pid?.toString().containsIfPresent(criteria.pid)) return false
        if (!record.processName.containsIfPresent(criteria.processName)) return false
        val appNeedle = criteria.applicationPackage.trim()
        if (appNeedle.isNotEmpty()) {
            val verified = record.applicationAssociation as? LogcatApplicationAssociation.Verified ?: return false
            if (!verified.packageName.contains(appNeedle, ignoreCase = true)) return false
        }
        return true
    }

    private fun String?.containsIfPresent(rawNeedle: String): Boolean {
        val needle = rawNeedle.trim()
        return needle.isEmpty() || this?.contains(needle, ignoreCase = true) == true
    }
}

internal class StructuredLogcatBuffer(
    private val maxRecords: Int = 10_000,
    private val maxBytes: Long = 4L * 1024 * 1024,
) {
    private val records = ArrayDeque<StructuredLogcatRecord>()
    var totalBytes: Long = 0
        private set
    var droppedOldest: Boolean = false
        private set

    val size: Int
        get() = records.size

    init {
        require(maxRecords > 0)
        require(maxBytes > 0)
    }

    fun add(record: StructuredLogcatRecord) {
        val bounded = boundSingleRecord(record)
        records.addLast(bounded)
        totalBytes += byteSize(bounded)
        while (records.size > maxRecords || totalBytes > maxBytes) {
            totalBytes -= byteSize(records.removeFirst())
            droppedOldest = true
        }
    }

    fun snapshot(): List<StructuredLogcatRecord> = records.toList()

    fun latest(
        criteria: DiagnosticFilterCriteria,
        limit: Int = 100,
    ): List<StructuredLogcatRecord> {
        require(limit > 0)
        return records.asSequence().filter { DiagnosticFilter.matches(it, criteria) }.toList().takeLast(limit)
    }

    fun clear() {
        records.clear()
        totalBytes = 0
        droppedOldest = false
    }

    private fun boundSingleRecord(record: StructuredLogcatRecord): StructuredLogcatRecord {
        if (byteSize(record) <= maxBytes) return record
        droppedOldest = true
        val retainedRaw = tailUtf8(record.rawText, (maxBytes - 1).coerceAtLeast(0))
        return StructuredLogcatRecord(
            sequence = record.sequence,
            rawText = retainedRaw,
            kind = if (record.kind == StructuredLogcatKind.STDERR) {
                StructuredLogcatKind.STDERR
            } else {
                StructuredLogcatKind.UNPARSED
            },
        )
    }

    private fun byteSize(record: StructuredLogcatRecord): Long =
        record.rawText.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1

    private fun tailUtf8(value: String, maximumBytes: Long): String {
        var start = value.length
        var retained = 0L
        while (start > 0) {
            val previous = value.offsetByCodePoints(start, -1)
            val bytes = value.substring(previous, start).toByteArray(StandardCharsets.UTF_8).size
            if (retained + bytes > maximumBytes) break
            retained += bytes
            start = previous
        }
        return value.substring(start)
    }
}
