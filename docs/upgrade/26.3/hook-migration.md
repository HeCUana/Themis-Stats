# Minecraft 26.3 升级：钩子迁移表

> 核对日期：2026-10-21　｜　对应分支：`26.3`
> 原则：**只改编译错误不算完成**。每个钩子都要有「它还触发吗」「它记的对吗」的证据。

## 0. 总体结论

**本 Mod 的 Java 源码在 26.3 上不需要任何改动。**

原因不是「运气好」，而是可以核对的三件事：

1. **命名空间相同**。本项目从一开始就用 `loom.officialMojangMappings()`（Mojang 官方映射），
   而 26.1 起的游戏**本体就是官方名**（不再混淆）。所以「1.21.11 的官方名」与
   「26.3 的名字」天然对齐，**不存在** Yarn→Mojang 的改名工作。
2. **Carpet 的扩展面没有变**。逐文件比对 Carpet 1.4.194 与 v26.3 的源码：
   `CarpetServer.java`（含主规则管理器）、`fakes/ServerPlayerInterface.java`、
   `api/settings/CarpetRule.java` **逐字节相同**（差异行数 0）；
   `CarpetExtension` 接口的每个方法签名也一致。
3. **Mixin 目标没有变**。`themisstats.mixins.json` 的 `defaultRequire` 是 **1**：
   任何一条注入对不上，Mixin 会在启动阶段直接失败。26.3 上服务端**正常启动**，
   意味着 16 个 Mixin 的目标类、方法描述符与注入位置**全部命中**。

## 1. 16 个 Mixin 的逐条结论

| Mixin | 目标类 / 方法 | 26.3 结论 | 证据 |
| --- | --- | --- | --- |
| `BlockItemMixin` | `BlockItem#place` | 命中，语义不变 | 服务端启动无注入失败；`placement` 指标在 26.3 实测 +1 |
| `EntityMovementMixin` | `Entity#move` | 命中，语义不变 | `movement` 指标在 26.3 实测有增长 |
| `EntityPlayerActionPackAccessor` | Carpet `EntityPlayerActionPack` 私有字段 `actions` | 命中 | 假人档案里能读出「动作计划：未配置动作」 |
| `EntityPlayerMPFakeMixin` | Carpet `EntityPlayerMPFake#die` / `kill(ServerLevel)` / `createShadow` | 命中 | 假人死亡记 `deaths`；`/player <名> kill` 后会话以「管理员移除」结束 |
| `FishingHookMixin` | `FishingHook#retrieve` | 命中 | 见第 3 节「未执行」说明（钓获的端到端路径需要真实客户端时机） |
| `ItemEntityPickupMixin` | `ItemEntity#playerTouch` | 命中 | `picked_up` 指标在 26.3 实测 +1（钻石） |
| `LivingEntityDamageMixin` | `LivingEntity#actuallyHurt` | 命中 | 僵尸掉血与 `damage_dealt` 增量对齐（自校验） |
| `LivingEntityMixin` | `LivingEntity#die` | 命中 | 生物死亡路径正常 |
| `MerchantResultSlotMixin` | `MerchantResultSlot#onTake` | 命中 | 交易计数点仍在；端到端交易路径见第 3 节 |
| `PiglinBarterMixin` | `PiglinAi#stopHoldingOffHandItem` / `throwItemsTowardPlayer` | 命中 | `piglin_barters` 在 26.3 实测 +1（先验证猪灵副手真的拿到金锭） |
| `PlayerDamageMixin` | `Player#actuallyHurt` | 命中 | `damage_taken` 按分类精确记录：一次 15 点 magic 伤害 → magic 分类 +15 |
| `PlayerMixin` | `Player#tick` | 命中 | `online` 指标在 26.3 实测有增长 |
| `ResultSlotMixin` | `ResultSlot#onTake` | 命中 | 合成计数点仍在；端到端点击见第 3 节 |
| `ServerPlayerDropMixin` | `ServerPlayer#drop(Z)V` | 命中 | `dropped` 丢一个记 1、丢整组记整组 |
| `ServerPlayerGameModeMixin` | `ServerPlayerGameMode#destroyBlock` | 命中 | `mining` 计数点仍在 |
| `ServerPlayerMixin` | `ServerPlayer#die` | 命中 | 玩家死亡与击杀归属 |

> **26.3 的新机制没有引入重复计数**：方块转换（锄地、铲路、剥皮、打蜡／刮蜡）在 26.3 变成
> 新的原版数据类型，但这些转换**不走** `ServerPlayerGameMode#destroyBlock`（它不是破坏方块）
> 也不走 `BlockItem#place`（它不是放置方块），所以**不会**凭空多出一次挖掘加一次放置。

## 2. Carpet 侧调用点的逐条结论

| 调用点 | 1.21.11 | 26.3 | 结论 |
| --- | --- | --- | --- |
| `CarpetExtension` 生命周期钩子 | `onGameStarted` / `onServerLoaded` / `onTick` / `onPlayerLoggedIn` / `onPlayerLoggedOut` / `onServerClosed` / `registerCommands` / `scarpetApi` / `canHasTranslations` | **完全相同** | 附属注册一次；不重复注册规则 |
| 主规则管理器 | `CarpetServer.settingsManager.parseSettingsClass(...)` | **逐字节相同** | 18 条规则仍注册在 `/carpet` 主入口下 |
| 假人识别 | `EntityPlayerMPFake` + `isAShadow` | 相同（只删了一个 `checkFallDamage` 覆写） | 身份判定不变 |
| 动作计划读取 | `EntityPlayerActionPack`（accessor 读私有 `actions`） | 相同；Carpet 自己的实现改了（`interactOn` → `interact(player, hand, pos)`、`swing` 换成新 API） | 本 Mod 只读字段，不受影响 |
| 权限来源 | `carpet.fakes.ServerPlayerInterface` | **逐字节相同** | 不受影响 |
| 规则注解 | `carpet.api.settings.CarpetRule` / `Rule` / `Validator` / `RuleHelper` | **逐字节相同** | 规则名、默认值、验证器不变 |

## 3. 仍然「未执行」的项目（不是 26.3 引入的，是环境限制）

| 项目 | 为什么仍然未执行 | 26.3 是否更糟 |
| --- | --- | --- |
| `crafted` 增强指标 | 计数点在 `ResultSlot#onTake`，只能由真实客户端在合成台／物品栏里点击取结果触发 | 一样（`ResultSlot` 的位置没变） |
| 村民交易的**完整**交互路径 | 需要真实客户端的配方与交易界面 | 一样 |
| 钓获的端到端路径 | 咬钩窗口只有约 1 tick，自动假人无法稳定命中 | 一样 |
| 鞘翅**实飞**距离 | 公式与边界已有 6 个自动用例，真实飞行需要客户端 | 一样 |
| 侧栏的客户端渲染、按钮点击、分享预览 | 没有原版 26.3 客户端可操作 | 一样（服务端侧 objective 创建无报错） |
| 真人 → Shadow → 本人登录 | Carpet 1.4.194 与 26.3 都禁止给假人创建 Shadow | 一样 |

## 4. 26.3 上**实测发现**的差异（属于游戏机制，不是本 Mod 的缺陷）

| 现象 | 实测 | 对本 Mod 的影响 | 处理 |
| --- | --- | --- | --- |
| 召唤**没进过服的名字**会联网查 Mojang 档案 | Carpet 的 `/player <名字> spawn` → 原版 `OldUsersConverter.convertMobOwnerIfNecessary` → `CachedUserNameToIdResolver.lookupGameProfile`，在**服务端主线程上阻塞式**发 HTTPS 请求；名字不在 `usercache.json` 里就会 404，于是刷出 `Couldn't find profile with name: X`、`Could not lookup user whitelist entry for X`，并出现 `Can't keep up!`（实测一次召唤卡 2.2 秒） | **与本 Mod 无关**（调用栈里没有 themisstats），1.21.11 与 26.3 的实现**完全同构**，不是 26.3 回归 | 把名字预置进服务端 `usercache.json`（`tools/seed_usercache.py`）→ 解析命中本地缓存，**不联网、不报警、不卡顿**（已实测） |
| 假人**回血很快** | 26.3 上假人 15 点伤害后约 4 秒就回满（假人从不吃东西，饱和度不会耗尽，回血一直生效） | 会让「低生命值」告警的两次采样对**时机**敏感 | 告警逻辑未改（仍是连续两次低于阈值）；测试用一次 19 点重击把血量压到 1 点来保证触发 |
| `data get entity <玩家> Health` 读的是**存档副本** | 26.3 上该值明显滞后（读到 20.0 时假人实际已被射到约 2 点），照着它算伤害会**把假人打死** | 只能用于展示，不能当控制依据 | 测试改用本 Mod 自己的观察值（`/themisstats bot` 的「生命／饥饿」，每 2 秒采样活体数据） |
| `attribute <实体> <属性> set <值>` 在 26.3 上不存在 | `/help attribute` → `/attribute <target> <attribute> (get|base|modifier)`：**写入类子命令被移除** | 没法用它把最大生命压到 5 来固定血量 | 同上，只用 `damage` |
| `data merge entity <玩家>` 被拒绝 | 26.3 明确报「Unable to modify player data」 | 没法用「饿着」来停掉回血 | 告警阶段改用**和平难度**（不让敌对生物刷出来），并在结束时恢复难度 |
| **和平难度下召唤不出敌对生物** | 26.3 在 peaceful 下 `summon zombie` **静默失败**（没有任何输出） | 用例里的「召唤一只僵尸做自校验」直接落空 | 自校验用例保持 `easy`，改用**夜晚**避开日照燃烧 |
| 白天的僵尸会**日照燃烧** | 26.3 白天召唤的僵尸几秒内就掉了 3 点血 | 「实际掉血」与指标增量对不上 | 自校验前 `time set night`，结束后恢复 |
| 敌对生物会**淹死假人** | 26.3 的测试世界出生点附近有水，生存模式的假人日志里是 `bot_a drowned`，一死背包就空，掉落／放置用例执行不到 | 用例中断 | 指标阶段先铺一块干燥的平地（`prepare_arena`） |
| 玩家实体死亡后从假人列表里消失 | `data get entity` 返回「No entity was found」 | 测试里读取血量要注意实体是否还在 | 断言改成先读再判；假人被打死时重新召唤并重订阅 |
| **阶段之间会互相影响** | `offline` 阶段把对象负责人换成 `Bot_B`；告警阶段的订阅者是假人自己，可见性规则是 `owners` 时就**收不到**告警（看起来像功能坏了） | 单独跑某个阶段可能莫名其妙不触发 | 每个告警阶段自己把负责人前置条件摆好（绑定给自己） |
| 新世界里**敌对怪物很多** | 26.3 的全新测试世界在出生点周围刷出 **178** 个敌对生物（骷髅射箭、猪灵用矛） | 额外伤害会混进 `damage_taken` 等指标 | 清场从「只清猪灵和僵尸」改成「清掉所有非玩家、非掉落物实体」；伤害断言改为**按分类**核对 |
| 垂直向下右键**放不下方块** | 26.3 的新世界里假人站在平地上，垂直向下右键时方块会卡在它自己脚下，原版直接拒绝 | 放置指标为 0（**是用例摆位问题，不是钩子问题**：搭一堵到眼睛高度的墙后平视放置，主手石头 5→4，`placement` 立刻 +1） | 用例改成「铺平地 + 造墙 + 平视 + 分两批发指令」 |
| `limit=1` 会选到**别的**僵尸 | 世界里新刷出的僵尸会顶替被测的那只，于是「实际掉血」自校验读到别的僵尸 | 自校验失效 | 被召唤的僵尸打上 `e2e` 标签，按标签选中 |

> 模组在这些场景下的**记账是正确的**：一次 15 点的 `magic` 伤害，模组按**分类**精确记成
> `damage_taken / magic = 15`（用统计库当标尺核对过），没有把别的来源混进来。
