package com.sheen.adb.core

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class DiagnosticRedactorTest {
    @Test
    fun `redacts network identity pairing code and private key`() {
        val raw = "target=192.168.1.8 [fe80::1]:4455 pairingCode=123456 " +
            "-----BEGIN PRIVATE KEY----- secret -----END PRIVATE KEY----- " +
            "-----BEGIN CERTIFICATE----- cert -----END CERTIFICATE-----"
        val redacted = DiagnosticRedactor.redact(raw)

        assertFalse(redacted.contains("192.168.1.8"))
        assertFalse(redacted.contains("fe80::1"))
        assertFalse(redacted.contains("123456"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("cert "))
        assertTrue(redacted.contains("已脱敏"))
    }
}
