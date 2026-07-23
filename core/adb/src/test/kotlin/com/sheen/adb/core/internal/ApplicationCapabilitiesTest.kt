package com.sheen.adb.core.internal

import com.sheen.adb.core.AndroidUidIdentity
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ApplicationCapabilitiesTest {
    @Test
    fun `current user parser accepts supported output and rejects ambiguous values`() {
        assertEquals(ApplicationParsers.currentUser("0\n"), 0)
        assertEquals(ApplicationParsers.currentUser("Current user: 10\n"), 10)
        assertEquals(ApplicationParsers.currentUser("warning\nCurrent user: 12\n"), 12)
        assertNull(ApplicationParsers.currentUser(""))
        assertNull(ApplicationParsers.currentUser("unknown"))
        assertNull(ApplicationParsers.currentUser("-1"))
        assertNull(ApplicationParsers.currentUser("0\n10"))
    }

    @Test
    fun `package parser distinguishes empty malformed duplicate and capacity`() {
        assertTrue(ApplicationParsers.packageNames("") is PackageNamesParse.Empty)

        val parsed = ApplicationParsers.packageNames(
            "package:com.example.alpha uid:10123\n" +
                "package:com.example.alpha uid:10123\n" +
                "package:org.example.beta\n",
        ) as PackageNamesParse.Success
        assertEquals(parsed.names, linkedSetOf("com.example.alpha", "org.example.beta"))
        assertEquals(parsed.uidsByPackage["com.example.alpha"], 10123)
        assertNull(parsed.uidsByPackage["org.example.beta"])

        val conflicting = ApplicationParsers.packageNames(
            "package:com.example.alpha uid:10123\npackage:com.example.alpha uid:10124\n",
        ) as PackageNamesParse.Success
        assertNull(conflicting.uidsByPackage["com.example.alpha"])

        assertTrue(ApplicationParsers.packageNames("package:valid.example\nROM noise") is PackageNamesParse.Malformed)
        val overLimit = (0..20_000).joinToString("\n") { "package:com.example.p$it" }
        assertTrue(ApplicationParsers.packageNames(overLimit) is PackageNamesParse.CapacityExceeded)
    }

    @Test
    fun `package validation is conservative and command arguments are fixed`() {
        assertTrue(ApplicationParsers.isValidPackageName("com.example_app.client2"))
        assertFalse(ApplicationParsers.isValidPackageName(""))
        assertFalse(ApplicationParsers.isValidPackageName("single"))
        assertFalse(ApplicationParsers.isValidPackageName("com.example;id"))
        assertFalse(ApplicationParsers.isValidPackageName("com.example\nid"))
        assertFalse(ApplicationParsers.isValidPackageName("com." + "a".repeat(260)))

        assertEquals(ApplicationCommands.listThirdParty(10), "pm list packages -3 -U --user 10")
        assertEquals(ApplicationCommands.listDisabledThirdParty(10), "pm list packages -3 -d --user 10")
        assertEquals(ApplicationCommands.forceStop(10, "com.example.client"), "am force-stop --user 10 com.example.client")
        assertEquals(ApplicationCommands.setEnabled(10, "com.example.client", false), "pm disable-user --user 10 com.example.client")
        assertEquals(ApplicationCommands.setEnabled(10, "com.example.client", true), "pm enable --user 10 com.example.client")
    }

    @Test
    fun `android uid normalization preserves user and app identity without guessing missing values`() {
        assertEquals(AndroidUidIdentity.fromRawUid(1_010_123), AndroidUidIdentity(userId = 10, appId = 10_123))
        assertEquals(AndroidUidIdentity.fromRawUid(10_123), AndroidUidIdentity(userId = 0, appId = 10_123))
        assertNull(AndroidUidIdentity.fromRawUid(-1))
        assertNull(AndroidUidIdentity.fromRawUid(null))
    }
}
