package com.sheen.adb.feature.logcat

import com.sheen.adb.core.ProcessApplicationAssociation
import com.sheen.adb.core.ProcessAssociationUnknownReason
import com.sheen.adb.core.StructuredLogcatKind
import com.sheen.adb.core.StructuredLogcatLevel
import com.sheen.adb.core.StructuredLogcatRecord
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatAnalysisWindowTest {
    @Test
    fun `all enabled structured filters combine with AND`() {
        val window = LogcatAnalysisWindow(sessionId = "session-a", processGeneration = 7)
        val reader = record(
            sequence = 1,
            level = StructuredLogcatLevel.INFO,
            tag = "FixtureReader",
            message = "loaded synthetic item",
            pid = 123,
            processName = "fixture.reader",
            association = ProcessApplicationAssociation.Verified("com.example.reader"),
        )
        val writer = record(
            sequence = 2,
            level = StructuredLogcatLevel.ERROR,
            tag = "FixtureWriter",
            message = "stored synthetic item",
            pid = 456,
            processName = "fixture.writer",
            association = ProcessApplicationAssociation.Verified("com.example.writer"),
        )
        window.add(reader)
        window.add(writer)

        window.updateFilter(
            LogcatAnalysisFilter(
                levels = setOf(StructuredLogcatLevel.INFO),
                tagQuery = "reader",
                keyword = "LOADED",
                pidQuery = "123",
                processQuery = "reader",
                applicationQuery = "example.reader",
            ),
        )

        assertEquals(window.snapshot(), listOf(reader))
        assertTrue(window.snapshot().single().kind == StructuredLogcatKind.PARSED)
        assertTrue(window.run { updateFilter(filter.copy(tagQuery = "writer")); snapshot() }.isEmpty())
    }

    @Test
    fun `unknown and multiple associations never satisfy unique application filter`() {
        val window = LogcatAnalysisWindow(sessionId = "session-a", processGeneration = 7)
        window.add(
            record(
                sequence = 1,
                association = ProcessApplicationAssociation.Multiple(
                    setOf("com.example.shared.one", "com.example.shared.two"),
                ),
            ),
        )
        window.add(
            record(
                sequence = 2,
                association = ProcessApplicationAssociation.Unknown(ProcessAssociationUnknownReason.NO_MATCH),
            ),
        )

        window.updateFilter(LogcatAnalysisFilter(applicationQuery = "example"))

        assertTrue(window.snapshot().isEmpty())
    }

    @Test
    fun `pause freezes visible records while resume catches up and clear removes current analysis`() {
        val window = LogcatAnalysisWindow(sessionId = "session-a", processGeneration = 7)
        window.add(record(sequence = 1, message = "before"))
        window.pause()
        window.add(record(sequence = 2, message = "during"))

        assertEquals(window.snapshot().map { it.message }, listOf("before"))
        assertEquals(window.visibleText(), record(sequence = 1, message = "before").rawText)

        window.resume()
        assertEquals(window.snapshot().map { it.message }, listOf("before", "during"))
        window.clear()
        assertTrue(window.snapshot().isEmpty())
        assertEquals(window.visibleText(), "")
    }

    @Test
    fun `window is bounded exposes latest visible records and exports only current matches`() {
        val window = LogcatAnalysisWindow(
            sessionId = "session-a",
            processGeneration = 7,
            maxLines = 3,
            maxBytes = 1_000,
            visibleLimit = 2,
        )
        repeat(5) { index ->
            window.add(record(sequence = index.toLong(), message = "message-$index"))
        }

        assertTrue(window.droppedOldest)
        assertEquals(window.snapshot().map { it.message }, listOf("message-3", "message-4"))
        window.updateFilter(LogcatAnalysisFilter(keyword = "message-4"))
        assertEquals(window.visibleText(), record(sequence = 4, message = "message-4").rawText)
    }

    @Test
    fun `session and process generation reject stale structured records`() {
        val window = LogcatAnalysisWindow(sessionId = "session-a", processGeneration = 7)

        assertFalse(window.add(record(sequence = 1, sessionId = "session-b")))
        assertFalse(window.add(record(sequence = 2, generation = 8)))
        assertTrue(window.add(record(sequence = 3)))
        assertEquals(window.snapshot().map { it.sequence }, listOf(3L))
    }

    private fun record(
        sequence: Long,
        sessionId: String = "session-a",
        generation: Long = 7,
        level: StructuredLogcatLevel? = StructuredLogcatLevel.INFO,
        tag: String? = "Fixture",
        message: String? = "synthetic",
        pid: Int? = 123,
        processName: String? = "fixture.process",
        association: ProcessApplicationAssociation = ProcessApplicationAssociation.Verified("com.example.fixture"),
    ): StructuredLogcatRecord = StructuredLogcatRecord(
        sessionId = sessionId,
        snapshotGeneration = generation,
        sequence = sequence,
        rawText = "07-23 10:20:30.000  123  124 I Fixture: ${message.orEmpty()}",
        kind = StructuredLogcatKind.PARSED,
        pid = pid,
        level = level,
        tag = tag,
        message = message,
        processName = processName,
        applicationAssociation = association,
    )
}
