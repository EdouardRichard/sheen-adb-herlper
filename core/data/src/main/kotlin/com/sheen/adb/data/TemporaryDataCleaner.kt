package com.sheen.adb.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface TemporaryDataCleaner {
    suspend fun clear(): Boolean
}

class AppTemporaryDataCleaner(context: Context) : TemporaryDataCleaner {
    private val cacheDirectory = context.applicationContext.cacheDir
    private val codeCacheDirectory = context.applicationContext.codeCacheDir

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        clearChildren(cacheDirectory) && clearChildren(codeCacheDirectory)
    }

    private fun clearChildren(directory: java.io.File): Boolean {
        val root = directory.canonicalFile
        return root.listFiles().orEmpty().all { child ->
            val target = child.canonicalFile
            target.path.startsWith(root.path + java.io.File.separator) && target.deleteRecursively()
        }
    }
}
