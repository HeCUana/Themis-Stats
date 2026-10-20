# 版本与依赖核对说明

日期：2026-10-21
项目：Themis Stats（忒弥斯统计）`0.1.0`

本文件记录本项目**实际使用**的版本，以及每个版本是如何核对出来的。所有 API 结论都来自
**真实的目标版本源码或反编译产物**，不是凭印象猜测。

---

## 1. 目标版本

| 项目 | 标识 |
| --- | --- |
| 展示名称 | Themis Stats（忒弥斯统计） |
| 仓库／Gradle 项目名 | `themis-stats` |
| Fabric Mod ID／资源命名空间 | `themisstats` |
| Java 基础包名 | `dev.themisstats` |
| 正式根命令 | `/themisstats`（可选别名 `/tstats`） |
| Carpet 规则前缀与分类 | `themisStats` |
| 世界内数据目录 | `<世界根目录>/themisstats/` |
| 数据库文件名 | `stats.db` |
| 首发版本 | `0.1.0`（P0 全部 ＋ P1 运行告警与增强指标 ＋ P2 查询接口与显式导入） |

命名覆盖：构建配置、Fabric 元数据、Java 包、Mixin 配置、资源目录、数据库路径、日志、命令、
规则、README、测试与 JAR 名称。项目中**没有**残留任何服务器品牌命名。

---

## 2. 锁定的版本组合

| 组件 | 版本 | 核对方式 |
| --- | --- | --- |
| Minecraft | 1.21.11 | `https://meta.fabricmc.net/v2/versions/game` 确认存在 |
| Java | 21 | Carpet 1.4.194 的 `build.gradle` 设定 `options.release = 21`；本机使用 Zulu 21 |
| Fabric Loader | 0.18.2（开发用 0.19.5） | Carpet 1.4.194 `gradle.properties` 的 `loader_version=0.18.2`；实测服务端使用 0.19.5 |
| Fabric API | 0.141.6+1.21.11 | Modrinth 上针对 `1.21.11` 的最新发布版，SHA1 `c98467cbbaf4d197377266795ae015f4130d65b6` |
| Carpet | 1.4.194 | Modrinth `version/HzPcczDK`，SHA1 `7825091272979cd8ed4f88f13408fdbba08f9e8d` |
| Fabric Loom | 1.13.6 | `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml`；与 Carpet 使用的 `1.13-SNAPSHOT` 同一版本线 |
| Gradle | 9.1.0 | 与 Carpet 1.4.194 的 `gradle/wrapper/gradle-wrapper.properties` 一致 |
| 映射 | Mojang 官方映射 | Carpet 1.4.194 使用 `loom.officialMojangMappings()`；本项目保持一致 |
| SQLite JDBC | 3.53.4.0 | Maven Central 最新发布版，以嵌套 JAR 形式打入发布包 |

### 为什么用 Mojang 官方映射

Carpet 1.4.194 的 `build.gradle` 中写的是 `mappings loom.officialMojangMappings()`。
本项目采用同一套映射，因此 Carpet 的公开类型与成员
（`carpet.patches.EntityPlayerMPFake`、`carpet.helpers.EntityPlayerActionPack`、
`carpet.fakes.ServerPlayerInterface`、`carpet.api.settings.*`）解析到的名字与核对过的源码**完全一致**，
不存在 Yarn／Mojang 名字错位的风险。

---

## 3. Carpet 适配基线

Carpet 1.4.194 的源码（`https://github.com/gnembon/fabric-carpet` 标签 `1.4.194`）是本项目的适配基线。
本项目实际依赖的 API 与结论如下，全部来自该标签的真实源码：

| 用途 | API | 结论 |
| --- | --- | --- |
| 附属注册 | `CarpetServer.manageExtension(CarpetExtension)` | 必须在 Fabric 的 `main` 入口点调用，早于 Carpet 的 `server` 入口点 |
| 规则注册 | `carpet.api.settings.SettingsManager#parseSettingsClass(Class)` | `CarpetServer.settingsManager` 的静态类型是 `carpet.settings.SettingsManager`，它**继承自** `carpet.api.settings.SettingsManager`，因此可以直接调用 `parseSettingsClass` 把规则加入**主**管理器 |
| 规则命名 | `carpet.settings.ParsedRule` | 新式 `@Rule` 注解的规则名取自**字段名**，因此字段名必须等于规则名 |
| 规则校验 | `carpet.api.settings.Validator` | 整数范围校验器按 Carpet 的 `notifyFailure` 约定实现，非法值拒绝且旧值保留 |
| 规则持久化 | 主管理器 `identifier = "carpet"` | 配置文件为 `<世界>/carpet.conf`，因此 `setDefault` / `removeDefault` 直接生效 |
| 规则翻译 | `CarpetExtension#canHasTranslations(String)` | 主管理器的命名空间是 `carpet.`，键为 `carpet.rule.<规则名>.name/.desc/.extra.<n>` 与 `carpet.category.themisStats` |
| 假人识别 | `carpet.patches.EntityPlayerMPFake#isAShadow` | 与 Carpet 自己的脚本 API `carpet.script.external.Carpet#playerType` 的判定方式一致 |
| 假人连接 | `carpet.patches.FakeClientConnection` | 使用 `EmbeddedChannel`，据此把「其他模组的假人」判为 `unknown` |
| 动作计划 | `carpet.fakes.ServerPlayerInterface#getActionPack()` | 公开接口；`EntityPlayerActionPack#actions` 是私有字段，因此用一个 **Mixin 访问器**只读访问，不使用反射猜字段 |
| Scarpet 函数注册 | `CarpetExtension#scarpetApi(CarpetExpression)` | Carpet 官方的扩展钩子；`CarpetExpression#getExpr()` 拿到 `Expression`，用 `Expression#addCustomFunction(String, ILazyFunction)` 注册即可，不需要 Mixin Carpet 内部 |
| Scarpet 函数签名 | `Fluff.ILazyFunction` | 必须实现 `pure()` 与 `transitive()`；本 Mod 的三个函数都返回 `false`（它们读的是实时数据库，输出会随时间变化），否则 Carpet 会在解析期就把它们当常量折叠 |

### 生命周期顺序（实测确认）

```
Fabric main 入口点        -> ThemisStats#onInitialize -> CarpetServer.manageExtension
Fabric server 入口点      -> CarpetServer.onGameStarted -> 主管理器建立 + 解析 Carpet 自带规则
                              -> 依次调用扩展的 onGameStarted -> 本项目在这里注册 18 条规则
MinecraftServer 构造      -> Commands 构造 -> CarpetServer.registerCarpetCommands -> 扩展的 registerCommands
MinecraftServer.loadLevel -> CarpetServer.onServerLoaded -> 扩展的 onServerLoaded -> 打开数据库
每 tick                   -> CarpetServer.tick -> 扩展的 onTick
```

命令分发器在**世界加载之前**建立，因此 `/themisstats` 的命令树在注册时拿不到数据库服务。
命令实现因此在**执行时**通过 `ThemisStatsRuntime.services()` 解析服务，而不是在注册时持有引用。

### 一次性动作的执行时机（实测确认，脚本化自测必须知道）

Carpet 的一次性动作（`/player <名字> use once`、`drop`、`dropStack` 等）与控制台命令的**生效时刻不一样**：

```
某个 tick:  实体 tick（假人的动作计划在这里执行）   ->  控制台命令队列在 tick 结束后才被清空
```

- 动作由 `EntityPlayerActionPack#tick` 触发，**在实体 tick 期间**执行；
- 控制台命令由 `MinecraftServer#runAllTasks()` 在**该 tick 结束后**统一执行；
- 因此「先转向再右键」如果写在**同一条指令链**里，右键执行时转向还没生效；
  而且 `use once` 是**一次性**动作（`Action#once()` 的 `limit=1`），打空一次就没有了。

**结论**：脚本化自测要让动作依赖的命令**提前一批**发出去，等它落地后再发动作。

> 注意 `/player <名字> drop` 与 `/player <名字> drop mainhand` **不是一回事**：
> 前者是 `makeActionCommand("drop", ActionType.DROP_ITEM)`，走 `Player#drop(boolean)`，
> **下一个 tick 才执行**；后者是 `makeDropCommand`，立即调用 `player.drop(ItemStack, boolean, boolean)`。
> 本 Mod 的 `dropped` 计数点挂在 `ServerPlayer#drop(Z)V` 上，所以**只有前者会被统计**。

### 假人会把自己丢出来的东西捡回去

`ServerPlayer#drop` 生成的掉落物有 **10 tick** 的拾取延迟，假人站在原地就会把它收回主手。
所以「丢整组时记了几个」这种断言，必须在每次丢弃前用 `item replace` 重新铺好一组**已知数量**的物品，
否则主手数量会自己涨回去，断言变成「碰巧对上」。

### 让假人下线：原版 `/kick` 不管用（实测确认）

Carpet 的假人连接 `NetHandlerPlayServerFake` 重写了 `disconnect(Component)`，
**只处理**「闲置」（`multiplayer.disconnect.idling`）与「重复登录」（`multiplayer.disconnect.duplicate_login`）
两种断开消息，其余一律**吞掉**。所以原版 `/kick <假人>` 对假人**没有任何效果**：
假人还在在线列表里，命令也不报错。

真正能让假人下线的是 Carpet 自己的 `/player <名字> kill`，它走
`EntityPlayerMPFake#kill(ServerLevel)` → `kill(Component)` → `connection.onDisconnect(...)`，
再由 Carpet 的 `ServerGamePacketListenerImpl_coreMixin` 触发
`CarpetServer#onPlayerLoggedOut` → 扩展钩子 `CarpetExtension#onPlayerLoggedOut`。
本 Mod 的会话结算与「意外离线」告警都挂在这个钩子上，所以**假人下线模组一定看得到**。

> 结束原因的区分（本 Mod 的 `EntityPlayerMPFakeMixin` 负责标记）：
> - `/player <名字> kill` → 标记 `ADMIN_REMOVAL`（管理员移除）；
> - `kill(Component)`（只有「闲置」「重复登录」两条路径会走到）→ **不标记**，
>   结算时按假人身份落成 `UNKNOWN`（原因未知）。
> 假人在本机只能走前一条路径，所以「原因未知」这一分支**本地无法触发**。

### 假人取不到档案就静默消失

`EntityPlayerMPFake#createFake` 是**先取档案再创建**的：
`fetchGameProfile` → `ResolvableProfile#resolveProfile(server.services().profileResolver())`，
取到档案之后才 `placeNewPlayer`。取档案失败时 `whenCompleteAsync` 的异常分支**直接 return**，
**连一行日志都不打印**，命令看起来成功了，人却没来。

档案缓存在 `server/usercache.json`（名字 → UUID）。本机到 Mojang 的档案服务会超时，
所以只有**已经进过服的名字**能被召唤，而且要等一次超时（实测 3～70 秒）。

---

## 4. Minecraft 1.21.11 的 API 核对

以下结论都来自 1.21.11 官方映射下的反编译源码（Vineflower 1.10.1）：

| 用途 | API | 结论 |
| --- | --- | --- |
| 挖掘 | `ServerPlayerGameMode#destroyBlock(BlockPos)` | 只从挖掘路径（`destroyAndAck`、延迟破坏）进入；命令、爆炸、活塞不经过。返回 `true` 不代表方块真的被移除，因此本 Mod 在调用前后比较方块状态，只有状态真的改变才计数 |
| 放置 | `BlockItem#place(BlockPlaceContext)` | 返回 `InteractionResult.SUCCESS` 才算成功；门／床一次放置只调用一次，因此一次操作计 1；`BucketItem` 不是 `BlockItem`，液体不计入 |
| 死亡 | `ServerPlayer#die(DamageSource)` | **完全覆盖** `LivingEntity#die` 且不调用 `super`，因此玩家的死亡必须单独挂钩，否则会被漏掉 |
| 死亡（非玩家） | `LivingEntity#die(DamageSource)` | 用 `getKillCredit()` 取得服务端自己的归属，与原版击杀归属一致 |
| 交易 | `MerchantResultSlot#onTake` | 在 `Merchant#notifyTrade` 调用点注入，正好是交易真正执行的那一次 |
| 钓获 | `FishingHook#retrieve(ItemStack)` | 在战利品表抽取与 `pullEntity` 两个调用点注入；空收竿两个点都不经过 |
| 鞘翅距离 | `LivingEntity#isFallFlying()` | 逐 tick 采样位置，单 tick 位移超过 20 方块视为传送或跳变 |
| 维度 id | `ResourceKey#identifier()` | 1.21.11 中是 `identifier()`，不是 `location()` |
| 权限 | `Commands.LEVEL_GAMEMASTERS.check(source.permissions())` | 1.21.11 移除了 `CommandSourceStack#hasPermission(int)`，改用 `PermissionSet` |
| 侧边栏文本 | `ClientboundSetScorePacket(String, String, int, Optional<Component>, Optional<NumberFormat>)` | 封包本身带显示文本；配合 `BlankFormat.INSTANCE` 可以把分数列隐藏，只留下 64 位业务数值 |
| 侧边栏渲染 | 客户端 `Gui#displayScoreboardSidebar` | 渲染 `playerScoreEntry.ownerName()` 与 `formatValue(numberFormat)`；`blank` 格式返回空组件，因此数值完全由文本承载 |
| 最多 15 行 | 客户端 `displayScoreboardSidebar` 的 `limit(15L)` | 本 Mod 默认显示 Top 10，安全 |

### P1 七类增强指标的挂点

| 指标 | 挂点 | 结论 |
| --- | --- | --- |
| `damage_dealt` / `damage_taken` | `LivingEntity#actuallyHurt(ServerLevel, DamageSource, float)` 与 **`Player#actuallyHurt(...)`** | **两个都必须挂**：`Player` **覆盖**了 `LivingEntity#actuallyHurt` 并且覆盖版本**不调用** `super`，因此只挂 `LivingEntity` 时玩家（真人与假人）的伤害**一次都不会被记到**。两处各挂一次不会重复计数，因为覆盖版本不会调用被覆盖版本 |
| 伤害口径 | `actuallyHurt` 内的 `setHealth(getHealth() - var10)` | 护甲、抵抗、吸收生命与图腾的结算都在方法内部完成，因此「方法前后血量差」就是**实际扣掉的血**，过量伤害也不会超过当时的血量 |
| `movement` | `Entity#move(MoverType, Vec3)` | 只统计 `MoverType.SELF`；传送与活塞推动是别的 `MoverType`，不会进来 |
| `crafted` | `ResultSlot#onTake(Player, ItemStack)` | 取一次合成结果计 1 |
| `picked_up` | `ItemEntity#playerTouch(Player)` 里的 `Inventory#add(ItemStack)` 调用点 | 数量取「加入前件数 − 加入后件数」；**物品名必须在 `Inventory.add` 之前读**，因为整叠被收下时原版会把栈清空再把件数还原，清空后的 `getItem()` 是 `minecraft:air` |
| `dropped` | `ServerPlayer#drop(Z)V` | 必须写完整描述符，`drop` 有重载。**数量语义**：`drop(false)`（按 Q）只丢**一个**，`drop(true)`（Ctrl+Q）才丢**整组**——`Inventory#removeFromSelected` 只在 `true` 时取整叠数量，所以计数必须跟着标志走，不能直接取 `getSelectedItem().getCount()`。**死亡掉落不计数**：死亡走 `Inventory#dropAll` → `player.drop(ItemStack, boolean, boolean)`，不是这条路径 |
| `piglin_barters` | `PiglinAi#stopHoldingOffHandItem(ServerLevel, Piglin, boolean)` ＋ `PiglinAi#throwItemsTowardPlayer(Piglin, Player, List<ItemStack>)` | 两个方法都是 `static`；猪灵交易的方法在 `PiglinAi` 而不在 `Piglin`。只有 `bl=true` 的那次完成才算交换，归属取实际被丢物品的玩家（`MemoryModuleType.NEAREST_VISIBLE_PLAYER`，由 `PlayerSensor` 写入）。**实测时要避开四个原版机制**：① **1.21.11 的猪灵转换不再由游戏规则控制**——`piglinConversion` 规则已经不存在，转换由 `AbstractPiglin#customServerAiStep` 的 `timeInOverworld > 300` 触发（主世界 15 秒），解法是给猪灵加 NBT 标志 `IsImmuneToZombification:1b`（原版自己就是这么防转换的）；② 附近没金子时猪灵会「恐慌」乱跑，10 秒能跑出 8 格，早就超出 5 格触及范围，所以金锭要先拿在手上；③ **猪灵会自己捡地上的金锭**（`ItemTags.PIGLIN_LOVED`），捡到就进入「欣赏」状态，此时 `PiglinAi#canAdmire` 为假（`ADMIRING_ITEM` 记忆存在），**再右键它也不收**，所以测之前要 `kill @e[type=minecraft:item]`；④ 假人必须是**创造模式**：生存模式下玩家会被当成可攻击目标，猪灵会直接把它打死（实测 `bot_a was slain by Piglin`）。另外 1.21.11 的 `PiglinAi#BARTERING_ITEM` 是**单个物品** `Items.GOLD_INGOT`（不是标签），兑换判定是 `itemStack.is(Items.GOLD_INGOT)` |

### P2 的两个实现要点

| 用途 | API | 结论 |
| --- | --- | --- |
| 含 `:` 的参数 | Brigadier `StringArgumentType#string()` | `word()` 只允许字母、数字与 `_- . +`，**不允许 `:`**；`string()` 不加引号时同样不接受 `:`，**加引号可以**。因此分类参数用 `string()`，写 `minecraft:stone` 时要加引号。**不要自造参数类型**——见下一行 |
| **参数类型必须原版已注册** | `net.minecraft.commands.synchronization.ArgumentTypeInfos` | 原版在 `PlayerList#placeNewPlayer` 里（经 `sendPlayerPermissionLevel` → `Commands#sendCommands`）**为每个进服的玩家序列化整棵命令树**，序列化器要为每个参数类型在 `ArgumentTypeInfos` 里找登记项。自定义类型没登记就抛 `IllegalArgumentException`，而这个异常被 Carpet 静默吞掉 —— 服务端日志里一个字都没有，**所有玩家都进不了世界**。所以本 Mod **只使用原版已注册的类型**：分类用 `StringArgumentType.string()`，维度用 `IdentifierArgument.id()`。注册进 `ArgumentTypeInfos` 也没用：原版客户端不认识自定义类型 |
| 含空格的路径 | Brigadier `StringArgumentType#greedyString()` | `string()` 遇到空格就断，必须用 `greedyString()` |

> 防回归：命令注册后由 `dev.themisstats.command.ArgumentTypeSelfCheck` 扫描整棵命令树，
> 发现原版未登记的参数类型就 ERROR 日志（启动日志实测：「命令树参数类型自检通过，共 966 种，
> 全部为原版已注册类型」）。


---

## 5. 构建与产物

```bash
./gradlew build
```

- 产物：`build/libs/themis-stats-0.1.0.jar`
- 发布包**已经**包含 SQLite JDBC 驱动（以 Fabric 的 `META-INF/jars/` 嵌套 JAR 形式），
  部署时不需要另外安装依赖。
- `fabric.mod.json` 的 `depends` 声明了 `carpet >= 1.4.194` 与 `fabric-api`，
  缺少依赖时 Fabric Loader 会直接拒绝加载，而不是让本 Mod 在缺少 Carpet 的环境里半工作。

---

## 6. 已实测的组合

| Minecraft | Java | Loader | Fabric API | Carpet | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1.21.11 | 21 | 0.19.5 | 0.141.6+1.21.11 | 1.4.194 | 服务端正常启动、加载本 Mod、注册 18 条规则、创建数据库、采集与查询均正常；schema 从 v4 迁移到 v6 实测通过 |

其它组合**未经实测**。
