# Minecraft 26.3 升级与回退说明

> 适用：Themis Stats 0.2.0+mc26.3（MC 26.3 正式版）
> 旧版本：0.1.0（MC 1.21.11），发布在 `V20261020.0.1.0` 与 `V20261021.0.1.0`

## 1. 目标环境

| 组件 | 版本 |
| --- | --- |
| Minecraft | **26.3**（正式版） |
| Java | **25**（构建与运行都用；Minecraft 26.1 起的最低要求） |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.164.0+26.3 |
| Carpet | 26.3（`fabric-carpet-26.3+v260915.jar`） |
| Themis Stats | `themis-stats-0.2.0+mc26.3.jar` |

## 2. 部署步骤

1. **备份世界**。Minecraft 26.1 起世界存储有较大变化，升级前务必整份备份
   （世界、玩家数据、`carpet.conf`、Themis Stats 数据目录）。
2. 把 `themis-stats-0.2.0+mc26.3.jar` 放进服务端 `mods/`。
3. 确认服务端同时装了 Fabric Loader 0.19.5、Fabric API 0.164.0+26.3、Carpet 26.3。
4. 启动服务端。日志里应当出现：

```
[Themis Stats] v0.2.0+mc26.3 initialised, registered as a Carpet extension
[ThemisStats] registered the themisStats rule category (18 rules) in the /carpet manager
[ThemisStats] 命令树参数类型自检通过，共 1159 种，全部为原版已注册类型
[ThemisStats] statistics module ready, database .\world\.\themisstats\stats.db
```

> **不兼容时会直接拒绝启动**，不会带着错误的依赖硬跑：
> `fabric.mod.json` 里声明的是 `minecraft: ~26.3`、`carpet: ~26.3`、`java: >=25`，
> 版本对不上时 Fabric Loader 会在加载阶段报出明确的依赖错误。

5. 客户端**不需要**安装本 Mod：原版 26.3 客户端连上来即可使用查询、侧栏与聊天菜单。

## 3. 数据与配置

| 项 | 变化 |
| --- | --- |
| 数据库路径 | **不变**：`<世界>/themisstats/stats.db` |
| schema 版本 | **不变**：仍是 v6（1→2→3→4→5→6 的迁移链与 1.21.11 相同） |
| 旧库处理 | 26.3 **不会重置旧库**：实测把 1.21.11 的统计库放进 26.3 世界后启动，账号、累计、会话、负责人、排除、修正、偏好逐表行数与累计值**完全一致**，schema 版本不变，也没有触发多余迁移 |
| 指标 ID / 单位 / 身份枚举 | **全部不变**（15 类指标、四种身份） |
| Carpet 规则 | 18 条规则**名字、默认值、语义不变**，仍注册在 `/carpet` 主入口下；`carpet.conf` 里的持久值继续生效 |
| 历史分类 ID | 26.3 里注册表若缺少旧 ID，**原记录仍可查询并保留原 ID**，不会因为解析不到就删除 |

## 4. 回退

> **不要**把 26.3 转换过的世界直接交给 1.21.11 的服务端试跑：世界格式已经变了。

回退需要**成套**恢复升级前��快照：

| 要恢复的东西 | 从哪来 |
| --- | --- |
| 旧 Mod jar | `themis-stats-0.1.0.jar`（发布 `V20261021.0.1.0` 的附件） |
| 旧运行环境 | Java 21、Fabric Loader 0.18.2、Fabric API 0.141.6+1.21.11、Carpet 1.4.194 |
| 旧世界 | 升级前备份的世界目录（**未转换的**那份） |
| 旧统计库 | 升级前备份的 `<世界>/themisstats/stats.db`（含 `stats.db.v<N>.bak`） |
| 旧 Carpet 配置 | 升级前备份的 `carpet.conf` |

> 统计库在本次升级中**没有结构变化**，所以旧库可以直接被旧版本读取；
> 但这是本次实测得出的结论，不是通例——如果后续版本改过 schema，就必须用升级前的备份回退。

## 5. 已知的版本差异（实测结论，不是猜测）

| 差异 | 影响 | 处理 |
| --- | --- | --- |
| 游戏不再混淆 | 不再需要 mappings；`remapJar` 变成 `jar`；`modImplementation` 变成 `implementation` | 已按新 Loom 约定改构建脚本 |
| 命令类包名重排 | 服务端命令类从 `net.minecraft.commands` 移到 `net.minecraft.server.commands`（例如 `DamageCommand`） | 本 Mod 不直接引用这些类，无需改动 |
| `instant_health` 在 26.3 生效期间血量会持续变化 | 自动化用例在读血量时可能读到**正在回血的中间值**（一次读数 8.500001，而实际已被打到 5） | 测试脚本治疗后显式 `effect clear`，读数才稳定 |
| 方块转换（锄地、铲路、剥皮、打蜡/刮蜡）成为新的原版数据类型 | 这些是**转换**而不是「挖掘 + 放置」 | 采集点挂在 `ServerPlayerGameMode#destroyBlock` 与 `BlockItem#place` 上，转换路径不走这两个点，因此不会凭空多记一次挖掘加一次放置 |

## 6. 召唤假人时的联网查档案与卡顿（**不是本 Mod 的问题，但会卡服务端**）

用 Carpet 召唤一个**没进过服的名字**时：

```
/player 1 spawn
```

会走原版的「名字 → 档案」解析：

```
carpet.commands.PlayerCommand.spawn
  → net.minecraft.server.players.OldUsersConverter.convertMobOwnerIfNecessary
    → net.minecraft.server.players.CachedUserNameToIdResolver.get
      → CachedUserNameToIdResolver.lookupGameProfile   ← 在服务端主线程上阻塞式发 HTTPS
```

名字不在服务端的 `usercache.json` 里时，这次查询会 404，于是：

* 打出 `Couldn't find profile with name: X`、`Could not lookup user whitelist entry for X` 的 WARN；
* 服务端出现 `Can't keep up!`（实测一次召唤卡 **2.2 秒**，路径上会查两次）；
* 假人**最终仍然会进服**（离线模式下回退到本地算的 UUID），只是过程很难看。

**这不是 Themis Stats 造成的**（调用栈里没有本 Mod），而且 **1.21.11 与 26.3 的实现完全同构**
（两版的 `CachedUserNameToIdResolver` 都有同一个 `resolveOfflineUsers` 开关与同一个
`lookupGameProfile` 路径），不是 26.3 引入的回归。

**解法**：把要用的名字预置进服务端的 `usercache.json`，解析就会命中本地缓存——不联网、不报警、不卡顿：

```powershell
# 离线 UUID 与原版 UUIDUtil.createOfflinePlayerUUID 一致
python tools/seed_usercache.py server-263 bot_a Bot_B
# 改完要重启服务端才生效（档案缓存在启动时读入）
```

已实测：预置前召唤会刷 Mojang 查询警告，预置后同一命令**日志干净**。

---

## 7. 未验证项

见 `test-report.md` 的「未执行」清单。**没有原版 26.3 客户端**可操作，因此侧栏的实际渲染、
按钮点击、分享预览等客户端侧项目没有实测；`crafted` 增强指标依然只能由真实客户端点击合成结果触发。
