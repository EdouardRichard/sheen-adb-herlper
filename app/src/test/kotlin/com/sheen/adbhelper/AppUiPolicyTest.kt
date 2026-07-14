package com.sheen.adbhelper

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationStage
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class AppUiPolicyTest {
    private val endpoint = AdbEndpoint("example.local", 37001)

    @Test
    fun `renders all allowed connection status labels`() {
        assertEquals(connectionStatusName(AdbConnectionState.Disconnected()), "未连接")
        assertEquals(connectionStatusName(AdbConnectionState.Connecting(endpoint)), "连接中")
        assertEquals(connectionStatusName(AdbConnectionState.AwaitingAuthorization(endpoint)), "等待设备授权")
        assertEquals(connectionStatusName(AdbConnectionState.Connected(endpoint, "session")), "已连接")
        assertEquals(connectionStatusName(AdbConnectionState.Pairing(endpoint)), "配对中")
        assertEquals(connectionStatusName(AdbConnectionState.Disconnecting), "断开中")
        assertEquals(
            connectionStatusName(AdbConnectionState.Error(AdbError.Unknown(AdbOperationStage.CONNECT), "safe")),
            "错误",
        )
    }

    @Test
    fun `connection dependent menu is disabled while offline`() {
        assertFalse(isMenuEnabled(requiresConnection = true, connected = false))
        assertTrue(isMenuEnabled(requiresConnection = false, connected = false))
        assertTrue(isMenuEnabled(requiresConnection = true, connected = true))
    }

}
