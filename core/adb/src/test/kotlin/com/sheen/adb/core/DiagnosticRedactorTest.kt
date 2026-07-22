package com.sheen.adb.core

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class DiagnosticRedactorTest {
    private val bareQrServiceInstance = "synthetic-adb-pairing-B8"
    private val bareQrPassword = "syntheticQrPass8"
    private val bareQrPayload = "WIFI:T:ADB;S:$bareQrServiceInstance;P:$bareQrPassword;;"

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

    @Test
    fun `redacts v003 file apk shell and logcat context`() {
        val digest = "a".repeat(64)
        val raw = "remotePath=/storage/emulated/0/synthetic/file.bin; " +
            "safUri=content://synthetic.provider/tree/private; " +
            "packageName=com.example.synthetic; " +
            "apkPath=/data/app/synthetic/base.apk; sha256=$digest; " +
            "shellOutput=SYNTHETIC_SHELL_CONTENT; logcat=SYNTHETIC_LOGCAT_CONTENT"

        val redacted = DiagnosticRedactor.redact(raw)

        assertFalse(redacted.contains("/storage/emulated/0"))
        assertFalse(redacted.contains("content://"))
        assertFalse(redacted.contains("com.example.synthetic"))
        assertFalse(redacted.contains("/data/app"))
        assertFalse(redacted.contains(digest))
        assertFalse(redacted.contains("SYNTHETIC_SHELL_CONTENT"))
        assertFalse(redacted.contains("SYNTHETIC_LOGCAT_CONTENT"))
    }

    @Test
    fun `redacts a complete wireless pairing QR payload`() {
        val serviceInstance = "synthetic-adb-pairing-A7"
        val qrPassword = "syntheticQrPass9"
        val qrPayload = "WIFI:T:ADB;S:$serviceInstance;P:$qrPassword;;"
        val redacted = DiagnosticRedactor.redact("qrPayload=$qrPayload")

        assertFalse(redacted.contains(qrPayload))
    }

    @Test
    fun `redacts a bare wireless pairing QR payload anywhere in text`() {
        val redacted = DiagnosticRedactor.redact("pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrPayload))
    }

    @Test
    fun `redacts the service instance from a bare wireless pairing QR payload`() {
        val redacted = DiagnosticRedactor.redact("pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrServiceInstance))
    }

    @Test
    fun `redacts the password from a bare wireless pairing QR payload`() {
        val redacted = DiagnosticRedactor.redact("pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrPassword))
    }

    @Test
    fun `redacts the service instance from a QR payload in a message field`() {
        val redacted = DiagnosticRedactor.redact("message=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrServiceInstance))
    }

    @Test
    fun `redacts the password from a QR payload in a message field`() {
        val redacted = DiagnosticRedactor.redact("message=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrPassword))
    }

    @Test
    fun `redacts the service instance from a QR payload in an exception field`() {
        val redacted = DiagnosticRedactor.redact("exception=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrServiceInstance))
    }

    @Test
    fun `redacts the password from a QR payload in an exception field`() {
        val redacted = DiagnosticRedactor.redact("exception=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrPassword))
    }

    @Test
    fun `redacts the service instance from a QR payload in a context field`() {
        val redacted = DiagnosticRedactor.redact("context=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrServiceInstance))
    }

    @Test
    fun `redacts the password from a QR payload in a context field`() {
        val redacted = DiagnosticRedactor.redact("context=pairing material $bareQrPayload observed")

        assertFalse(redacted.contains(bareQrPassword))
    }

    @Test
    fun `redacts an explicit arbitrary length QR password`() {
        val qrPassword = "syntheticQrPass9"
        val redacted = DiagnosticRedactor.redact("qrPassword=$qrPassword")

        assertFalse(redacted.contains(qrPassword))
    }

    @Test
    fun `redacts NSD service instance while retaining the safe service type`() {
        val serviceInstance = "synthetic-adb-pairing-A7"
        val redacted = DiagnosticRedactor.redact(
            "serviceType=_adb-tls-pairing._tcp; serviceName=$serviceInstance",
        )

        assertTrue(redacted.contains("_adb-tls-pairing._tcp"))
        assertFalse(redacted.contains(serviceInstance))
    }

    @Test
    fun `redacts an IPv4 endpoint address and port`() {
        val address = "192.0.2.44"
        val endpoint = "$address:37123"
        val redacted = DiagnosticRedactor.redact("endpoint=$endpoint")

        assertFalse(redacted.contains(address))
        assertFalse(redacted.contains(endpoint))
    }

    @Test
    fun `redacts an unbracketed scoped IPv6 endpoint address and port`() {
        val address = "fe80::7%synthetic0"
        val endpoint = "$address:37124"
        val redacted = DiagnosticRedactor.redact("endpoint=$endpoint")

        assertFalse(redacted.contains(address))
        assertFalse(redacted.contains(endpoint))
    }

    @Test
    fun `redacts a bracketed scoped IPv6 endpoint address and port`() {
        val address = "fe80::8%synthetic1"
        val endpoint = "[$address]:37125"
        val redacted = DiagnosticRedactor.redact("endpoint=$endpoint")

        assertFalse(redacted.contains(address))
        assertFalse(redacted.contains(endpoint))
    }

    @Test
    fun `redacts an explicit package name field`() {
        val packageName = "com.example.syntheticdiagnostics"
        val redacted = DiagnosticRedactor.redact("packageName=$packageName; stage=DISCOVERY")

        assertFalse(redacted.contains(packageName))
    }

    @Test
    fun `redacts a standalone application package field`() {
        val packageName = "com.example.syntheticdiagnostics"
        val redacted = DiagnosticRedactor.redact("application=$packageName; stage=DISCOVERY")

        assertFalse(redacted.contains(packageName))
    }

    @Test
    fun `redacts a bare package value in diagnostic context`() {
        val packageName = "com.example.syntheticdiagnostics"
        val redacted = DiagnosticRedactor.redact("selection failed for $packageName")

        assertFalse(redacted.contains(packageName))
    }

    @Test
    fun `redacts a raw exception field body`() {
        val rawExceptionBody = "exceptionBodyMarkerA7"
        val redacted = DiagnosticRedactor.redact("exception=$rawExceptionBody")

        assertFalse(redacted.contains(rawExceptionBody))
    }

    @Test
    fun `redacts a raw message field body`() {
        val rawMessageBody = "messageBodyMarkerB8"
        val redacted = DiagnosticRedactor.redact("message=$rawMessageBody")

        assertFalse(redacted.contains(rawMessageBody))
    }

    @Test
    fun `redacts a raw context field body`() {
        val rawContextBody = "contextBodyMarkerC9"
        val redacted = DiagnosticRedactor.redact("context=$rawContextBody")

        assertFalse(redacted.contains(rawContextBody))
    }

    @Test
    fun `structured diagnostic fields use an explicit safe whitelist`() {
        val fields = DiagnosticRedactor.safeFields(
            mapOf(
                "stage" to "FILE_TRANSFER",
                "outcome" to "FAILED",
                "technicalCode" to "SESSION_INVALID",
                "count" to 3,
                "remotePath" to "/storage/emulated/0/synthetic/file.bin",
                "safUri" to "content://synthetic.provider/tree/private",
                "packageName" to "com.example.synthetic",
                "shellOutput" to "SYNTHETIC_SHELL_CONTENT",
                "logcat" to "SYNTHETIC_LOGCAT_CONTENT",
                "qrPayload" to "WIFI:T:ADB;S:synthetic-adb-pairing-A7;P:syntheticQrPass9;;",
                "qrPassword" to "syntheticQrPass9",
                "serviceName" to "synthetic-adb-pairing-A7",
                "endpoint" to "fe80::7%synthetic0:37124",
                "application" to "com.example.syntheticdiagnostics",
                "exception" to "IllegalStateException: syntheticQrPass9",
                "message" to "synthetic-adb-pairing-A7 failed",
                "context" to "192.0.2.44:37123",
            ),
        )

        assertEquals(fields.keys, setOf("stage", "outcome", "technicalCode", "count"))
        assertEquals(fields["stage"], "FILE_TRANSFER")
        assertEquals(fields["outcome"], "FAILED")
        assertEquals(fields["technicalCode"], "SESSION_INVALID")
        assertEquals(fields["count"], "3")
        listOf(
            "remotePath",
            "safUri",
            "packageName",
            "shellOutput",
            "logcat",
            "qrPayload",
            "qrPassword",
            "serviceName",
            "endpoint",
            "application",
            "exception",
            "message",
            "context",
        ).forEach { unsafeKey ->
            assertFalse(fields.containsKey(unsafeKey))
        }
    }
}
