package com.sheen.adb.core

import kotlin.time.Duration

enum class AdbOperationStage {
    ADDRESS,
    DISCOVERY,
    CONNECT,
    AUTHENTICATE,
    PAIR,
    SHELL,
    OVERVIEW,
    PROCESSES,
    LOGCAT,
    APPLICATIONS_LIST,
    APPLICATION_FORCE_STOP,
    APPLICATION_SET_ENABLED,
    APPLICATION_VERIFY,
    FILE_TRANSFER,
    APK_EXTRACTION,
    FILE_BROWSER,
    DISCONNECT,
}

enum class AdbExclusiveOperationKind(val stage: AdbOperationStage) {
    FILE_TRANSFER(AdbOperationStage.FILE_TRANSFER),
    APK_EXTRACTION(AdbOperationStage.APK_EXTRACTION),
    LOGCAT(AdbOperationStage.LOGCAT),
}

interface ExclusiveAdbOperationLease : AutoCloseable {
    val token: String
    val sessionId: String
    val kind: AdbExclusiveOperationKind
    val isActive: Boolean

    fun release()

    override fun close() = release()
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

    data object DiscoverySessionChanged : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "无线发现所属的 ADB 会话已变化。"
        override val nextStep = "请在当前会话中重新开始扫描。"
        override val technicalCode = "ADB_DISCOVERY_SESSION_CHANGED"
    }

    data object DiscoveryTimeout : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "无线发现已超时。"
        override val nextStep = "请确认无线调试已开启后重新扫描。"
        override val technicalCode = "ADB_DISCOVERY_TIMEOUT"
    }

    data object DiscoveryNetworkUnavailable : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "当前网络无法用于无线发现。"
        override val nextStep = "请连接到可用的局域网后重新扫描。"
        override val technicalCode = "ADB_DISCOVERY_NETWORK_UNAVAILABLE"
    }

    data object DiscoveryPermissionUnavailable : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "无线发现所需的系统能力当前不可用。"
        override val nextStep = "请检查应用权限和系统网络设置后重试。"
        override val technicalCode = "ADB_DISCOVERY_PERMISSION_UNAVAILABLE"
    }

    data object DiscoveryResolutionFailed : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "无法解析已发现的无线调试服务。"
        override val nextStep = "请保持无线调试页面开启并重新扫描。"
        override val technicalCode = "ADB_DISCOVERY_RESOLUTION_FAILED"
    }

    data object DiscoveryPlatformFailure : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "系统无线发现服务暂时不可用。"
        override val nextStep = "请稍后重新扫描，或改用手动输入。"
        override val technicalCode = "ADB_DISCOVERY_PLATFORM_FAILURE"
    }

    data object DiscoveryConflict : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "已有无线发现任务正在运行。"
        override val nextStep = "请先取消当前扫描，再开始新的扫描。"
        override val technicalCode = "ADB_DISCOVERY_CONFLICT"
    }

    data object DiscoveryManagerClosed : AdbError {
        override val stage = AdbOperationStage.DISCOVERY
        override val userMessage = "无线发现管理器已关闭。"
        override val nextStep = "请重新打开应用后再开始扫描。"
        override val technicalCode = "ADB_DISCOVERY_MANAGER_CLOSED"
    }

    data class OperationConflict(
        val requestedKind: AdbExclusiveOperationKind,
        val activeKind: AdbExclusiveOperationKind,
    ) : AdbError {
        override val stage = requestedKind.stage
        override val userMessage = "另一个长时间 ADB 操作正在运行。"
        override val nextStep = "请先停止或取消当前操作，然后重试。"
        override val technicalCode = "OPERATION_CONFLICT"
    }

    data class SessionInvalid(
        val operationKind: AdbExclusiveOperationKind,
    ) : AdbError {
        override val stage = operationKind.stage
        override val userMessage = "发起操作的 ADB 会话已失效或已切换。"
        override val nextStep = "请返回当前设备会话并重新发起操作。"
        override val technicalCode = "SESSION_INVALID"
    }

    data object RemotePathInvalid : AdbError {
        override val stage = AdbOperationStage.FILE_BROWSER
        override val userMessage = "远端路径无效或超过安全长度。"
        override val nextStep = "请从当前目录的已验证条目重新进入。"
        override val technicalCode = "ADB_REMOTE_PATH_INVALID"
    }

    data object RemotePermissionDenied : AdbError {
        override val stage = AdbOperationStage.FILE_BROWSER
        override val userMessage = "当前 ADB 身份无权读取该目录。"
        override val nextStep = "返回上级目录并选择可访问的位置。"
        override val technicalCode = "ADB_REMOTE_PERMISSION_DENIED"
    }

    data object RemotePathNotFound : AdbError {
        override val stage = AdbOperationStage.FILE_BROWSER
        override val userMessage = "该远端路径已不存在。"
        override val nextStep = "刷新上级目录后重试。"
        override val technicalCode = "ADB_REMOTE_PATH_NOT_FOUND"
    }

    data object RemoteDirectoryCapacityExceeded : AdbError {
        override val stage = AdbOperationStage.FILE_BROWSER
        override val userMessage = "目录超过 10,000 项安全上限。"
        override val nextStep = "不会显示部分结果；请选择较小的目录。"
        override val technicalCode = "ADB_REMOTE_DIRECTORY_CAPACITY_EXCEEDED"
    }

    data object RemoteSessionInvalid : AdbError {
        override val stage = AdbOperationStage.FILE_BROWSER
        override val userMessage = "目录结果所属的 ADB 会话已失效或已切换。"
        override val nextStep = "请在当前设备会话中重新打开文件浏览。"
        override val technicalCode = "ADB_REMOTE_SESSION_INVALID"
    }

    data object RemoteSourceChanged : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "传输期间源文件发生了变化。"
        override val nextStep = "请确认源文件不再被修改后重新传输。"
        override val technicalCode = "SOURCE_CHANGED"
    }

    data object RemoteIntegrityUnavailable : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "当前设备无法可靠确认文件完整性。"
        override val nextStep = "请更换设备或路径后重试；不会把未经确认的结果标记为成功。"
        override val technicalCode = "INTEGRITY_UNAVAILABLE"
    }

    data object NoProgressTimeout : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "文件传输长时间没有进展。"
        override val nextStep = "请检查连接和设备存储状态后重试。"
        override val technicalCode = "NO_PROGRESS_TIMEOUT"
    }

    data object LocalFileReadFailed : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "无法读取主控端所选源文件。"
        override val nextStep = "请确认文件仍存在且可读取，然后重新选择文件。"
        override val technicalCode = "LOCAL_FILE_READ_FAILED"
    }

    data object LocalFileWriteFailed : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "无法写入主控端所选保存位置。"
        override val nextStep = "请确认目标目录仍可写且空间充足，然后重试。"
        override val technicalCode = "LOCAL_FILE_WRITE_FAILED"
    }

    data object RemoteFilePermissionDenied : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "当前 ADB 身份无权读取远端文件或写入目标目录。"
        override val nextStep = "请选择共享存储中当前 ADB 身份可访问的位置。"
        override val technicalCode = "REMOTE_FILE_PERMISSION_DENIED"
    }

    data object RemoteFilePathNotFound : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "远端源文件或目标路径已不存在。"
        override val nextStep = "刷新远端目录后重新选择文件或目标位置。"
        override val technicalCode = "REMOTE_FILE_PATH_NOT_FOUND"
    }

    data object RemoteFileStreamClosed : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "文件传输的 ADB 子流在完成前已关闭。"
        override val nextStep = "当前连接会保留；请直接重试，若持续失败再重新连接。"
        override val technicalCode = "REMOTE_FILE_STREAM_CLOSED"
    }

    data object RemoteConflict : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "目标位置已存在同名文件。"
        override val nextStep = "请选择覆盖、自动重命名或取消。"
        override val technicalCode = "CONFLICT"
    }

    data object RemoteCleanupFailed : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "未能清理本次任务产生的临时文件。"
        override val nextStep = "请检查目标目录中的临时项后再重试。"
        override val technicalCode = "CLEANUP_FAILED"
    }

    data object RemoteCommitFailed : AdbError {
        override val stage = AdbOperationStage.FILE_TRANSFER
        override val userMessage = "文件已传输，但无法安全提交到目标名称。"
        override val nextStep = "原目标会尽力保持不变；请检查目标目录后重试。"
        override val technicalCode = "REMOTE_COMMIT_FAILED"
    }

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

    data class CommandStreamClosed(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "本次 ADB 命令流在完成前已关闭。"
        override val nextStep = "活动连接仍会保留；请直接重试命令，若持续出现再重新连接。"
        override val technicalCode = "ADB_COMMAND_STREAM_CLOSED"
    }

    data class IoFailure(override val stage: AdbOperationStage) : AdbError {
        override val userMessage = "本次 ADB 子流发生输入输出错误。"
        override val nextStep = "活动连接仍会保留；可直接重试，若设备已离线再重新连接。"
        override val technicalCode = "ADB_IO_FAILURE"
    }

    data object PairingUnsupported : AdbError {
        override val stage = AdbOperationStage.PAIR
        override val userMessage = "当前配对方式不可用。"
        override val nextStep = "请改用六位配对码，或重新打开系统无线调试配对页面。"
        override val technicalCode = "ADB_PAIR_UNSUPPORTED"
    }

    data object PairingSessionConflict : AdbError {
        override val stage = AdbOperationStage.PAIR
        override val userMessage = "已有设备保持连接，不能直接开始新的配对。"
        override val nextStep = "请先确认并断开当前设备，再重新开始配对。"
        override val technicalCode = "ADB_PAIR_SESSION_CONFLICT"
    }

    data object ApplicationCurrentUserUnavailable : AdbError {
        override val stage = AdbOperationStage.APPLICATIONS_LIST
        override val userMessage = "无法确认被控端当前 Android 用户。"
        override val nextStep = "检查 ROM 是否支持当前用户查询；不会回退到其他用户。"
        override val technicalCode = "ADB_APP_CURRENT_USER_UNAVAILABLE"
    }

    data object ApplicationListUnsupported : AdbError {
        override val stage = AdbOperationStage.APPLICATIONS_LIST
        override val userMessage = "ROM 未提供可安全解析的第三方应用列表。"
        override val nextStep = "其他调试功能仍可使用；可重连后重试应用列表。"
        override val technicalCode = "ADB_APP_LIST_UNSUPPORTED"
    }

    data object ApplicationListCapacityExceeded : AdbError {
        override val stage = AdbOperationStage.APPLICATIONS_LIST
        override val userMessage = "应用列表超过 20,000 项安全上限。"
        override val nextStep = "不会加载部分列表；请检查被控端包管理服务后重试。"
        override val technicalCode = "ADB_APP_LIST_CAPACITY_EXCEEDED"
    }

    data class ApplicationPackageNotFound(
        override val stage: AdbOperationStage,
    ) : AdbError {
        override val userMessage = "目标应用已不存在或不再属于当前列表。"
        override val nextStep = "刷新应用列表后，以设备当前状态为准。"
        override val technicalCode = "ADB_APP_PACKAGE_NOT_FOUND"
    }

    data class ApplicationTargetNotAllowed(
        override val stage: AdbOperationStage,
    ) : AdbError {
        override val userMessage = "该应用不在允许操作的当前用户第三方应用范围内。"
        override val nextStep = "系统应用、本机 Sheen 自身、未知状态或范围外目标不可绕过限制。"
        override val technicalCode = "ADB_APP_TARGET_NOT_ALLOWED"
    }

    data class ApplicationPolicyRejected(
        override val stage: AdbOperationStage,
    ) : AdbError {
        override val userMessage = "ROM 或设备管理策略拒绝了应用操作。"
        override val nextStep = "在被控端检查企业管理或系统策略，然后刷新确认实际状态。"
        override val technicalCode = "ADB_APP_POLICY_REJECTED"
    }

    data object ApplicationStateVerifyFailed : AdbError {
        override val stage = AdbOperationStage.APPLICATION_VERIFY
        override val userMessage = "操作后读取到的应用状态与目标状态不一致。"
        override val nextStep = "刷新列表并以设备实际状态为准。"
        override val technicalCode = "ADB_APP_STATE_VERIFY_FAILED"
    }

    data class ApplicationOutcomeUnknown(
        override val stage: AdbOperationStage,
    ) : AdbError {
        override val userMessage = "连接中断、超时或取消，无法确认设备是否已执行操作。"
        override val nextStep = "重新连接并刷新列表，以设备实际状态为准。"
        override val technicalCode = "ADB_APP_OUTCOME_UNKNOWN"
    }

    data class ApplicationSessionInvalid(
        override val stage: AdbOperationStage,
    ) : AdbError {
        override val userMessage = "发起操作的 ADB 会话已失效或已切换。"
        override val nextStep = "返回当前设备的应用列表并重新发起操作。"
        override val technicalCode = "ADB_APP_SESSION_INVALID"
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
    val outputMode: ShellOutputMode = ShellOutputMode.SEPARATED,
    val wasTruncated: Boolean = false,
)

enum class ShellOutputMode { SEPARATED, MERGED }

data class DeviceOverview(
    val brand: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val deviceCode: String? = null,
    val androidVersion: String? = null,
    val sdk: String? = null,
    val buildDisplay: String? = null,
    val buildFingerprint: String? = null,
    val securityPatch: String? = null,
    val cpuAbi: String? = null,
    val availableCores: Int? = null,
    val memoryTotalBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val storageAvailableBytes: Long? = null,
    val batteryPercent: Int? = null,
    val chargingState: String? = null,
    val temperatureCelsius: Double? = null,
    val uptimeSeconds: Long? = null,
    val networkAddresses: List<String> = emptyList(),
)

data class DynamicDeviceMetrics(
    val memoryTotalBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val batteryPercent: Int? = null,
    val chargingState: String? = null,
    val temperatureCelsius: Double? = null,
    val uptimeSeconds: Long? = null,
)

data class DeviceProcess(
    val name: String,
    val pid: Int,
    val uid: String? = null,
    val state: String? = null,
    val residentMemoryBytes: Long? = null,
)

data class ProcessSnapshot(
    val processes: List<DeviceProcess>,
    val degradedReason: String? = null,
)

enum class ProcessAssociationUnknownReason {
    MISSING_UID,
    INVALID_UID,
    NO_MATCH,
    SESSION_MISMATCH,
    GENERATION_MISMATCH,
    PID_REUSED,
    PROCESS_EXITED,
}

sealed interface ProcessApplicationAssociation {
    data class Verified(val packageName: String) : ProcessApplicationAssociation
    data class Multiple(val packageNames: Set<String>) : ProcessApplicationAssociation
    data class Unknown(val reason: ProcessAssociationUnknownReason) : ProcessApplicationAssociation
}

enum class ProcessAnalysisCapability {
    UID,
    STATE,
    RESIDENT_MEMORY,
    APPLICATION_ASSOCIATION,
}

data class ProcessAnalysisEntry(
    val snapshotGeneration: Long,
    val process: DeviceProcess,
    val applicationAssociation: ProcessApplicationAssociation,
    val unavailableCapabilities: Set<ProcessAnalysisCapability> = emptySet(),
)

data class ProcessAnalysisSnapshot(
    val sessionId: String,
    val generation: Long,
    val entries: List<ProcessAnalysisEntry>,
    val degradedReason: String? = null,
)

data class ProcessRecordAssociation(
    val processName: String?,
    val applicationAssociation: ProcessApplicationAssociation,
)

enum class StructuredLogcatKind { PARSED, UNPARSED, STDERR }

enum class StructuredLogcatLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL, ASSERT }

data class StructuredLogcatTimestamp(
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)

data class StructuredLogcatRecord(
    val sessionId: String,
    val snapshotGeneration: Long,
    val sequence: Long,
    val rawText: String,
    val kind: StructuredLogcatKind,
    val timestamp: StructuredLogcatTimestamp? = null,
    val uid: Int? = null,
    val pid: Int? = null,
    val tid: Int? = null,
    val level: StructuredLogcatLevel? = null,
    val tag: String? = null,
    val message: String? = null,
    val processName: String? = null,
    val applicationAssociation: ProcessApplicationAssociation = ProcessApplicationAssociation.Unknown(
        ProcessAssociationUnknownReason.NO_MATCH,
    ),
)

enum class RemoteApplicationEnabledState {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

data class AndroidUidIdentity(
    val userId: Int,
    val appId: Int,
) {
    companion object {
        private const val PER_USER_RANGE = 100_000

        fun fromRawUid(rawUid: Int?): AndroidUidIdentity? {
            val verified = rawUid?.takeIf { it >= 0 } ?: return null
            return AndroidUidIdentity(
                userId = verified / PER_USER_RANGE,
                appId = verified % PER_USER_RANGE,
            )
        }
    }
}

enum class ApplicationField {
    VERSION_CODE,
    VERSION_NAME,
    INSTALLER_PACKAGE,
}

data class RemoteApplication(
    val packageName: String,
    val userId: Int,
    val enabledState: RemoteApplicationEnabledState,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val installerPackage: String? = null,
    val isSystem: Boolean,
    val androidUid: Int? = null,
) {
    val uidIdentity: AndroidUidIdentity?
        get() = AndroidUidIdentity.fromRawUid(androidUid)
}

data class ApplicationSnapshot(
    val sessionId: String,
    val userId: Int,
    val applications: List<RemoteApplication>,
    val unavailableFields: Set<ApplicationField>,
    val degradedReason: String? = null,
)

enum class RemoteFileKind { FILE, DIRECTORY, SYMLINK, OTHER }

enum class RemoteLinkResolution { NOT_A_LINK, VERIFIED, MISSING, PERMISSION_DENIED, LOOP, UNSUPPORTED }

enum class RemoteDirectorySource { STAT_V2, LIST_V2, SYNC_V1_DEGRADED }

data class RemoteBreadcrumb(val label: String, val path: String)

data class RemotePathEntry(
    val absolutePath: String,
    val displayName: String,
    val kind: RemoteFileKind,
    val sizeBytes: Long?,
    val modifiedEpochSeconds: Long?,
    val mode: Int?,
    val deviceId: Long?,
    val inode: Long?,
    val linkResolution: RemoteLinkResolution = RemoteLinkResolution.NOT_A_LINK,
    val targetKind: RemoteFileKind? = null,
) {
    val enterable: Boolean get() = kind == RemoteFileKind.DIRECTORY ||
        (kind == RemoteFileKind.SYMLINK && linkResolution == RemoteLinkResolution.VERIFIED && targetKind == RemoteFileKind.DIRECTORY)
    val selectable: Boolean get() = kind == RemoteFileKind.FILE ||
        (kind == RemoteFileKind.SYMLINK && linkResolution == RemoteLinkResolution.VERIFIED && targetKind == RemoteFileKind.FILE)
}

data class RemoteDirectorySnapshot(
    val sessionId: String,
    val directory: String,
    val entries: List<RemotePathEntry>,
    val sourceCapabilities: Set<RemoteDirectorySource>,
    val loadedAtMonotonicMillis: Long,
) {
    val breadcrumbs: List<RemoteBreadcrumb>
        get() {
            if (directory == "/") return listOf(RemoteBreadcrumb("/", "/"))
            val parts = directory.trim('/').split('/').filter(String::isNotEmpty)
            var path = ""
            return listOf(RemoteBreadcrumb("/", "/")) + parts.map { part ->
                path += "/$part"
                RemoteBreadcrumb(part, path)
            }
        }
}

data class FileTransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long?,
) {
    init {
        require(transferredBytes >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
    }
}

data class RemoteFileTransferReceipt(
    val sessionId: String,
    val transferredBytes: Long,
) {
    init { require(transferredBytes >= 0L) }
}

enum class RemoteFileConflictPolicy { CANCEL, OVERWRITE, AUTO_RENAME }

data class RemoteUploadPlan(
    val sessionId: String,
    val directory: String,
    val requestedName: String,
    val stagedPath: String,
    val finalPath: String,
    val conflictExists: Boolean,
)

data class RemoteUploadCommitReceipt(
    val sessionId: String,
    val finalPath: String,
    val replacedExisting: Boolean,
)

sealed interface ApplicationMutationResult {
    val sessionId: String

    data class Verified(
        override val sessionId: String,
        val application: RemoteApplication,
    ) : ApplicationMutationResult

    data class RequestAccepted(override val sessionId: String) : ApplicationMutationResult

    data class OutcomeUnknown(
        override val sessionId: String,
        val reason: AdbError,
    ) : ApplicationMutationResult
}

enum class LogcatLevel(val argument: String) {
    VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E"), FATAL("F")
}

enum class LogcatBuffer(val argument: String) {
    MAIN("main"), SYSTEM("system"), CRASH("crash"), RADIO("radio"), EVENTS("events")
}

data class LogcatConfig(
    val minimumLevel: LogcatLevel = LogcatLevel.INFO,
    val buffers: Set<LogcatBuffer> = setOf(LogcatBuffer.MAIN, LogcatBuffer.SYSTEM, LogcatBuffer.CRASH),
) {
    init { require(buffers.isNotEmpty()) }
}

data class LogcatLine(val text: String, val fromStandardError: Boolean = false)
