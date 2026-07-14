# 贡献指南

提交改动前请完整阅读 `AGENTS.md`、当前需求、生命周期规范与 ADR。只实现当前版本范围，保持 app/feature → core 依赖方向，ADB 协议与命令必须留在 `:core:adb`。

依赖许可证仅接受 Apache-2.0、MIT、BSD-2-Clause、BSD-3-Clause。不得提交密钥、真实设备地址或输出。至少运行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

协议、权限、持久化或模块边界变化须先更新 ADR 与相关文档。真机结果必须注明设备/系统范围，未验证项不得写成通过。
