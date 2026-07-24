package com.sheen.adb.core.internal.processes

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessApplicationAssociationTest {
    @Test
    fun `exact package and package suffix resolve one application`() {
        val candidates = mapOf("com.example.app" to "示例应用")

        val exact = ProcessApplicationAssociationResolver.resolve("com.example.app", candidates)
        val worker = ProcessApplicationAssociationResolver.resolve("com.example.app:worker", candidates)

        assertEquals(exact?.applicationName, "示例应用")
        assertEquals(worker?.packageName, "com.example.app")
        assertTrue(exact?.wholeApplicationAllowed == true)
    }

    @Test
    fun `ambiguous package candidates and unrelated process remain unknown`() {
        val ambiguous = ProcessApplicationAssociationResolver.resolve(
            processName = "com.example.shared:worker",
            candidates = mapOf(
                "com.example.shared" to "应用一",
                "com.example.shared:worker" to "应用二",
            ),
        )
        val missing = ProcessApplicationAssociationResolver.resolve(
            "system_server",
            mapOf("com.example.app" to "示例应用"),
        )

        assertNull(ambiguous)
        assertNull(missing)
    }

    @Test
    fun `missing label uses fixed fallback without enabling ambiguous whole app action`() {
        val resolved = ProcessApplicationAssociationResolver.resolve(
            "com.example.app",
            mapOf("com.example.app" to null),
        )

        assertEquals(resolved?.applicationName, "无法解析应用名")
        assertFalse(resolved?.applicationName.isNullOrBlank())
    }
}
