package com.sheen.adb.data

import org.testng.Assert.assertEquals
import org.testng.annotations.Test

class ProfileMutationsTest {
    @Test
    fun `creates updates and orders profiles by recent connection`() {
        val first = mergeSuccessfulConnection(
            emptyList(), "first.local", 30001, "第一台", false, "identity", 100, { "one" },
        ).first
        val second = mergeSuccessfulConnection(
            first, "second.local", 30002, "第二台", false, "identity", 200, { "two" },
        ).first
        val updated = mergeSuccessfulConnection(
            second, "FIRST.local", 40001, "ignored", false, "identity", 300, { "unused" },
        ).first

        assertEquals(updated.map(DeviceProfile::id), listOf("one", "two"))
        assertEquals(updated.first().debugPort, 40001)
        assertEquals(updated.first().firstConnectedAtEpochMillis, 100)
        assertEquals(updated.first().displayName, "第一台")
    }
}
