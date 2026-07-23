# v0.04 验证记录

> 日期：2026-07-23  
> 范围：`specs/004-wireless-device-insights`  
> 证据边界：只记录自动化、构建和脱敏扫描结果；本文件不包含真实端点、service name、配对材料、包名上下文、Shell/Logcat 或设备标识。

## 1. 自动化与构建（T074）

环境：Windows PowerShell，`JAVA_HOME=C:\Users\Richard\.gradle\sheen-jdk21`，所有 Gradle 命令使用 `--no-parallel --no-daemon`。

### 1.1 首轮失败与修复

首轮命令：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-parallel --no-daemon
```

结果：`FAILED`（38 秒）。`:core:adb:lintDebug` 报 7 errors / 4 warnings：2 项 `ACCESS_NETWORK_STATE` 库模块权限推导错误，5 项 Android 13 extension 3 / Android 14 extension 7 API 静态推导错误。没有跳过该失败；按 T073A 先更新 Plan/Tasks，再仅在已有 Manifest 权限、API 33/34 guard 和 `SecurityException` 结构化降级覆盖的平台调用边界添加窄范围 lint annotation。未新增权限或 lint baseline。

修复验证：

```powershell
.\gradlew.bat :core:adb:testDebugUnitTest `
  --tests com.sheen.adb.core.internal.NsdDiscoveryAdapterTest `
  --tests com.sheen.adb.core.internal.WirelessDiscoveryFactoryTest `
  :core:adb:lintDebug --no-parallel --no-daemon
```

结果：`PASS`（30 秒）。

### 1.2 全量重跑

同一全量命令从头重跑结果：`BUILD SUCCESSFUL`（77 秒）。

| 检查 | 结果 | 实际证据 |
|---|---|---|
| JVM 单元测试 | PASS | 54 个 TestNG XML suite，383 tests，0 failures，0 errors，0 skipped |
| Android lint | PASS_WITH_WARNINGS | 0 errors；App 聚合报告为 10 warnings / 1 hint |
| Debug assemble | PASS | `app-debug.apk`，42,742,434 bytes |
| `git diff --check` | PASS | 无空白错误 |

Lint 未隐藏项：7 项依赖新版本提示；3 项来自 Bouncy Castle/Kadb 的 `TrustAllX509TrustManager` 警告；1 项 Compose primitive state autoboxing hint。版本升级不属于 v0.04 范围；TLS 警告属于既有 Kadb 协议/证书适配风险，不能据 lint 通过宣称已解决。`spake2-java 1.0.5` 的许可证问题仍独立阻断发布。

## 2. Merged Manifest

Debug merged Manifest 的权限恰为：

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.CHANGE_WIFI_MULTICAST_STATE`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.FOREGROUND_SERVICE`

本功能 Service 恰为一个 `com.sheen.adbhelper.localpairing.LocalPairingForegroundService`，`exported=false`、`foregroundServiceType=shortService`。禁止的相机、位置、Nearby、存储、开机、自启动、电池豁免和无障碍权限命中数为 0。

## 3. 断链与敏感值扫描

- Markdown：扫描 79 个 tracked Markdown 文件、143 个本地相对链接，broken=0。
- 敏感制品：tracked key/certificate/keystore 文件 0；tracked `adb_identity`、`device-logs`、`real-device-data`、`pairing-material`、`exported-logcat` 路径 0。
- 私钥 header 扫描有 1 个命中，位于 `DiagnosticRedactorTest` 的合成输入，用于证明脱敏器删除该模式；不是密钥材料。
- 私网 IPv4 扫描有 1 个命中，位于 v0.01 历史需求文档的通用格式示例；不是设备证据或本次测试端点。v0.04 代码/测试继续使用 `com.example`、文档保留地址、`device.local` 和 `fixture` 等合成标记。

结论：仓库扫描未发现真实配对材料、真实设备端点、真实包名上下文、签名材料或设备 Logcat 证据。

## 4. 真机与外部证据状态

自动化和 Debug 构建通过不等于以下项目通过：

| 后续任务 | 状态 | 原因 |
|---|---|---|
| T075：40 次 QR/六位码配对 | NOT_RUN | 需要 Android 11、13、16 设备和统一计时记录 |
| T076：本机通知与 15 服务 LAN 发现 | NOT_RUN | 需要 Android 11–16、至少两种 OEM 样式和受控网络 |
| T077：至少 10 名首次用户可用性 | NOT_RUN | 需要参与者与主持记录 |
| T078：20 轮隔离网络流量观察 | NOT_RUN | 需要 packet capture 或 sentinel 环境 |
| T079：200 应用与诊断真机矩阵 | NOT_RUN | 需要受控应用/进程/Logcat 数据集 |

### 4.1 T075 配对固定协议

执行前环境检查：Android SDK `adb` 可用，但已授权连接设备数为 0，且没有 Android emulator 进程。当前环境不能覆盖 Android 11、13、16，也不能让被控端系统 scanner 扫描控制端 QR。

| 协议 | 计划次数 | 实际次数 | 成功次数 | 成功率 | P95 | 30 秒超时 | 状态 |
|---|---:|---:|---:|---|---|---:|---|
| QR 配对 | 20 | 0 | 0 | N/A | N/A | 0 | NOT_RUN |
| 六位配对码 | 20 | 0 | 0 | N/A | N/A | 0 | NOT_RUN |

脱敏失败分类只有 `ENVIRONMENT_NO_DEVICE`；它不是功能失败样本，不进入成功率分母。SC001、SC004、SC010、SC011、SC013 维持 `NOT_RUN`，不得由 QR/Kadb 单元测试替代。

### 4.2 T076 本机通知与 LAN 固定协议

同一环境检查确认设备数为 0，因此无法打开系统无线调试、锁屏/解锁、切换 OEM 通知样式，或准备 15 个真实 DNS-SD 服务及 IPv4/IPv6/网络切换条件。

| 协议 | 计划次数 | 实际次数 | 成功次数 | 成功率 | P95 | 状态 |
|---|---:|---:|---:|---|---|---|
| 本机模式启动/5 秒结果 | 20 | 0 | 0 | N/A | N/A | NOT_RUN |
| 配对通知/3 秒到达 | 20 | 0 | 0 | N/A | N/A | NOT_RUN |
| 每轮 15 服务 LAN 发现 | 20 | 0 | 0 | N/A | N/A | NOT_RUN |

Android 11–16、至少两种 OEM 样式、锁屏到解锁、IPv4/IPv6 和网络切换均为 `NOT_RUN`。SC002、SC003、SC005、SC015、SC018 不作通过判断；自动化只证明 deadline、token、锁屏决策、generation 和资源清理策略。

## 5. T074 结论

- 自动化与 Debug 构建：**PASS_WITH_WARNINGS**。
- merged Manifest 与仓库脱敏扫描：**PASS**。
- 真机、可用性、网络观察：**NOT_RUN**，不得由自动化结果替代。
- 发布合规：**BLOCKED**；ADR 0004 的 `spake2-java` 条件未关闭，且 v0.04 新依赖的应用内许可证/分发 NOTICE 仍待补齐。
