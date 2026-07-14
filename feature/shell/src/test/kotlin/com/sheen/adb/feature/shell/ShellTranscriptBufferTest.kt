package com.sheen.adb.feature.shell

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ShellTranscriptBufferTest {
    @Test
    fun `drops oldest output and keeps utf8 boundary`() {
        val buffer = ShellTranscriptBuffer(maxOutputBytes = 12)
        buffer.add(ShellEntry(1, "first", stdout = "12345678", status = ShellEntryStatus.SUCCEEDED))
        buffer.add(ShellEntry(2, "second", stdout = "中文中文", status = ShellEntryStatus.SUCCEEDED))

        assertEquals(buffer.snapshot().size, 1)
        assertEquals(buffer.snapshot().single().stdout, "中文中文")
        assertTrue(buffer.droppedOldestOutput)
    }

    @Test
    fun `truncates one oversized entry from the front`() {
        val buffer = ShellTranscriptBuffer(maxOutputBytes = 5)
        buffer.add(ShellEntry(1, "command", stdout = "0123456789", status = ShellEntryStatus.SUCCEEDED))
        assertEquals(buffer.snapshot().single().stdout, "56789")
    }
}
