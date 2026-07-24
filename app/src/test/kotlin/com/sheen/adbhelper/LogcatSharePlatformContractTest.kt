package com.sheen.adbhelper

import java.nio.file.Files
import java.nio.file.Path
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class LogcatSharePlatformContractTest {
    @Test
    fun `logcat provider is non exported and limited to share cache directory`() {
        val manifest = String(Files.readAllBytes(Path.of("src/main/AndroidManifest.xml")))
        val paths = String(Files.readAllBytes(Path.of("src/main/res/xml/logcat_share_paths.xml")))
        val app = String(Files.readAllBytes(Path.of("src/main/kotlin/com/sheen/adbhelper/SheenApp.kt")))
        val vm = String(
            Files.readAllBytes(
                Path.of("../feature/logcat/src/main/kotlin/com/sheen/adb/feature/logcat/LogcatScreen.kt"),
            ),
        )
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertTrue(paths.contains("logcat-share/"))
        assertFalse(paths.contains("path=\".\""))
        assertFalse(paths.contains("files-path"))
        assertTrue(vm.contains("Intent.ACTION_SEND"))
        assertTrue(vm.contains("Intent.EXTRA_STREAM"))
        assertTrue(vm.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertFalse(app.contains("ACTION_SEND_MULTIPLE"))
    }
}
