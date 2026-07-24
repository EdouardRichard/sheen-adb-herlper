package com.sheen.adb.data

import java.nio.file.Files
import kotlin.io.path.pathString
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatShareFileStoreTest {
    @Test
    fun `share lease has explicit chooser states and only one file in restricted directory`() {
        val root = Files.createTempDirectory("logcat-share-test").toFile()
        val store = LogcatShareFileStore(root)
        val lease = store.prepare("sensitive log", nowMillis = 1_000L)

        assertEquals(lease.state, LogcatShareState.PREPARED)
        assertEquals(store.markChooserOpened(lease), LogcatShareState.CHOOSER_OPENED)
        assertEquals(store.markTargetSelected(lease), LogcatShareState.TARGET_SELECTED)
        assertEquals(lease.file.parentFile.name, "logcat-share")
        assertEquals(lease.file.readText(), "sensitive log")
        assertEquals(lease.file.parentFile.listFiles()?.size, 1)
        assertFalse(store.hasPersistentPermission)
    }

    @Test
    fun `cancel cleans immediately while unknown and selected expire after one hour`() {
        val root = Files.createTempDirectory("logcat-share-expiry").toFile()
        val store = LogcatShareFileStore(root)
        val cancelled = store.prepare("cancel", 0L)
        store.cancel(cancelled)
        assertFalse(cancelled.file.exists())

        val unknown = store.prepare("unknown", 0L)
        store.markChooserOpened(unknown)
        assertEquals(store.cleanupExpired(nowMillis = 3_600_001L), 1)
        assertFalse(unknown.file.exists())
    }
}
