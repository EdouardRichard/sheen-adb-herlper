package com.sheen.adb.core.internal

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.sheen.adb.core.AdbEndpoint

internal class KadbProtocolClientFactory(context: Context) : AdbProtocolClientFactory {
    init {
        KadbCert.configure(
            store = AndroidKeystorePrivateKeyStore(context.applicationContext),
            policy = KadbCertPolicy(
                keySizeBits = 2048,
                certValidityDays = 3650,
                autoHealInvalidPrivateKey = false,
                subject = KadbCertPolicy.Subject(
                    cn = "Sheen ADB Helper",
                    ou = "Local ADB Host",
                    o = "Sheen",
                ),
            ),
        )
    }

    override fun open(endpoint: AdbEndpoint): AdbProtocolClient {
        KadbCert.ensureReady()
        val kadb = Kadb.create(
            host = endpoint.host,
            port = endpoint.port,
            connectTimeout = CONNECT_TIMEOUT_MS,
            socketTimeout = IO_TIMEOUT_MS,
        )
        return object : AdbProtocolClient {
            override fun execute(command: String): ProtocolShellResponse {
                val response = kadb.shell(command)
                return ProtocolShellResponse(
                    stdout = response.output,
                    stderr = response.errorOutput,
                    exitCode = response.exitCode,
                )
            }

            override fun close() = kadb.close()
        }
    }

    override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) {
        val transientCode = pairingCode.concatToString()
        try {
            Kadb.pair(
                host = endpoint.host,
                port = endpoint.port,
                pairingCode = transientCode,
                name = "Sheen ADB Helper",
            )
        } finally {
            pairingCode.fill('\u0000')
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 30_000
    }
}
