package com.sheen.adb.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatOutputStoreTest {
    @Test
    fun `saves mode and utc name through a part file leaving one committed document`() {
        val backend = Backend()
        val store = LogcatOutputStore(SafDocumentStore(backend), Instant.parse("2026-07-23T08:09:10Z"))

        val result = store.save("tree", LogcatOutputMode.CONTINUOUS, "hello")

        assertTrue(result is SafStoreResult.Success)
        assertEquals(backend.children("tree").size, 1)
        val file = backend.children("tree").single()
        assertTrue(file.displayName.startsWith("sheen-logcat-continuous-20260723T080910Z"))
        assertEquals(backend.bytes(file.documentId).decodeToString(), "hello")
        assertTrue(backend.children("tree").none { it.displayName.endsWith(".part") })
    }

    @Test
    fun `commit failure removes staged part and reports failure`() {
        val backend = Backend(failRename = true)
        val store = LogcatOutputStore(SafDocumentStore(backend), Instant.parse("2026-07-23T08:09:10Z"))

        val result = store.save("tree", LogcatOutputMode.SNAPSHOT, "hello")

        assertTrue(result is SafStoreResult.Failure)
        assertTrue(backend.children("tree").isEmpty())
    }

    private class Backend(private val failRename: Boolean = false) : SafDocumentBackend {
        private val seq = AtomicInteger()
        private val nodes = linkedMapOf<String, Node>("tree" to Node("tree", "tree", "", ByteArray(0), null, setOf(SafCapability.READ, SafCapability.CREATE)))
        data class Node(val id: String, var displayName: String, val mime: String, var bytes: ByteArray, val parent: String?, val caps: Set<SafCapability>)

        fun childMetadata(parent: String) = nodes.values.filter { it.parent == parent }.map {
            SafDocumentMetadata(it.id, it.displayName, it.mime, it.bytes.size.toLong(), 1L, it.caps)
        }
        fun bytes(id: String) = nodes.getValue(id).bytes
        private fun nodeMetadata(id: String) = nodes[id]?.let { SafDocumentMetadata(it.id, it.displayName, it.mime, it.bytes.size.toLong(), 1L, it.caps) }
        override fun sourceMetadata(documentId: String) = nodeMetadata(documentId)
        override fun metadata(documentId: String) = nodeMetadata(documentId)
        override fun children(treeId: String): List<SafDocumentMetadata> = childMetadata(treeId)
        override fun create(treeId: String, mimeType: String, displayName: String): String {
            val id = "n${seq.incrementAndGet()}"
            nodes[id] = Node(id, displayName, mimeType, ByteArray(0), treeId, setOf(SafCapability.READ, SafCapability.WRITE, SafCapability.DELETE, SafCapability.RENAME))
            return id
        }
        override fun openInput(documentId: String) = ByteArrayInputStream(nodes.getValue(documentId).bytes)
        override fun openOutput(documentId: String) = object : ByteArrayOutputStream() {
            override fun close() { nodes.getValue(documentId).bytes = toByteArray(); super.close() }
        }
        override fun rename(documentId: String, displayName: String): String? = if (failRename) null else documentId.also { nodes.getValue(it).displayName = displayName }
        override fun delete(documentId: String): Boolean = nodes.remove(documentId) != null
    }
}
