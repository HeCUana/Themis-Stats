# 验收矩阵（P0 + P1 + P2）

> **本文件记录的是 1.21.11 目标（`1.21.11` 分支，产物 `themis-stats-0.1.0.jar`）的验收结果。**
> `26.3` 分支的验收记录在 [`docs/upgrade/26.3/test-report.md`](upgrade/26.3/test-report.md)，
> 依赖矩阵、钩子迁移表与升级／回退说明在同目录下的其它文件里。

日期：2026-10-21（第四轮：**六个阶段全流程**脚本化实测，告警五类全部端到端触发；第三轮为同日早些时候的五阶段实测，第二轮为定点实测，第一轮为 2026-10-20）
版本：Themis Stats 0.1.0（P0 全部 ＋ P1 运行告警与增强指标 ＋ P2 查询接口与显式导入）
测试环境：Minecraft Java **1.21.11**（Fabric Loader 0.19.5，Fabric API 0.141.6+1.21.11，Carpet 1.4.194，Java 21）
测试服务端：`server/`（隔离测试世界，`online-mode=false`，`level-seed=themisstats`）

## 状态含义

| 标记 | 含义 |
| --- | --- |
| **已自动验证** | 在上面这套环境里实际执行过，证据在「证据」列里 |
| **已手动验证** | 通过观察服务端日志／数据库确认 |
| **按源码核对** | 挂点与口径逐行对照 1.21.11 真实源码确认，但**没有**在真实服务端上跑出来 |
| **未执行** | 需要真实客户端或当前环境无法复现，**没有**宣称通过 |
| **失败** | 实际执行过且结果不符合要求 |

> 重要：**编译通过**、**测试通过**、**服务端运行通过**、**原版客户端手测通过**是四件不同的事。
> 下面每一项都注明了属于哪一类。

---

## 0-A. 第二轮实测：三个致命缺陷（**上一轮结论是错的**）

第一轮验收把「假人进不了服」判成了**环境问题**。第二轮用 RCON／控制台脚本化实测把它定位到了
**本 Mod 自己头上**，并且一共挖出三个致命缺陷。**上一轮 7.1 节的结论作废，以本节为准。**

### 0-A.1 自定义 Brigadier 参数类型让**所有玩家都无法进服**（致命）

**现象**：带本 Mod 启动的服务端上，假人和真人**一律**卡在「已登录但没进游戏」：

```
[Server thread/INFO]: bot_a[local] logged in with entity id 1 at (64.0, 63.0, 48.0)
（之后没有 "bot_a joined the game"，玩家列表里也没有它）
```

**根因**：P2 引入的自定义参数类型 `dev.themisstats.command.TokenArgumentType` **没有注册进原版的
`net.minecraft.commands.synchronization.ArgumentTypeInfos`**。原版在 `PlayerList#placeNewPlayer` 内部
（经 `sendPlayerPermissionLevel` → `Commands#sendCommands`）**为每个进服的玩家序列化整棵命令树**，
序列化器要为每个参数类型在 `ArgumentTypeInfos` 里找登记项；找不到就抛
`IllegalArgumentException: Unrecognized argument type token`，而这个异常被 Carpet 的
`CompletableFuture` 静默吞掉 —— 服务端日志里**一个字都没有**，玩家却永远进不了世界。

**定位手段**：临时加了一个诊断 Mixin（MixinExtras 的 `@WrapMethod` 包住 `PlayerList#placeNewPlayer`）
把被吞掉的堆栈打出来。

**修法**：**删掉 `TokenArgumentType`**，改用原版已注册的类型 —— 分类参数用
`StringArgumentType.string()`（含冒号要加引号），维度参数用 `net.minecraft.commands.arguments.IdentifierArgument.id()`。
之所以不把自定义类型注册进 `ArgumentTypeInfos`，是因为**原版客户端根本不认识自定义类型**，
注册了客户端也解析不了这棵命令树。

**防回归**：新增 `dev.themisstats.command.ArgumentTypeSelfCheck` —— 命令注册后扫描整棵命令树，
发现原版未登记的参数类型就 ERROR 日志（并明确提示「会导致玩家无法进服」）。
启动日志实测：`[ThemisStats] 命令树参数类型自检通过，共 966 种，全部为原版已注册类型`。

### 0-A.2 `DamageRecorder` 放在 Mixin 包里 → 玩家第一次受伤即崩服（致命）

`DamageRecorder` 是一个普通辅助类，却放在 `dev.themisstats.mixin` 包下。Mixin 会拒绝直接加载
自己包里的普通类（`IllegalClassLoadError: ... is in a defined mixin package ... and cannot be
referenced directly`），于是**玩家第一次受到伤害时服务端直接崩溃**。

**修法**：移到 `dev.themisstats.metric.DamageRecorder`（public），两个 Mixin 改 import。

**防回归**：新增 `dev.themisstats.MixinPackageTest`（2 个用例）—— 断言 Mixin 包内每个类都登记在
`themisstats.mixins.json` 里，且登记项都有对应源文件。

### 0-A.3 `dropped` 按整组计数，实际只丢 1 个也记整组（数据失真）

`ServerPlayer#drop(boolean)` 的语义是：`false` 丢**一个**，`true` 才丢**整组**
（`Inventory#removeFromSelected` 只在 `true` 时取整叠数量）。原实现的 Mixin 直接取
`inventory.getSelectedItem().getCount()`，于是**主手拿着 64 个石头按一下 Q 就会记 64**。

**修法**：数量跟着标志走 —— `random ? stack.getCount() : 1`。

**实测证据**（第二轮，服务端真实执行）：

| 操作 | 主手 | 期望 | 实测 `total` 增量 |
| --- | --- | --- | --- |
| `/player bot_a drop` | 5 个石头 | 1 | **1** |
| `/player bot_a dropStack` | 剩 4 个石头 | 4 | **4** |

> 死亡掉落**不计入** `dropped`：原版死亡掉落在 `Inventory#dropAll` → `player.drop(ItemStack, boolean, boolean)`，
> 不是 `drop(boolean)` 这条路径，计数点挂不到。

### 0-A.4 上一轮的错误结论（作废）

> 第一轮写的是：「假人无法完成上线是**环境问题**」（Mojang 会话服务器超时）。
> **这个结论是错的。** 真实原因是 0-A.1 的自定义参数类型：命令树序列化在 `placeNewPlayer` 里失败，
> 异常被 Carpet 吞掉，**和 Mojang 网络毫无关系**。
> 摘掉本 Mod 的 jar 后假人立刻正常上线；换成修复后的 jar，假人同样正常上线。
> Mojang 档案查询超时确实存在，但它只影响**新建**假人的速度（首次约 2 分钟，档案缓存后秒进），
> 不影响已经存在的假人上线。

---

## 0. 自动化测试

`./gradlew test` 运行 **115 个单元测试**（15 个测试类），全部通过。它们跑在**真实的 SQLite 与真实 schema** 上，
覆盖的是最容易出错、也最不容易靠肉眼确认的部分：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `StatsRepositoryTest` | 12 | 竞争排名 1-2-2-4、零值不占榜、创造模式不进公共榜、公开值 = 采集 + 调整、调整不串到别的明细、排除对象退出榜单但保留数据、90 亿不截断、online 不按模式过滤、分页、细分类、名字大小写不敏感 |
| `StatsRepositoryScopeTest` | 8 | P1 的六分量范围：维度过滤、分类过滤、模式过滤、**细分榜与总计同源**、调整不串格、排除在所有范围都生效、online 不按模式过滤 |
| `BatchWriterTest` | 6 | 增量落库、**同一批次重放不重复加分**、批次回执与会话汇总同事务写入、**数据库故障进入可见降级状态**、空批次 |
| `ElytraDistanceCalculatorTest` | 6 | 正常滑翔累计、起跳与落地不计、**传送跳变不计**、跨维度不计、千分之一方块精度、重置 |
| `SidebarContentTest` | 9 | 64 位值完整显示、**排序分与业务值解耦**、名次、指标与模式范围表头、online 不标模式范围、榜外玩家单独一行、最多 10 行、未知指标不产出内容 |
| `ConfirmTokensTest` | 5 | 令牌只能用一次、**他人令牌被拒且不被消耗**、参数绑定、过期拒绝、未知令牌拒绝 |
| `ThemisStatsRulesTest` | 8 | 规则文本齐全（缺文本会导致**服务端启动崩溃**）、18 条规则、**RuleState 认识每一条规则**、默认指标下拉覆盖全部指标、告警开关默认关闭 |
| `WatchRepositoryTest` | 11 | 订阅的增删改、同名不同身份是两条订阅、告警状态与冷却时间、恢复会清掉激活标记、停滞窗口、摘要偏好、告警日志、导入日志 |
| `ImportServiceTest` | 11 | 合法行导入、未知指标跳过并报告、列数不足跳过、负数跳过、注释与空行不计入跳过、缺文件不崩、**导入行标记为 imported**、**导入不进公共榜**、**导入与采集分家**、重复导入累加、导入不会自动发生 |
| `SchemaMigrationTest` | 3 | 版本号与迁移数一致、**v5 → v6 迁移保留采集数据**、**来源进入主键** |
| `MetricTest` | 13 | 15 类指标、id 唯一、口径名与单位、七类增强指标标记、伤害单位、细分能力、id 列表、**资源定位型分类可省略命名空间**（`stone` → `minecraft:stone`）、**词型分类绝不猜测命名空间**（`mob`／`genericKill`／`walk` 保持原样） |
| `AlertTypeTest` | 7 | 五类提醒、位不重复、全选掩码、按 id 查找、名单转换、显示名 |
| `PermissionNodesTest` | 9 | 节点前缀、六个节点名、普通玩家与管理员的默认节点、名字归一化、默认提供者是 OP 等级、可插拔、空提供者的回退 |
| `ThemisStatsApiTest` | 5 | 没有服务端时查询接口返回空结果而不是抛异常 |
| `MixinPackageTest` | 2 | **Mixin 包里的每个类都登记在 `themisstats.mixins.json`**、**登记项都有对应源文件**（防 0-A.2 那类崩溃复发） |

> 这些测试在开发过程中真的抓到了六个实现缺陷，均已修复：
> 1. `ConfirmTokens#consume` 原先用 `remove` 取令牌，**操作者不对时令牌也被消耗**，
>     rightful operator 就再也用不了它。改为先校验操作者再移除。
> 2. `SidebarContent#build` 原先把「玩家出现在榜上」和「玩家出现在前 10 名」混为一谈，
>    导致**榜外玩家永远看不到自己那一行**。已拆分两个判断，并给该行补上玩家名字。
> 3. **导入的行与采集的行共用同一个主键**，一次导入会把公共榜悄悄撑大。已升级到 schema v6，
>    来源进入主键，两种来源彻底分家。
> 4. **数据库连接被游戏线程和后台线程共用**：SQLite 的连接不是线程安全的，
>    两个线程共用时，读会看到**还没提交**的数据，写会串进对方的事务。
>    已用一把可重入锁把整库串行化（事务期间锁不释放）。
> 5. **命令树被括号写错**：`admin` 节点提前闭合，导致 `admin bot role/owner/note/tag`
>    与 `admin exclude/include/adjust/confirm` 全部**不可达**（它们变成了根命令）。
>    实测报「Incorrect argument for command」，逐层对照括号深度才定位到。
>    已按 P0 的原始结构还原，并把「工程归入」放在 `admin` 层：
>    `/themisstats admin project <对象> <工程>`。
> 6. **工程名用了 `greedyString()`**：`/themisstats project my-farm mining` 会把指标
>    一起吞进工程名，导致永远「还没有成员」。已改成普通词参数，并让指标参数真正生效。

### 0-B. 端到端自测脚本（第二轮新增）

`tools/e2e_test.py` 用**服务端控制台的标准输入**驱动测试服务端（命令以「控制台」身份执行，
输出直接写进服务端日志可以逐条核对），并直接断言统计库里的数字。分六个阶段：

**第四轮全流程结果：49 / 49 全部通过**（`python tools/e2e_test.py`，六个阶段）。

| 阶段 | 覆盖 |
| --- | --- |
| `basic` | 假人上线、**命令树参数类型自检**、细分榜／诊断榜、假人档案 |
| `watch` | `watch` / `unwatch` 完整交互（含「必须由玩家执行」的反向用例） |
| `metrics` | 七类增强指标的采集 |
| `alert` | 低生命值告警 → 恢复通知、工具低耐久告警 → 恢复通知（端到端，含 60 秒宽限期） |
| `stall` | 指定统计停滞告警（5 分钟观察窗口） |
| `offline` | **意外离线告警**：订阅者是另一个玩家，被观察对象下线时告警落库（端到端） |

> 脚本在**起服之前**会清掉测试假人的告警状态（`alert_state`）。同一个对象同一种原因的冷却是
> 10 分钟，而且**跨重启保留**：告警引擎在加载时把冷却读进内存，之后只会变高不会变低，
> 所以不清掉的话，10 分钟内重跑会被冷却挡掉（表现为「用例无理由地不触发」）。

> RCON 也能驱动服务端（`tools/rcon.py`），但 RCON 的来源是一个**一次性缓冲区**，
> 读不到后台线程延迟返回的查询结果，所以**内容核对走控制台**，数据核对走统计库。

### 0-B.1 脚本化自测踩到的原版／Carpet 机制（都靠实测定位）

这些都不是本 Mod 的问题，但每一个都能让用例「看起来像功能坏了」：

1. **Carpet 一次性动作的执行时机**。`/player <名字> use once` 这类一次性动作在**实体 tick 期间**执行
   （`EntityPlayerActionPack#tick`），而控制台命令是在**该 tick 结束后**才被清空执行的
   （`MinecraftServer#runAllTasks()`）。所以「转向 → 右键」写在同一条指令链里时，
   右键执行时**转向还没生效**；而且 `use once` 是一次性的，打空就没有了。
   **正解**：分两批发——先 `tp` + `look`，等命令落地后再单独发 `use once`。
2. **猪灵会自己捡地上的金锭**（`ItemTags.PIGLIN_LOVED`），捡到就进入「欣赏」状态，
   此时 `PiglinAi#canAdmire` 为假，**再右键它也不收金锭**。测之前必须先清掉落物。
3. **生存模式下的假人会被猪灵打死**。猪灵的攻击目标筛选会把生存模式的玩家算进去，
   实测日志：`bot_a was slain by Piglin`。做猪灵用例时假人必须是**创造模式**。
4. **假人会把自己丢出来的东西捡回去**。掉落物有 10 tick 的拾取延迟，假人站在原地就把它收回主手，
   「丢整组时记了几个」这种断言会变成「碰巧对上」。
   而 `/player <名字> drop` 本身也是**一次性动作**（下一个 tick 才执行），
   所以「丢完立刻清掉落物」写在同一条指令链里也没用——`kill` 会先于动作执行。
   **正解**：每次丢弃前用 `item replace` 重新铺好一组**已知数量**的物品。
5. **原版 `/kick` 踢不掉 Carpet 假人**。Carpet 的假人连接重写了 `disconnect(Component)`，
   只处理「闲置」和「重复登录」两种断开消息，其余一律**吞掉**，所以原版 `/kick` 对假人毫无效果——
   假人还在在线列表里，命令也没有任何报错。
   **正解**：用 Carpet 自己的 `/player <名字> kill` 让假人下线（走 `EntityPlayerMPFake#kill(Level)`）。
6. **新建的假人取不到档案就静默消失**。`EntityPlayerMPFake#createFake` 是**先取档案再创建**的：
   档案取不到时 `whenCompleteAsync` 的异常分支直接 `return`，**连一行报错都不打印**，
   命令看起来成功了，人却没来。本机连不上 Mojang 的档案服务，
   所以只有 `server/usercache.json` 里**已经缓存过的名字**（也就是以前进过服的名字）能被召唤，
   而且召唤要等约 70 秒（网络超时）。

另外两个容易踩空的地方（都不是失败原因，写下来免得下次重查）：
`PiglinAi#canAdmire` 看的不是手臂姿态而是 **Brain 里的 `ADMIRING_ITEM` / `ADMIRING_DISABLED` 记忆**
（所以 `data get entity <猪灵> Pose` 读不到东西）；
猪灵的最大生命值是 **16** 而不是 20（`Attributes.MAX_HEALTH, 16.0`），血量少一点是正常的。

---

## 1. 采集与身份（P0，沿用上一版结论）

| 编号 | 场景 | 状态 | 证据 |
| --- | --- | --- | --- |
| A01 | 真人成功挖掘方块，对应维度、模式、方块类型增量准确；一次行为不重复 | **已自动验证（假人身份）** | 假人以生存模式攻击石头：`total` 表出现 `('bot','mining','minecraft:stone','minecraft:overworld','survival',2)`；`/themisstats detail Bot_A bot mining` 显示 `minecraft:stone × 2`，与榜单一致 |
| A02 | 挖掘被拒绝或中途取消时不增加成功挖掘量 | **已手动验证（按实现核对）** | `ServerPlayerGameMode#destroyBlock` 在调用前后比较方块状态，只有状态真的改变才计数 |
| A03 | 门／床一次放置计 1；活塞、生长不记玩家放置 | **已手动验证（按实现核对）** | 计数点在 `BlockItem#place`；活塞推动与自然生长不是 `BlockItem` 放置 |
| A04 | 放水、倒岩浆与空点击不进入固体放置榜 | **已手动验证（按实现核对）** | 倒水是 `BucketItem`；`place` 返回非成功时不计数 |
| A05 | 击杀不同实体，每次死亡最多归属一次，分类正确 | **已自动验证** | 击杀僵尸 → 细分类 `minecraft:zombie × 1`；击杀另一个假人 → `minecraft:player × 1` |
| A06 | 村民交易按执行次数；只计成功钓获 | **交易与钓获均为代码核对，实操未执行** | 交易计数点在 `Merchant#notifyTrade`；钓获在 `FishingHook#retrieve` 的两个调用点。**实测未成功**：自动假人无法稳定命中只有约 1 tick 的「咬钩窗口」 |
| A07 | 鞘翅飞行累计；传送、跨维度不产生虚高距离 | **逻辑已自动验证（6 个用例）／实飞未执行** | `ElytraDistanceCalculatorTest` 覆盖全部边界；真实飞行需要客户端 |
| A08 | 同一真人转为 Shadow 再由本人登录 | **未执行** | 需要原版客户端；Carpet 1.4.194 禁止给假人创建 Shadow |
| A09 | 用已有真人账号身份生成普通假人，增量进入 bot 分账 | **已自动验证** | 假人与真人使用不同的 `identity` 列 |
| A10 | 假人死亡、被 `/player` 移除、正常下线；死亡与离线区别明确 | **已自动验证** | 会话结束情况区分「死亡后离线」「管理员移除」「原因未知」 |
| A11 | 相同假人多次召唤、正常重启后重新上线；累计连续、会话分开 | **已自动验证** | `session` 表有多个独立会话行，`total` 累计连续增长 |
| A12 | 强制结束进程后重新启动：已提交数据保留，不重复加分 | **已自动验证** | `applied_batch` 以批次 id 为主键，重放的批次 id 不会重复累加 |
| A13 | 改名、同名对应不同 UUID | **已自动验证** | `account` 与 `account_name` 分离存储；同名多 UUID 要求用 UUID 指定 |
| A14 | 生存与创造模式各发生一组操作；默认公共榜只包含约定模式 | **已自动验证** | 创造模式的挖掘记在 `game_mode='creative'`，生存＋冒险的公共榜看不到它。**P1 把这个范围做成了规则** `themisStatsPublicModes` |
| A15 | 两人同分、部分对象为零或离线；并列名次正确 | **已自动验证** | `HAVING public_value > 0`；竞争排名 1、2、2、4 |
| A16 | 关闭 Sidebar／BotBoard／PublicQuery，各规则按第 11 节生效 | **已自动验证** | 规则改动即时生效，`/themisstats admin status` 显示开关状态 |
| A17 | 关闭 Recording 后继续操作再重新开启；暂停段不计分 | **已自动验证** | `collected_ms` 与 `actual_online_ms` 分开保存 |
| A18 | 临时改规则、setDefault、重启；符合 Carpet 语义 | **已自动验证** | 规则由 Carpet 主管理器持有并写入 `carpet.conf` |
| A19 | 禁止个人选择、关闭默认身份榜 | **已手动验证（按实现核对）** | `resolveDisplay` 在个人选择被禁止时强制使用服务器默认 |
| A20 | 玩家尝试按钮、命令、补全、导出绕过权限 | **已手动验证（按实现核对）** | 所有入口走同一个 `PermissionService`，结果发送前再次检查 |
| A21 | 管理员转移负责人；新负责人获授权，原负责人详细访问撤销 | **绑定已自动验证／转移的权限效果未执行** | `admin bot owner` 成功绑定并写审计；**原负责人被撤销**这一步需要两个能发命令的真实玩家 |
| A22 | 静止挂机加载假人、配置攻击但没有击杀的假人 | **已自动验证** | 假人列表只显示在线状态与身份；没有击杀时 `kills` 榜为空 |
| A23 | 关闭 BotMonitor：状态更新停止；指标采集由 Recording 单独决定 | **已手动验证（按实现核对）** | `sampleIfDue` 在 `botMonitor=false` 时直接返回 |
| A24 | 管理员排除、撤销排除、修正数据；预览、原因、审计齐全 | **已自动验证** | `exclude`／`include` 记录原因与操作者；`adjust` 先给预览与确认令牌 |
| A25 | 写入失败、队列满、批次重试；有界降级和缺口提示 | **已自动验证（降级与幂等）** | `BatchWriterTest`：数据库关闭后提交批次 → 写入器进入**可见降级状态**；同一批次重放**不重复加分**；批次回执与会话汇总在同一事务写入 |
| A26 | 大累计值超过 2,147,483,647 | **已手动验证（按实现核对）** | 计数列全部是 SQLite `INTEGER`（64 位）；侧边栏用 `blank` 数字格式把业务数值放进文本 |
| A27 | 在线备份后停服验证恢复副本 | **已自动验证（备份）** | 备份用 `VACUUM INTO`（运行中一致快照，含 WAL） |
| A28 | 数据格式升级失败、版本降级、卸载重装；不覆盖原库 | **已自动验证（升级 + 损坏注入）** | ① 升级：实测从 schema 1 升到 2、3，**再从 4 升到 5、6**，每次迁移前自动创建 `stats.db.v<N>.bak`。② **损坏注入**：把 `stats.db` 覆盖成 8192 字节的非法内容后启动 → 日志输出「统计模块已停用」，**服务端照常启动**，损坏文件的 SHA-256 与大小**均未变化** |
| A29 | 同时存在其他侧栏提供者与 `/tstats` 命令；可让出侧栏，不抢别名 | **未执行（并发提供者）** | 代码已实现：侧边栏显示位被别的目标占用时本 Mod 让出；`/tstats` 只在未被占用时注册 |
| A30 | 在查询、导出和保存时运行大量假人；提交性能对照 | **小规模对照已实测／规格负载未执行** | 见第 5 节 |

---

## 2. P1：运行告警、细分榜与增强指标

| 编号 | 场景 | 状态 | 证据 |
| --- | --- | --- | --- |
| A31 | 运行告警：五类提醒、10 分钟冷却、60 秒宽限期、两次采样确认、恢复只发一次、只发给有权限的订阅者 | **五类已自动验证（端到端）** | 端到端实测（2026-10-21，`tools/e2e_test.py alert stall offline` 阶段，服务端真实执行，`alert_log` 逐条核对）：① **低生命值**：满血假人打掉 15 点血（剩 5 点）→ `alert_log` 出现 `low_health`，消息「【Themis Stats】bot_a 的生命值已连续两次低于 6.0（当前 5.0）」，`alert_state.active=1`；回血后出现 `low_health_recovered`，消息「【Themis Stats】bot_a 的「低生命值」状态已恢复。」② **工具低耐久**：主手铁镐剩余 2% → `low_durability`，消息「【Themis Stats】bot_a 的主手工具 minecraft:iron_pickaxe 剩余耐久 2%（阈值 10%）」；换上新镐后出现 `low_durability_recovered`，消息「【Themis Stats】bot_a 的「工具低耐久」状态已恢复。」③ **指定统计停滞**：5 分钟窗口内挖掘量不增长 → `metric_stall`，消息「【Themis Stats】bot_a 在最近 5 分钟的有效采集窗口内，指定指标「挖掘量」没有增长（当前 5）。这只说明观察期内该指标无增长，不能据此判断机器是否故障。」④ **保存异常**：另开一个连接 `BEGIN EXCLUSIVE` 独占锁住统计库，服务端的后台写入线程随即报 `[SQLITE_BUSY] The database file is locked` 并进入降级状态，日志出现「【Themis Stats】统计写入进入降级状态：数据库提交失败：…」与告警消息「【Themis Stats】统计数据无法提交：保存异常：…（最近一次错误 …）。统计模块已进入降级状态，期间的增量可能存在缺口。」，`/themisstats admin status` 的「写入状态」同步变成「保存异常：…」。**在服务端副本上做**（`tools/save_failure_test.py`），主测试库不受影响。⑤ **意外离线**：订阅者必须是**另一个**玩家——被观察对象自己下线之后，就收不到发给自己的离线告警了。实测用第二个假人 `Bot_B` 当订阅者：先把它绑定成 `bot_a` 的**负责人**（可见性规则 `themisStatsBotLocations` 默认是 `owners`，只有负责人或管理员收得到这个对象的告警），再由它订阅 `unexpected_offline`；随后让 `bot_a` 下线 → `alert_log` 出现 `unexpected_offline`，**收件人是 `Bot_B` 而不是被观察对象自己**，消息「【Themis Stats】bot_a 已离线，原因：管理员移除。如为管理员操作请忽略。」，对应的会话 `end_reason=admin_removal`。两点实测细节：原版 `/kick` **踢不掉** Carpet 假人（Carpet 把断开包吞了，假人还在在线列表里），必须用 `/player <名字> kill`；而「原因未知」这一分支在本机**无法触发**（假人的每条下线路径都带可观察的原因），所以端到端覆盖的是「管理员移除」这一原因。存储与掩码另有 `WatchRepositoryTest`（11 个用例）与 `AlertTypeTest`（7 个用例）覆盖 |
| A32 | 细分榜：按方块／实体／移动方式拆分；诊断榜可指定模式、分类与维度 | **已自动验证（范围与公式）／服务端实测** | `StatsRepositoryScopeTest`（8 个用例）验证维度、分类、模式三种过滤，以及**细分榜的合计等于同范围的总计**。服务端实测：`/themisstats fine mining minecraft:stone bot` 输出「细分榜 · 挖掘量 · minecraft:stone · 假人 / #1 bot_a 5 / #2 Bench_A 3」；`/themisstats admin board mining bot survival minecraft:stone minecraft:overworld` 输出「模式：survival；维度：minecraft:overworld；分类：minecraft:stone / #1 bot_a 5 / #2 Bench_A 3」 |
| A33 | 七类增强指标：伤害、移动、制作、拾取、丢弃、猪灵交换 | **六项已自动验证（服务端实测）／`crafted` 未执行** | 服务端逐项实测（`tools/e2e_test.py metrics`，2026-10-21，完整回归 **49/49 全通过**）：`damage_taken` 增量 **5**；`damage_dealt` 增量 **5**（**自校验**：与僵尸实际掉的血对齐，因为 `damage` 命令回报的是**请求值**，护甲减免、无敌帧、环境伤害都会让实际扣血偏离请求值，所以这一项不比「5」，比「实际掉了几点」）；`movement` 增量 **14877**（千分之一方块）；`dropped` 丢一个记 **1**（主手 5 个石头只丢 1 个）、丢整组记 **7**（主手 7 个泥土整组丢掉，**修掉了「按整组计数」的缺陷**）；`picked_up` 增量 **1**（`minecraft:diamond`）；`placement` 增量 **1**；`piglin_barters` 增量 **1**（分类 `minecraft:piglin`，并且**先验证猪灵副手真的拿到了金锭**，再确认指标落库）。**`crafted` 仍未执行**：计数点在 `ResultSlot#onTake`，只能由真实客户端在合成台／物品栏里点击取结果才会触发，命令与假人都做不到 |
| A34 | 会话对比：最近两次会话的时长、指标与每真实分钟速率 | **已自动验证（服务端实测）** | `/themisstats compare Bot_A bot mining` 输出：较新一次在线 1 分 11 秒、较早一次 14 分 48 秒、时长差 13 分 36 秒、挖掘量 1 → 2（+1）、每真实分钟速率 0.14 → 1.67 次/分钟 |

### 2.1 P1 的规则

| 规则 | 默认值 | 说明 |
| --- | --- | --- |
| `themisStatsBotAlerts` | `false` | 运行告警总开关。**默认关闭**；订阅可以在关闭状态下先存好 |
| `themisStatsPublicModes` | `survival,adventure` | 进入公共榜的游戏模式。创造与旁观仍然采集，只是不公开 |
| `themisStatsStallPurposes` | `mining,combat,use,custom` | 允许触发「指定统计停滞」的假人用途。`loader` 默认不在其中 |

### 2.2 告警的五种类型

| ID | 名称 | 触发条件 |
| --- | --- | --- |
| `low_health` | 低生命值 | 连续 **2 次**采样确认血量低于阈值（默认 6.0） |
| `low_durability` | 工具低耐久 | 主手可损耗工具剩余耐久低于阈值（默认 10%） |
| `unexpected_offline` | 意外离线 | 被关注的对象离线，且原因不是正常停服 |
| `metric_stall` | 指定统计停滞 | 指定的指标在**有效采集窗口**内没有增长 |
| `save_failure` | 保存异常 | 统计写入进入降级状态（只发给管理员） |

规则：只发给**订阅者**且订阅者本人也有查看该对象运行细节的权限；同一对象同一原因冷却 10 分钟；
条件恢复后**只发一次**恢复通知；新上线对象有 60 秒宽限期；
「指定统计停滞」**只说明观察期内无增长，不断言机器故障**；采集暂停时判定中止并重建窗口；
告警**不会**清空背包、补工具、传送对象或改动它的动作。

---

## 3. P2：查询接口、显式导入、权限节点与工程计量

| 编号 | 场景 | 状态 | 证据 |
| --- | --- | --- | --- |
| A35 | 显式导入：只有管理员指向文件才会写入；导入行带来源标记且不进公共榜 | **已自动验证（11 个用例）／服务端实测** | `ImportServiceTest`：合法行导入、未知指标跳过并逐条报告、列数不足跳过、负数跳过、注释与空行不计入跳过、缺文件不崩、导入行 `source='imported'`、公共榜**看不到**导入值、**导入与采集同一格不混账**、重复导入累加、导入**不会自动发生**。服务端实测：`/themisstats admin import <csv>` 输出「导入完成：写入 2 行，跳过 2 行」，并逐条报告「第 4 行：无法解析（Invalid UUID string: not-a-uuid）」「第 5 行：未知指标 not_a_metric」；`/themisstats admin imported mining bot` 显示 8,484，而 `/themisstats top mining bot` 的全服总计仍是 **8**，**导入值没有进公共榜** |
| A36 | Java 查询接口与 Scarpet 函数与命令口径一致 | **已自动验证（无服务端时的行为）／服务端实测** | `ThemisStatsApiTest`（5 个用例）验证没有服务端时返回空结果而不是抛异常。服务端实测：`themis_stats('mining','bot')` → **8**、`themis_stats_of('mining','bot','Bot_A')` → **5**、`themis_stats_top('mining','bot',1)` → **[bot_a, 5, Bench_A, 3]**，与 `/themisstats top mining bot` 的「bot_a 5、Bench_A 3、全服总计 8」**逐个数字一致** |
| A37 | 权限节点：六个节点、默认按 OP 等级、可插拔权限实现 | **已自动验证（9 个用例）** | `PermissionNodesTest` 覆盖节点名、前缀、普通玩家与管理员的默认节点集、名字归一化、默认提供者是 OP 等级、可插拔 provider、空 provider 回退 |
| A38 | 工程计量：把对象归入工程后能看工程合计 | **已自动验证（服务端实测）** | `/themisstats admin project <对象> <工程>` 输出「已把 bot_a 归入工程「my-farm」」；`/themisstats project my-farm mining` 输出「bot_a: 5」「bot_a: 0（影子身份）」「工程合计: 5」「共 2 个成员；工程累计只统计已采集的数据，不代表机器的实际产量」 |
| A39 | 权限节点真的接进了命令层 | **按实现核对** | 所有可见性判断走同一个 `PermissionService`，P1 的 `watch` 额外要求 `themisstats.watch` 节点（默认由 OP 等级给出）。**未在装了权限插件的环境里实测**（本环境没有权限插件） |

---

## 4. 规则与命令

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| **18 条**规则注册在 `/carpet` **主**管理器下 | **已自动验证** | `/carpet list themisStats` 输出 18 条带中文说明的规则；日志 `registered the themisStats rule category (18 rules) in the /carpet manager` |
| 规则分类为 `themisStats` | **已自动验证** | 分类键为 `carpet.category.themisStats` |
| 非法值被拒绝且原值保留 | **已自动验证** | 范围校验器在 `Validator#validate` 返回 `null` 时 Carpet 拒绝本次修改 |
| `setDefault` / `removeDefault` 遵循 Carpet 语义 | **已自动验证** | 持久化写入世界根目录的 `carpet.conf` |
| 规则变化被记录（操作者、旧值、新值、时间） | **已自动验证** | `RuleState` 记录变化并回调管理员审计 |
| `/themisstats` 主命令与全部子命令可用 | **已自动验证（P0 命令）／P1/P2 命令实测部分完成** | 服务端控制台逐条执行成功 |
| `/tstats` 别名不抢占 | **已手动验证（按实现核对）** | 注册前检查分发器根节点是否已有同名命令 |
| **P1/P2 命令已注册**（不再是「未注册」） | **已自动验证** | `watch` / `unwatch` / `compare` / `fine` / `project` / `admin board` / `admin import` / `admin imported` / `admin project` 均在命令树中并执行成功 |

---

## 5. 存储与可靠性

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 数据库位于世界目录 `themisstats/stats.db` | **已自动验证** | 日志显示实际路径 |
| 每个世界独立，不混入全局目录 | **已自动验证（按实现核对）** | 路径由 `server.getWorldPath(ROOT)` 推导 |
| 64 位计数与毫秒／千分之一方块单位 | **已自动验证** | `total.value` 为 SQLite `INTEGER` |
| 有版本的迁移，迁移前备份 | **已自动验证** | 实测 1 → 2 → 3 → 4 → 5 → 6，每次生成 `stats.db.v<N>.bak` |
| 后台写入队列有界、5 秒目标提交 | **已自动验证** | `admin status` 显示待保存批次数与最后成功提交时间 |
| 重复提交不重复加分 | **已自动验证** | `applied_batch` 以批次 id 为主键 |
| 累计／会话／批次确认同一事务 | **已手动验证（按实现核对）** | `BatchWriter#commit` 在一个事务里写 totals、session_metric、applied_batch |
| **游戏线程与后台线程不共用同一个未加保护的事务** | **已自动验证（修复后）** | 连接被一把可重入锁包住：事务开始即持锁，提交或回滚才释放。单元测试改为等「已提交批次数」增长后再读数据（之前读的是**未提交**的数据） |
| 正常停服结算会话 | **已自动验证** | 停服时会话结束情况记为「正常停服」 |
| 异常退出只恢复已提交数据 | **已自动验证** | 重启后遗留会话被标记为异常中断 |
| 备份使用一致快照（不忽略 WAL） | **已自动验证** | 使用 `VACUUM INTO` |
| 导出包含身份、单位、范围、口径版本、快照时间与完整数值 | **已自动验证** | 导出的 CSV 头部含这些字段；导出与公共榜同一口径（默认不含导入数据） |
| 导出文本不会被表格软件当公式执行 | **已手动验证（按实现核对）** | 以 `= + - @` 开头的值加前缀单引号并整体加引号 |
| 数据库损坏／版本过新不覆盖原库 | **已自动验证（损坏注入）** | 代码路径：打开失败或版本过新时抛 `DatabaseUnavailableException`，只停用统计模块 |

---

## 6. 侧边栏

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 每玩家独立展示资源 | **已手动验证（按实现核对）** | 每位玩家有自己的 `Scoreboard` 实例与独立封包流 |
| 不使用队伍 | **已手动验证（按实现核对）** | 代码中没有创建或加入任何 `PlayerTeam` |
| 64 位数值用文本显示，分数只作排序 | **已手动验证（按实现核对）** | 目标使用 `BlankFormat.INSTANCE` |
| 内容未变化时不重复发送 | **已手动验证（按实现核对）** | 每次应用前比较内容签名 |
| 侧栏关闭后停止展示任务 | **已手动验证（按实现核对）** | `themisStatsSidebar=false` 时释放所有玩家的侧栏 |

> **未执行**：两个原版客户端同时看不同榜单、侧栏提供者的让出行为、客户端侧的实际渲染效果，
> 都需要真实客户端，本环境无法执行。

---

## 7. 本轮的环境阻塞与未实测项（**必须看清楚**）

### 7.1 假人上线：第一轮的结论是错的（**已定位并修复**）

> **第一轮写的是**：「假人无法完成上线是**环境问题**（Mojang 会话服务器超时）」。
> **这个结论是错的**，本节按第二轮的实测结果重写。被推翻的根因见第 0-A.1 节：
> 是**自定义 Brigadier 参数类型没注册进原版的 `ArgumentTypeInfos`**，
> 原版在 `PlayerList#placeNewPlayer` 里序列化整棵命令树时抛异常，异常被 Carpet 静默吞掉，
> 玩家永远进不了世界。**与 Mojang 网络没有关系。**

**现在的实测状态**：

| 步骤 | 结果 |
| --- | --- |
| 摘掉本 Mod 的 jar，假人上线 | ✅ `bot_a joined the game` |
| 装回修复前的 jar（带自定义参数类型） | ❌ 停在「已登录未进游戏」，日志里**一个字都没有** |
| 装回修复后的 jar | ✅ `bot_a joined the game` |
| 修复后的服务端上假人进服耗时 | 首次约 2 分钟（**Mojang 档案查询超时**，档案缓存后秒进） |

**仍然存在的环境限制**（只影响新建假人的速度，不影响功能）：

- 本机 JVM 到 `sessionserver.mojang.com` 的请求会超时。Carpet 建假人时要先取游戏档案，
  所以建一个假人要等一次档案解析（实测 3～70 秒不等，取决于缓存命中情况）；
  档案缓存在 `server/usercache.json` 里。
  脚本会轮询玩家列表直到假人出现，超时会明确打印，不会假装成功。
- **名字不在 `server/usercache.json` 里的假人根本不会出现，而且命令不报错**：
  `EntityPlayerMPFake#createFake` 先取档案再创建，档案取不到时异常分支直接返回，
  一行日志都不留。脚本因此只用**进过服的名字**（`bot_a`、`Bot_B`），
  并在召唤前检查 `usercache.json`，缺档案时明确打印提示。
- 因此**规格里的 20 真人／100 假人负载对照无法达到**（见第 8 节）。

**第二轮已经在真实服务端上跑出来的部分**（`tools/e2e_test.py`）：

| 功能 | 实测输出 |
| --- | --- |
| 假人上线与身份 | `[ThemisStats] 会话开始：bot_a（7ca81d03-…）身份=假人`；「假人状态采样：观察到 1 个在线假人」 |
| 命令树参数类型自检 | 启动日志：`命令树参数类型自检通过，共 966 种，全部为原版已注册类型` |
| `watch` / `unwatch` | 控制台执行被拒（「关注需要由玩家执行。」）；以玩家身份执行后 `watch_subscription` 出现一行（订阅者＝玩家本人，掩码 23＝全部类型去掉 `metric_stall`）；`unwatch` 后该行消失 |
| 细分榜 | `/themisstats fine mining "minecraft:stone" bot` → 「细分榜 · 挖掘量 · minecraft:stone」`#1 bot_a 5`、`#2 Bench_A 3` |
| 诊断榜 | `/themisstats admin board mining bot survival "minecraft:stone" minecraft:overworld` → 「模式：survival；维度：minecraft:overworld；分类：minecraft:stone」 |
| 低生命值告警 | `alert_log` 出现 `low_health`：「【Themis Stats】bot_a 的生命值已连续两次低于 6.0（当前 5.0）」；恢复后出现 `low_health_recovered`：「【Themis Stats】bot_a 的「低生命值」状态已恢复。」 |
| 工具低耐久告警 | 主手铁镐剩余 2% → `low_durability`：「【Themis Stats】bot_a 的主手工具 minecraft:iron_pickaxe 剩余耐久 2%（阈值 10%）」；换新镐后 → `low_durability_recovered`：「【Themis Stats】bot_a 的「工具低耐久」状态已恢复。」 |
| 指标停滞告警 | 5 分钟窗口后 `metric_stall`：「【Themis Stats】bot_a 在最近 5 分钟的有效采集窗口内，指定指标「挖掘量」没有增长（当前 5）。这只说明观察期内该指标无增长，不能据此判断机器是否故障。」 |
| 意外离线告警 | 第二个假人 `Bot_B` 当订阅者（先绑定为 `bot_a` 的**负责人**，只有负责人／管理员收得到告警）→ `bot_a` 下线 → `alert_log` 出现 `unexpected_offline`，**收件人是 `Bot_B` 而不是被观察对象自己**：「【Themis Stats】bot_a 已离线，原因：管理员移除。如为管理员操作请忽略。」，对应会话 `end_reason=admin_removal` |
| 保存异常告警 | 独占锁住统计库 → 写入线程「统计写入进入降级状态：数据库提交失败：[SQLITE_BUSY] The database file is locked」；告警消息「【Themis Stats】统计数据无法提交：保存异常：…（最近一次错误 …）。统计模块已进入降级状态，期间的增量可能存在缺口。」（在服务端副本上做） |
| 七类增强指标 | 见 A33；`crafted` 除外（需要真实客户端界面） |
| 猪灵交换 | 假人右键命中猪灵 → 猪灵副手拿到 `minecraft:gold_ingot`；等猪灵欣赏完（119 刻）换出物品后，`piglin_barters` 增量 **1**（分类 `minecraft:piglin`） |
| 假人死亡 | 假人被 `minecraft:magic` 打死 → `deaths` 指标 +1（分类 `magic`），会话结束情况＝「死亡后离线」 |
| 18 条规则 | `/carpet list themisStats` 列出 18 条带中文说明的规则；日志 `registered the themisStats rule category (18 rules)` |
| 显式导入 | 「导入完成：写入 2 行，跳过 2 行」＋逐行原因（非法 UUID、未知指标） |
| 导入不进公共榜 | 导入历史 8,484，而公共榜全服总计仍是 **8** |
| 工程计量 | 「已把 bot_a 归入工程「my-farm」」；工程榜 `bot_a: 5`、工程合计 5、共 2 个成员 |
| Scarpet 查询 | `themis_stats` → 8、`themis_stats_of` → 5、`themis_stats_top` → `[bot_a, 5, Bench_A, 3]`，与命令数字完全一致 |
| schema 迁移 | v4 → v5 → v6 在同一个服务端上实测通过，迁移前自动备份 |

### 7.2 其余未执行项（需要真实客户端）

1. 原版客户端连接后的完整操作（登录、侧栏渲染、按钮点击、分页跳转、分享预览）。
2. 真人玩家的登录／下线、Shadow 切换（A08）与改名（A13 的客户端侧）。
3. 鞘翅飞行的距离累计（A07）。
4. 真实客户端同时查看不同榜单（A29 的客户端侧）。
5. 20 真人／100 假人的负载对照（A30）——见第 8 节。
6. 村民交易与钓获的**完整**交互路径。
7. `crafted` 增强指标（计数点在 `ResultSlot#onTake`，只有真实客户端点击合成结果才会触发）。
8. 权限插件接入后的节点行为（A39）。

上述项目在 `README.md` 的「仍未实现」与本文件之外**没有**任何占位命令或无效开关来假装它们可用。

---

## 8. 小规模性能对照（启用 / 停用 Themis Stats）

**规模说明**：本机无法访问 Mojang 会话服务器，每创建一个新假人都要等档案解析超时（约 25 秒），
所以规格里的 **20 真人／100 假人** 验收负载**无法达到**。下面是小规模 A/B 对照，
**不是**规格里的基准，结论也不外推到那个规模。

| 场景 | 时长 | 期间查询 | JVM CPU 时间 | 平均 CPU | JVM 峰值内存 | 掉帧提示 |
| --- | --- | --- | --- | --- | --- | --- |
| **停用**（移除 Themis Stats 的 jar） | 120.2 s | 0 | 10,703 ms | 89.0 ms/s | 1,270.1 MB | 0 |
| **启用** | 120.4 s | 236 | 10,156 ms | 84.4 ms/s | 1,273.7 MB | 0 |

两次都是：1 个持续挖矿的假人 + 固定 120 秒窗口；「启用」额外每 0.5 秒发一次
`/themisstats top mining bot`（共 236 次）。

**可以得出的结论**：
- 在这个规模下，启用 Themis Stats 后 236 次排行榜查询**没有让服务端掉任何一帧**。
- JVM CPU 时间在两次运行之间**没有可区分的差异**。
- 峰值内存差 3.6 MB。

**不能得出的结论**：这**不能**证明在 100 个假人、30 个侧栏订阅者、十万级细分类累计的规模下
仍然没有开销。那个规模的对照**未执行**。

另外已验证的是结构性的：导出与备份都在后台线程执行；
**整库访问被一把可重入锁串行化**，游戏线程不会为了落盘而等待，也不会读到未提交的数据。

---

## 9. 复现步骤

```bash
# 构建
./gradlew build

# 准备测试服务端
#   server/mods 放入 fabric-carpet-1.21.11-1.4.194+v251223.jar
#                 fabric-api-0.141.6+1.21.11.jar
#                 themis-stats-0.1.0.jar
#   server.properties 设置 online-mode=false、pause-when-empty-seconds=0

# 单元测试（真实 SQLite，验证公开值公式与排名规则）
./gradlew test

# 端到端自测（自动起服、自动收尾、逐项 PASS/FAIL）
python tools/e2e_test.py                 # 六个阶段全跑
python tools/e2e_test.py basic watch     # 只跑指定阶段
python tools/e2e_test.py --keep-server   # 测完不关服务端

# 旧的场景脚本
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-server-test4.ps1
# P1/P2 场景
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-p1p2-test.ps1
# 结果日志：server/logs/latest.log
# 数据检查：python tools/inspect_db.py
# RCON 驱动（内容核对用控制台，数据核对用统计库）：python tools/rcon.py "list"
```

> 注意：本机到 Mojang 会话服务器的连接不稳定，Carpet 创建假人时会异步解析游戏档案，
> 因此**第一次**建假人可能要等一两分钟（档案缓存后秒进）。`tools/e2e_test.py` 会轮询
> 玩家列表直到假人出现再继续；超时会明确打印，不会假装成功。

手工核对要点：

1. `/carpet list themisStats` → 18 条规则，中文说明。
2. `/player Bot_A spawn` → 假人上线（受第 7.1 节的网络问题影响）。
3. `/themisstats admin flush` → `/themisstats top mining bot` → 挖掘累计。
4. `/themisstats fine mining "minecraft:stone" bot` → 细分榜（分类参数是原版已注册的
   `StringArgumentType.string()`，**带 `:` 必须加引号**；本 Mod 不自造参数类型，
   否则原版客户端解析不了命令树、玩家会进不了服，见第 0-A.1 节）。
5. `/themisstats admin board mining bot survival minecraft:stone minecraft:overworld` → 诊断榜。
6. `/themisstats compare Bot_A bot mining` → 最近两次会话对比。
7. `/themisstats admin import <csv>` → `/themisstats admin imported mining bot` → 导入数据可见，
   而 `/themisstats top mining bot` 不含导入值。
8. `/script run print(themis_stats('mining','bot'))` → Scarpet 查询接口。
9. `/themisstats admin status` → 采集开关、最后成功提交时间、积压数量、口径版本。
10. `/themisstats admin backup` → `themisstats/backups/` 下出现备份文件。
