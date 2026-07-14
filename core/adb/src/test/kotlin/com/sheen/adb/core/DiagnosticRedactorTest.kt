package com.sheen.adb.core

import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class DiagnosticRedactorTest {
    @Test
    fun `redacts network identity pairing code and private key`() {
        val syntheticCode = (0..5).joinToString("")
        val raw = "target=192.0.2.8 [2001:db8::1]:4455 pairingCode=$syntheticCode " +
            "-----BEGIN PRIVATE KEY----- secret -----END PRIVATE KEY----- " +
            "-----BEGIN CERTIFICATE----- cert -----END CERTIFICATE-----"
        val redacted = DiagnosticRedactor.redact(raw)

        assertFalse(redacted.contains("192.0.2.8"))
        assertFalse(redacted.contains("2001:db8::1"))
        assertFalse(redacted.contains(syntheticCode))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("cert "))
        assertTrue(redacted.contains("已脱敏"))
    }
}
