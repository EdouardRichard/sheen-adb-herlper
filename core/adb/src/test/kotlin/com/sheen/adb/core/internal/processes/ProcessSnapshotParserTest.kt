package com.sheen.adb.core.internal.processes

import com.sheen.adb.core.ProcessFieldState
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

class ProcessSnapshotParserTest {
    @Test
    fun `parses ps and calculates normalized cpu and pss from two samples`() {
        val entries = ProcessSnapshotParser.parse(
            psText = """
                USER PID PPID NAME
                u0_a123 101 1 com.example.app
            """.trimIndent(),
            sessionId = "session-a",
            generation = 7,
            firstProcessTicks = mapOf(101 to 100L),
            secondProcessTicks = mapOf(101 to 130L),
            elapsedTotalTicks = 60L,
            processorCount = 2,
            pssKiBByPid = mapOf(101 to 1536L),
            startTimeTicksByPid = mapOf(101 to 900L),
        )

        val entry = entries.single()
        assertEquals(entry.identity.startTimeTicks, 900L)
        assertEquals(entry.parentPid, 1)
        assertEquals(entry.cpuPercent, 100.0)
        assertEquals(entry.cpuState, ProcessFieldState.AVAILABLE)
        assertEquals(entry.pssMiB, 1.5)
        assertEquals(entry.pssState, ProcessFieldState.AVAILABLE)
    }

    @Test
    fun `missing counters remain calculating or unknown without fake zero`() {
        val entries = ProcessSnapshotParser.parse(
            psText = """
                UID PID NAME
                u0_a123 101 com.example.app
                u0_a124 bad com.example.invalid
            """.trimIndent(),
            sessionId = "session-a",
            generation = 8,
            firstProcessTicks = mapOf(101 to 100L),
            secondProcessTicks = emptyMap(),
            elapsedTotalTicks = null,
            processorCount = 1,
            pssKiBByPid = emptyMap(),
            startTimeTicksByPid = emptyMap(),
        )

        val entry = entries.single()
        assertNull(entry.cpuPercent)
        assertEquals(entry.cpuState, ProcessFieldState.CALCULATING)
        assertNull(entry.pssMiB)
        assertEquals(entry.pssState, ProcessFieldState.UNKNOWN)
        assertNull(entry.parentPid)
    }

    @Test
    fun `counter rollback and exited process do not fabricate usage`() {
        val entries = ProcessSnapshotParser.parse(
            psText = """
                USER PID PPID NAME
                u0_a123 101 1 com.example.app
            """.trimIndent(),
            sessionId = "session-a",
            generation = 9,
            firstProcessTicks = mapOf(101 to 200L),
            secondProcessTicks = mapOf(101 to 100L),
            elapsedTotalTicks = 50L,
            processorCount = 1,
            pssKiBByPid = emptyMap(),
            startTimeTicksByPid = mapOf(101 to 1L),
        )

        assertNull(entries.single().cpuPercent)
        assertEquals(entries.single().cpuState, ProcessFieldState.UNKNOWN)
    }
}
