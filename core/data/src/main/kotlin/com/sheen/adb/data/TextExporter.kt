package com.sheen.adb.data

import android.content.Context
import android.net.Uri
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface TextExporter {
    suspend fun writeUtf8(target: Uri, text: String): Boolean
}

class SafTextExporter(context: Context) : TextExporter {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun writeUtf8(target: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(target, "wt")?.use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } ?: error("Unable to open selected document")
        }.isSuccess
    }
}
