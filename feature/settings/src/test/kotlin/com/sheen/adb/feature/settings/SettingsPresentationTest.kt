package com.sheen.adb.feature.settings

import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class SettingsPresentationTest {
    @Test
    fun `clear data requires explicit confirmation`() {
        val requested = SettingsUiState("0.0.1", clearResult = "old").requestClearConfirmation()
        assertTrue(requested.showClearConfirmation)
        assertNull(requested.clearResult)
        assertFalse(requested.dismissClearConfirmation().showClearConfirmation)
    }
}
