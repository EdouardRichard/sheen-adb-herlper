package com.sheen.adb.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface AdbSessionManager : AutoCloseable {
    val connectionState: StateFlow<AdbConnectionState>
    val diagnosticEvents: StateFlow<List<AdbDiagnosticEvent>>

    suspend fun connect(
        endpoint: AdbEndpoint,
        timeout: Duration = 15.seconds,
    ): AdbOperationResult<Unit>

    suspend fun pair(
        pairingEndpoint: AdbEndpoint,
        pairingCode: CharArray,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<Unit>

    suspend fun executeShell(
        command: String,
        timeout: Duration = 30.seconds,
    ): AdbOperationResult<ShellResult>

    suspend fun disconnect(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    fun clearDiagnosticEvents()

    override fun close()
}

suspend fun AdbSessionManager.runSheenPoc(
    timeout: Duration = 10.seconds,
): AdbOperationResult<ShellResult> = executeShell(PocCommands.ECHO, timeout)

private object PocCommands {
    const val ECHO = "echo sheen-poc"
}
