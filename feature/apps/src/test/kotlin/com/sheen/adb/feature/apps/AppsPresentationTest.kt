package com.sheen.adb.feature.apps

import com.sheen.adb.core.ApplicationIconEncoding
import com.sheen.adb.core.ApplicationIconFallback
import com.sheen.adb.core.ApplicationIconPayload
import com.sheen.adb.core.ApplicationMetadataStatus
import com.sheen.adb.core.RemoteApplication
import com.sheen.adb.core.RemoteApplicationEnabledState
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class AppsPresentationTest {
    @Test
    fun `display name is primary while package stays visible and missing name falls back to package`() {
        val application = app("com.example.reader")
        val named = AppsPresentation.present(
            application,
            AppsApplicationMetadata("阅读器", null, ApplicationMetadataStatus.AVAILABLE),
        )
        assertEquals(named.primaryLabel, "阅读器")
        assertEquals(named.packageLabel, application.packageName)

        val fallback = AppsPresentation.present(
            application,
            AppsApplicationMetadata(null, null, ApplicationMetadataStatus.UNAVAILABLE),
        )
        assertEquals(fallback.primaryLabel, application.packageName)
        assertNull(fallback.packageLabel)
        assertTrue(fallback.icon is AppsIconPresentation.Placeholder)
    }

    @Test
    fun `same display names keep distinct visible package labels`() {
        val metadata = AppsApplicationMetadata("同名应用", null, ApplicationMetadataStatus.AVAILABLE)
        val first = AppsPresentation.present(app("com.example.first"), metadata)
        val second = AppsPresentation.present(app("com.example.second"), metadata)

        assertEquals(first.primaryLabel, second.primaryLabel)
        assertEquals(first.packageLabel, "com.example.first")
        assertEquals(second.packageLabel, "com.example.second")
    }

    @Test
    fun `metadata states have explicit concise degradation messages`() {
        val expected = mapOf(
            ApplicationMetadataStatus.PENDING to "正在读取名称和图标",
            ApplicationMetadataStatus.AVAILABLE to null,
            ApplicationMetadataStatus.UNAVAILABLE to "名称或图标不可用",
            ApplicationMetadataStatus.TOO_LARGE to "应用信息超过安全上限",
            ApplicationMetadataStatus.PARSE_FAILED to "应用信息解析失败",
            ApplicationMetadataStatus.SESSION_CHANGED to "应用信息已随连接失效",
            ApplicationMetadataStatus.TIMED_OUT to "应用信息读取超时",
        )

        expected.forEach { (status, message) ->
            val presented = AppsPresentation.present(app("com.example.fixture"), AppsApplicationMetadata(status = status))
            assertEquals(presented.metadataMessage, message)
        }
    }

    @Test
    fun `long and bidirectional display text is bounded without losing exact package identity`() {
        val unsafe = "安全名称\u202E" + "x".repeat(300)

        val presented = AppsPresentation.present(
            app("com.example.identity"),
            AppsApplicationMetadata(unsafe, null, ApplicationMetadataStatus.AVAILABLE),
        )

        assertTrue(presented.primaryLabel.length <= AppsPresentation.MAX_DISPLAY_NAME_CHARS)
        assertFalse(presented.primaryLabel.contains('\u202E'))
        assertEquals(presented.packageLabel, "com.example.identity")
    }

    @Test
    fun `encoded icon above one MiB is rejected to the common placeholder`() {
        val accepted = AppsPresentation.present(
            app("com.example.accepted"),
            AppsApplicationMetadata("Accepted", icon(1024 * 1024), ApplicationMetadataStatus.AVAILABLE),
        )
        val rejected = AppsPresentation.present(
            app("com.example.rejected"),
            AppsApplicationMetadata("Rejected", icon(1024 * 1024 + 1), ApplicationMetadataStatus.AVAILABLE),
        )

        assertTrue(accepted.icon is AppsIconPresentation.Encoded)
        assertTrue(rejected.icon is AppsIconPresentation.Placeholder)
        assertEquals(rejected.icon, AppsIconPresentation.Placeholder)
    }

    private fun icon(size: Int) = ApplicationIconPayload(
        encoding = ApplicationIconEncoding.PNG,
        width = 1,
        height = 1,
        encodedBytes = ByteArray(size),
        fallback = ApplicationIconFallback.NONE,
    )

    private fun app(packageName: String) = RemoteApplication(
        packageName = packageName,
        userId = 0,
        enabledState = RemoteApplicationEnabledState.ENABLED,
        isSystem = false,
    )
}
