# Themis Stats V20261021.0.2.0（Minecraft 26.3，P0 + P1 + P2）

面向 **Carpet 生电服** 的玩家统计、排行榜与假人运行观察附属。

服务端安装，**原版客户端无需安装任何东西**即可使用全部已实现的功能。

- Mod ID：`themisstats`　｜　Java 包名：`dev.themisstats`
- 根命令：`/themisstats`（可选别名 `/tstats`，被占用时不注册）
- Carpet 规则分类：`themisStats`（注册在 `/carpet` **主**规则管理器下）
- 世界内数据：`<世界>/themisstats/stats.db`（内置 SQLite，无需另搭数据库）

> **发布标识**：tag `V20261021.0.2.0`　｜　附件 `themis-stats-26.3-V20261021.0.2.0.jar`
>
> **这一版面向 Minecraft 26.3 正式版。** 服务端在 26.3 上要用本版；
> **1.21.11 服务端请继续用 `V20261021.0.1.0`**（本版是给 26.3 构建的，不能装到 1.21.11 上）。
>
> **正在用 `V20261020.0.1.0`？请务必升级。** 那一版有三个致命缺陷，
> 其中「所有玩家都进不了服」会让服务端直接没法用。逐条说明见文末
> 「0.1.0 发布后修掉的三个致命缺陷」。
>
> **本版修掉的问题：侧栏会让客户端崩溃。** 个人侧栏的「创建目标」数据包原先
> **每次内容变化都重发**（在线时长每秒都在变，等于每秒重发一次）。26.3 客户端对同一个
> 目标只接受一次创建包，收到第二份就会抛
> `IllegalArgumentException: An objective with the name 'themisstats_sidebar' already exists!`
> 并**断开连接**。1.21.11 的客户端会默默替换掉同名目标，所以这个问题以前看不出来。
> 现在创建包**每个客户端只发一次**，之后只更新显示槽与分值。
> 验证：单测锁定「只创建一次」这条不变量；运行期实测 12 轮内容变化只发出 1 次创建包。

---

## 环境要求

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Minecraft | **26.3**（正式版） | 本版首发基线；1.21.11 用 `V20261021.0.1.0` |
| Java | **25**+ | 26.1 起要求 Java 25；构建、测试、运行统一用 JDK 25 |
| Fabric Loader | 0.19.5 | 实测版本 |
| Fabric API | 0.164.0+26.3 | |
| **Carpet** | **26.3** | **硬依赖**，缺少时 Fabric Loader 直接拒绝加载 |

> **Minecraft 26.1 起游戏不再混淆**，Fabric 换成了不重映射的新版 Loom：
> 不再需要 mappings，`modImplementation` 换成 `implementation`，`remapJar` 换成 `jar`。
> 本项目本来就用 **Mojang 官方映射**，所以 26.3 版本的**业务源码一行未改**；
> Carpet 的扩展面（`CarpetServer`、`ServerPlayerInterface`、`CarpetRule`）与 1.4.194 逐字节相同。
> 其它组合未经实测。

---

## 安装

1. 把 `themis-stats-0.2.0+mc26.3.jar` 放进服务端 `mods/`。
2. 同时安装 Carpet 26.3 与 Fabric API 0.164.0+26.3（Fabric Loader 0.19.5）。
3. 启动服务端，日志应出现：

```
[Themis Stats] v0.2.0+mc26.3 initialised, registered as a Carpet extension
[ThemisStats] registered the themisStats rule category (18 rules) in the /carpet manager
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

### 十五类指标（P0 八类 ＋ P1 七类增强）

| 指标 | 口径 |
| --- | --- |
| `online` | 会话中**采集启用期间**的实际经过时长；停机与暂停采集段不计 |
| `mining` | 由玩家行为**成功破坏**的方块数量，徒手也可统计 |
| `placement` | 一次成功的**固体方块放置操作**计 1（门／床一次算 1） |
| `kills` | 服务端**最终归属**的击杀事件，每个目标死亡只记一次 |
| `deaths` | 对象**真正发生的死亡** |
| `villager_trades` | 一次**成功执行**的交易计 1 |
| `fishing` | 一次**成功钓获**计 1 |
| `elytra_distance` | 鞘翅滑翔时的连续有效位移 |
| `damage_dealt` | **血量实际减少量**（护甲、抵抗、吸收生命与图腾的结算已算在内） |
| `damage_taken` | **血量实际减少量**，过量伤害按实际扣血计 |
| `movement` | 仅 `MoverType.SELF` 的位移，分类为走／游／骑乘／飞行 |
| `crafted` | 从合成结果格**取出**一次计 1 |
| `picked_up` | 背包在拾取**之前**比拾取**之后**多出的件数 |
| `dropped` | 主动丢弃一次计 1 |
| `piglin_barters` | 猪灵**收到**物品并完成一次交换计 1 |

明确的边界：

- 取消、拒绝、未真正移除的破坏都不计；命令、爆炸、活塞推动、自然生长不归给玩家。
- 放水、倒岩浆、活塞移动不进入固体放置榜。
- 玩家击杀玩家单独分类为 `minecraft:player`。
- 生存＋冒险是行为指标的公共榜默认范围（由 `themisStatsPublicModes` 决定）；
  创造与旁观的数据照样采集保存，只是默认不公开。
- `online` **不**按模式过滤，它统计的是各身份实际被采集的在线时长。

### 排行榜

- 三类身份各自独立榜，默认不混排。
- **竞争排名**（1、2、2、4），零值不占榜但资料页仍显示零。
- 公开累计 = 符合筛选的采集累计 **+** 该明细范围的管理员调整；榜单、全服总计、与前一名的差距、
  导出全部用这一条公式，**口径一致**。
- 管理员调整落在具体明细范围（对象 ＋ 身份 ＋ 指标 ＋ 分类 ＋ 维度 ＋ 模式），
  所以细分类与总计永远不会互相矛盾；原始会话值与调整分开保存。
- **显式导入的历史数据不参与这条公式**，它带自己的来源标记，与采集的数据分属不同的行。

### 细分榜与诊断榜

- `/themisstats fine <指标> <分类> [身份] [页码]`：按方块／实体／移动方式拆分后的累计榜。
- `/themisstats admin board <指标> <身份> <模式> [分类] [维度] [页码]`：管理员可以指定任意
  模式、分类与维度，不受公共模式范围限制。

### 运行告警（P1）

五类提醒：低生命值、工具低耐久、意外离线、指定统计停滞、保存异常。

- 总开关 `themisStatsBotAlerts` **默认关闭**；订阅可以先存好，打开后立即生效。
- 只发给**订阅者**，且订阅者本人也要有查看该对象运行细节的权限。
- 同一对象同一原因冷却 10 分钟；条件恢复后**只发一次**恢复通知；新上线对象有 60 秒宽限期。
- 「指定统计停滞」**只说明观察期内无增长，不会断言机器故障**；采集暂停时判定中止并重建窗口。
- 告警**不会**清空背包、补工具、传送假人或改动它的动作。

### 会话对比（P1）

`/themisstats compare <对象> <身份> [指标]` 给出最近两次会话的时长差、指标差与**每真实分钟速率**，
并说明低 TPS 会让每分钟的实际次数变少、暂停采集的区间不计入分母。

### 显式数据导入（P2）

- 导入**永远是显式的**：没有管理员指向一个文件，就不会有任何数据进入数据库。
- CSV 列：`uuid,name,identity,metric,category,dimension,gameMode,value`
  （`#` 开头是注释行，空行忽略；读不懂的行会被跳过并在结果里逐条报告，不会中断整次导入）。
- 导入的行带 `imported` 来源标记，**公共榜、细分榜与导出都不会因为一次导入而变大**。
- 导入代表的是**管理员写进文件里的内容**，不是过去真人与假人行为的还原。

### 查询接口（P2）

Java 接口 `dev.themisstats.api.ThemisStatsApi`（`top` / `valueOf` / `breakdown` / `onlineCounts`）
与三个 Scarpet 函数：

```
themis_stats('mining', 'bot')                -> 整个身份组的公开累计
themis_stats_of('mining', 'bot', 'bot_a')    -> 单个对象的公开累计
themis_stats_top('mining', 'bot', 1)         -> 第一页的 [名字, 数值] 列表
```

它们与命令使用**同一条**公开公式，脚本看到的数字与聊天命令看到的数字**永远一致**。

### 权限节点（P2）

`themisstats.query` / `view_details` / `sidebar` / `watch` / `export` / `manage`。
没有权限插件时由 OP 等级决定（管理员全部有，普通玩家只有 `query` 与 `sidebar`）；
装了权限插件可以用 `PermissionNodes.setProvider(...)` 接入自己的实现。

### 工程计量（P2）

`/themisstats admin project <对象> <工程>` 把对象归入工程，
`/themisstats project <工程> [指标] [页码]` 列出成员与工程合计，
并明确提示「工程累计只统计已采集的数据，不代表机器的实际产量」。

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
- **在线 / 动作计划 / 观察到的行为是三件不同的事**：配置了攻击不代表发生了击杀，
  静止挂机的假人也不会被判定为故障，更不会据此宣称农场在正常生产。

### 数据与可靠性

- 目标每 5 秒批量提交；账号离线与正常停服时请求结算。
- 每批有稳定身份，重试**不重复加分**；累计、会话汇总、批次回执在同一事务内写入。
- 队列有界；满时进入**可见的降级状态**并记录缺口，游戏线程**不为落盘等待**。
- **整库访问被一把可重入锁串行化**：SQLite 的连接不是线程安全的，
  游戏线程与后台线程共用时，读会看到未提交的数据，写会串进对方的事务。
- 异常退出只恢复已提交数据，遗留会话标为「异常中断」，**停机时间不算在线**。
- 备份用 `VACUUM INTO` 取运行中的一致快照（不忽略 WAL）。
  备份**只含本 Mod 数据**，不等于世界备份。
- 导出 CSV／JSON 带身份、单位、范围、口径版本、快照时间与**完整数值**；文本会被防公式注入。

---

## Carpet 规则（18 条）

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
| `themisStatsBotAlerts` | 布尔 | `false` | 运行告警总开关（关闭时订阅仍可保存） |
| `themisStatsPublicModes` | 字符串 | `survival,adventure` | 进入公共榜的游戏模式 |
| `themisStatsStallPurposes` | 字符串 | `mining,combat,use,custom` | 允许触发「指定统计停滞」的用途 |

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
| `/themisstats fine <指标> <分类> [身份] [页码]` | 细分榜 |
| `/themisstats project <工程> [指标] [页码]` | 工程计量 |
| `/themisstats watch <对象> <身份> [提醒] [指标]` | 订阅运行告警 |
| `/themisstats unwatch <对象> <身份>` | 取消订阅 |
| `/themisstats compare <对象> <身份> [指标]` | 最近两次会话对比 |
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
| `/themisstats admin project <对象> <工程>` | 把对象归入工程 |
| `/themisstats admin exclude <对象> <身份> <原因>` | 排除公开榜（不删数据） |
| `/themisstats admin include <对象> <身份> <原因>` | 撤销排除 |
| `/themisstats admin adjust <对象> <身份> <指标> <+\|-\|=> <数值> <原因>` | 数据修正（**先预览，再确认**） |
| `/themisstats admin board <指标> <身份> <模式> [分类] [维度] [页码]` | 诊断榜（可指定模式／分类／维度） |
| `/themisstats admin import <文件>` | 显式导入 CSV |
| `/themisstats admin imported <指标> <身份>` | 查看导入进来的历史数据 |
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
| 订阅运行告警 | 依 `themisstats.watch` | 依节点 | 允许 | 不适用 |
| 数据修正、排除、导出、备份 | 不允许 | 不允许 | 允许 | 允许 |

**假人负责人**是管理员建立的关联记录，不由假人名称或聊天声明产生，也不附带 Carpet 的 `/player` 等操作权限。
所有命令、按钮、补全、异步返回、分享、导出和侧栏走同一套权限检查，并且**在发送结果之前**会重新检查一次，
所以规则或权限一变，旧的越权页面立刻失效。

---

## 验证情况

| 项目 | 结果 |
| --- | --- |
| 依赖解析 | Minecraft **26.3 正式版**（Mojang 清单里 `type=release`）、Loader 0.19.5、Fabric API 0.164.0+26.3、Carpet 26.3、Loom 1.17.21、Gradle 9.5.1、Java 25 —— 全部实际解析并跑起来 |
| 单元测试 | **115 个全通过**（真实 SQLite + 真实 schema，不是 mock） |
| 端到端自测 | `tools/e2e_test.py` 六个阶段（2026-10-21 在**干净的 26.3 专用服务端**上全流程跑通，**49 / 49**）：假人上线与命令树自检、`watch`/`unwatch` 完整交互、七类增强指标、低生命值与低耐久告警及恢复、5 分钟窗口的停滞告警、**意外离线告警（订阅者是另一个玩家）** |
| 16 个 Mixin | **全部注入成功**（`defaultRequire=1`，注入不上就启动失败）；服务端日志无任何 Mixin 失败 |
| 旧数据兼容 | 1.21.11 的统计库在 26.3 上打开后**逐表行数与累计值完全一致**，schema 版本不变、未触发迁移（在副本里验证，原库未改写） |
| 1.21.11 维护基线 | 旧分支仍可用原工具链（Gradle 9.1.0 + Loom 1.13.6 + Java 21）构建，115 个单元测试全绿 |
| 客户端侧 | **未执行**（本机没有原版 26.3 客户端），详见文末「明确未执行的部分」 |
| 服务端实测 | 挖掘、放置、击杀（含 `minecraft:player` 分类）、死亡、统计在线时长、会话对比 |
| 规则与命令 | `/carpet list themisStats` 输出 **18** 条带中文说明的规则；命令逐条跑通 |
| 存储 | schema 1 → 2 → 3 → **4 → 5 → 6** 迁移实测，每次迁移前自动备份 |
| 损坏注入 | 数据库被写坏后统计模块停用并给出诊断，**服务端照常启动，原库未被覆盖** |
| 写入幂等 | 同一批次重放不重复加分；故障时进入可见降级状态 |
| 线程安全 | 游戏线程与后台线程共用一个 SQLite 连接 → 已用可重入锁串行化（事务期间不释放） |
| 侧栏 64 位 | 排序分与业务值解耦、90 亿完整显示（自动用例） |
| 性能对照 | 小规模 A/B：启用与停用的 CPU 时间差异落在运行间噪声内，两边**掉帧都是 0 条** |

### 0.1.0 发布后修掉的三个致命缺陷（**建议尽快更新**）

这一版是在 0.1.0 发布**之后**做第二轮实测时挖出来的，已全部修复：

1. **所有玩家都进不了服**：本 Mod 注册了原版不认识的 Brigadier 参数类型，
   原版在玩家进服时序列化命令树会抛异常，异常被 Carpet 静默吞掉。
   已改为只用原版已注册的类型，并加了启动自检（发现未注册的类型会 ERROR 日志）。
2. **玩家第一次受伤即崩服**：一个普通辅助类被放在了 Mixin 包里，Mixin 拒绝加载它。
   已移到 `dev.themisstats.metric` 包，并加了单元测试防回归。
3. **`dropped` 指标按整组计数**：按 Q 丢一个也会记成整叠的数量。已改成跟着原版语义走。

> 上一版发布说明里写的「假人进不了服是 Mojang 网络问题」**是错的**，
> 真实原因就是上面第 1 条。详见 `docs/ACCEPTANCE.md` 第 0-A 节。

### 明确未执行的部分

这些**没有**在本次发布中执行，**不应**当作已通过（详见 `docs/ACCEPTANCE.md` 第 7 节）：

- 原版客户端连接后的完整操作：侧栏实际渲染、聊天菜单按钮、分享预览。
- 真人玩家登录／下线、**Shadow 切换**、改名。
- **`crafted` 增强指标**：计数点在 `ResultSlot#onTake`，只能由真实客户端点击合成结果触发。
- **钓获的端到端路径**（咬钩窗口只有约 1 tick，自动假人无法稳定命中）。
- 鞘翅飞行的**实飞**距离累计（测量逻辑已由 6 个自动用例覆盖）。
- 「意外离线」告警里的**「原因未知」**分支（假人的每条下线路径都带可观察的原因，
  本机实测覆盖的是「管理员移除」；这一类告警本身已端到端触发）。
- 两个真实客户端同时看不同榜单。
- **26.3 上的容量与性能对照**（1.21.11 上只做过小规模 A/B；本机达不到规格里的规模）。
- 26.3 的 GitHub 侧发布流程与 CI 产物尚未在真实 CI 上跑过（工作流已写好，未触发）。
- 20 真人／100 假人的负载基准（本环境达不到）。
- 负责人转移后「原负责人被撤销详细访问」那一步（需要两个能发命令的真实玩家）。
- 权限插件接入后的节点行为。

本 Mod 不会自动禁用 `/player`，不会批量修改 Carpet 的其他规则，
不导入原版统计文件，也不把原版累计总数当作已分账的数据。

### 已知问题：召唤没进过服的名字会卡一下（**与本 Mod 无关**）
`/player <名字> spawn` 遇到**没进过服的名字**时，原版会替它联网查一次 Mojang 档案
（`CachedUserNameToIdResolver.lookupGameProfile`，在服务端主线程上阻塞式发 HTTPS），
名字不存在就 404，于是控制台会刷：

```
[xx:xx:xx WARN]: Couldn't find profile with name: X
[xx:xx:xx WARN]: Could not lookup user whitelist entry for X
[xx:xx:xx WARN]: Can't keep up! Is the server overloaded? Running 2245ms or 44 ticks behind
```

假人**最终还是会正常进服**（离线模式下会回退到本地算的 UUID），只是过程会卡一下。
1.21.11 与 26.3 的实现完全一样，不是 26.3 引入的回归。

**规避办法**：把要用的名字预先写进服务端的 `usercache.json`，解析就会命中本地缓存——
不联网、不报警、不卡顿（离线 UUID 的算法是
`UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))`）：

```json
[{"uuid":"<离线 UUID>","name":"<名字>","expiresOn":"2026-12-31 00:00:00 +0800"}]
```

写完**重启服务端**才生效（档案缓存在启动时读入）。

---

## 从源码构建

```bash
# 需要 JDK 21
./gradlew build      # 产物：build/libs/themis-stats-0.1.0.jar
./gradlew test       # 115 个单元测试
./gradlew runServer  # 启动隔离的测试服务端
```

Windows 下用 `gradlew.bat`。发布包**已内嵌** SQLite 驱动，部署时不需要另外安装依赖。

验收脚本在 `tools/`：**`e2e_test.py`**（端到端自测：自动起服 → 六个阶段逐项 PASS/FAIL → 汇总）、
`console_server.py`（用标准输入驱动服务端控制台）、`rcon.py`（RCON 客户端）、
`run-server-test4.ps1`（完整场景）、`run-p1p2-test.ps1`（P1/P2 场景）、
`run-alerts-test.ps1`（告警定向场景）、`run-kill-test.ps1`（击杀归属）、
`run-corruption-test.ps1`（损坏注入）、`run-benchmark.ps1`（性能对照）、`inspect_db.py`（查库）、
`start-server.ps1`（起一个带可见控制台的服务端）。

---

## 发布物校验

> 下面是**Minecraft 26.3 目标**的构建产物（`build/libs/themis-stats-0.2.0+mc26.3.jar`，
> 与 `server-263/mods/` 里实测用的那一份逐字节一致）。
> 发布时它作为附件 `themis-stats-26.3-V20261021.0.2.0.jar` 上传，内容完全相同。

```
文件   themis-stats-0.2.0+mc26.3.jar
       （发布附件名 themis-stats-26.3-V20261021.0.2.0.jar）
大小   12,247,362 字节
SHA-256  80fb22bd246374faa40b697a592ea4f9ea7e94fdbc1f9487bae736ab31273944
```

> JAR 打包会写入文件时间戳，因此**不是逐字节可复现**的：同样的源码重新构建会得到不同的 SHA-256
>（内容一致）。需要可复现构建请自行加上 `preserveFileTimestamps=false` 与 `reproducibleFileOrder=true`。

---

## 许可证

MIT，见 [`LICENSE`](LICENSE)。
