package com.sheen.adb.data

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class LogcatOutputMode { SNAPSHOT, CONTINUOUS }

class LogcatOutputStore(
    private val saf: SafDocumentStore,
    private val clock: Instant = Instant.now(),
) {
    fun save(
        treeId: String,
        mode: LogcatOutputMode,
        text: String,
        conflictPolicy: SafConflictPolicy = SafConflictPolicy.AUTO_RENAME,
    ): SafStoreResult<SafDocumentMetadata> {
        val displayName = "sheen-logcat-${mode.name.lowercase()}-${UTC_FORMAT.format(clock)}.txt"
        val target = when (val prepared = saf.prepareTarget(treeId, displayName, "text/plain")) {
            is SafStoreResult.Success -> prepared.value
            is SafStoreResult.Failure -> return prepared
        }
        return try {
            saf.openTarget(target).use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            when (val committed = saf.commit(target, conflictPolicy)) {
                is SafStoreResult.Success -> committed
                is SafStoreResult.Failure -> {
                    saf.cleanup(target)
                    committed
                }
            }
        } catch (_: Throwable) {
            saf.cleanup(target)
            SafStoreResult.Failure(SafStoreError.IO_FAILURE)
        }
    }

    private companion object {
        val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
