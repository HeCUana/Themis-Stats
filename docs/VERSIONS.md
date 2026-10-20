# 版本与依赖核对说明

日期：2026-10-20
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
| 首发版本 | `0.1.0`（P0 功能范围） |

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

### 生命周期顺序（实测确认）

```
Fabric main 入口点        -> ThemisStats#onInitialize -> CarpetServer.manageExtension
Fabric server 入口点      -> CarpetServer.onGameStarted -> 主管理器建立 + 解析 Carpet 自带规则
                              -> 依次调用扩展的 onGameStarted -> 本项目在这里注册 15 条规则
MinecraftServer 构造      -> Commands 构造 -> CarpetServer.registerCarpetCommands -> 扩展的 registerCommands
MinecraftServer.loadLevel -> CarpetServer.onServerLoaded -> 扩展的 onServerLoaded -> 打开数据库
每 tick                   -> CarpetServer.tick -> 扩展的 onTick
```

命令分发器在**世界加载之前**建立，因此 `/themisstats` 的命令树在注册时拿不到数据库服务。
命令实现因此在**执行时**通过 `ThemisStatsRuntime.services()` 解析服务，而不是在注册时持有引用。

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
| 1.21.11 | 21 | 0.19.5 | 0.141.6+1.21.11 | 1.4.194 | 服务端正常启动、加载本 Mod、注册 15 条规则、创建数据库、采集与查询均正常 |

其它组合**未经实测**。
