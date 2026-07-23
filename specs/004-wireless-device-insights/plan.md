# Implementation Plan: v0.04 无线发现与设备分析增强

**Branch**: `004-wireless-device-insights` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-wireless-device-insights/spec.md`

## Summary

本版本在既有单一 `AdbSessionManager` 与 Kadb 适配层上增加两类无线调试入口：主控端显示 Android 标准无线调试二维码供被控端系统扫描，以及六位配对码配对；同时通过 Android `NsdManager` 仅发现系统公布的 ADB TLS 配对/连接服务，不枚举子网或探测端口。本机模式默认配对码；最长 2 分钟的本机配对窗口、端口发现、截止时间和输入校验由 `:core:adb` 的项目自有状态机负责，`:app` 中唯一非导出 `shortService` 只承担 Android 生命周期、通知、RemoteInput 与解锁事件适配。拒绝通知或 OEM 不兼容时回退到应用内输入。

应用管理沿用当前用户第三方应用范围，先返回包列表，再在当前 Session 内以有界、可取消的 APK 元数据读取逐步补齐名称与图标；不可可靠获取时保留包名和明确占位。Logcat 与进程增强只做结构化解析、基础筛选和可靠身份关联，不增加后台采集、进程控制或高级诊断。

## Technical Context

- **Language/Version**: Kotlin 2.3.20，JVM toolchain 17
- **Primary Dependencies**: Android Gradle Plugin 9.1.1、Jetpack Compose BOM 2026.06.01、AndroidX Lifecycle 2.10.0、Kotlin Coroutines 1.10.2、Kadb 2.1.1、Okio 3.17.0；计划新增 `com.google.zxing:core:3.5.4`（Apache-2.0，仅 QR 编码）与 `net.dongliu:apk-parser:2.6.10`（BSD-2-Clause，仅隔离在 APK 元数据适配器）。静态 POM/许可证/维护门禁通过后允许以隔离 adapter 接线来执行构建、恶意 ZIP 与 Android API 30 验证；这些动态门禁通过前 US4 保持 BLOCKED。任一动态门禁失败会触发重新规划，不以整项 `UNSUPPORTED` 冒充应用名/图标需求完成。
- **Storage**: DataStore 仅保留既有非敏感设备档案；二维码内容、配对口令、发现结果、应用元数据/图标、进程和 Logcat 均仅在内存及当前 Session 生命周期内；用户明确导出仍使用 SAF
- **Testing**: TestNG/JVM 单元测试、Android lint、Gradle Debug assemble、merged Manifest 检查，以及 Android 11/12、13、14、15、16 真机/受控网络验收
- **Target Platform**: Android 11+（minSdk 30，targetSdk/compileSdk 36）
- **Project Type**: 多模块 Android 应用（Kotlin + Compose）
- **Performance Goals**: 配对 30 秒内得到结果；本机发现 5 秒内给出结果/受限状态；局域网 15 个服务下 10 秒内发现至少 95%；200 个应用中至少 95% 在 10 秒内显示名称/图标或明确降级；筛选在受控数据集上 1 秒内更新
- **Constraints**: 纯本地；一个活动 ADB Session；所有协议、配对、发现语义和原始命令位于 `:core:adb`；所有操作有取消、有限超时、结构化错误和确定性清理；局域网发现仅前台 10 秒窗口，本机配对唯一后台例外最长 2 分钟；不记录或持久化敏感值
- **Scale/Scope**: 5 个用户故事；约 42 项功能需求；最多 15 个发现服务、200 个应用、10,000 行或 4 MiB Logcat 原始缓冲、最新 100 条可见窗口；影响 `:app`、`:core:adb`、`:feature:devices`、`:feature:apps`、`:feature:processes`、`:feature:logcat`

## Constitution Check

### 设计前检查

| Gate | 结论 | 设计证据 |
|---|---|---|
| 纯本地与最小权限 | PASS | 无账号/后端/遥测；只新增已获批准的 `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_NETWORK_STATE`；不声明定位、相机、Nearby、存储、开机或电池豁免权限。权限边界登记于 [`docs/权限矩阵.md`](../../docs/权限矩阵.md)。 |
| ADB 边界与单 Session | PASS | `NsdManager` 平台适配、ADB 服务解析、QR/配对、本机配对窗口状态机、应用元数据读取与 Logcat 解析均由 `:core:adb` 的项目自有接口封装；`:app` 只适配 Service/通知/系统解锁事件，UI 不持有 Kadb/Socket/原始命令。配对/连接新设备前继续执行显式 Session 替换确认。 |
| 敏感数据默认保护 | PASS | QR 密码、六位码、真实端点与服务材料仅驻留内存；成功、失败、取消、超时、页面离开、Session 变化均清理；通知 `VISIBILITY_PRIVATE`、锁屏无输入动作、接收端再次校验解锁；诊断只记录脱敏类别。 |
| 依赖与分发受控 | PASS（实现受门禁约束） | 计划依赖 `ZXing core 3.5.4` 为 Apache-2.0 且无运行时传递依赖，只用于 QR 编码；`apk-parser 2.6.10` 为 BSD-2-Clause，限定在可替换适配器并设置输入上限。实施任务必须先复核 POM、许可证、传递依赖与 Android 兼容性。既有 `spake2-java 1.0.5` GPL 冲突仍阻断对外发布。 |
| 可验证、范围受控 | PASS | 每条异步路径均带 generation/sessionId 防旧结果；正常、空、受限、取消、超时、离线和解析失败均有明确状态；本 Plan 只生成设计与治理文档，不修改业务代码或 Manifest。 |

### 设计后复核

| Gate | 结论 | Phase 1 证据 |
|---|---|---|
| 纯本地与最小权限 | PASS | [ADR 0007](../../docs/adr/0007-wireless-discovery-and-local-pairing-lifecycle.md) 固定 NSD-only、10 秒前台扫描、2 分钟本机短服务和拒绝降级；契约禁止主动端口探测。 |
| ADB 边界与单 Session | PASS | [wireless-pairing contract](contracts/wireless-pairing.md) 明确平台适配与配对协调均在 `:core:adb`；所有连接动作仍通过唯一 manager。 |
| 敏感数据默认保护 | PASS | [data-model.md](data-model.md) 将敏感字段标为非持久、非日志并定义清理终态；通知状态不携带可公开端点或配对码。 |
| 依赖与分发受控 | PASS（发布仍 BLOCKED） | [research.md](research.md) 记录依赖许可证、维护风险、替代与移除路径；现存 Kadb 许可证门禁保持公开。 |
| 可验证、范围受控 | PASS | 三份契约与 [quickstart.md](quickstart.md) 覆盖组件、状态机、边界、自动化和真机矩阵；未增加高级诊断或后台采集。 |

## Project Structure

### Documentation (this feature)

```text
specs/004-wireless-device-insights/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── wireless-pairing.md
│   ├── application-insights.md
│   └── diagnostics-filtering.md
└── tasks.md                 # 由后续 /speckit-tasks 生成
```

### Source Code (repository root)

```text
app/
├── src/main/AndroidManifest.xml
├── src/main/kotlin/com/sheen/adbhelper/
│   ├── SheenApplication.kt
│   ├── SheenApp.kt
│   └── localpairing/        # shortService、通知/RemoteInput 与解锁事件平台适配
└── src/test/kotlin/com/sheen/adbhelper/

core/adb/
├── src/main/kotlin/com/sheen/adb/core/
│   ├── AdbModels.kt
│   ├── AdbSessionManager.kt
│   └── internal/
│       ├── DefaultAdbSessionManager.kt
│       ├── discovery/       # NsdManager、Network、MulticastLock 平台适配
│       ├── pairing/         # QR 材料、配对尝试、本机窗口状态机与服务关联
│       ├── applications/    # APK 元数据读取/解析适配与容量策略
│       └── diagnostics/     # structured logcat、进程/应用可靠关联
└── src/test/kotlin/com/sheen/adb/core/

feature/devices/
├── src/main/kotlin/com/sheen/adb/feature/devices/
│   ├── DevicesViewModel.kt
│   └── DevicesScreen.kt
└── src/test/kotlin/com/sheen/adb/feature/devices/

feature/apps/
├── src/main/kotlin/com/sheen/adb/feature/apps/
│   ├── AppsModels.kt
│   ├── AppsViewModel.kt
│   └── AppsScreen.kt
└── src/test/kotlin/com/sheen/adb/feature/apps/

feature/processes/
├── src/main/kotlin/com/sheen/adb/feature/processes/
│   ├── ProcessesViewModel.kt
│   └── ProcessesScreen.kt
└── src/test/kotlin/com/sheen/adb/feature/processes/

feature/logcat/
├── src/main/kotlin/com/sheen/adb/feature/logcat/
│   ├── LogcatBuffer.kt
│   ├── LogcatViewModel.kt
│   └── LogcatScreen.kt
└── src/test/kotlin/com/sheen/adb/feature/logcat/
```

**Structure Decision**: 不新增 feature 或 core 模块。无线入口归入已有 `:feature:devices`；Android 前台服务、通知构建、`KeyguardManager` 和动态解锁接收器只存在于负责平台装配的 `:app`。所有可脱离 Android UI 平台测试的本机配对策略、窗口状态、截止时间、token 校验、ADB/NSD、APK 元数据和诊断解析统一位于 `:core:adb`，以保持依赖方向 `:app`/`:feature:* → :core:*`，并满足 `:app` 仅组装、导航和平台适配的边界。UI 只消费不可变项目自有状态，不直接访问 `NsdManager`、Kadb、Socket 或 Shell。

## Design Decisions

### 1. 无线发现与配对

- QR 内容固定为 `WIFI:T:ADB;S:<service-instance>;P:<password>;;`。使用 `SecureRandom` 按 Android Studio 参考实现生成 `studio-` 加 10 个 `A-Z/a-z/0-9/-` 字符的 service instance，以及 12 个 QR delimiter-safe ASCII 字符的 password；有效期默认 2 分钟且每次尝试不可复用。`:feature:devices` 仅用 ZXing core 的 QR writer 编码并显示（不引入 scanner/camera integration），随后等待被控端无线调试设置扫描后公布匹配的 `_adb-tls-pairing._tcp` 服务；解析后调用现有 Kadb 配对客户端。
- 只发现 `_adb-tls-pairing._tcp` 与 `_adb-tls-connect._tcp`；不发现旧式不安全 `_adb._tcp`，不枚举地址空间或探测端口。
- 同一 `(Network, serviceType, serviceName)` 的重复回调去重。跨 pairing/connect 记录只在成功建立 TLS/ADB Session 后，由 `AdbSessionManager` 已验证的主机公钥指纹生成进程内 opaque `verifiedDeviceId`，并同时回填到同一 attempt 中用户选择的 pairing observation 与最终连接成功的 connect observation；只有两个 observation 的该值相同才合并。配对成功但尚未连接时，或 Kadb 未暴露可靠对端身份时，默认分开展示并要求用户选择，不按名称、地址或端口猜测。
- API 30–32 使用 legacy discover/resolve 加临时 `MulticastLock`；API 33+ 绑定当前 `Network`；API 34+ 使用多地址回调。每次发现有 generation token，停止后丢弃迟到回调并释放监听器/锁。
- manager 接线分为两个独立实施边界：先在 `AdbModels.kt` 与 `AdbSessionManager.kt` 定义 `AdbOperationStage.DISCOVERY`、不含平台/端点原文的结构化发现错误、项目自有 discovery source/coordinator 契约与状态流；再仅在 `DefaultAdbSessionManager.kt` 实现唯一活动发现、generation/session guard、并发拒绝、超时/取消终态和 `close()` 清理。这样 Android NSD 类型不会泄漏到公开 API，并保持每个实施任务最多修改两个文件。
- Android NSD 装配也分为两个实施边界：`AdbManagerProvider.kt` 与 `AndroidNsdDiscoveryAdapter.kt` 负责 application Context 单例装配和 source 生命周期；随后由独立任务仅修改 `NsdDiscoveryPolicy.kt` 与 `AndroidNsdDiscoveryAdapter.kt`，为 discovery 已启动后的异步 resolve 权限拒绝增加内部 failure 分支、锁外单次终态通知与确定性资源释放，避免 `SecurityException` 逃出平台 callback 或退化为 timeout。该拆分保持每任务最多两个文件。
- QR manager 接线拆为公共契约与协议实现两个边界：先仅在 `AdbModels.kt` 与 `AdbSessionManager.kt` 增加使用 `PairingSecret`/`PairingMethod` 的项目自有 QR 配对入口、已有 Session 冲突错误以及默认拒绝并清理 secret 的兼容实现；再仅修改 `KadbProtocolClientFactory.kt` 与 `DefaultAdbSessionManager.kt`，让 QR 与六位码复用一个 Kadb client path。manager 在已有 Session 时必须保留该 Session 并返回结构化冲突，配对成功只回到未连接状态，不创建或替换 Session。该拆分保持每个实施任务最多修改两个文件，并使现有 fake manager 可在公共契约扩展后继续编译。
- ViewModel 接入 QR 前增加 manager-owned orchestration 边界，避免 `:feature:devices` 直接实例化 `core:adb` internal coordinator 或自行处理 service endpoint：先用 manager 集成失败测试固定 material 失效、observation 精确匹配、取消/超时、已有 Session 冲突和不自动连接；再在 `PairingModels.kt` 与 `QrPairingCoordinator.kt` 暴露只读且可由核心失效的项目自有 QR material 接口；随后由 `AdbSessionManager.kt` 增加默认 `UNSUPPORTED` 的创建、提交 observation 与取消契约，最后只在 `DefaultAdbSessionManager.kt` 持有 coordinator、把项目自有 observation 转为 endpoint 并复用既有 `pairWithSecret`。这样 feature 只看 payload/matrix 和脱敏状态，password、service 精确匹配、endpoint 选择与 Kadb 调用仍全部归 `:core:adb` 所有，每个子任务最多修改两个文件。

### 2. 本机通知与短时前台服务

- 本机 coordinator 继续由唯一 manager 持有，但公共接线分为独立契约与实现两步：先仅在 `AdbSessionManager.kt` 定义项目自有 `LocalPairingController`、状态流与默认 `UNSUPPORTED` 兼容入口，使 App/Feature 不需要强转 `DefaultAdbSessionManager` 或导入 internal 类型；再由 `LocalPairingCoordinator.kt` 与 `DefaultAdbSessionManager.kt` 实现唯一窗口、发现/提交复用和终态清理。该拆分保持每任务最多两个文件，并让既有 fake manager 在契约扩展后继续编译。
- Feature 本机模式也拆为状态机与协调两步：先仅修改 `DevicesPairingModels.kt` 和 `DevicesPairingReducer.kt`，固定默认配对码、发现/通知/权限/OEM/离页 effect；再单独修改 `DevicesViewModel.kt`，把这些 effect 接到公共 `LocalPairingController`。这样 reducer RED/GREEN 与 coroutine manager flow 各自可验证，且不超过每任务两个文件。
- App 装配在 UI 接线前增加独立 bridge 同步步骤：`LocalPairingAppBridge` 只根据既有公共 controller flow 幂等启停唯一 short service，不再次创建 window；随后 `SheenApp.kt` 与 `DevicesScreen.kt` 才接权限、系统设置和本机状态。这样保持单一 coordinator/window，并继续满足每任务最多两个文件。
- UI 接线前以独立 reducer 回归固定本机终态清理：成功、失败、取消、过期或不支持时必须原子清除 `localWindowActive`、停止发现并关闭通知状态，避免随后生命周期离页把已完成结果覆盖成取消。
- `:core:adb` 的 `LocalPairingCoordinator` 持有唯一 attempt/window、5 秒发现状态、2 分钟单调时钟硬截止、service lost/Session change 终态和共用提交入口。Activity 可见时由 `:app` 启动一个非导出 `shortService`，5 秒内调用 `startForeground`；Service 只观察核心状态并翻译为 Android 通知，在成功、取消、配对服务消失、超时、Session 改变或 `onTimeout` 时停止并撤销通知。
- Android 13+ 按需请求通知权限；拒绝、通知关闭、RemoteInput/OEM 不兼容均回退应用内同一输入状态机，不阻断配对。
- 锁屏发布无 action 的隐私化通知。Service 仅在窗口活动期间动态注册非导出的 `ACTION_USER_PRESENT` 接收器，并在收到事件或前台恢复时重新查询 `KeyguardManager.isDeviceLocked`；确认解锁后才把同一通知替换为带 `RemoteInput` 的版本，停止时确定性注销接收器。提交使用显式、一次性、mutable PendingIntent；API 31+ 在 `Notification.Action.Builder.setAuthenticationRequired(true)` 上要求认证，并在 Service 接收端再次检查 `KeyguardManager`、session token、六位 ASCII 数字与截止时间。
- 前台服务不承载普通局域网扫描、文件、Logcat 或其他任务，不自启、不监听开机、不请求忽略电池优化。

### 3. 应用名称与图标

- 第一阶段继续由 `listApplications` 快速返回包名和管理状态；第二阶段 `observeApplicationMetadata(expectedSessionId)` 逐步补齐展示名与图标，UI 立即显示包名占位并标示 loading/unavailable/too-large/parse-failed 等降级。
- `:core:adb` 先以 `pm list packages -3 -U --user <id>` 获取包名与 UID，再以受控 `pm path --user <id> <package>` 输出获得基础 APK 路径。路径只在核心层按既有命令转义策略使用，禁止 UI 拼接 Shell。APK 字节通过既有 `ProtocolSyncStream.receive` 读取到有界内存 sink；读取前后校验 32 MiB 上限，支持取消、超时、Session guard，并在完成或失败后关闭 Sync stream、释放字节，不调用面向 SAF 的公开下载流程且不落盘。单包顺序解析、总耗时 10 秒、单图标编码上限 1 MiB、全局图标 LRU 上限 16 MiB。
- `apk-parser` 隐藏在项目自有接口之后，只接收受限内存字节；解析完成即释放原始 APK 字节。T001 的静态供应链门禁通过后才可隔离接线；恶意 ZIP 测试和 API 30 Android 运行时 smoke test 是 US4 完成门禁，在两者实际通过前 US4 保持 BLOCKED。任一失败即回到 Plan 选择替代实现，不能把包名占位或整项 `UNSUPPORTED` 计为 FR023–FR025/SC008 完成。
- 搜索使用本地规范化后的 `packageName OR displayName`；元数据到达时重新计算，名称缺失时包名搜索始终可用。同名应用始终显示包名。

### 4. Logcat 与进程分析

- `:core:adb` 把 threadtime 行解析为时间、PID、TID、级别、标签、消息和原始文本；无法解析的行保留为 structured `UNPARSED`，不丢失但不伪造身份。
- 进程快照增加应用关联结果 `VERIFIED / MULTIPLE / UNKNOWN`。应用清单解析 Android UID 并归一化为 `(userId, appId)`；进程 UID 同样归一化后，只在当前 Session、相同快照代次、同 userId/appId 且候选唯一时建立关联。共享 UID、多用户冲突、PID 复用、进程退出、多进程或字段缺失明确标记未知/多候选，不以 PID 或进程名前缀猜测。
- Logcat 的 level/tag/keyword/PID/process/application 与进程的 PID/name/application 均按交集过滤。继续使用 10,000 行或 4 MiB 原始内存边界与最新 100 条可见窗口；不做崩溃/ANR 识别、资源趋势或后台采集。

## Verification Strategy

1. JVM 单元测试覆盖 QR 编码、六位码校验、状态机终态清理、NSD 回调去重/过期丢弃、IPv4/IPv6 解析、服务关联拒绝猜测、APK 容量/解析降级、名称/包名搜索、Logcat parser 与组合筛选、PID 复用和多候选关联。
   `DevicesViewModel` 的 pairing/discovery coroutine 测试使用与现有 Coroutines 1.10.2 同版本的 `kotlinx-coroutines-test`（test scope only）替换 JVM `Dispatchers.Main`；该 artifact 不进入运行时、Manifest 或发布依赖，版本仍由 `libs.versions.toml` 集中管理。
2. app/feature 策略测试覆盖通知权限拒绝、锁屏 action-free、解锁后 RemoteInput、2 分钟截止、页面前后台、Session 切换和应用内回退。
3. 运行 `gradlew testDebugUnitTest lintDebug assembleDebug`（本仓库设置 `JAVA_HOME=C:\Users\Richard\.gradle\sheen-jdk21`），检查 `merged_manifest` 只含登记权限及一个非导出 `shortService`。
4. 成功率指标采用固定证据协议：SC001 做 40 次配对（QR/配对码各 20 次，至少覆盖 Android 11、13、16）；SC002 做 20 次本机启动；SC003 做 20 次发现到通知；SC005 在受控网络每轮公布 15 个服务并执行 20 轮。计时统一从用户点击开始到首个明确结果、通知或服务集合稳定，记录成功次数、P95 与失败分类，真实端点和 service name 脱敏。
5. SC012 使用固定脚本对至少 10 名首次用户执行一次 QR 与一次配对码任务，以无需主持人纠正且 2 分钟内完成为成功；只记录聚合计数和脱敏观察。SC016 在隔离网络通过数据包捕获或 sentinel 主机监听 20 轮 LAN discovery，证明除 DNS-SD/mDNS 查询、已公布服务解析和用户选择后的连接外，没有子网枚举或未公布端口探测。
6. 真机矩阵按故事拆分记录：Android 11–16 的 QR/配对码；同机模式的通知拒绝/关闭、至少两种 OEM 样式及锁屏/解锁；15 个 NSD 服务、IPv4/IPv6 与网络切换；200 应用及中英文标签/缺失图标；PID 复用/共享 UID/多进程；10k/4MiB Logcat 边界。
7. 分别报告自动化通过、真机通过、可用性结果、网络观察结果和发布合规；任一证据缺失写 `NOT_RUN`/`BLOCKED`，`spake2-java` 门禁解决前不得报告可发布。

## Complexity Tracking

无宪法例外。新增前台服务属于已批准且有 ADR 的窄范围平台组件；没有新增模块、后台常驻架构或第二个 ADB Session。
