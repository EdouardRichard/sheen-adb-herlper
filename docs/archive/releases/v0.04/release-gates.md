# v0.04 交付与发布门禁

> 评估日期：2026-07-23  
> 原则：功能实现、自动化、真机、可用性、网络观察和发布合规六类结论互不替代。

## 汇总结论

| 门禁 | 状态 | 依据与限制 |
|---|---|---|
| 功能实现 | CONDITIONAL | T001–T073A 的代码/文档任务已完成，五个用户故事的项目自有接口、状态机、Compose 入口和 TDD 契约已实现；但 Android 11–16 设备验收、QR 系统 scanner 互操作和 apk-parser API 30 runtime 未执行，因此不能写成端到端功能通过。 |
| 自动化与构建 | PASS_WITH_WARNINGS | [verification.md](verification.md) 记录 383/383 单测、lint 0 errors、Debug assemble、Manifest、断链与敏感值扫描通过；10 warnings / 1 hint 保留，不能解释为风险已消失。 |
| 真机验证 | NOT_RUN | 当前 `adb` 连接设备数为 0、无 emulator；T075/T076/T079 的成功率、P95、OEM、200 应用和诊断矩阵均为 0 样本/N/A。 |
| 首次用户可用性 | NOT_RUN | [usability.md](usability.md) 记录参与者 0，SC012 未判定；自动化 UI 测试不替代至少 10 名首次用户。 |
| 网络观察 | BLOCKED | [network-observation.md](network-observation.md) 记录缺少 Android discovery 流量和隔离网络；没有用空捕获冒充“无主动探测”，SC016 未判定。 |
| 发布合规 | BLOCKED | `spake2-java 1.0.5` 为 GPL-3.0-or-later 且 ADR 0004 未关闭；ZXing/apk-parser 的分发 LICENSE/NOTICE 与应用内展示尚未补齐，当前页面还误标 spake2-java。 |

## 功能范围判断

自动化已证明：

- QR/六位码、本机窗口、NSD-only discovery、可靠身份合并、单 Session 与终态清理的状态/资源边界。
- 当前用户第三方应用名称、图标、包名 OR 搜索、容量/损坏输入降级和 Session 清理。
- 结构化 threadtime、UNPARSED/STDERR、UID/PID 可靠关联、进程和 Logcat 六类 AND 筛选、有界窗口与当前可见导出。
- 没有新增相机、位置、Nearby、存储、自启动、电池豁免、无障碍或进程控制能力。

尚未由外部证据证明：

- Android 11–16 QR/Kadb 互操作、API/extension 差异、通知/锁屏/OEM 样式、真实 mDNS 网络行为。
- 200 应用 10 秒目标、名称/包名筛选 1 秒目标、诊断 1 秒更新及设备端 10k/4MiB 压力。
- 40 次配对成功率/P95、20 轮本机/LAN 指标、首次用户 90% 指标和 20 轮无主动探测观察。

## 发布阻断项

1. 按 [ADR 0004](../../../adr/0004-spake2-license-compliance.md) 替换/重新授权 `spake2-java 1.0.5`，并以实际运行时图证明条件关闭。
2. 修正应用内第三方许可证清单，准确列出 spake2-java、ZXing core 3.5.4、apk-parser 2.6.10，并随分发提供各许可证要求的文本/NOTICE。
3. 完成 T075–T079 所列真机、可用性与网络观察；任何未达标项按失败分类重新修复和复验。
4. 复核 lint 中 Kadb/Bouncy Castle trust-manager 警告与 Android 11–16 TLS/配对行为；不得仅 suppression 或文档化后视为解决。

## 最终状态

v0.04 当前可以作为“已实现并通过自动化的开发分支”继续真机验收；不得创建对外发布完成结论、release tag 或分发制品。发布状态保持 **BLOCKED**。
