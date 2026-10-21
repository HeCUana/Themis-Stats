# Minecraft 26.3 升级：依赖矩阵

> 核对日期：2026-10-21　｜　分支：`26.3`（从 `1.21.11` 的 `a841bff` 切出，1.21.11 分支原样保留）
> 全部版本都**实际解析并跑起来了**，来源逐条写在下表；没有用 `latest`／`SNAPSHOT` 之外的动态选择。

## 1. 依赖矩阵

| 项目 | 1.21.11 分支（保留） | 26.3 分支（本次） | 来源与核对方式 |
| --- | --- | --- | --- |
| Minecraft | `1.21.11` | `26.3`（**正式版**） | Mojang 版本清单 `version_manifest_v2.json` 里该条目的 `type` 为 `release`（快照是 `snapshot`）。编译用 `minecraft-merged-deobf-26.3.jar`，运行用 `26.3-server.jar`（SHA-1 `33680f5f…e05f4c`，与清单一致） |
| Java | 21（Zulu 21.0.11） | **25**（Zulu 25.0.4.1 LTS） | Minecraft 26.1 起要求 Java 25。构建 JVM、编译 toolchain、测试 JVM、专用服务端运行**统一**用同一套 JDK 25；产物 class 文件版本 **69**（Java 25） |
| Gradle | 9.1.0 | **9.5.1** | Fabric 26.2 官方文章给的就是 Loom 1.17 + Gradle 9.5.1；26.3 文章写 9.6.0，但本机 **无法下载** 9.6.0（`services.gradle.org` / `downloads.gradle.org` 均不可达），9.5.1 已完整缓存并实际跑通 |
| Loom | `fabric-loom` 1.13.6（**带重映射**） | **`net.fabricmc.fabric-loom` 1.17.21**（**不重映射**） | 26.1 起游戏不再混淆，Fabric 换成了新插件：不再需要 mappings，`remapJar` 换成 `jar`，`modImplementation` 换成 `implementation` |
| Fabric Loader | 0.18.2 | **0.19.5** | Fabric 26.3 官方文章给的当期稳定版；Carpet v26.3 自己的 `gradle.properties` 也是 0.19.5 |
| Fabric API | 0.141.6+1.21.11 | **0.164.0+26.3** | `maven.fabricmc.net` 上 26.3 线最新的是 0.164.0+26.3（另有 0.163.0+26.3、0.160.x+26.3 等） |
| Carpet | 1.4.194（Modrinth maven） | **26.3**（Modrinth maven） | Carpet 官方 `v26.3` 标签的发布制品 `fabric-carpet-26.3+v260915.jar`（1,541,420 字节）；`maven.modrinth:carpet:26.3` 的 pom 可解析 |
| 映射 | `loom.officialMojangMappings()` | **不需要映射**（游戏本体即官方名） | 本项目本来就用 Mojang 官方映射，1.21.11 与 26.3 的命名空间一致，这是本次源码几乎不用改的根本原因 |
| SQLite 驱动 | `org.xerial:sqlite-jdbc` 3.53.4.0 | 同（**未变**） | 仍然 `implementation` + `include`（打进产物），Java 25 下正常 |
| Mixin | 0.8.x（随 Loader） | 0.8.7（`sponge-mixin 0.17.4+mixin.0.8.7`） | 服务端启动日志实读 |

## 2. 关键的上游依据

| 来源 | 用到的结论 |
| --- | --- |
| [Fabric for Minecraft 26.1](https://fabricmc.net/2026/03/14/261.html) | 26.1 起**不再混淆**、Yarn 不再支持、换用不重映射的 `net.fabricmc.fabric-loom`、`modImplementation`→`implementation`、`remapJar`→`jar`、**Java 25 最低要求** |
| [Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html) | Loom 1.17 + Gradle 9.5.1 的基线；方块／物品 id 拆成 `BlockIds` / `BlockItemIds` / `ItemIds` |
| [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html) | Loom 1.17 + Gradle 9.6.0（本文用 9.5.1，理由见上）、Loader 0.19.5；**方块转换（block transformer）成为新的原版数据类型**；燃料／堆肥改成物品组件 |
| [Carpet v26.3 gradle.properties](https://github.com/gnembon/fabric-carpet/blob/v26.3/gradle.properties) | `minecraft_version=26.3`、`loader_version=0.19.5`、`loom_version=1.17-SNAPSHOT`、`mod_version=26.3` |
| [Carpet v26.3 build.gradle](https://github.com/gnembon/fabric-carpet/blob/v26.3/build.gradle) | 插件 id `net.fabricmc.fabric-loom`、**没有 mappings 行**、`implementation` 而非 `modImplementation`、`options.release = 25`、无 `remapJar` |
| [Carpet v26.3 CarpetExtension](https://github.com/gnembon/fabric-carpet/blob/v26.3/src/main/java/carpet/CarpetExtension.java) | 附属接口与 1.4.194 **逐个方法一致**（生命周期、规则、翻译、Scarpet API 钩子） |

## 3. 本机网络与工具链的现实约束

这些**不是**上游问题，是本机环境事实，升级过程中绕开了：

| 现象 | 影响 | 绕法 |
| --- | --- | --- |
| `services.gradle.org` / `downloads.gradle.org` 不可达 | 换不了 Gradle 版本 | 用本地已完整缓存的 **9.5.1**（`~/.gradle/wrapper/dists/gradle-9.5.1-bin`） |
| `raw.githubusercontent.com` / `maven.gnomecraft.com` DNS 不通 | 拿不到 Carpet 源码压缩包、拿不到官方 maven | Carpet 源码走 **GitHub API 的 contents 接口**（带本机凭据，限额 5000/h）逐文件取回；Carpet 制品走 **Modrinth maven** |
| GitHub release 的 CDN（`objects.githubusercontent.com`）超时 | 下不动 JDK / Carpet 的 release 附件 | JDK 25 改从 **Azul CDN** 取；Carpet 制品改从 Modrinth maven 取 |
| Mojang 的大文件下载会中途断流 | Fabric 启动器自己下 62MB 服务端 jar 会失败 | 复用 **Loom 已校验过的**原版服务端 jar（SHA-1 与清单一致），并用 `-Dfabric.customLoaderPath` 指向本地 loader jar |
| 本机没有 Java 25 | 编译与运行达不到官方要求 | 下载 **Zulu 25.0.4.1** 到仓库内 `.toolchains/`（已 gitignore），作为 Gradle JVM、toolchain、测试 JVM 与服务端运行时 |

## 4. 产��物

| 项 | 值 |
| --- | --- |
| 产物名 | `themis-stats-0.2.0+mc26.3.jar`（`themis-stats` 命名沿用仓库现状，版本段带 `+mc26.3` 以便一眼看出目标 MC） |
| Mod 版本 | `0.2.0+mc26.3`（写进 `fabric.mod.json` 的 `version`） |
| class 文件版本 | 69（Java 25） |
| 依赖声明 | `minecraft: ~26.3`、`carpet: ~26.3`、`fabricloader: >=0.19.5`、`java: >=25`、`fabric-api: *` |
