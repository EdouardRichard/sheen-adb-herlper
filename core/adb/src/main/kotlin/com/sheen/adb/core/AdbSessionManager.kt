package com.sheen.adb.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
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

    suspend fun loadDeviceOverview(timeout: Duration = 20.seconds): AdbOperationResult<DeviceOverview>

    suspend fun refreshDynamicMetrics(timeout: Duration = 10.seconds): AdbOperationResult<DynamicDeviceMetrics>

    suspend fun listProcesses(timeout: Duration = 15.seconds): AdbOperationResult<ProcessSnapshot>

    fun streamLogcat(config: LogcatConfig): Flow<AdbOperationResult<LogcatLine>>

    suspend fun disconnect(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    suspend fun clearHostIdentity(timeout: Duration = 5.seconds): AdbOperationResult<Unit>

    fun clearDiagnosticEvents()

    fun reportInvalidAddress(userMessage: String)

    override fun close()
}
