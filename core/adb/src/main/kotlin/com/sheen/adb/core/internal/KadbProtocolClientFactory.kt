package com.sheen.adb.core.internal

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.sheen.adb.core.AdbEndpoint
import java.nio.charset.StandardCharsets
import okio.Buffer

internal class KadbProtocolClientFactory(context: Context) : AdbProtocolClientFactory {
    private val privateKeyStore = AndroidKeystorePrivateKeyStore(context.applicationContext)

    init {
        KadbCert.configure(
            store = privateKeyStore,
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
                val separated = kadb.supportsFeature("shell_v2")
                if (!separated) {
                    val response = kadb.shell(command)
                    val stdout = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES).apply {
                        append(response.output.toByteArray(StandardCharsets.UTF_8))
                    }
                    val stderr = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES).apply {
                        append(response.errorOutput.toByteArray(StandardCharsets.UTF_8))
                    }
                    return ProtocolShellResponse(
                        stdout = stdout.text(),
                        stderr = stderr.text(),
                        exitCode = response.exitCode,
                        streamsSeparated = false,
                        wasTruncated = stdout.truncated || stderr.truncated,
                    )
                }
                val stdout = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES)
                val stderr = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES)
                var exitCode = 0
                kadb.openShell(command).use { stream ->
                    var finished = false
                    while (!finished) {
                        when (val packet = stream.read()) {
                            is AdbShellPacket.StdOut -> stdout.append(packet.payload)
                            is AdbShellPacket.StdError -> stderr.append(packet.payload)
                            is AdbShellPacket.Exit -> {
                                exitCode = packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                                finished = true
                            }
                        }
                    }
                }
                return ProtocolShellResponse(
                    stdout = stdout.text(),
                    stderr = stderr.text(),
                    exitCode = exitCode,
                    streamsSeparated = true,
                    wasTruncated = stdout.truncated || stderr.truncated,
                )
            }

            override fun openShellStream(command: String): ProtocolShellStream {
                if (!kadb.supportsFeature("shell_v2")) {
                    val adbStream = kadb.open("shell:$command")
                    return object : ProtocolShellStream {
                        private var ended = false
                        override fun read(): ProtocolShellPacket {
                            if (ended) return ProtocolShellPacket.Exit(0)
                            val buffer = Buffer()
                            val count = adbStream.source.read(buffer, STREAM_CHUNK_BYTES)
                            if (count < 0) {
                                ended = true
                                return ProtocolShellPacket.Exit(0)
                            }
                            return ProtocolShellPacket.StandardOutput(buffer.readByteArray())
                        }
                        override fun close() = adbStream.close()
                    }
                }
                val stream = kadb.openShell(command)
                return object : ProtocolShellStream {
                    override fun read(): ProtocolShellPacket = when (val packet = stream.read()) {
                        is AdbShellPacket.StdOut -> ProtocolShellPacket.StandardOutput(packet.payload)
                        is AdbShellPacket.StdError -> ProtocolShellPacket.StandardError(packet.payload)
                        is AdbShellPacket.Exit -> ProtocolShellPacket.Exit(
                            packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0,
                        )
                    }

                    override fun close() = stream.close()
                }
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

    override fun clearIdentity() = KadbCert.clear()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 30_000
        const val MAX_SHELL_OUTPUT_BYTES = 1024 * 1024
        const val STREAM_CHUNK_BYTES = 16_384L
    }

    private class BoundedByteTail(private val limit: Int) {
        private var data = ByteArray(0)
        var truncated = false
            private set

        fun append(value: ByteArray) {
            if (value.isEmpty()) return
            if (value.size >= limit) {
                data = value.copyOfRange(value.size - limit, value.size)
                truncated = true
                return
            }
            val overflow = (data.size + value.size - limit).coerceAtLeast(0)
            if (overflow > 0) truncated = true
            data = data.copyOfRange(overflow.coerceAtMost(data.size), data.size) + value
        }

        fun text(): String {
            var start = 0
            while (start < data.size && data[start].toInt().and(0xC0) == 0x80) start++
            return String(data, start, data.size - start, StandardCharsets.UTF_8)
        }
    }
}
