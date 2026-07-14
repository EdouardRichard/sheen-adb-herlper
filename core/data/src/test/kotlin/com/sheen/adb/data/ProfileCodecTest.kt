package com.sheen.adb.data

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.annotations.Test

class ProfileCodecTest {
    @Test
    fun `round trips profile metadata without delimiter ambiguity`() {
        val profile = DeviceProfile(
            id = "profile-1",
            displayName = "客厅 | 平板",
            host = "device.local",
            debugPort = 37123,
            firstConnectedAtEpochMillis = 100,
            lastConnectedAtEpochMillis = 200,
            isLocal = false,
            identityReference = "host-v1",
        )
        assertEquals(ProfileCodec.decode(ProfileCodec.encode(profile)), profile)
    }

    @Test
    fun `rejects corrupt persisted records`() {
        assertNull(ProfileCodec.decode("not-a-profile"))
    }
}
