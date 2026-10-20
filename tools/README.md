# Themis Stats 开发与验收工具

这些脚本用于本地构建后的自动化验证，不属于发布产物。

| 脚本 | 用途 |
| --- | --- |
| `start-server.ps1` | 启动一个**带可见控制台**的测试服务端（不用 nogui，不重定向输入，窗口里可以直接敲命令） |
| `run-server-test4.ps1` | 启动隔离测试服务端，生成真实数据（挖掘、放置、击杀、死亡），并逐条执行 `/carpet` 与 `/themisstats` 命令 |
| `run-p1p2-test.ps1` | P1/P2 场景：细分榜、诊断榜、会话对比、关注／取消关注、显式导入、工程计量、Scarpet 查询接口 |
| `run-alerts-test.ps1` | 运行告警的定向场景：低生命值 → 恢复通知 → 指定统计停滞窗口 |
| `run-kill-test.ps1` | 击杀归属的定向验证（僵尸击杀与玩家击杀玩家两个分支） |
| `run-remaining-test.ps1` | 剩余验收项：玩家击杀玩家、钓获、负责人绑定、备份与导出 |
| `run-corruption-test.ps1` | 数据库损坏注入：损坏后启动，验证统计模块停用且**原库不被覆盖** |
| `run-benchmark.ps1` | 启用 / 停用 Themis Stats 的小规模性能对照，结果写入 `benchmark-result.txt` |
| `run-diag.ps1` | 排查用：起服、召唤一个假人、打印它有没有真正进游戏 |
| `inspect_db.py` | 直接读取测试世界的 `stats.db`，打印累计、会话、排除、修正与审计表的内容 |

## 使用

```powershell
# 构建
./gradlew build

# 准备测试服务端
#   server/mods 放入 carpet、fabric-api 与 themis-stats 的 jar
#   server.properties 设置 online-mode=false、pause-when-empty-seconds=0

# 单元测试（真实 SQLite，109 个用例）
./gradlew test

# 跑完整验收场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-server-test4.ps1

# 跑 P1/P2 场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-p1p2-test.ps1

# 起一个可以手动玩的服务端
powershell -NoProfile -ExecutionPolicy Bypass -File tools/start-server.ps1

# 检查数据
python tools/inspect_db.py
```

## 三个环境限制（会影响可执行的验收范围）

1. **无法访问 Mojang 会话服务器**。Carpet 创建假人时会异步解析游戏档案，解析失败要等超时，
   有时候假人会一直停在「已登录但没进游戏」，导致依赖假人在线的场景跑不出来。
   脚本会**如实报告**假人没有上线，不会假装通过。
2. **没有原版客户端**，因此侧栏的实际渲染、按钮点击、分享预览一类交互无法手测。
3. **规格要求的 20 真人／100 假人负载达不到**，性能对照只能做小规模的 A/B。

## 排查假人为什么没上线

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-diag.ps1
```

脚本会起服、召唤 `Bot_A`、等 90 秒，然后把日志里与「登录」「进游戏」「会话开始」「档案查询超时」
有关的行打出来。常见结论：

- 出现 `Read timed out` / `Couldn't look up profile properties` → **本机到 Mojang 的网络问题**，
  与本 Mod 无关。
- 有 `logged in with entity id` 但没有 `joined the game` → 档案或皮肤请求卡住，**假人没有真正上线**。
