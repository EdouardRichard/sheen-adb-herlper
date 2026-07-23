package com.sheen.adb.feature.processes

import com.sheen.adb.core.DeviceProcess
import com.sheen.adb.core.ProcessAnalysisEntry
import com.sheen.adb.core.ProcessApplicationAssociation
import com.sheen.adb.core.ProcessAssociationUnknownReason
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessesAnalysisPolicyTest {
    @Test
    fun `pid process name and application filters accept partial text and combine with AND`() {
        val entries = listOf(
            entry(1234, "fixture.worker", ProcessApplicationAssociation.Verified("com.example.reader")),
            entry(2345, "fixture.remote", ProcessApplicationAssociation.Verified("com.example.writer")),
        )
        val state = ProcessesUiState(
            isConnected = true,
            sessionId = "session-a",
            entries = entries,
            pidQuery = "23",
            processQuery = "WORK",
            applicationQuery = "example.read",
        )

        assertEquals(state.visibleEntries.map { it.process.pid }, listOf(1234))
        assertEquals(state.copy(pidQuery = "1234").visibleEntries.single().process.pid, 1234)
        assertTrue(state.copy(pidQuery = "", processQuery = "", applicationQuery = "").visibleEntries == entries)
        assertTrue(state.copy(processQuery = "remote").visibleEntries.isEmpty())
    }

    @Test
    fun `multiple association remains explicit and unknown never matches application filter`() {
        val multiple = entry(
            100,
            "fixture.shared",
            ProcessApplicationAssociation.Multiple(setOf("com.example.shared.one", "com.example.shared.two")),
        )
        val unknown = entry(
            101,
            "fixture.unknown",
            ProcessApplicationAssociation.Unknown(ProcessAssociationUnknownReason.MISSING_UID),
        )
        val state = ProcessesUiState(
            isConnected = true,
            entries = listOf(multiple, unknown),
            applicationQuery = "shared.one",
        )

        assertEquals(state.visibleEntries.single().applicationAssociation, multiple.applicationAssociation)
        assertFalse(state.visibleEntries.single().applicationAssociation is ProcessApplicationAssociation.Verified)
        assertTrue(state.copy(applicationQuery = "unknown").visibleEntries.isEmpty())
    }

    @Test
    fun `refresh classification distinguishes empty exited unsupported and cancelled`() {
        val previous = listOf(entry(100, "fixture.old", ProcessApplicationAssociation.Verified("com.example.old")))
        val current = listOf(entry(101, "fixture.new", ProcessApplicationAssociation.Verified("com.example.new")))

        assertEquals(
            ProcessesPolicy.classifyRefresh(previous, current, degradedReason = null),
            ProcessesAnalysisStatus.PROCESSES_EXITED,
        )
        assertEquals(
            ProcessesPolicy.classifyRefresh(emptyList(), emptyList(), degradedReason = null),
            ProcessesAnalysisStatus.EMPTY,
        )
        assertEquals(
            ProcessesPolicy.classifyRefresh(emptyList(), emptyList(), degradedReason = "unsupported"),
            ProcessesAnalysisStatus.UNSUPPORTED,
        )
        assertEquals(ProcessesPolicy.cancelledStatus(), ProcessesAnalysisStatus.CANCELLED)
    }

    @Test
    fun `session switch clears entries filters exit state and stale generation`() {
        val dirty = ProcessesUiState(
            isConnected = true,
            sessionId = "session-a",
            generation = 7,
            entries = listOf(entry(100, "fixture.old", ProcessApplicationAssociation.Verified("com.example.old"))),
            pidQuery = "100",
            processQuery = "old",
            applicationQuery = "example",
            status = ProcessesAnalysisStatus.PROCESSES_EXITED,
        )

        val switched = ProcessesPolicy.changedSession(dirty, connected = true, sessionId = "session-b")

        assertTrue(switched.entries.isEmpty())
        assertEquals(switched.generation, 0)
        assertEquals(switched.pidQuery, "")
        assertEquals(switched.processQuery, "")
        assertEquals(switched.applicationQuery, "")
        assertEquals(switched.status, ProcessesAnalysisStatus.EMPTY)
        assertEquals(
            ProcessesPolicy.changedSession(switched, connected = false, sessionId = null).status,
            ProcessesAnalysisStatus.DISCONNECTED,
        )
    }

    private fun entry(
        pid: Int,
        name: String,
        association: ProcessApplicationAssociation,
    ) = ProcessAnalysisEntry(
        snapshotGeneration = 1,
        process = DeviceProcess(name = name, pid = pid, uid = "u0_a123"),
        applicationAssociation = association,
    )
}
