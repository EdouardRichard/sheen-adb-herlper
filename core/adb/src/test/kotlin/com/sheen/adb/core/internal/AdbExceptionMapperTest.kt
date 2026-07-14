package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationStage
import java.io.EOFException
import java.net.ConnectException
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
        assertTrue(AdbExceptionMapper.map(ConnectException(), stage) is AdbError.NetworkUnreachable)
        assertTrue(AdbExceptionMapper.map(SocketTimeoutException(), stage) is AdbError.Timeout)
        assertTrue(AdbExceptionMapper.map(SSLHandshakeException("x"), stage) is AdbError.AuthenticationFailed)
        assertTrue(AdbExceptionMapper.map(ProtocolException(), stage) is AdbError.ProtocolIncompatible)
        assertTrue(AdbExceptionMapper.map(EOFException(), stage) is AdbError.RemoteClosed)
    }

    @Test
    fun `only authentication failures allow pairing fallback`() {
        val stage = AdbOperationStage.CONNECT
        val authentication = AdbExceptionMapper.map(TestPairAuthException(), stage)
        val refusedPort = AdbExceptionMapper.map(ConnectException(), stage)

        assertTrue(authentication is AdbError.AuthenticationFailed)
        assertTrue(authentication.allowsPairingFallback)
        assertTrue(!refusedPort.allowsPairingFallback)
    }

    private class TestPairAuthException : Exception()

    @Test
    fun `does not expose exception messages in technical details`() {
        val syntheticCode = (0..5).joinToString("")
        val details = AdbExceptionMapper.safeTechnicalDetails(
            IllegalStateException("192.0.2.3 pairingCode=$syntheticCode"),
            null,
        )
        assertTrue("192.0.2.3" !in details)
        assertTrue(syntheticCode !in details)
    }
}
