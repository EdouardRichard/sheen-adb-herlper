package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbEndpoint

internal data class ProtocolShellResponse(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val streamsSeparated: Boolean,
    val wasTruncated: Boolean,
)

internal sealed interface ProtocolShellPacket {
    data class StandardOutput(val bytes: ByteArray) : ProtocolShellPacket
    data class StandardError(val bytes: ByteArray) : ProtocolShellPacket
    data class Exit(val code: Int) : ProtocolShellPacket
}

internal interface ProtocolShellStream : AutoCloseable {
    fun read(): ProtocolShellPacket
}

internal interface AdbProtocolClient : AutoCloseable {
    fun execute(command: String): ProtocolShellResponse
    fun openShellStream(command: String): ProtocolShellStream
}

internal interface AdbProtocolClientFactory {
    fun open(endpoint: AdbEndpoint): AdbProtocolClient
    suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray)
    fun clearIdentity()
}
