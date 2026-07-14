package com.sheen.adb.core

import kotlin.time.Duration

enum class AdbOperationStage {
    ADDRESS,
    CONNECT,
    AUTHENTICATE,
    PAIR,
    SHELL,
    DISCONNECT,
}

enum class AdbDiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    CANCELLED,
    FAILED,
    RESOURCE_CLOSED,
}

data class AdbDiagnosticEvent(
    val sequence: Long,
    val stage: AdbOperationStage,
    val outcome: AdbDiagnosticOutcome,
    val technicalCode: String,
    val redactedTarget: String,
    val causeType: String? = null,
)

sealed interface AdbError {
    val stage: AdbOperationStage
    val userMessage: String
    val nextStep: String
    val technicalCode: String
    val allowsPairingFallback: Boolean get() = false

    data class InvalidAddress(override val userMessage: String) : AdbError {
        override val stage = AdbOperationStage.ADDRESS
        override val nextStep = "检查地址格式；IPv6 请使用 [地址]:端口。"
        override val technicalCode = "ADB_ADDRESS_INVALID"
    }

    data class NetworkUnreachable(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "目标网络或端口不可达。"
        override val nextStep =
            "确认两台设备在同一局域网、无线调试仍开启，并使用无线调试主页面显示的调试端口；Android 11+ 请勿默认填写 5555。"
        override val technicalCode = "ADB_NETWORK_UNREACHABLE"
    }

    data class Timeout(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "操作已超时。"
        override val nextStep = "检查无线调试页面上的端口是否已变化，然后重试。"
        override val technicalCode = "ADB_TIMEOUT"
    }

    data class AuthenticationFailed(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "ADB 身份未获信任或 TLS 认证失败。"
        override val nextStep = "首次连接可使用系统无线调试页面显示的配对码；已配对设备请移除旧记录后重试。"
        override val technicalCode = "ADB_AUTH_FAILED"
        override val allowsPairingFallback = true
    }

    data class DeviceRejected(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "设备拒绝了本次 ADB 操作。"
        override val nextStep = "在被控设备上确认授权提示，或重新打开无线调试后重试。"
        override val technicalCode = "ADB_DEVICE_REJECTED"
    }

    data class ProtocolIncompatible(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "目标端口不是兼容的 ADB 服务。"
        override val nextStep = "确认使用的是调试端口而非配对端口；旧式设备需先由用户启用 ADB TCP/IP。"
        override val technicalCode = "ADB_PROTOCOL_INCOMPATIBLE"
    }

    data class RemoteClosed(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "ADB 会话已被远端关闭。"
        override val nextStep = "返回系统无线调试页面确认端口，然后重新连接。"
        override val technicalCode = "ADB_REMOTE_CLOSED"
    }

    data class Unknown(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "发生未识别的 ADB 错误。"
        override val nextStep = "断开后重试；若持续出现，请复制脱敏技术详情用于排查。"
        override val technicalCode = "ADB_UNKNOWN"
    }
}

sealed interface AdbConnectionState {
    data class Disconnected(
        val reason: DisconnectionReason = DisconnectionReason.NONE,
    ) : AdbConnectionState
    data class Connecting(val endpoint: AdbEndpoint) : AdbConnectionState
    data class AwaitingAuthorization(val endpoint: AdbEndpoint) : AdbConnectionState
    data class Connected(val endpoint: AdbEndpoint, val sessionId: String) : AdbConnectionState
    data class Pairing(val endpoint: AdbEndpoint) : AdbConnectionState
    data object Disconnecting : AdbConnectionState
    data class Error(val error: AdbError, val technicalDetails: String) : AdbConnectionState
}

enum class DisconnectionReason {
    NONE,
    CONNECT_CANCELLED,
    PAIR_CANCELLED,
    SHELL_CANCELLED,
    DISCONNECT_CANCELLED,
}

sealed interface AdbOperationResult<out T> {
    data class Success<T>(val value: T) : AdbOperationResult<T>
    data class Failure(val error: AdbError) : AdbOperationResult<Nothing>
    data object Cancelled : AdbOperationResult<Nothing>
}

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val elapsed: Duration,
)
