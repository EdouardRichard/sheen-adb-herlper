# ADB Session 与协议现状

> 类型：当前主干事实
> 依据：HEAD 源码/测试、ADR 0001/0002/0004/0007；不是未来需求或历史日志。

## 归属与边界

- `:core:adb` 拥有端点解析、Kadb 适配、Socket/TLS、Android 11+ 六位码与 QR 配对流程、ADB 主机身份、ADB TLS NSD 发现、命令/流和结构化错误。
- 对外入口是项目自有 `AdbSessionManager`；Kadb、Okio、SPAKE2、协议异常和原始 ADB 命令不向 Feature/UI 泄漏。
- Android `NsdManager`、`Network` 和服务解析由核心内部平台适配器封装；Feature 只消费项目自有 discovery、pairing 和 local-window 状态。
- 应用进程通过 `AppContainer` 共享一个管理器；`DefaultAdbSessionManager` 只保留一个活跃 Session。Feature 在连接其他目标前要求显式替换确认，配对成功本身不创建或替换 Session。
- IPv4、主机名和方括号 IPv6 端点由核心解析；诊断目标使用脱敏表示。

## 状态与生命周期

- 连接在协议探针成功后才进入已连接状态；只有认证失败允许显示配对回退。
- 连接、配对、无线发现、Shell、概览、进程、应用管理、Logcat、断开和身份清除返回项目自有结果/错误。
- 操作绑定 Session ID；切换或断开后，旧结果和流不得污染新 Session。
- 候选连接、命令子流和持续流按所有权关闭。Shell 的成功、非零退出、超时、取消及已知命令流错误只关闭命令子流，不破坏仍有效的连接。
- Logcat 持续流没有连接级空闲读超时；停止、取消、断开和 Session 切换分别关闭相应流/连接。

## 无线发现与配对

- 普通局域网发现只订阅 `_adb-tls-pairing._tcp` 与 `_adb-tls-connect._tcp`，默认前台运行 10 秒；不枚举子网、不探测未公布主机或端口。
- 同一 network/type/name 的重复观察去重；PAIRING 与 CONNECT 只有在 TLS/Session 提供相同可靠身份时才可合并，名称、地址和端口不构成设备身份。
- QR 负载、随机 service instance、password 和 matrix 只在当前 attempt 内存中存在；六位码与 QR 最终复用同一 Kadb 客户端路径。成功、失败、取消、过期和离页均使材料失效。
- 本机模式默认六位码。唯一 local pairing window 最长 2 分钟，5 秒发现阶段和 deadline 由核心状态机约束；`:app` 的非导出 `shortService` 只适配通知、RemoteInput、锁屏与系统超时。
- API 30–32 的临时组播锁、API 33+ 当前网络绑定、API 34+ 多地址解析，以及 generation 迟到回调拒绝均封装在核心发现适配器中。

## 身份与诊断

- ADB RSA 私钥以 Android Keystore AES-GCM 包装后保存在不可备份的应用私有目录；配对码在核心 `finally` 中覆盖。
- QR password、service instance、NSD observation 和通知输入 token 不进入 DataStore、SavedState、剪贴板或普通诊断。
- 诊断事件为最近 100 条的进程内有界列表，只含序号、阶段、结果、技术代码、脱敏目标和异常类型，可手动清空。
- 诊断不得包含真实端点、包名/用户 ID、配对码、密钥、原始命令、输出或设备 Logcat。

## 有效决策与限制

- 协议适配与身份存储见 [ADR 0001](../adr/0001-adb-protocol-library.md)，诊断事件见 [ADR 0002](../adr/0002-in-memory-diagnostic-events.md)。
- NSD-only 发现、可靠身份合并与最长 2 分钟本机配对生命周期见 [ADR 0007](../adr/0007-wireless-discovery-and-local-pairing-lifecycle.md)。Android 11–16 QR 互操作、NSD 兼容和 OEM 通知样式仍需按 v0.04 真机矩阵验证。
- Kadb 2.1.1 的 `spake2-java 1.0.5` 许可证冲突仍阻断对外发布合规，见 [ADR 0004](../adr/0004-spake2-license-compliance.md)。
