# Minecraft 26.3 升级：现有实现清单（阶段 0 基线）

> 盘点日期：2026-10-21　｜　来源分支：`1.21.11` 的 `a841bff`
> 用途：这份清单是**回归范围**。26.3 适配不是重写，每一项都必须在新版本上仍然成立；
> 未实现的能力不得借适配之名删掉，也不得用占位冒充。

## 1. 项目身份（必须保持不变）

| 项 | 值 |
| --- | --- |
| Mod ID | `themisstats` |
| 入口点 | `dev.themisstats.ThemisStats`（`environment: server`） |
| 根命令 | `/themisstats`，可选别名 `/tstats`（**被占用时不注册**） |
| 规则前缀 | `themisStats`（注册在 Carpet **主**规则管理器下，即 `/carpet` 入口） |
| 规则分类 | `themisStats`（18 条） |
| 世界内数据 | `<世界>/themisstats/stats.db`（内置 SQLite 驱动） |
| 身份枚举 | `human` / `bot` / `shadow` / `unknown` |
| 权限节点 | `themisstats.query`、`themisstats.view_details`、`themisstats.watch`、`themisstats.sidebar`、`themisstats.export`、`themisstats.manage` |

## 2. 指标（15 类，ID 与单位不得变）

| ID | 名称 | 单位 | 可细分 | 细分分类是资源定位型 |
| --- | --- | --- | --- | --- |
| `online` | 统计在线时长 | 毫秒 | 否 | — |
| `mining` | 挖掘量 | 次 | 是 | 是 |
| `placement` | 放置量 | 次 | 是 | 是 |
| `kills` | 击杀量 | 次 | 是 | 是 |
| `deaths` | 死亡数 | 次 | 是 | 是 |
| `villager_trades` | 村民交易 | 次 | 否 | — |
| `fishing` | 钓获次数 | 次 | 否 | — |
| `elytra_distance` | 鞘翅距离 | 千分之一方块 | 否 | — |
| `damage_dealt` | 造成伤害 | 点生命值 | 是 | 是 |
| `damage_taken` | 受到伤害 | 点生命值 | 是 | 是 |
| `movement` | 其他移动 | 千分之一方块 | 是 | 是 |
| `crafted` | 制作物品 | 次 | 是 | 是 |
| `picked_up` | 拾取物品 | 次 | 是 | 是 |
| `dropped` | 丢弃物品 | 次 | 是 | 是 |
| `piglin_barters` | 猪灵交换 | 次 | 否 | — |

## 3. Carpet 规则（18 条，名字、默认值与语义不得变）

| 规则 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `themisStatsRecording` | boolean | `true` | 是否采集指标 |
| `themisStatsSidebar` | boolean | `true` | 是否显示侧栏 |
| `themisStatsHumanBoard` | boolean | `true` | 真人榜是否公开 |
| `themisStatsBotBoard` | boolean | `true` | 假人榜是否公开 |
| `themisStatsShadowBoard` | boolean | `true` | Shadow 榜是否公开 |
| `themisStatsDefaultBoard` | string | `mining` | 默认榜 |
| `themisStatsDefaultGroup` | string | `bot` | 默认身份组 |
| `themisStatsDefaultDisplay` | string | `sidebar` | 默认展示方式 |
| `themisStatsCarouselSeconds` | int | `30` | 轮播间隔 |
| `themisStatsRefreshSeconds` | int | `5` | 侧栏刷新间隔 |
| `themisStatsAllowPlayerChoice` | boolean | `true` | 允许玩家自选榜 |
| `themisStatsPublicQuery` | boolean | `true` | 允许公开查询 |
| `themisStatsBotMonitor` | boolean | `true` | 是否采样假人状态 |
| `themisStatsBotSampleSeconds` | int | `2` | 假人状态采样间隔 |
| `themisStatsBotLocations` | string | `owners` | 谁能看到假人精确位置（`ops`/`owners`/`all`） |
| `themisStatsBotAlerts` | boolean | `false` | 运行告警总开关（**默认关闭**） |
| `themisStatsPublicModes` | string | `survival,adventure` | 进入公共榜的模式 |
| `themisStatsStallPurposes` | string | `mining,combat,use,custom` | 允许触发停滞告警的用途 |

## 4. 命令树

| 顶层命令 | 权限 | 用途 |
| --- | --- | --- |
| `/themisstats top <指标> [身份]` | `query` | 公共榜 |
| `/themisstats fine <指标> [分类] [身份]` | `query` | 细分榜 |
| `/themisstats me` | `query` | 自己的统计 |
| `/themisstats rank` | `query` | 自己的名次 |
| `/themisstats explain <指标>` | `query` | 口径、单位、过滤条件与采集起点 |
| `/themisstats detail <对象> <身份>` | `view_details` | 对象细节 |
| `/themisstats bot <对象>` | `view_details` | 假人档案（装备、动作计划、观测时间） |
| `/themisstats bots` | `query` | 假人列表 |
| `/themisstats sessions <对象>` | `view_details` | 会话列表 |
| `/themisstats session <对象> <身份>` | `view_details` | 最近一次会话 |
| `/themisstats compare <对象> <身份> [指标]` | `view_details` | 最近两次会话对比 |
| `/themisstats watch <对象> <身份> [提醒] [指标]` | `watch` | 订阅告警（**必须由玩家执行**） |
| `/themisstats unwatch <对象> <身份>` | `watch` | 取消订阅 |
| `/themisstats default …` / `off` / `fixed` / `player` / `me` / `carousel` | `sidebar` | 侧栏偏好 |
| `/themisstats admin status` | `manage` | 保存时间、队列、积压、缺口、三类在线数 |
| `/themisstats admin bot owner/role/note/tag` | `manage` | 负责人、用途、备注、标签 |
| `/themisstats admin project <对象> <工程>` | `manage` | 工程归入 |
| `/themisstats admin exclude/include <对象> <身份> <原因>` | `manage` | 排除／恢复公开榜 |
| `/themisstats admin adjust <对象> <身份> <指标> <+|=|-> <数值> <原因>` | `manage` | 数据修正（**先预览再确认**） |
| `/themisstats admin board <指标> <身份> <模式> [分类] [维度] [页码]` | `manage` | 诊断榜 |
| `/themisstats admin import <文件>` / `imported` | `manage` | 显式导入与导入历史 |
| `/themisstats admin audit [页码]` | `manage` | 管理操作日志 |
| `/themisstats admin flush` | `manage` | 立即提交 |
| `/themisstats admin export <指标> <身份> <csv\|json>` | `export` | 导出 |
| `/themisstats admin backup` | `manage` | 一致性备份 |
| `/themisstats admin confirm <令牌>` | `manage` | 确认待执行变更 |

## 5. Mixin / Accessor 清单（16 个）

| 类 | 目标 | 注入点 | 业务作用 |
| --- | --- | --- | --- |
| `BlockItemMixin` | `BlockItem` | `place` | `placement` 计数（成功放置） |
| `EntityMovementMixin` | `Entity` | `move` | `movement` 计数（有效位移） |
| `EntityPlayerActionPackAccessor` | Carpet `EntityPlayerActionPack` | accessor | 读取假人动作计划 |
| `EntityPlayerMPFakeMixin` | Carpet `EntityPlayerMPFake` | `die` / `kill(ServerLevel)` / `createShadow` | 假人死亡、管理员移除、Shadow 切换的结束原因标记 |
| `FishingHookMixin` | `FishingHook` | `retrieve` | `fishing` 计数（成功钓获） |
| `ItemEntityPickupMixin` | `ItemEntity` | `playerTouch` | `picked_up` 计数 |
| `LivingEntityDamageMixin` | `LivingEntity` | `actuallyHurt` | 非玩家的伤害（生物侧） |
| `LivingEntityMixin` | `LivingEntity` | `die` | 生物死亡 |
| `MerchantResultSlotMixin` | `MerchantResultSlot` | `onTake` | `villager_trades` 计数（按**执行次数**） |
| `PiglinBarterMixin` | `PiglinAi` | `stopHoldingOffHandItem` / `throwItemsTowardPlayer` | `piglin_barters` 计数 |
| `PlayerDamageMixin` | `Player` | `actuallyHurt` | 玩家侧伤害（`Player` 覆写了 `actuallyHurt`） |
| `PlayerMixin` | `Player` | `tick` | 在线时长采集点 |
| `ResultSlotMixin` | `ResultSlot` | `onTake` | `crafted` 计数（合成结果被取走） |
| `ServerPlayerDropMixin` | `ServerPlayer` | `drop(Z)V` | `dropped` 计数（原版语义：丢一个记一个、丢整组记整组） |
| `ServerPlayerGameModeMixin` | `ServerPlayerGameMode` | `destroyBlock` | `mining` 计数（真正破坏方块） |
| `ServerPlayerMixin` | `ServerPlayer` | `die` | 玩家死亡与击杀归属 |

> `themisstats.mixins.json` 的 `defaultRequire` 是 **1**：注入不上就直接启动失败，
> 不存在「静默跳过」的余地。

## 6. 数据库（`<世界>/themisstats/stats.db`，SQLite）

| 表 | 内容 |
| --- | --- |
| `meta` | schema 版本 |
| `account` / `account_name` | 账号与历史名字 → UUID 关联 |
| `session` / `session_metric` / `session_pause` | 会话、每次会话的指标、采集暂停区间 |
| `total` | 累计值（主键含来源，采集与导入分家） |
| `collection_window` | 采集窗口 |
| `applied_batch` | 增量批次幂等表（重放不重复加分） |
| `watch_subscription` / `alert_state` / `alert_log` | 告警订阅、状态与日志 |
| `bot_profile` / `bot_owner_history` / `bot_tag` | 假人档案、负责人变更历史、标签 |
| `adjustment` | 管理员修正（与原始采集分开） |
| `exclusion` | 公开榜排除标记 |
| `display_pref` | 侧栏偏好 |
| `audit` | 管理操作审计 |

schema 迁移链：**1 → 2 → 3 → 4 → 5 → 6**，每次迁移前自动 `stats.db.v<N>.bak` 备份。

## 7. 其它必须保住的能力

- **会话语义**：同 UUID 的 human／bot／shadow 分账；身份切换分段；停机时间不计在线；异常结束不补造时长。
- **告警**：五类（低生命值／低耐久／意外离线／指定统计停滞／保存异常）、10 分钟冷却、60 秒宽限期、两次采样确认、恢复只发一次、只发给有权限的订阅者。
- **侧栏**：只做显示，不持久化业务累计；64 位值不因网络包的 int 字段截断；排序分与业务值解耦；不占用 team、不清空别人的 objective。
- **Scarpet 扩展**：`themis_stats`、`themis_stats_of`、`themis_stats_top` 三个函数。
- **线程**：游戏线程不等待落盘；整库访问用可重入锁串行化。
