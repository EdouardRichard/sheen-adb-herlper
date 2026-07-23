package com.sheen.adb.core.internal.diagnostics

internal enum class StructuredLogcatKind { PARSED, UNPARSED, STDERR }

internal enum class StructuredLogcatLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, ASSERT }

internal data class LogcatTimestamp(
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)

internal sealed interface LogcatApplicationAssociation {
    data class Verified(val packageName: String) : LogcatApplicationAssociation
    data class Multiple(val packageNames: Set<String>) : LogcatApplicationAssociation
    data class Unknown(val reason: String? = null) : LogcatApplicationAssociation
}

internal data class StructuredLogcatRecord(
    val sequence: Long,
    val rawText: String,
    val kind: StructuredLogcatKind,
    val timestamp: LogcatTimestamp? = null,
    val uid: Int? = null,
    val pid: Int? = null,
    val tid: Int? = null,
    val level: StructuredLogcatLevel? = null,
    val tag: String? = null,
    val message: String? = null,
    val processName: String? = null,
    val applicationAssociation: LogcatApplicationAssociation = LogcatApplicationAssociation.Unknown(),
)

internal object StructuredLogcatParser {
    private val withUid = Regex(
        "^(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+" +
            "(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.+?):\\s?(.*)$",
    )
    private val withoutUid = Regex(
        "^(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+" +
            "(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.+?):\\s?(.*)$",
    )

    fun parse(
        sequence: Long,
        rawText: String,
        fromStandardError: Boolean,
    ): StructuredLogcatRecord {
        if (fromStandardError) {
            return StructuredLogcatRecord(sequence, rawText, StructuredLogcatKind.STDERR)
        }
        val match = withUid.matchEntire(rawText)
        if (match != null) return parseMatch(sequence, rawText, match.groupValues, hasUid = true)
        val legacy = withoutUid.matchEntire(rawText)
        if (legacy != null) return parseMatch(sequence, rawText, legacy.groupValues, hasUid = false)
        return StructuredLogcatRecord(sequence, rawText, StructuredLogcatKind.UNPARSED)
    }

    private fun parseMatch(
        sequence: Long,
        rawText: String,
        groups: List<String>,
        hasUid: Boolean,
    ): StructuredLogcatRecord {
        val timestamp = LogcatTimestamp(
            month = groups[1].toIntOrNull() ?: return unparsed(sequence, rawText),
            day = groups[2].toIntOrNull() ?: return unparsed(sequence, rawText),
            hour = groups[3].toIntOrNull() ?: return unparsed(sequence, rawText),
            minute = groups[4].toIntOrNull() ?: return unparsed(sequence, rawText),
            second = groups[5].toIntOrNull() ?: return unparsed(sequence, rawText),
            millisecond = groups[6].toIntOrNull() ?: return unparsed(sequence, rawText),
        )
        if (!timestamp.isValid()) return unparsed(sequence, rawText)
        val uidIndex = if (hasUid) 7 else -1
        val pidIndex = if (hasUid) 8 else 7
        val tidIndex = if (hasUid) 9 else 8
        val levelIndex = if (hasUid) 10 else 9
        val tagIndex = if (hasUid) 11 else 10
        val messageIndex = if (hasUid) 12 else 11
        val uid = if (hasUid) groups[uidIndex].toNonNegativeIntOrNull() ?: return unparsed(sequence, rawText) else null
        val pid = groups[pidIndex].toNonNegativeIntOrNull() ?: return unparsed(sequence, rawText)
        val tid = groups[tidIndex].toNonNegativeIntOrNull() ?: return unparsed(sequence, rawText)
        val level = groups[levelIndex].singleOrNull()?.toStructuredLevel() ?: return unparsed(sequence, rawText)
        val tag = groups[tagIndex].trim().takeIf(String::isNotEmpty) ?: return unparsed(sequence, rawText)
        return StructuredLogcatRecord(
            sequence = sequence,
            rawText = rawText,
            kind = StructuredLogcatKind.PARSED,
            timestamp = timestamp,
            uid = uid,
            pid = pid,
            tid = tid,
            level = level,
            tag = tag,
            message = groups[messageIndex],
        )
    }

    private fun unparsed(sequence: Long, rawText: String) =
        StructuredLogcatRecord(sequence, rawText, StructuredLogcatKind.UNPARSED)

    private fun LogcatTimestamp.isValid(): Boolean =
        month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59 &&
            second in 0..60 && millisecond in 0..999

    private fun String.toNonNegativeIntOrNull(): Int? = toIntOrNull()?.takeIf { it >= 0 }

    private fun Char.toStructuredLevel(): StructuredLogcatLevel? = when (this) {
        'V' -> StructuredLogcatLevel.VERBOSE
        'D' -> StructuredLogcatLevel.DEBUG
        'I' -> StructuredLogcatLevel.INFO
        'W' -> StructuredLogcatLevel.WARN
        'E' -> StructuredLogcatLevel.ERROR
        'F' -> StructuredLogcatLevel.FATAL
        'A' -> StructuredLogcatLevel.ASSERT
        else -> null
    }
}
