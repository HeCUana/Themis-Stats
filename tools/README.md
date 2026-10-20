# Themis Stats 开发与验收工具

这些脚本用于本地构建后的自动化验证，不属于发布产物。

| 脚本 | 用途 |
| --- | --- |
| `start-server.ps1` | 启动一个**带可见控制台**的测试服务端（不用 nogui，不重定向输入，窗口里可以直接敲命令） |
| `run-server-test4.ps1` | 启动隔离测试服务端，生成真实数据（挖掘、放置、击杀、死亡），并逐条执行 `/carpet` 与 `/themisstats` 命令 |
| `run-kill-test.ps1` | 击杀归属的定向验证（僵尸击杀与玩家击杀玩家两个分支） |
| `run-remaining-test.ps1` | 剩余验收项：玩家击杀玩家、钓获、负责人绑定、备份与导出 |
| `run-corruption-test.ps1` | 数据库损坏注入：损坏后启动，验证统计模块停用且**原库不被覆盖** |
| `run-benchmark.ps1` | 启用 / 停用 Themis Stats 的小规模性能对照，结果写入 `benchmark-result.txt` |
| `inspect_db.py` | 直接读取测试世界的 `stats.db`，打印累计、会话、排除、修正与审计表的内容 |

## 使用

```powershell
# 构建
./gradlew build

# 准备测试服务端
#   server/mods 放入 carpet、fabric-api 与 themis-stats 的 jar
#   server.properties 设置 online-mode=false

# 单元测试（真实 SQLite，38 个用例）
./gradlew test

# 跑完整验收场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-server-test4.ps1

# 起一个可以手动玩的服务端
powershell -NoProfile -ExecutionPolicy Bypass -File tools/start-server.ps1

# 检查数据
python tools/inspect_db.py
```

## 两个环境限制（会影响可执行的验收范围）

1. **无法访问 Mojang 会话服务器**。Carpet 创建假人时会异步解析游戏档案，解析失败要等超时，
   每个新假人大约多花 25 秒。脚本用轮询 `themisstats bots` 来等待假人真的上线，不会盲发后续命令。
2. **没有原版客户端**。侧栏的实际渲染、聊天菜单按钮、分享预览、Shadow 切换这些只能由真人操作，
   因此在 `docs/ACCEPTANCE.md` 里被明确列为**未执行**，而不是当作已通过。

## 脚本编码

脚本带 UTF-8 BOM。Windows PowerShell 5.1 在没有 BOM 时会按 ANSI 读取 `.ps1`，中文会被打乱，
甚至会把字符串引号截断从而导致语法错误。
