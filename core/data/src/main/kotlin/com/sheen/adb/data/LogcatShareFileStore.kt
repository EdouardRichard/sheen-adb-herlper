package com.sheen.adb.data

import java.io.File
import java.nio.charset.StandardCharsets

enum class LogcatShareState { PREPARED, CHOOSER_OPENED, TARGET_SELECTED, CANCELLED, OUTCOME_UNKNOWN, EXPIRED, CLEANED }

data class LogcatShareLease(
    val file: File,
    var state: LogcatShareState,
    val createdAtMillis: Long,
)

class LogcatShareFileStore(cacheDirectory: File) {
    private val shareDirectory = File(cacheDirectory, "logcat-share").also { it.mkdirs() }
    val hasPersistentPermission: Boolean = false

    fun prepare(text: String, nowMillis: Long): LogcatShareLease {
        check(shareDirectory.canonicalPath.startsWith(cacheDirectoryCanonical(shareDirectory.parentFile)))
        val file = File(shareDirectory, "logcat-${nowMillis}-${System.nanoTime()}.txt")
        file.writeText(text, StandardCharsets.UTF_8)
        file.setLastModified(nowMillis)
        return LogcatShareLease(file, LogcatShareState.PREPARED, nowMillis)
    }

    fun markChooserOpened(lease: LogcatShareLease): LogcatShareState =
        lease.transition(LogcatShareState.CHOOSER_OPENED)

    fun markTargetSelected(lease: LogcatShareLease): LogcatShareState =
        lease.transition(LogcatShareState.TARGET_SELECTED)

    fun markOutcomeUnknown(lease: LogcatShareLease): LogcatShareState =
        lease.transition(LogcatShareState.OUTCOME_UNKNOWN)

    fun cancel(lease: LogcatShareLease) {
        lease.state = LogcatShareState.CANCELLED
        lease.file.delete()
    }

    fun cleanupExpired(nowMillis: Long): Int {
        val expiry = 60L * 60L * 1000L
        return shareDirectory.listFiles().orEmpty().count { file ->
            val expired = nowMillis - file.lastModified() >= expiry
            if (expired) file.delete() else false
        }
    }

    private fun LogcatShareLease.transition(next: LogcatShareState): LogcatShareState {
        state = next
        return state
    }

    private fun cacheDirectoryCanonical(directory: File?) = directory?.canonicalPath.orEmpty()
}
