# 调试与诊断能力现状

> 类型：当前主干事实
> 依据：HEAD 的 Shell、进程、Logcat 实现与当前自动化测试。

## Shell

- `:feature:shell` 管理内存转录、风险确认和取消；实际执行由 `:core:adb` 的命名入口完成。
- 普通输入逐字符原样执行。检测到电脑端 `adb shell` 包装写法时，UI 明示设备端内容并要求选择；用户仍可选择原样执行。
- 输出区分 stdout/stderr（协议不支持时标记合并），状态区分成功、失败、取消、超时和断开。
- 转录只在当前进程/Session 内，输出上限 1 MiB；超限丢弃最早内容。命令级结束或错误不应关闭仍有效的 Session。

## 进程

- `:feature:processes` 提供当前 Session 的只读分析快照、刷新和复制；可按完整/部分 PID、进程名和应用包名筛选，所有非空条件取 AND，没有 kill、强停或应用修改入口。
- `:core:adb` 负责 `ps`/ROM 输出解析、当前用户第三方应用 UID 快照与关联。只有 Session、snapshot generation 和规范化 `(userId, appId)` 唯一一致时才返回 `VERIFIED`。
- 共享 UID 为 `MULTIPLE`；缺失/非法 UID、跨用户、快照不一致、PID 复用或进程退出为 `UNKNOWN`。不按进程名前缀或包名相似度猜测唯一应用。
- Feature 区分加载、内容、空、进程退出、不支持、取消、错误和断开；Session 切换清除旧条目、筛选和 generation。

## Logcat

- `:feature:logcat` 只在页面前台且用户点击开始后收集；启动时先取得当前进程分析 generation，再订阅同 Session/代次的结构化流。停止拒绝后续记录，离页、断开或 Session 切换同时清除临时分析窗口。
- 命令、threadtime 解析、stderr/unparsed 分类、PID 复用保护和进程/应用关联位于 `:core:adb`；Feature 使用 10,000 行或 4 MiB 的原始内存环形缓冲，展示当前过滤后的最新 100 条。
- 支持采集最低等级/缓冲区，以及结果 level、tag、keyword、PID、process、application 六类筛选；所有启用条件取 AND。应用筛选只匹配 `VERIFIED`，`UNKNOWN`/`MULTIPLE` 不作为唯一命中。
- 无法解析的行和 stderr 保留原始文本且明确标记，不伪造 level、PID 或 tag。暂停冻结可见窗口，继续后只从有界缓冲重算，不重读无界历史。
- 清屏清除原始/可见记录与关联；复制和用户主动 SAF 导出只使用当前可见匹配内容。
- 日志不自动落盘、不上传、不进入诊断事件；导出只包含用户当前可见内容。

## 当前限制

- 当前持续流可能从设备缓冲区较早位置开始，历史较多时到达当前尾部较慢。
- 不提供 crash/ANR 自动识别、CPU/内存趋势、跨时段比较、线程转储、网络抓包、后台 Logcat 或进程控制。
- Android 设备上的 PID 复用、共享 UID、多进程和 10k/4MiB 压力矩阵仍须按 v0.04 真机计划验证；自动化通过不替代真机证据。
