package com.sheen.adb.core.internal

import com.sheen.adb.core.WirelessDeviceNamePolicy
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

class WirelessDeviceNamePolicyTest {
    @Test
    fun trimsSafeNameAndRejectsUnsafeOrLongValues() {
        assertEquals("Pixel", WirelessDeviceNamePolicy.sanitize("  Pixel "))
        assertNull(WirelessDeviceNamePolicy.sanitize("Pixel\u202E"))
        assertNull(WirelessDeviceNamePolicy.sanitize("x".repeat(81)))
        assertNull(WirelessDeviceNamePolicy.sanitize("line\nbreak"))
    }
}
