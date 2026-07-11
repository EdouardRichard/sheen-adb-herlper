package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbEndpoint

internal data class ProtocolShellResponse(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

internal interface AdbProtocolClient : AutoCloseable {
    fun execute(command: String): ProtocolShellResponse
}

internal interface AdbProtocolClientFactory {
    fun open(endpoint: AdbEndpoint): AdbProtocolClient
    suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray)
}
