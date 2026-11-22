# TickTuner 协作与维护指引（人 / AI Agent 必读）

1. **先读工程说明**：动手阅读或修改代码前，请先阅读 [docs/工程说明.md](docs/工程说明.md)，了解完整项目结构、技术栈、四态多形态设计规范、三大模块交互红线、测试与发版流程。
2. **改完必须同步更新文档**：**每次功能新增、界面调整、架构改动或发版后，必须同步更新 `docs/工程说明.md`**（以及发版时同步更新 `README.md`、`docs/进度.md`、`dist/安装说明.txt`），确保后续接手的人或 Agent 看到的始终是最新状态。
3. **关键硬约束速览**：
   - 完全离线，不新增 `INTERNET` 权限，不随意引入第三方依赖。
   - 保持包名 `com.gtool` 不变（支持覆盖安装保留用户数据）。
   - `TunerScreen()`、`MetronomeScreen()`、`ChordsScreen()` 入口签名保持无参；`GToolApp` 保持 `NavHost` 单一结构槽位，旋转/折展不销毁 ViewModel。
   - 修改 `AppSettings` 必须提供默认值保证 DataStore JSON 向后兼容。
   - 所有 PowerShell 脚本请用 `pwsh`（PowerShell 7）执行；新增纯逻辑须补 JVM 单元测试（JUnit 4）。
