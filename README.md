# Themis Stats（忒弥斯统计）

面向 **Carpet 生电服** 的玩家统计、排行榜与假人运行观察附属（Fabric Mod）。

- Mod ID：`themisstats`
- Java 包名：`dev.themisstats`
- 正式根命令：`/themisstats`（可选别名 `/tstats`，被占用时不注册）
- Carpet 规则前缀与分类：`themisStats`
- 世界内数据目录：`<世界根目录>/themisstats/`
- 数据库：`stats.db`（内置 SQLite，无需另外部署数据库）
- 首个版本：`0.1.0`（P0 功能范围）

本 Mod **只在服务端安装**。原版客户端无需安装任何东西，即可使用 P0 的全部功能。

---

## 1. 适配与验证过的版本

| 组件 | 使用的版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.21.11 | 首发基线 |
| Java | 21 | 编译与运行均使用 Java 21 |
| Fabric Loader | 0.18.2+ | 开发与测试使用 0.19.5 |
| Fabric API | 0.141.6+1.21.11 | 提供命令注册与重生事件 |
| Carpet | 1.4.194 | **强依赖**；1.4.194 是已核对的源码基线 |
| Loom / Gradle | 1.13.6 / 9.1.0 | 构建工具链 |
| 映射 | Mojang 官方映射 | 与 Carpet 1.4.194 保持一致 |

> 映射说明：本项目刻意使用 **Mojang 官方映射**，因为 Carpet 1.4.194 本身也是用官方映射构建的。
> 保持同一命名空间，才能保证 `carpet.patches.EntityPlayerMPFake`、`carpet.helpers.EntityPlayerActionPack`、
> `carpet.api.settings.*` 等接口解析到的成员与实际核对的源码完全一致。

未在上面列出的组合**没有经过实测**，请勿假定兼容。缺少 Carpet 时，Fabric Loader 会直接拒绝加载本 Mod。

---

## 2. 安装与卸载

1. 把 `themis-stats-0.1.0.jar` 放进服务端的 `mods/` 目录。
2. 同时安装 **Carpet 1.4.194** 与 **Fabric API 0.141.6+1.21.11**（二者都是必需依赖）。
3. 启动服务端。启动日志中会出现：

   ```
   [ThemisStats] registered the themisStats rule category (15 rules) in the /carpet manager
   [ThemisStats] statistics module ready, database <世界>/themisstats/stats.db
   ```

4. 用 `/carpet list themisStats` 查看规则，用 `/themisstats help` 查看命令。

**卸载**：移除本 Mod 的 JAR 即可。统计数据库**不会**被删除，重新安装兼容版本后累计继续保留。
需要彻底清理时，手动删除世界目录下的 `themisstats/` 文件夹。

---

## 3. Carpet 规则（15 条，注册在 `/carpet` 主管理器下）

所有规则都注册进 Carpet 的**主**规则管理器，因此由 `/carpet` 统一管理，并遵循 Carpet 自身的
`setDefault` / `removeDefault` 持久化语义（持久化文件为世界根目录下的 `carpet.conf`）。

| 规则 | 类型 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `themisStatsRecording` | 布尔 | `true` | 是否采集统计指标与统计在线时长。关闭只暂停采集，**不删除历史** |
| `themisStatsSidebar` | 布尔 | `true` | 是否开放个人侧边栏服务。关闭**不影响**指标采集与历史查询 |
| `themisStatsHumanBoard` | 布尔 | `true` | 是否开放真人公共榜与公共汇总 |
| `themisStatsBotBoard` | 布尔 | `true` | 是否开放普通假人公共榜与公共汇总 |
| `themisStatsShadowBoard` | 布尔 | `false` | 是否开放 Shadow 公共榜与公共汇总 |
| `themisStatsDefaultBoard` | 字符串 | `mining` | 默认侧栏指标，必须是本版本已启用的指标 |
| `themisStatsDefaultGroup` | 字符串 | `human` | 默认身份组（`human` / `bot` / `shadow`） |
| `themisStatsDefaultDisplay` | 字符串 | `fixed` | 默认侧栏模式（`off` / `fixed` / `carousel`） |
| `themisStatsCarouselSeconds` | 整数 | `15` | 轮播间隔，有效范围 5～300 秒 |
| `themisStatsRefreshSeconds` | 整数 | `5` | 排名／侧栏快照刷新间隔，有效范围 1～60 秒 |
| `themisStatsAllowPlayerChoice` | 布尔 | `true` | 是否允许玩家覆盖全服默认侧栏偏好 |
| `themisStatsPublicQuery` | 布尔 | `true` | 是否允许普通玩家查询公开榜与他人公开汇总 |
| `themisStatsBotMonitor` | 布尔 | `true` | 是否采样假人运行状态 |
| `themisStatsBotSampleSeconds` | 整数 | `2` | 假人状态采样间隔，有效范围 1～30 秒 |
| `themisStatsBotLocations` | 字符串 | `owners` | 精确位置／装备／受限运行细节的可见范围（`ops` / `owners` / `all`） |

**非法值一律拒绝，旧值保留。** 例如 `/carpet themisStatsCarouselSeconds 1` 会被拒绝，
`themisStatsCarouselSeconds` 保持原值。

**`removeDefault` 的确切语义**（Carpet 1.4.194 内置行为）：`/carpet removeDefault <规则>` 会把该规则
从 `carpet.conf` 中删除，**并立即把运行中的值重置为内置默认值**（不是恢复到 `setDefault` 之前的值）。
`/carpet <规则> <值>` 只影响本次运行；`/carpet setDefault <规则> <值>` 才会写入 `carpet.conf`。
数据库中**不会**保存第二份可覆盖 Carpet 的全局规则真值。

### 开关之间的关系

| 操作 | 指标采集 | 假人状态采样 | 历史查询 | 公开展示 |
| --- | --- | --- | --- | --- |
| 关闭 `Sidebar` | 继续 | 由 `BotMonitor` 决定 | 保留 | 仅侧栏关闭 |
| 关闭某身份 `Board` | 继续 | 由 `BotMonitor` 决定 | 本人／管理员仍可查询 | 该组公开榜与公共汇总关闭 |
| 关闭 `PublicQuery` | 继续 | 由 `BotMonitor` 决定 | 本人／管理员保留 | 普通玩家的公共查询、分享与侧栏全部关闭 |
| 关闭 `Recording` | 指标与统计在线时长暂停 | 可继续用于运行观察 | 保留 | 旧快照仍可显示，并标注采集暂停 |
| 关闭 `BotMonitor` | 可继续 | 停止 | 历史保留，当前状态标记不可观察 | 指标榜不受影响 |

`Recording=false` 时仍然保存最小的身份、连接会话与暂停区间元数据，供运行观察与崩溃恢复使用；
这**不是**继续计分。会话实际在线时长与有效统计时长分别展示。

---

## 4. 统计口径

八个 P0 指标，全部按 **发生时的身份** 分账。真人、普通假人、Shadow 即使共用同一个 UUID 也**不会混账**。

| 指标 ID | 名称 | 计数口径 | 细分维度 |
| --- | --- | --- | --- |
| `online` | 统计在线时长 | 会话中**采集启用期间**的实际经过时长；停机时间与暂停采集段不计 | 身份、会话 |
| `mining` | 挖掘量 | 由玩家行为**成功破坏**的方块数量，徒手也可统计 | 方块注册 ID、维度、模式 |
| `placement` | 放置量 | 一次成功的**固体方块放置操作**计 1 | 方块注册 ID、维度、模式 |
| `kills` | 击杀量 | 服务端**最终归属**给该对象的击杀事件，每个目标死亡只记一次 | 实体注册 ID、维度、模式 |
| `deaths` | 死亡数 | 对象**真正发生的死亡** | 死亡原因、维度、模式 |
| `villager_trades` | 村民交易 | 一次**成功执行**的交易计 1 | 维度、模式 |
| `fishing` | 钓获次数 | 一次**成功钓获**计 1 | 维度、模式 |
| `elytra_distance` | 鞘翅距离 | 鞘翅滑翔时的连续有效位移 | 维度、模式 |

### 明确的边界

- **挖掘**：只在方块真的被移除时计数。被其他模组拒绝、中途取消、点击空位置都不计。
  命令、爆炸、活塞推动、自然生长不归给玩家。
- **放置**：一次放置**操作**计 1，因此门、床一次算 1，界面写的是「放置次数」而不是「净新增方块数」。
  放水、倒岩浆、活塞移动、自然生长都不进入固体放置榜。
- **击杀**：使用服务端自己的归属（与原版击杀归属一致）。同一次死亡不会从直接攻击和统计回调各加一次。
  玩家击杀玩家的分类是 `minecraft:player`。
- **死亡**：只统计真正的死亡。Carpet 的 `/player kill` 对假人执行的是**移除**而不是死亡，因此不计入死亡数，
  但会作为会话结束情况记录为「管理员移除」。
- **村民交易**：按成功执行的交易次数计，不是获得的物品数量。
- **钓获**：空甩竿、空收竿不计；钓到战利品或钩到实体都算一次成功钓获。
- **鞘翅距离**：跨维度不���；单个 tick 位移超过 **20 方块**视为传送或加载跳变，不计入。
  小于该阈值的传送会被计入，这是已知的漏计边界，可用 `/themisstats explain elytra_distance` 查看。
- **模式过滤**：有模式分类的行为指标，公共榜默认只包含 **生存 ＋ 冒险**；创造与旁观的数据仍然被采集和保存，
  可以通过管理员的详细查询查看。`online` **不**按模式过滤，它统计的是各身份实际被采集的在线时长。

### 公开累计的计算公式

```
公开累计 = 符合筛选条件的采集累计 + 该范围内管理员调整值
```

排行榜、全服总计、与前一名的差距、导出**全部**使用这一条公式，因此它们之间不会互相矛盾。
管理员调整落在具体的明细范围（对象 ＋ 身份 ＋ 指标 ＋ 分类 ＋ 维度 ＋ 模式）上，总计由明细汇总得出，
所以方块／实体分类与总计始终一致。原始会话统计与管理员调整是**分开保存**的。

### 排名

- 按指标值降序，采用**竞争排名**（1、2、2、4）。
- 零值对象不占 Top 榜名额，但自己的资料页仍显示零；没有正数记录时显示「暂无排名」。
- 在线与离线对象共同参与累计榜。
- 数字缩写只影响显示；排序、差值与导出使用完整数值。

---

## 5. 命令

### 查询与个人设置

| 命令 | 说明 |
| --- | --- |
| `/themisstats` | 打开主菜单 |
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

`<对象>` 可以是唯一的名字或 UUID。名字对应多个 UUID 时，命令会列出所有候选并要求用 UUID 指定，
**不会按名字合并**。离线对象来自本地已记录的资料，不需要访问外部账号服务。

### 管理命令（需要 OP 等级 2 或控制台）

| 命令 | 说明 |
| --- | --- |
| `/themisstats admin status` | 保存时间、队列、积压、口径版本、适配能力 |
| `/themisstats admin bot owner <对象> <负责人>` | 绑定负责人（记录变更历史，旧负责人立即失去详细访问权） |
| `/themisstats admin bot role <对象> <用途>` | 设置用途（`loader` / `mining` / `combat` / `use` / `custom`） |
| `/themisstats admin bot note <对象> <备注>` | 设置备注（长度与控制字符受限） |
| `/themisstats admin bot tag <对象> <add\|remove> <标签>` | 增删分组标签 |
| `/themisstats admin exclude <对象> <身份> <原因>` | 排除公开榜（不删除记录） |
| `/themisstats admin include <对象> <身份> <原因>` | 撤销排除 |
| `/themisstats admin adjust <对象> <身份> <指标> <+\|-\|=> <数值> <原因>` | 数据修正（**先预览，再确认**） |
| `/themisstats admin audit [页码]` | 管理操作日志 |
| `/themisstats admin flush` | 请求提交待保存数据 |
| `/themisstats admin export <指标> <身份> <csv\|json>` | 导出统计 |
| `/themisstats admin backup` | 创建一致的统计数据库备份 |
| `/themisstats admin confirm <令牌>` | 确认当前待执行的变更 |

**数据修正流程**：`admin adjust` 只做预览，列出受影响的具体明细范围与变化后的数值，并给出一个
**确认令牌**；执行 `/themisstats admin confirm <令牌>` 才会真正写入。令牌绑定操作者、参数与 60 秒过期时间，
**只能使用一次**，确认前参数也无法被修改。修正后的数值不得为负，计数型指标不接受小数。

---

## 6. 权限

| 能力 | 普通玩家 | 假人负责人 | 管理员（OP ≥ 2） | 控制台 |
| --- | --- | --- | --- | --- |
| 查询自己的统计与名次 | 允许 | 允许 | 允许 | 可指定对象 |
| 查询公开榜与他人公开汇总 | 依 `themisStatsPublicQuery` | 依规则 | 允许 | 允许 |
| 调整自己的侧栏 | 依 `themisStatsAllowPlayerChoice` | 依规则 | 允许 | 不适用 |
| 查看假人公开汇总 | 依对应榜单规则 | 同普通玩家 | 允许 | 允许 |
| 查看假人精确位置、装备、详细会话 | 默认不允许 | 仅已绑定对象 | 允许 | 允许 |
| 数据修正、排除、导出、备份 | 不允许 | 不允许 | 允许 | 允许 |

- **假人负责人**是管理员建立的关联记录，**不会**因为假人名称或聊天声明而自动产生，
  也**不会**因此获得 Carpet 的 `/player` 等操作权限。
- `themisStatsBotLocations` 决定精确位置、装备与受限运行细节的可见范围（`ops` / `owners` / `all`），
  管理员始终可查。
- 所有命令、按钮、补全、异步返回、分享、导出和侧栏都遵守同一套权限检查，
  并且**在发送结果之前**会重新检查一次可见范围，因此权限或规则一变，旧的越权页面立刻失效。

---

## 7. 个人侧边栏

- 模式：`off`（关闭）、`fixed`（固定指标）、`carousel`（自动轮播）、`default`（恢复服务器默认）。
- **每个玩家拥有独立的展示资源**：每位玩家有自己的计分板与数据，只向该玩家发送封包。
  本 Mod **不创建队伍、不修改队伍颜色或碰撞规则**，也不修改世界的全局计分板。
- 关闭时只撤销本 Mod 自己拥有的展示资源，不会清空其他 Mod 或插件的计分板。
- 如果世界计分板的侧边栏显示位已被别的提供者占用，本 Mod 会**让出**该显示位而不是争抢。
- 64 位数值使用**文本**显示：业务数值写在条目文字里，计分板分数只作为**排序分**，
  目标使用原版 `blank` 数字格式隐藏分数列。因此 9 000 000 000 这样的数值既不会溢出也不会被截断。
- 轮播间隔与数据刷新间隔相互独立；内容没有变化时不会重复发送相同的展示。
- 玩家断线、模式切换或服务停止时会正确释放本 Mod 的展示资源。

---

## 8. 假人档案、观察与会话

- 档案字段：身份、名称与 UUID、负责人、用途（`loader` / `mining` / `combat` / `use` / `custom`）、
  分组标签、备注、统计资格、最近运行。
- 观察内容：在线状态、维度与坐标、生命、饥饿、主副手物品、可损耗工具耐久、动作计划、最近成功行为与本次累计。
- 采样间隔由 `themisStatsBotSampleSeconds` 控制（默认 2 秒）。采样只读取有限字段，
  **不扫描周边农场，也不加载区块**。
- 动作计划通过针对 Carpet `EntityPlayerActionPack` 的 Mixin 访问器读取（只读）。
  当前版本无法观察的字段会明确标注「当前版本不可观察」，不会猜测。
- **在线、动作计划、观察到的行为是三件不同的事**：配置了攻击动作不代表发生了击杀，
  静止挂机的假人也不会被判定为故障。Mod 不会据此宣称农场正在正常生产。
- 离线对象只显示**最后已知**状态，并明确标注为「离线（最后已知）」，不会伪装成当前装备或当前位置。
- 会话结束情况区分：正常下线、死亡后离线、管理员移除、正常停服、异常中断、原因未知、身份切换。
  **无法确定原因时写「原因未知」，不会猜测成管理员操作。**
- 报告区分实际在线时长、有效采集时长、采集暂停区间、数据完整性与待保存增量。

---

## 9. 数据保存

- 位置：`<世界根目录>/themisstats/stats.db`，每个世界独立，不与其他世界混用。
- 计数使用 64 位整数；时间使用毫秒，距离使用千分之一方块，**不会**用 `int` 截断。
- 正常情况下目标每 5 秒批量提交增量；账号离线与正常停服时请求结算。
- 每一批提交都有稳定身份，重试同一批**不会重复加分**；累计、会话汇总与批次确认在**同一事务**内完成。
- 写入队列有界。队列满时进入**明确的采集降级状态**：记录缺口、统计被丢弃的增量、在
  `/themisstats admin status` 中显示，游戏线程**不会**为了等待磁盘而卡住。
- 异常退出时只恢复**已提交**的数据；遗留会话会被标记为「异常中断」，停机时间**不会**被算成在线时间。
  不宣称零丢失，已保存的缺口信息可以查询。

### 备份与恢复

- `/themisstats admin backup` 使用 SQLite 的一致快照方式（`VACUUM INTO`）创建备份，
  **不会**直接复制正在使用中的主数据库文件而忽略 WAL。
- 备份文件位于 `<世界>/themisstats/backups/`，文件名形如 `stats-backup-20261020-055540.db`。
- 备份**只包含本 Mod 的统计数据**，不等于备份 Minecraft 世界、玩家背包或 Carpet 配置，
  这些需要服主另外纳入服务器备份。
- 恢复请在**停服状态**下进行：先把现有 `stats.db` 复制一份留底，再用备份文件替换。
  恢复前请确认版本与世界对应关系。

### 升级

- 数据格式升级前会自动创建一致备份；迁移失败时中止迁移，不会产生半更新状态，原库不会被覆盖。
- 降级遇到不支持的数据格式时明确拒绝写入。
- 迁移版本记录在 `meta` 表的 `schema_version` 中。

---

## 10. 故障与诊断

| 现象 | 处理 |
| --- | --- |
| 启动时报缺少 Carpet | 安装 Carpet 1.4.194（或兼容版本）后重启 |
| 统计模块停用 | 日志中会有「统计模块已停用」以及原因（数据库损坏或版本过新）。**原库不会被覆盖**，修复后重启 |
| 写入异常 | `/themisstats admin status` 显示最近错误、积压批次数、首次故障时间 |
| 队列满 / 降级 | 状态页显示「采集降级中」与原因；恢复后按原批次提交，不会重复计数 |
| 数据库版本过新 | 本 Mod 拒绝写入并停用统计模块，服务器其他功能照常运行 |
| 侧边栏不显示 | 检查 `themisStatsSidebar`、对应身份的 `Board` 开关、`themisStatsPublicQuery`，以及世界计分板的侧边栏显示位是否被别的提供者占用 |

---

## 11. 性能

小规模 A/B 对照（同一台机器、同一世界、1 个持续挖矿的假人、固定 120 秒窗口）：

| 场景 | 期间查询 | JVM CPU 时间 | 峰值内存 | 掉帧提示 |
| --- | --- | --- | --- | --- |
| 停用 Themis Stats | 0 | 10,703 ms | 1,270.1 MB | 0 条 |
| 启用 Themis Stats | 236 | 10,156 ms | 1,273.7 MB | 0 条 |

在这个规模下，236 次排行榜查询**没有造成任何掉帧**，CPU 时间差异落在运行间噪声内。

**这不是规格里的基准。** 规格建议的 20 真人／100 假人／30 侧栏订阅者的验收负载**未执行**，
本环境无法达到（每个新假人需要约 25 秒的档案解析）。不要把上表外推到那个规模。
详细说明见 [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) 第 5 节。

结构上可以确定的是：导出与备份都在后台线程执行，游戏线程不会为了落盘而等待；
指标采集走成功事件，不每 tick 遍历方块、实体或玩家统计文件。

---

## 12. 未实现（P1 / P2）

P0 **不包含**以下内容，本 Mod 也没有注册任何对应的假命令或无效开关：

- P1：运行告警订阅（`themisStatsBotAlerts`）、`watch` / `unwatch` / `compare` 命令、
  细分方块／实体排行榜、维度与模式筛选的公开榜、`damage_dealt` / `damage_taken` / `movement` /
  `crafted` / `picked_up` / `dropped` / `piglin_barters` 七类增强指标、负责人结束摘要。
- P2：显式数据导入、Scarlet／Java 查询接口、权限插件节点、工程与机器计量扩展。

本 Mod 不自动禁用 `/player`，不批量修改 Carpet 的其他规则，不自动导入原版统计文件，
也不把原版累计总数当作已分账的数据。

---

## 13. 从源码构建与验证

```bash
# 需要 JDK 21
./gradlew build          # 产物：build/libs/themis-stats-0.1.0.jar
./gradlew test           # 单元测试：公开值公式、竞争排名、排除与模式过滤
./gradlew runServer      # 启动一个隔离的测试服务端
```

Windows 下使用 `gradlew.bat`。构建产物包含内置的 SQLite 驱动，发布包**不需要**另外安装依赖。

### 自动化验收

`tools/` 目录下带有可直接运行的验收脚本：

```powershell
# 启动隔离测试服务端，生成真实数据并逐条执行命令
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-server-test4.ps1

# 击杀归属的定向验证
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-kill-test.ps1

# 直接检查统计数据库
python tools/inspect_db.py
```

脚本会轮询直到假人真正上线（Carpet 创建假人时会异步解析游戏档案，机器无法访问 Mojang 会话服务器时
这一步可能耗时约一分钟）。

版本与依赖的核对说明见 [`docs/VERSIONS.md`](docs/VERSIONS.md)，
P0 验收矩阵（含**未执行项**的明确说明）见 [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md)。
