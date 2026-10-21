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
| **`e2e_test.py`** | **端到端自测**：自动起服 → 六个阶段逐项验证 → 汇总 PASS/FAIL → 自动收尾。命令走控制台标准输入（输出进服务端日志可逐条核对），数据结论直接断言统计库 |
| `console_server.py` | `e2e_test.py` 的底层：用标准输入驱动服务端控制台的库 |
| `rcon.py` | 纯标准库的 RCON 客户端（协议按原版 `RconClient` 对齐），用来快速驱动服务端 |
| `start-server-rcon.ps1` | 开 RCON、后台起服、日志重定向的辅助脚本 |
| `deploy.ps1` | 把 `build/libs/themis-stats-0.1.0.jar` 复制到 `server/mods/`（写进 `server/` 只能走脚本） |
| `debug_piglin.py` | 单独调试「猪灵交换」：把每一步（坐标、手上物品、交互后状态）都打出来 |
| `debug_piglin2.py` … `debug_piglin5.py` | 猪灵用例的分步排查：射线有没有命中（用 `attack` 探针）、假人手里有没有金锭、猪灵的副手与 Brain 记忆、掉落物干扰、**分两批发指令的时序**。结论已经写进 `docs/VERSIONS.md` 与 `docs/ACCEPTANCE.md` |
| `save_failure_test.py` | 验证「保存异常」告警的端到端触发：**在服务端副本上**独占锁住统计库（`BEGIN EXCLUSIVE`），让后台写入线程拿到 `SQLITE_BUSY` 并进入降级状态，看告警是否发出。**不改动任何文件**，主测试库不受影响 |
| `release.py` | 发布新版本到 GitHub：创建 Release（tag + 正文取自 `docs/RELEASE-*.md`）并上传 `build/libs` 里的 jar 附件。凭据取自本机 Git Credential Manager，**不打印 token**；默认直连（本机代理不一定在跑） |
| `experiment-no-mod.ps1` | 对照实验：摘掉本 Mod 的 jar 起服，验证假人能否正常上线 |
| `mc_client.py` | **未完成的实验工具**：最小 1.21.11 协议客户端骨架，尚未联调通过，不要当成可用工具 |

## 使用

```powershell
# 构建
./gradlew build

# 准备测试服务端
#   server/mods 放入 carpet、fabric-api 与 themis-stats 的 jar
#   server.properties 设置 online-mode=false、pause-when-empty-seconds=0

# 单元测试（真实 SQLite，115 个用例）
./gradlew test

# 端到端自测（推荐；自动起服、自动收尾）
python tools/e2e_test.py
python tools/e2e_test.py basic watch metrics alert stall offline

# 跑完整验收场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-server-test4.ps1

# 跑 P1/P2 场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-p1p2-test.ps1

# 起一个可以手动玩的服务端
powershell -NoProfile -ExecutionPolicy Bypass -File tools/start-server.ps1

# 检查数据
python tools/inspect_db.py
```

## 发布新版本到 GitHub

```powershell
# 1. 确认上传的是**实测过的那一份** jar（与 server-263/mods/ 里的逐字节一致）
Get-FileHash build/libs/themis-stats-0.2.0+mc26.3.jar -Algorithm SHA256

# 2. 把发布说明写进 docs/RELEASE-0.2.0.md（它就是 Release 正文）

# 3. 发布（tag + 正文 + jar 附件）
python tools/release.py publish

# 4. 核对线上附件的大小与 sha256
python tools/release.py verify
```

> tag 命名沿用仓库惯例 `V<日期>.<版本>`，例如 `V20261021.0.2.0`；
> 附件名是 `themis-stats-<MC 版本>-<tag>.jar`。
> 脚本用 GitHub API 发布，tag 落在默认分支（`main`）的 HEAD 上。
> **本机代理不一定在跑**，脚本默认直连；`git push` 若报连不上代理，
> 用 `git -c http.proxy= -c https.proxy= push origin 26.3` 直连推。
> 发布参数（tag、jar、说明文件）都写在 `tools/release.py` 顶部，发版前先改那里。

## 三个环境限制（会影响可执行的验收范围）

1. **本机到 Mojang 会话服务器慢**。Carpet 创建假人时会异步解析游戏档案，解析要等超时，
   所以建一个假人要等 3～70 秒（档案缓存在 `server/usercache.json` 里）。
   **名字不在缓存里的假人根本不会出现，而且命令不报错**（取档案失败时异常分支直接返回，
   一行日志都不留）——脚本因此只用进过服的名字，并在召唤前检查 `usercache.json`。
   这不影响功能，只是慢。脚本会**如实报告**假人没有上线，不会假装通过。
2. **没有原版客户端**，因此侧栏的实际渲染、按钮点击、分享预览、`crafted` 增强指标一类交互无法手测。
3. **规格要求的 20 真人／100 假人负载达不到**，性能对照只能做小规模的 A/B。

## 脚本化自测的四个时序／机制坑（会让人误以为功能坏了）

1. **一次性动作在命令之前生效**。`/player <名字> use once` 这类一次性动作在**实体 tick 期间**执行，
   而控制台命令是在**该 tick 结束后**才被清空执行的。所以「转向 → 右键」写在同一条指令链里时，
   右键执行时**转向还没生效**，而且 `use once` 是一次性的，打空就没有了。
   **正解**：分两批发——先 `tp` + `look`，等命令落地后再单独发 `use once`。
2. **假人会把自己丢出来的东西捡回去**。掉落物有 10 tick 的拾取延迟，假人站在原地就把它收回主手，
   于是「丢整组」的数量对不上。**正解**：每次丢弃前用 `item replace` 重新铺好一组**已知数量**的物品。
3. **原版 `/kick` 踢不掉 Carpet 假人**。Carpet 的假人连接只处理「闲置」和「重复登录」两种断开消息，
   其余一律吞掉，所以 `/kick` 对假人毫无效果，假人还在在线列表里。
   **正解**：用 `/player <名字> kill` 让假人下线。
4. **告警冷却跨重启保留，而且只会变高不会变低**。同一个对象同一种原因的冷却是 10 分钟，
   告警引擎在加载时把它读进内存。脚本因此在**起服之前**清掉测试假人的 `alert_state`，
   否则 10 分钟内重跑会被冷却挡掉（表现为「用例无理由地不触发」）。

## 排查假人为什么没上线

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-diag.ps1
```

脚本会起服、召唤 `Bot_A`、等 90 秒，然后把日志里与「登录」「进游戏」「会话开始」「档案查询超时」
有关的行打出来。常见结论：

- 出现 `Read timed out` / `Couldn't look up profile properties` → **本机到 Mojang 的网络慢**，
  假人要等档案解析，与本 Mod 无关；等一会儿就会进游戏。
- 有 `logged in with entity id` 但没有 `joined the game` → **假人没有真正上线**。
  这时先看日志里有没有 `[ThemisStats] 命令树参数类型自检通过`：
  **没有这一行就说明命令树里有原版未注册的参数类型，原版序列化命令树时会抛异常，
  异常被 Carpet 静默吞掉，于是所有玩家都进不了世界**（0.1.0 发布后的那个致命缺陷）。
  有了这一行，才去查网络。

