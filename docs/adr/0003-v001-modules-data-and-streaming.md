# ADR 0003：v0.01 模块、元数据与前台流式能力

- 状态：采用，待真机验收
- 日期：2026-07-14
- 负责人：Sheen ADB 助手项目

## 背景

Phase 0 只有 `:app` 与 `:core:adb`。v0.01 需要设备档案、概览、Shell、只读进程、前台 Logcat、设置与清除，同时保持 ADB 协议/命令、持久化和 UI 的单向边界。

## 候选方案

1. 所有页面留在 `:app`：文件少，但破坏 app 仅装配/导航的职责，容易让命令和持久化进入 UI。
2. 按规范拆分 core 与 feature：构建模块增加，但依赖方向可由 Gradle 强制；采用。
3. 引入 DI/导航/数据库框架：可减少少量装配代码，但增加依赖、体积和许可证面；v0.01 不采用。

## 决策

- 使用 `:core:adb`、`:core:data`、`:core:ui`、`:feature:devices`、`:feature:overview`、`:feature:shell`、`:feature:processes`、`:feature:logcat`；设置作为同等独立业务页面放入 `:feature:settings`，`:app` 只保留 Application 容器、单导航宿主与装配。
- feature 只依赖 core，不互相依赖。所有业务 Shell 命令、ROM 解析、Logcat 命令和流关闭在 `:core:adb`。
- 设备档案元数据使用 DataStore Preferences；使用版本化 Base64 字段编码，损坏记录明确忽略，不保存 Shell/进程/Logcat 内容。
- Kadb 的主机身份仍按 ADR 0001 使用全局 Android Keystore 包装身份；多个档案共享身份引用。删除档案会删除引用，最后一个引用删除时清除实际身份。
- Logcat 使用 Kadb shell-v2 流，仅在页面前台且用户点击开始后收集；取消在不可取消 IO 清理段关闭流。容量由 feature 中的有界内存缓冲强制为 10,000 行或 4 MiB。
- Shell 会话输出以 1 MiB 有界内存缓冲保存；底层无 shell-v2 时明确标记合并输出。
- 不新增危险权限、后台组件、服务或业务网络依赖。

## 影响

- 新增 AndroidX DataStore 1.2.0（Apache-2.0）；其余使用已有 AndroidX/Coroutines。
- Application 进程内只创建一个 `AdbSessionManager`，由其独占活跃会话；ViewModel 使用 session ID 丢弃/清空旧设备状态。
- `feature:settings` 是规范清单之外为满足“新增功能默认独立 feature”而补充的物理模块，不改变依赖方向。
- Compose 仪器化测试通常引入不在白名单的 JUnit EPL 依赖，因此自动化使用 TestNG 覆盖状态与策略，实际语义/触控在真机清单验收。

## 回滚

feature 可逐个从 app 装配移除；core 自有契约不暴露 Kadb 类型。DataStore 只有 v1 记录，可清除回滚。Logcat 流可退回显式“不支持”，不影响连接、Shell 与档案格式。
