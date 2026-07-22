package com.sheen.adb.core.internal

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.stream.AdbSyncDirEntry
import com.flyfishxu.kadb.stream.AdbSyncDirEntryV2
import com.flyfishxu.kadb.stream.AdbSyncStat
import com.flyfishxu.kadb.stream.AdbSyncStatV2
import com.sheen.adb.core.AdbEndpoint
import java.io.EOFException
import java.nio.charset.StandardCharsets
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

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
            socketTimeout = KadbProtocolTimeouts.SOCKET_IO_TIMEOUT_MS,
        )
        return object : AdbProtocolClient {
            override fun execute(command: String): ProtocolShellResponse =
                openShellCommand(command).use { it.execute() }

            override fun openShellCommand(command: String): ProtocolShellCommand {
                val separated = kadb.supportsFeature("shell_v2")
                if (!separated) {
                    val stream = kadb.open("shell:$command")
                    return object : ProtocolShellCommand {
                        override fun execute(): ProtocolShellResponse {
                            val output = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES)
                            val buffer = Buffer()
                            while (true) {
                                val count = stream.source.read(buffer, STREAM_CHUNK_BYTES)
                                if (count < 0) break
                                output.append(buffer.readByteArray())
                            }
                            return ProtocolShellResponse(
                                stdout = output.text(),
                                stderr = "",
                                exitCode = 0,
                                streamsSeparated = false,
                                wasTruncated = output.truncated,
                            )
                        }

                        override fun close() = stream.close()
                    }
                }
                val stream = kadb.openShell(command)
                return object : ProtocolShellCommand {
                    override fun execute(): ProtocolShellResponse {
                        val stdout = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES)
                        val stderr = BoundedByteTail(MAX_SHELL_OUTPUT_BYTES)
                        var exitCode = 0
                        var finished = false
                        while (!finished) {
                            val packet = try {
                                stream.read()
                            } catch (error: Throwable) {
                                throw normalizedStreamReadFailure(error)
                            }
                            when (packet) {
                                is AdbShellPacket.StdOut -> stdout.append(packet.payload)
                                is AdbShellPacket.StdError -> stderr.append(packet.payload)
                                is AdbShellPacket.Exit -> {
                                    exitCode = packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                                    finished = true
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

                    override fun close() = stream.close()
                }
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
                    override fun read(): ProtocolShellPacket = try {
                        when (val packet = stream.read()) {
                            is AdbShellPacket.StdOut -> ProtocolShellPacket.StandardOutput(packet.payload)
                            is AdbShellPacket.StdError -> ProtocolShellPacket.StandardError(packet.payload)
                            is AdbShellPacket.Exit -> ProtocolShellPacket.Exit(
                                packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0,
                            )
                        }
                    } catch (error: Throwable) {
                        throw normalizedStreamReadFailure(error)
                    }

                    override fun close() = stream.close()
                }
            }

            override fun openSync(): ProtocolSyncStream {
                val supportsStatV2 = kadb.supportsFeature("stat_v2")
                val supportsListV2 = kadb.supportsFeature("ls_v2")
                val supportsSendRecvV2 = kadb.supportsFeature("sendrecv_v2")
                val sync = kadb.openSync()
                return object : ProtocolSyncStream {
                    override val version = if (supportsListV2 && supportsStatV2) {
                        ProtocolSyncVersion.V2
                    } else {
                        ProtocolSyncVersion.V1
                    }
                    override val transferVersion = if (supportsSendRecvV2) {
                        ProtocolSyncVersion.V2
                    } else {
                        ProtocolSyncVersion.V1
                    }

                    override fun list(path: String): List<ProtocolRemoteEntry> = if (supportsListV2) {
                        sync.listV2(path).map { it.toProtocolEntry() }
                    } else {
                        sync.list(path).map { it.toProtocolEntry() }
                    }

                    override fun lstat(path: String): ProtocolRemoteStat = if (supportsStatV2) {
                        sync.lstatV2(path).toProtocolStat()
                    } else {
                        sync.lstat(path).toProtocolStat()
                    }

                    override fun stat(path: String): ProtocolRemoteStat = if (supportsStatV2) {
                        sync.statV2(path).toProtocolStat()
                    } else {
                        sync.lstat(path).toProtocolStat()
                    }

                    override fun recv(path: String, sink: (ByteArray, Int, Int) -> Unit) {
                        sync.recv(
                            object : Sink {
                                override fun write(source: Buffer, byteCount: Long) {
                                    var remaining = byteCount
                                    while (remaining > 0L) {
                                        val count = minOf(remaining, SYNC_TRANSFER_CHUNK_BYTES.toLong()).toInt()
                                        val bytes = source.readByteArray(count.toLong())
                                        sink(bytes, 0, bytes.size)
                                        remaining -= count
                                    }
                                }

                                override fun flush() = Unit
                                override fun timeout(): Timeout = Timeout.NONE
                                override fun close() = Unit
                            },
                            path,
                        )
                    }

                    override fun send(
                        path: String,
                        mode: Int,
                        modifiedEpochMillis: Long,
                        source: (ByteArray) -> Int,
                    ) {
                        sync.send(
                            object : Source {
                                override fun read(sink: Buffer, byteCount: Long): Long {
                                    val requested = minOf(byteCount, SYNC_TRANSFER_CHUNK_BYTES.toLong()).toInt()
                                    val buffer = ByteArray(requested)
                                    val count = source(buffer)
                                    if (count < 0) return -1L
                                    require(count <= requested)
                                    sink.write(buffer, 0, count)
                                    return count.toLong()
                                }

                                override fun timeout(): Timeout = Timeout.NONE
                                override fun close() = Unit
                            },
                            path,
                            mode,
                            modifiedEpochMillis,
                        )
                    }

                    override fun close() = sync.close()
                }
            }

            override fun close() = kadb.close()
        }
    }

    override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) {
        try {
            Kadb.pair(
                host = endpoint.host,
                port = endpoint.port,
                pairingCode = pairingCode.concatToString(),
                name = "Sheen ADB Helper",
            )
        } finally {
            pairingCode.fill('\u0000')
        }
    }

    override fun clearIdentity() = KadbCert.clear()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_SHELL_OUTPUT_BYTES = 1024 * 1024
        const val STREAM_CHUNK_BYTES = 16_384L
        const val SYNC_TRANSFER_CHUNK_BYTES = 64 * 1024
    }

    private fun normalizedStreamReadFailure(error: Throwable): Throwable {
        if (error is ProtocolCommandStreamException) return error
        val type = error.javaClass.simpleName
        val message = error.message.orEmpty().lowercase()
        val isCommandEof = error is EOFException || type.contains("AdbStreamClosed") ||
            type.contains("StreamClosed") ||
            (error is IllegalStateException && (
                message.contains("not listening") ||
                    message.contains("stream closed") ||
                    message.contains("closed stream")
                ))
        return if (isCommandEof) ProtocolCommandStreamException() else error
    }

    private fun AdbSyncDirEntry.toProtocolEntry() = ProtocolRemoteEntry(
        name = name,
        mode = mode,
        size = size,
        modifiedEpochSeconds = mtimeSec,
        deviceId = null,
        inode = null,
    )

    private fun AdbSyncDirEntryV2.toProtocolEntry() = ProtocolRemoteEntry(
        name = name,
        mode = mode,
        size = size,
        modifiedEpochSeconds = mtimeSec,
        deviceId = dev,
        inode = ino,
    )

    private fun AdbSyncStat.toProtocolStat() = ProtocolRemoteStat(
        mode = mode,
        size = size,
        modifiedEpochSeconds = mtimeSec,
        deviceId = null,
        inode = null,
    )

    private fun AdbSyncStatV2.toProtocolStat() = ProtocolRemoteStat(
        mode = mode,
        size = size,
        modifiedEpochSeconds = mtimeSec,
        deviceId = dev,
        inode = ino,
    )

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

internal object KadbProtocolTimeouts {
    // Long-lived foreground streams must not inherit a connection-wide idle read timeout.
    // Finite commands remain bounded by AdbSessionManager coroutine timeouts.
    const val SOCKET_IO_TIMEOUT_MS = 0
}
