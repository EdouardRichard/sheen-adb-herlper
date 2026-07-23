package com.sheen.adb.core.internal

import com.sheen.adb.core.internal.applications.ApplicationMetadataParseFailure
import com.sheen.adb.core.internal.applications.ApplicationMetadataParseResult
import com.sheen.adb.core.internal.applications.ApplicationMetadataParser
import com.sheen.adb.core.internal.applications.DecodedApkIcon
import com.sheen.adb.core.internal.applications.DecodedApkMetadata
import com.sheen.adb.core.internal.applications.ParsedApplicationIconKind
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ApplicationMetadataParserTest {
    @Test
    fun `selects preferred locale label and returns bounded raster icon`() {
        val apk = syntheticApk()
        val parser = ApplicationMetadataParser { _, locale ->
            DecodedApkMetadata(
                packageName = "com.example.fixture",
                splitName = null,
                label = if (locale.language == "zh") "示例应用" else "Example",
                icons = listOf(DecodedApkIcon.Raster("res/mipmap/icon.png", density = 480, bytes = PNG_1X1)),
            )
        }

        val result = parser.parse(apk, preferredLocaleTags = listOf("zh-CN", "en-US"))

        assertTrue(result is ApplicationMetadataParseResult.Success)
        val metadata = (result as ApplicationMetadataParseResult.Success).metadata
        assertEquals(metadata.displayName, "示例应用")
        assertEquals(metadata.icon?.kind, ParsedApplicationIconKind.RASTER)
        assertEquals(metadata.icon?.width, 1)
        assertEquals(metadata.icon?.height, 1)
        assertTrue(metadata.icon?.encodedBytes?.contentEquals(PNG_1X1) == true)
    }

    @Test
    fun `uses adaptive foreground as an explicit fallback`() {
        val parser = ApplicationMetadataParser { _, _ ->
            DecodedApkMetadata(
                packageName = "com.example.fixture",
                splitName = null,
                label = "Example",
                icons = listOf(
                    DecodedApkIcon.Adaptive(
                        foreground = DecodedApkIcon.Raster("res/drawable/foreground.png", 0, PNG_1X1),
                        background = null,
                    ),
                ),
            )
        }

        val result = parser.parse(syntheticApk(), listOf("en-US")) as ApplicationMetadataParseResult.Success

        assertEquals(result.metadata.icon?.kind, ParsedApplicationIconKind.ADAPTIVE_FOREGROUND_FALLBACK)
        assertTrue(result.metadata.icon?.encodedBytes?.contentEquals(PNG_1X1) == true)
    }

    @Test
    fun `missing label and icon resources remain a successful nullable degradation`() {
        val parser = ApplicationMetadataParser { _, _ ->
            DecodedApkMetadata(
                packageName = "com.example.fixture",
                splitName = null,
                label = null,
                icons = listOf(DecodedApkIcon.Raster("res/mipmap/missing.png", 0, null)),
            )
        }

        val result = parser.parse(syntheticApk(), listOf("en-US")) as ApplicationMetadataParseResult.Success

        assertNull(result.metadata.displayName)
        assertNull(result.metadata.icon)
    }

    @Test
    fun `rejects corrupt zip before invoking third party decoder`() {
        val invoked = AtomicBoolean(false)
        val parser = ApplicationMetadataParser { _, _ ->
            invoked.set(true)
            error("must not decode")
        }

        val result = parser.parse("not-an-apk".toByteArray(), listOf("en-US"))

        assertFailure(result, ApplicationMetadataParseFailure.MALFORMED_ARCHIVE)
        assertFalse(invoked.get())
    }

    @Test
    fun `rejects zip traversal before invoking third party decoder`() {
        val invoked = AtomicBoolean(false)
        val parser = ApplicationMetadataParser { _, _ ->
            invoked.set(true)
            error("must not decode")
        }

        val result = parser.parse(zipOf("../escaped" to byteArrayOf(1)), listOf("en-US"))

        assertFailure(result, ApplicationMetadataParseFailure.UNSAFE_ARCHIVE)
        assertFalse(invoked.get())
    }

    @Test
    fun `rejects suspicious compression ratio before decoding`() {
        val invoked = AtomicBoolean(false)
        val parser = ApplicationMetadataParser { _, _ ->
            invoked.set(true)
            error("must not decode")
        }
        val compressedBomb = zipOf("res/raw/payload.bin" to ByteArray(2 * 1024 * 1024))

        val result = parser.parse(compressedBomb, listOf("en-US"))

        assertFailure(result, ApplicationMetadataParseFailure.UNSAFE_ARCHIVE)
        assertFalse(invoked.get())
    }

    @Test
    fun `rejects split apk instead of treating it as a base apk`() {
        val parser = ApplicationMetadataParser { _, _ ->
            DecodedApkMetadata(
                packageName = "com.example.fixture",
                splitName = "config.en",
                label = "Example",
                icons = emptyList(),
            )
        }

        val result = parser.parse(syntheticApk(), listOf("en-US"))

        assertFailure(result, ApplicationMetadataParseFailure.SPLIT_APK_UNSUPPORTED)
    }

    @Test
    fun `rejects apk bytes above the 32 MiB input limit`() {
        val invoked = AtomicBoolean(false)
        val parser = ApplicationMetadataParser { _, _ ->
            invoked.set(true)
            error("must not decode")
        }

        val result = parser.parse(ByteArray(32 * 1024 * 1024 + 1), listOf("en-US"))

        assertFailure(result, ApplicationMetadataParseFailure.APK_TOO_LARGE)
        assertFalse(invoked.get())
    }

    private fun assertFailure(result: ApplicationMetadataParseResult, expected: ApplicationMetadataParseFailure) {
        assertTrue(result is ApplicationMetadataParseResult.Failure)
        assertEquals((result as ApplicationMetadataParseResult.Failure).reason, expected)
    }

    private fun syntheticApk(): ByteArray = zipOf(
        "AndroidManifest.xml" to byteArrayOf(3, 0, 8, 0),
        "resources.arsc" to byteArrayOf(2, 0, 12, 0),
        "res/mipmap/icon.png" to PNG_1X1,
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
