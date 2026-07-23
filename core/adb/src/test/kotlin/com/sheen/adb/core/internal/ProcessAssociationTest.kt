package com.sheen.adb.core.internal

import com.sheen.adb.core.AndroidUidIdentity
import com.sheen.adb.core.DeviceProcess
import com.sheen.adb.core.ProcessApplicationAssociation
import com.sheen.adb.core.ProcessAssociationUnknownReason
import com.sheen.adb.core.RemoteApplication
import com.sheen.adb.core.RemoteApplicationEnabledState
import com.sheen.adb.core.internal.diagnostics.ProcessAssociation
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessAssociationTest {
    @Test
    fun `numeric and android user uid forms normalize to the same user app identity`() {
        assertEquals(ProcessAssociation.parseProcessUid("10123"), AndroidUidIdentity(0, 10123))
        assertEquals(ProcessAssociation.parseProcessUid("u0_a123"), AndroidUidIdentity(0, 10123))
        assertEquals(ProcessAssociation.parseProcessUid("u10_a123"), AndroidUidIdentity(10, 10123))
        assertEquals(ProcessAssociation.parseProcessUid("u0_i42"), null)
        assertEquals(ProcessAssociation.parseProcessUid("fixture"), null)
    }

    @Test
    fun `unique uid verifies all app processes while shared uid remains multiple`() {
        val applications = listOf(
            app("com.example.unique", 10123),
            app("com.example.shared.one", 10124),
            app("com.example.shared.two", 10124),
        )
        val processes = listOf(
            process(100, "u0_a123", "fixture.main"),
            process(101, "u0_a123", "fixture.remote"),
            process(102, "u0_a124", "fixture.shared"),
        )

        val snapshot = ProcessAssociation.resolve(
            expectedSessionId = "session-a",
            applicationSessionId = "session-a",
            expectedGeneration = 7,
            applicationGeneration = 7,
            applications = applications,
            processes = processes,
        )

        snapshot.entries.take(2).forEach {
            assertEquals(it.applicationAssociation, ProcessApplicationAssociation.Verified("com.example.unique"))
        }
        val shared = snapshot.entries.last().applicationAssociation as ProcessApplicationAssociation.Multiple
        assertEquals(shared.packageNames, setOf("com.example.shared.one", "com.example.shared.two"))
    }

    @Test
    fun `missing invalid cross-user session and generation identities never guess by process name`() {
        val deceptiveName = "com.example.unique:looks-related"
        val applications = listOf(app("com.example.unique", 10123))
        val processes = listOf(
            process(100, null, deceptiveName),
            process(101, "invalid", deceptiveName),
            process(102, "u10_a123", deceptiveName),
        )

        val resolved = ProcessAssociation.resolve(
            "session-a",
            "session-a",
            3,
            3,
            applications,
            processes,
        )
        assertEquals(
            resolved.entries.map { (it.applicationAssociation as ProcessApplicationAssociation.Unknown).reason },
            listOf(
                ProcessAssociationUnknownReason.MISSING_UID,
                ProcessAssociationUnknownReason.INVALID_UID,
                ProcessAssociationUnknownReason.NO_MATCH,
            ),
        )

        val wrongSession = ProcessAssociation.resolve("session-a", "session-b", 3, 3, applications, processes)
        assertTrue(wrongSession.entries.all {
            it.applicationAssociation == ProcessApplicationAssociation.Unknown(
                ProcessAssociationUnknownReason.SESSION_MISMATCH,
            )
        })
        val wrongGeneration = ProcessAssociation.resolve("session-a", "session-a", 4, 3, applications, processes)
        assertTrue(wrongGeneration.entries.all {
            it.applicationAssociation == ProcessApplicationAssociation.Unknown(
                ProcessAssociationUnknownReason.GENERATION_MISMATCH,
            )
        })
    }

    @Test
    fun `old generation pid reuse and exited pid are unknown`() {
        val snapshot = ProcessAssociation.resolve(
            "session-a",
            "session-a",
            9,
            9,
            listOf(app("com.example.current", 10124)),
            listOf(process(200, "u0_a124", "fixture.current")),
        )

        val reused = ProcessAssociation.associatePid(
            snapshot = snapshot,
            expectedSessionId = "session-a",
            expectedGeneration = 8,
            pid = 200,
        )
        assertEquals(
            reused.applicationAssociation,
            ProcessApplicationAssociation.Unknown(ProcessAssociationUnknownReason.GENERATION_MISMATCH),
        )
        val exited = ProcessAssociation.associatePid(snapshot, "session-a", 9, pid = 201)
        assertEquals(
            exited.applicationAssociation,
            ProcessApplicationAssociation.Unknown(ProcessAssociationUnknownReason.PROCESS_EXITED),
        )
    }

    private fun app(packageName: String, uid: Int) = RemoteApplication(
        packageName = packageName,
        userId = AndroidUidIdentity.fromRawUid(uid)!!.userId,
        enabledState = RemoteApplicationEnabledState.ENABLED,
        isSystem = false,
        androidUid = uid,
    )

    private fun process(pid: Int, uid: String?, name: String) = DeviceProcess(name, pid, uid = uid)
}
