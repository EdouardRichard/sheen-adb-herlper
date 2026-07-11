package com.sheen.adb.core

object DiagnosticRedactor {
    private val ipv4 = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?![A-Za-z0-9])")
    private val bracketedIpv6 = Regex("\\[[0-9A-Fa-f:.%]+]")
    private val pairingCode = Regex("(?i)(pair(?:ing)?[ _-]?code\\s*[:=]?\\s*)\\d{6}")
    private val privateKeyBlock = Regex(
        "-----BEGIN (?:RSA )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA )?PRIVATE KEY-----",
    )
    private val certificateBlock = Regex(
        "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----",
    )

    fun redact(value: String): String = value
        .replace(privateKeyBlock, "<私钥已脱敏>")
        .replace(certificateBlock, "<证书已脱敏>")
        .replace(pairingCode) { "${it.groupValues[1]}<配对码已脱敏>" }
        .replace(bracketedIpv6, "[<IPv6已脱敏>]")
        .replace(ipv4, "<IP已脱敏>")
        .take(2_000)
}
