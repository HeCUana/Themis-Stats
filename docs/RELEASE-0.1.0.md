# Themis Stats 0.1.0 (P0)

面向 **Carpet 生电服** 的玩家统计、排行榜与假人运行观察附属。

服务端安装，**原版客户端无需安装任何东西**即可使用 P0 全部功能。

- Mod ID：`themisstats`　｜　Java 包名：`dev.themisstats`
- 根命令：`/themisstats`（可选别名 `/tstats`，被占用时不注册）
- Carpet 规则分类：`themisStats`（注册在 `/carpet` **主**规则管理器下）
- 世界内数据：`<世界>/themisstats/stats.db`（内置 SQLite，无需另搭数据库）

---

## 环境要求

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Minecraft | 1.21.11 | 首发基线 |
| Java | 21+ | |
| Fabric Loader | 0.18.2+ | 实测 0.19.5 |
| Fabric API | 0.141.6+1.21.11 | |
| **Carpet** | **1.4.194** | **硬依赖**，缺少时 Fabric Loader 直接拒绝加载 |

> 本项目使用 **Mojang 官方映射**（`loom.officialMojangMappings()`），与 Carpet 1.4.194 保持同一命名空间，
> 因此 Carpet 的公开 API（`EntityPlayerMPFake`、`EntityPlayerActionPack`、`carpet.api.settings.*`）
> 解析到的成员与核对过的源码完全一致。其它组合未经实测。

---

## 安装

1. 把 `themis-stats-0.1.0.jar` 放进服务端 `mods/`。
2. 同时安装 Carpet 1.4.194 与 Fabric API 0.141.6+1.21.11。
3. 启动服务端，日志应出现：

```
[Themis Stats] v0.1.0 initialised, registered as a Carpet extension
[ThemisStats] registered the themisStats rule category (15 rules) in the /carpet manager
[ThemisStats] statistics module ready, database <世界>/themisstats/stats.db
```

**卸载**：移除 JAR 即可，统计数据库不会被删除；重新安装兼容版本后累计继续保留。
需要彻底清理时手动删除世界下的 `themisstats/` 目录。

---

## 功能

### 身份与会话

- `human` / `bot` / `shadow` / `unknown` 四态，**按行为发生时的身份**记账。
- UUID 只关联账号：同一个 UUID 的真人、普通假人、Shadow **各算各的**，绝不混账。
- 身份判定来自 Carpet 的对象状态（`EntityPlayerMPFake` + `isAShadow`），不靠名字、皮肤、IP 或队伍。
- 每个实际上线的对象一条独立会话；同一对象多次上线会分开记录，累计连续。
- 会话结束情况区分：正常下线、死亡后离线、管理员移除、正常停服、异常中断、原因未知、身份切换。
  **判断不了原因时写「原因未知」，不会猜成管理员操作。**

### 八类指标

| 指标 | 口径 |
| --- | --- |
| `online` | 会话中**采集启用期间**的实际经过时长；停机与暂停采集段不计 |
| `mining` | 由玩家行为**成功破坏**的方块数量，徒手也可统计 |
| `placement` | 一次成功的**固体方块放置操作**计 1（门／床一次算 1） |
| `kills` | 服务端**最终归属**的击杀事件，每个目标死亡只记一次 |
| `deaths` | 对象**真正发生的死亡** |
| `villager_trades` | 一次**成功执行**的交易计 1（拿到多个物品仍是 1 次） |
| `fishing` | 一次**成功钓获**计 1（空甩竿、空收竿不计） |
| `elytra_distance` | 鞘翅滑翔时的连续有效位移 |

明确的边界：

- 取消、拒绝、未真正移除的破坏都不计；命令、爆炸、活塞推动、自然生长不归给玩家。
- 放水、倒岩浆、活塞移动不进入固体放置榜。
- 玩家击杀玩家单独分类为 `minecraft:player`。
- 生存＋冒险是行为指标的公共榜默认范围；创造与旁观的数据照样采集保存，只是默认不公开。
- `online` **不**按模式过滤，它统计的是各身份实际被采集的在线时长。

### 排行榜

- 三类身份各自独立榜，默认不混排。
- **竞争排名**（1、2、2、4），零值不占榜但资料页仍显示零。
- 在线与离线对象共同参与累计。
- 公开累计 = 符合筛选的采集累计 **+** 该明细范围的管理员调整；榜单、全服总计、与前一名的差距、
  导出全部用这一条公式，**口径一致**。
- 管理员调整落在具体明细范围（对象 ＋ 身份 ＋ 指标 ＋ 分类 ＋ 维度 ＋ 模式），
  所以细分类与总计永远不会互相矛盾；原始会话值与调整分开保存。

### 个人侧边栏

- 模式：`off` / `fixed` / `carousel` / `default`。
- **每玩家独立的计分板与独立封包**，两人可以同时看不同的榜。
  不创建队伍、不改队伍颜色或碰撞规则、不动世界的全局计分板。
- 64 位数值走**文本**显示，计分板分数只作**排序键**（`blank` 数字格式隐藏分数列），
  90 亿不会被截断或溢出。
- 轮播与数据刷新独立；内容没变不重复发送。
- 世界侧边栏显示位被别的提供者占用时，本 Mod **让出**而不是争抢。

### 假人观察

- 档案：身份、负责人、用途（`loader`/`mining`/`combat`/`use`/`custom`）、标签、备注、统计资格、最近运行。
- 观察：在线状态、维度坐标、生命、饥饿、主副手、工具耐久、动作计划、最近成功行为与本次累计。
- 默认 **2 秒采样**，只读有限字段，**不扫描周边农场、不加载区块**。
- 动作计划通过针对 Carpet `EntityPlayerActionPack` 的 Mixin 访问器**只读**读取。
- **在线 / 动作计划 / 观察到的行为是三件不同的事**：配置了攻击不代表发生了击杀，
  静止挂机的假人也不会被判定为故障，更不会据此宣称农场在正常生产。
- 离线对象只显示**最后已知**状态并明确标注。

### 数据与可靠性

- 目标每 5 秒批量提交；账号离线与正常停服时请求结算。
- 每批有稳定身份，重试**不重复加分**；累计、会话汇总、批次回执在同一事务内写入。
- 队列有界；满时进入**可见的降级状态**并记录缺口，游戏线程**不为落盘等待**。
- 异常退出只恢复已提交数据，遗留会话标为「异常中断」，**停机时间不算在线**。
- 备份用 `VACUUM INTO` 取运行中的一致快照（不忽略 WAL）。
  备份**只含本 Mod 数据**，不等于世界备份。
- 导出 CSV／JSON 带身份、单位、范围、口径版本、快照时间与**完整数值**；文本会被防公式注入。

---

## Carpet 规则（15 条）

全部注册在 `/carpet` **主**规则管理器下，分类 `themisStats`。

| 规则 | 类型 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `themisStatsRecording` | 布尔 | `true` | 是否采集指标与统计在线时长（关闭只暂停，不删历史） |
| `themisStatsSidebar` | 布尔 | `true` | 是否开放个人侧边栏（关闭不影响采集） |
| `themisStatsHumanBoard` | 布尔 | `true` | 真人公共榜与公共汇总 |
| `themisStatsBotBoard` | 布尔 | `true` | 普通假人公共榜与公共汇总 |
| `themisStatsShadowBoard` | 布尔 | `false` | Shadow 公共榜与公共汇总 |
| `themisStatsDefaultBoard` | 字符串 | `mining` | 默认侧栏指标（必须是已启用指标） |
| `themisStatsDefaultGroup` | 字符串 | `human` | 默认身份组 |
| `themisStatsDefaultDisplay` | 字符串 | `fixed` | 默认侧栏模式（`off`/`fixed`/`carousel`） |
| `themisStatsCarouselSeconds` | 整数 | `15` | 轮播间隔，范围 5～300 |
| `themisStatsRefreshSeconds` | 整数 | `5` | 排名／侧栏刷新间隔，范围 1～60 |
| `themisStatsAllowPlayerChoice` | 布尔 | `true` | 允许玩家覆盖全服默认侧栏偏好 |
| `themisStatsPublicQuery` | 布尔 | `true` | 允许普通玩家查询公开榜与他人公开汇总 |
| `themisStatsBotMonitor` | 布尔 | `true` | 是否采样假人运行状态 |
| `themisStatsBotSampleSeconds` | 整数 | `2` | 假人状态采样间隔，范围 1～30 |
| `themisStatsBotLocations` | 字符串 | `owners` | 精确位置／装备可见范围（`ops`/`owners`/`all`） |

**非法值一律拒绝、旧值保留。** 持久化用 Carpet 自己的 `setDefault` / `removeDefault`
（写入世界根目录的 `carpet.conf`）；`removeDefault` 会把运行值重置为内置默认值。
数据库里**不保存**第二份能覆盖 Carpet 的规则真值。

```mcfunction
/carpet list themisStats
/carpet themisStatsRecording false
/carpet setDefault themisStatsShadowBoard true
/carpet removeDefault themisStatsShadowBoard
```

---

## 命令

### 查询与个人设置

| 命令 | 说明 |
| --- | --- |
| `/themisstats` | 主菜单 |
| `/themisstats help` | 用法与口径入口 |
| `/themisstats me` | 本人资料（真人／关联 Shadow） |
| `/themisstats player <对象> [身份]` | 查询指定账号的允许可见统计 |
| `/themisstats top <指标> [身份] [页码]` | 公开累计榜 |
| `/themisstats rank <指标> [身份]` | 自己的名次、差距与相邻名次 |
| `/themisstats detail <对象> <身份> <指标>` | 对象的类型细分累计 |
| `/themisstats board off` | 关闭个人侧栏 |
| `/themisstats board fixed <指标> [身份]` | 固定个人榜单 |
| `/themisstats board carousel [身份]` | 开启个人轮播 |
| `/themisstats board default` | 恢复服务器默认显示 |
| `/themisstats bots [页码]` | 假人列表 |
| `/themisstats bot <对象>` | 假人档案与状态 |
| `/themisstats sessions <对象> <身份> [页码]` | 会话列表 |
| `/themisstats session <会话ID>` | 指定会话详情 |
| `/themisstats explain <指标>` | 口径、单位、过滤条件与采集起点 |

`<对象>` 可以是唯一的名字或 UUID。名字对应多个 UUID 时会列出候选要求用 UUID 指定，**不会按名字合并**。

### 管理命令（OP 等级 2 或控制台）

| 命令 | 说明 |
| --- | --- |
| `/themisstats admin status` | 保存时间、队列、积压、缺口、口径版本、三类在线数 |
| `/themisstats admin bot owner <对象> <负责人>` | 绑定负责人（记录变更历史，旧负责人立即失去详细访问权） |
| `/themisstats admin bot role <对象> <用途>` | 设置用途（不改 Carpet 实际动作） |
| `/themisstats admin bot note <对象> <备注>` | 设置备注 |
| `/themisstats admin bot tag <对象> <add\|remove> <标签>` | 增删标签 |
| `/themisstats admin exclude <对象> <身份> <原因>` | 排除公开榜（不删数据） |
| `/themisstats admin include <对象> <身份> <原因>` | 撤销排除 |
| `/themisstats admin adjust <对象> <身份> <指标> <+\|-\|=> <数值> <原因>` | 数据修正（**先预览，再确认**） |
| `/themisstats admin audit [页码]` | 管理操作日志 |
| `/themisstats admin flush` | 请求提交待保存数据 |
| `/themisstats admin export <指标> <身份> <csv\|json>` | 导出统计 |
| `/themisstats admin backup` | 创建一致的统计数据库备份 |
| `/themisstats admin confirm <令牌>` | 确认待执行的变更 |

**数据修正流程**：`admin adjust` 只给预览（列出受影响的具体明细范围与变化后的数值）和一个
**确认令牌**；令牌绑定操作者、参数与 60 秒过期时间，**只能用一次**，确认前参数改不了。
修正后数值不得为负，计数型不接受小数。

---

## 权限

| 能力 | 普通玩家 | 假人负责人 | 管理员 | 控制台 |
| --- | --- | --- | --- | --- |
| 查自己的统计与名次 | 允许 | 允许 | 允许 | 可指定对象 |
| 查公开榜与他人公开汇总 | 依 `themisStatsPublicQuery` | 依规则 | 允许 | 允许 |
| 调整自己的侧栏 | 依 `themisStatsAllowPlayerChoice` | 依规则 | 允许 | — |
| 查假人公开汇总 | 依对应榜单规则 | 同普通玩家 | 允许 | 允许 |
| 查假人精确位置、装备、详细会话 | 默认不允许 | 仅已绑定对象 | 允许 | 允许 |
| 数据修正、排除、导出、备份 | 不允许 | 不允许 | 允许 | 允许 |

**假人负责人**是管理员建立的关联记录，不由假人名称或聊天声明产生，也不附带 Carpet 的 `/player` 等操作权限。
所有命令、按钮、补全、异步返回、分享、导出和侧栏走同一套权限检查，并且**在发送结果之前**会重新检查一次，
所以规则或权限一变，旧的越权页面立刻失效。

---

## 验证情况

| 项目 | 结果 |
| --- | --- |
| 单元测试 | **38 个全通过**（真实 SQLite + 真实 schema，不是 mock） |
| 服务端实测 | 挖掘、放置、击杀（含 `minecraft:player` 分类）、死亡、统计在线时长 |
| 规则与命令 | `/carpet list themisStats` 输出 15 条带中文说明的规则；29 条命令逐条跑通 |
| 存储 | schema 1 → 2 → 3 迁移实测，每次迁移前自动备份 |
| 损坏注入 | 数据库被写坏后统计模块停用并给出诊断，**服务端照常启动，原库未被覆盖** |
| 写入幂等 | 同一批次重放不重复加分；故障时进入可见降级状态 |
| 侧栏 64 位 | 排序分与业务值解耦、90 亿完整显示（自动用例） |
| 性能对照 | 小规模 A/B：启用与停用的 CPU 时间差异落在运行间噪声内，两边**掉帧都是 0 条** |

### 明确未执行的部分

这些**没有**在本次发布中执行，**不应**当作已通过（详见 `docs/ACCEPTANCE.md`）：

- 原版客户端连接后的完整操作：侧栏实际渲染、聊天菜单按钮、分享预览。
- 真人玩家登录／下线、**Shadow 切换（A08）**、改名。
- **钓获的端到端路径**（咬钩窗口只有约 1 tick，自动假人无法稳定命中；钩子本身按 1.21.11 源码核对过）。
- 鞘翅飞行的**实飞**距离累计（测量逻辑已由 6 个自动用例覆盖）。
- 两个真实客户端同时看不同榜单（A29 客户端侧）。
- 20 真人／100 假人的负载基准（本环境达不到：每个新假人需等约 25 秒的档案解析）。
- 负责人转移后「原负责人被撤销详细访问」那一步（需要两个能发命令的真实玩家）。

### P1 / P2 未实现

本次**没有**注册任何 P1 的假命令或无效开关：运行告警订阅、`themisStatsBotAlerts`、
`watch` / `unwatch` / `compare`、细分方块／实体排行榜、维度与模式筛选的公开榜，
以及 `damage_dealt` / `damage_taken` / `movement` / `crafted` / `picked_up` / `dropped` /
`piglin_barters` 七类增强指标。

本 Mod 不会自动禁用 `/player`，不会批量修改 Carpet 的其他规则，
不导入原版统计文件，也不把原版累计总数当作已分账的数据。

---

## 从源码构建

```bash
# 需要 JDK 21
./gradlew build      # 产物：build/libs/themis-stats-0.1.0.jar
./gradlew test       # 38 个单元测试
./gradlew runServer  # 启动隔离的测试服务端
```

Windows 下用 `gradlew.bat`。发布包**已内嵌** SQLite 驱动，部署时不需要另外安装依赖。

验收脚本在 `tools/`：`run-server-test4.ps1`（完整场景）、`run-kill-test.ps1`（击杀归属）、
`run-corruption-test.ps1`（损坏注入）、`run-benchmark.ps1`（性能对照）、`inspect_db.py`（查库）、
`start-server.ps1`（起一个带可见控制台的服务端）。

---

## 发布物校验

```
文件   themis-stats-0.1.0.jar
大小   12,164,074 字节
SHA-256  638ea2883623bddf3f3e414fcb8cf47bc38a7abe4f331ecdaca2ce2716e3e40a
SHA-1    fe2c707f825feceef0fe43894831da4870efb8ca
```

> JAR 打包会写入文件时间戳，因此**不是逐字节可复现**的：同样的源码重新构建会得到不同的 SHA-256
> （内容一致）。需要可复现构建请自行加上 `preserveFileTimestamps=false` 与 `reproducibleFileOrder=true`。

---

## 许可证

MIT，见 [`LICENSE`](LICENSE)。
