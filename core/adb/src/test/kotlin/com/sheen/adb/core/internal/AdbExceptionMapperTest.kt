package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationStage
import java.io.EOFException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class AdbExceptionMapperTest {
    @Test
    fun `maps expected network and protocol failures`() {
        val stage = AdbOperationStage.CONNECT
        assertTrue(AdbExceptionMapper.map(NoRouteToHostException(), stage) is AdbError.NetworkUnreachable)
        assertTrue(AdbExceptionMapper.map(SocketTimeoutException(), stage) is AdbError.Timeout)
        assertTrue(AdbExceptionMapper.map(SSLHandshakeException("x"), stage) is AdbError.AuthenticationFailed)
        assertTrue(AdbExceptionMapper.map(ProtocolException(), stage) is AdbError.ProtocolIncompatible)
        assertTrue(AdbExceptionMapper.map(EOFException(), stage) is AdbError.RemoteClosed)
    }

    @Test
    fun `does not expose exception messages in technical details`() {
        val details = AdbExceptionMapper.safeTechnicalDetails(
            IllegalStateException("192.168.1.3 pairingCode=123456"),
            null,
        )
        assertTrue("192.168.1.3" !in details)
        assertTrue("123456" !in details)
    }
}
