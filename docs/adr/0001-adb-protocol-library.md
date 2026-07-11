# ADR 0001：ADB 协议库与主机身份存储

- 状态：Phase 0 PoC 采用，待真机验收
- 日期：2026-07-11
- 负责人：Sheen ADB 助手项目

## 背景

Phase 0 必须证明 Android 11+ 主控应用可以直接连接用户输入的无线 ADB 调试端口、使用 6 位配对码完成首次配对、建立 ADB TLS 会话并执行单一 PoC 命令。协议、Socket、TLS、配对、密钥和原始命令必须留在 `:core:adb`；依赖许可证只允许 Apache-2.0、MIT、BSD-2-Clause 或 BSD-3-Clause。

Android 11+ 的配对不是普通 TLS 登录。AOSP 使用由 6 位共享秘密引导的 SPAKE2 配对交换；配对端口与后续 `_adb-tls-connect` 调试端口不同。协议依据为 [AOSP ADB Wi-Fi 架构](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md) 与 [Android 官方无线调试步骤](https://developer.android.com/tools/adb#wireless-android11-command-line)。

## 候选方案

| 方案 | 许可证 | Android 11+ 配对/TLS | 结论 |
|---|---|---:|---|
| `com.flyfishxu:kadb:2.1.1` | Apache-2.0 | 支持配对码、SPAKE2、TLS 1.3、直接 Shell 与旧式 ADB | 采用；有 `2.1.1` Git tag 与 Maven Central 元数据，可注入私钥存储 |
| `mobile-dev-inc/dadb` | Apache-2.0 | 支持传统 ADB/RSA，未提供 Android 11+ 配对码流程 | 不满足本 PoC 首次配对 |
| `cgutman/AdbLib` | BSD-2-Clause | 面向传统 ADB/RSA，无标准无线配对实现 | 不满足本 PoC 首次配对 |
| `MuntashirAkon/libadb-android` | 源文件采用 GPL-3.0-or-later OR Apache-2.0 双重声明 | 支持配对/TLS | 功能可行，但许可证表达、API/全局状态与集成面不如 Kadb 2.1.1 清晰，作为后备 |
| AOSP `adb` host 原生代码 | Apache-2.0 | 官方完整实现 | 需要 NDK/原生依赖与较大维护面，不符合最小 PoC；保留为长期可靠性后备 |
| LADB 原生库/源码 | 自定义许可证，限制非官方构建上架 Google Play | 支持本机配对 | 不在许可证白名单，禁止使用或复制 |

## 决策

采用 `com.flyfishxu:kadb:2.1.1`，仅在 `:core:adb` 的 `KadbProtocolClientFactory` 适配器内使用。对 `:app` 只暴露项目自有的 `AdbSessionManager`、`AdbConnectionState`、`AdbError`、端点与结果模型，不暴露 Kadb、Okio、Bouncy Castle、SPAKE2 或异常类型。

选择 `2.1.1` 而不是构建时最新的 `2.1.2`，原因是 `2.1.1` 有明确 Git tag（`95c4ac56cb1f4998003192e44323ed84843eaba0`），本次已对该 tag 的连接、配对、关闭、日志开关和 `KadbPrivateKeyStore` 接口进行源码审查。升级必须重新审查并修改本 ADR。

Kadb 的 RSA 私钥格式需要可读取的 PEM 字节，不能直接使用不可导出的 Android Keystore RSA 私钥。因此实现 `AndroidKeystorePrivateKeyStore`：

1. Android Keystore 生成不可导出的 AES-256/GCM 包装密钥；
2. ADB RSA 私钥仅以 AES-GCM 密文写入 `noBackupFilesDir/adb_identity/host-key.enc`；
3. 明文只在当前进程完成签名/TLS 时短暂存在于内存，不写日志、不导出、不备份；
4. 破损或无法解密时返回结构化失败，不静默轮换身份（`autoHealInvalidPrivateKey=false`），避免使已配对关系无提示失效。

配对码由 UI 转为 `CharArray` 后立即清空输入状态，核心操作在 `finally` 中覆盖数组。Kadb 的公开 API 要求临时构造 JVM `String`；该对象不持久化、不记录且在调用返回后不保留引用，但 JVM 字符串无法原地覆盖。这是 PoC 的已知内存生命周期限制，真机安全复核时必须再次评估。

## 依赖与许可证影响

Kadb Android 运行时依赖包括 Okio、Kotlin 标准库、Coroutines、AndroidX DocumentFile、Bouncy Castle、`spake2-java` 与 HiddenApiBypass。许可证均在本项目白名单内，详见 `docs/第三方依赖审查.md`。Kadb 会增加密码学与协议代码体积；Phase 0 优先验证协议可行性，不把未使用的文件管理等能力暴露到项目 API。Release 前必须测量 APK/R8 结果并考虑裁剪或替换。

Kadb 内部日志只在进程环境变量 `KADB_LOGGING=true` 时启用；本项目不设置该变量，也不接入日志或崩溃上报。项目生成的技术详情只包含错误类型、结构化代码和脱敏端点，不包含异常消息、Shell 输出、配对码或密钥。

## 影响与风险

- 优点：最小 Kotlin 集成即可覆盖 Android 11+ 配对、TLS、环回地址、旧式 ADB 与 Shell。
- 风险：当前没有 HyperOS 3 / Android 16 真机证据；不能据此宣称协议 PoC 已验收。
- 风险：Kadb 的连接 API 部分为阻塞式；项目在 IO dispatcher、有限超时和 `runInterruptible` 中调用，并在取消/失败的 `finally` 中关闭候选客户端。真机必须验证取消是否能在各 ROM/TLS 阶段及时释放 Socket。
- 风险：传递依赖体积较大，且 `spake2-java` 从限定到单一 group 的 JitPack 仓库解析；供应链固定版本但仍需后续依赖锁定/校验元数据。
- 风险：HiddenApiBypass 是 Kadb Android 传递依赖。它不申请权限、不用于绕过 ADB 授权，但发布前必须确认 Android 16/商店合规和 R8 行为。
- 风险：Android lint 会在 Kadb/Bouncy Castle 中报告空 `X509TrustManager`。ADB Wi-Fi 配对使用自签名证书并由 SPAKE2 共享秘密建立信任，不能套用公网 CA 校验；本项目不把该 trust manager 用于任何互联网/业务 TLS。仍需通过抓包、错误配对码和中间人负向真机测试确认实现与 AOSP 语义一致，不能仅以协议设计为由忽略告警。

## 验证门禁

- 自动化：端点解析、状态转换、错误映射、超时、取消和资源关闭单元测试。
- 构建：`:core:adb:testDebugUnitTest`、`:app:assembleDebug`、`lintDebug`。
- 真机：Android 11、HyperOS 3 / Android 16、至少一台非小米设备；分别验证远端、`127.0.0.1`、配对码、已配对重连、错误端口、取消、超时、掉线与旧式 `:5555`（设备可用时）。

## 回滚方式

删除 `KadbProtocolClientFactory` 与 Kadb Version Catalog 依赖，保留项目自有接口、会话状态机、UI 与测试，通过 `AdbProtocolClientFactory` 接入替代实现。身份密文格式有独立版本号；替代实现若不能复用 RSA 身份，应先增加迁移 ADR，不得静默删除或明文导出私钥。
