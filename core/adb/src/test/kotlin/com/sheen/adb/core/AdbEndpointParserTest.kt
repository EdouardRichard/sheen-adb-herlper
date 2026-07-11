package com.sheen.adb.core

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class AdbEndpointParserTest {
    @Test
    fun `parses IPv4 hostname and bracketed IPv6`() {
        assertEndpoint("192.168.1.20:5555", "192.168.1.20", 5555)
        assertEndpoint("device.local:37001", "device.local", 37001)
        assertEndpoint("[fe80::1%wlan0]:42123", "fe80::1%wlan0", 42123)
    }

    @Test
    fun `rejects ambiguous or out of range endpoints`() {
        assertTrue(AdbEndpointParser.parse("fe80::1:5555") is EndpointParseResult.Invalid)
        assertTrue(AdbEndpointParser.parse("192.168.1.2:0") is EndpointParseResult.Invalid)
        assertTrue(AdbEndpointParser.parse("999.168.1.2:5555") is EndpointParseResult.Invalid)
        assertTrue(AdbEndpointParser.parse("device.local") is EndpointParseResult.Invalid)
    }

    private fun assertEndpoint(raw: String, host: String, port: Int) {
        val result = AdbEndpointParser.parse(raw)
        assertTrue(result is EndpointParseResult.Valid)
        result as EndpointParseResult.Valid
        assertEquals(host, result.endpoint.host)
        assertEquals(port, result.endpoint.port)
    }
}
