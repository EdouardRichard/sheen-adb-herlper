package com.sheen.adb.feature.processes

import com.sheen.adb.core.ProcessFieldState
import com.sheen.adb.core.ProcessIdentity
import com.sheen.adb.core.ProcessSnapshotEntry
import com.sheen.adb.core.ProcessTerminationScope
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessesViewModelTest {
    @Test
    fun `whole application scope appears only for reliable package association`() {
        val associated = entry(101, generation = 4, packageName = "com.example.client")
        val unknown = entry(102, generation = 4)

        assertEquals(
            ProcessesPolicy.terminationScopes(associated),
            listOf(ProcessTerminationScope.SINGLE_PROCESS, ProcessTerminationScope.WHOLE_APPLICATION_FORCE_STOP),
        )
        assertEquals(
            ProcessesPolicy.terminationScopes(unknown),
            listOf(ProcessTerminationScope.SINGLE_PROCESS),
        )
    }

    @Test
    fun `confirmation is one shot and cancellation produces no request`() {
        val entry = entry(101, generation = 4, packageName = "com.example.client")
        val first = ProcessesPolicy.newConfirmation(entry, ProcessTerminationScope.SINGLE_PROCESS)
        val second = ProcessesPolicy.newConfirmation(entry, ProcessTerminationScope.SINGLE_PROCESS)
        assertNotEquals(first.nonce, second.nonce)

        val cancelled = ProcessesPolicy.cancelConfirmation(
            ProcessesUiState(
                isConnected = true,
                sessionId = "session-a",
                entries = listOf(entry),
                pendingTermination = first,
            ),
        )
        assertEquals(cancelled.pendingTermination, null)
        assertEquals(cancelled.terminationRequestCount, 0)
        assertFalse(ProcessesPolicy.canConfirm(cancelled, first.nonce))
    }

    @Test
    fun `stale generation and session cannot confirm or replace current snapshot`() {
        val current = ProcessesUiState(
            isConnected = true,
            sessionId = "session-a",
            generation = 8,
            entries = listOf(entry(101, generation = 8)),
        )
        val stale = listOf(entry(102, generation = 7))
        val otherSession = listOf(entry(103, sessionId = "session-b", generation = 9))

        assertFalse(ProcessesPolicy.acceptSnapshot(current, stale))
        assertFalse(ProcessesPolicy.acceptSnapshot(current, otherSession))
        assertTrue(ProcessesPolicy.acceptSnapshot(current, listOf(entry(104, generation = 9))))
    }

    @Test
    fun `confirmed application set contains only the selected package and field states stay explicit`() {
        val selected = entry(101, generation = 4, packageName = "com.example.client")
        val worker = entry(102, generation = 4, packageName = "com.example.client")
        val other = entry(201, generation = 4, packageName = "com.example.other")

        assertEquals(
            ProcessesPolicy.confirmedApplicationSet(selected, listOf(selected, worker, other)),
            setOf(selected.identity, worker.identity),
        )
        assertEquals(selected.cpuState, ProcessFieldState.CALCULATING)
        assertEquals(selected.pssState, ProcessFieldState.UNKNOWN)
    }

    private fun entry(
        pid: Int,
        sessionId: String = "session-a",
        generation: Long,
        packageName: String? = null,
    ) = ProcessSnapshotEntry(
        identity = ProcessIdentity(sessionId, pid, 900L + pid, "u0_a123", "process.$pid", generation),
        applicationPackage = packageName,
        cpuState = ProcessFieldState.CALCULATING,
        pssState = ProcessFieldState.UNKNOWN,
        parentPid = 1,
    )
}
