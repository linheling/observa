# 能否借 spark / ServerWarashi 补 Observa 的数据缺口

> 仅分析，不含实现。所有"实测"都在本机 `D:\C\MC\test`（NeoForge 21.1.249 / MC 1.21.1 / JDK 21 / Windows 11）完成；
> 分析日期 2026-10-26。实验结束后已把 spark jar 从 `run/mods/` 移除，开发服恢复原状。

> **现状更新（2026-10-27）**：本文件列出的缺口已按 §7 的推荐补完，不再需要 spark/ServerWarashi：
> `/system`（CPU/物理内存/swap/磁盘）、`/memory` 的逐池与逐收集器 GC 明细、`/network`（ping = `ServerPlayer.connection.latency()`，
> 收发帧数与字节走自插的 Netty 管道计数器，**没有用 Mixin**）、`/heap/histogram`（HPROF dump + 自研流式解析，与 `jcmd` 交叉校验）、
> 剖析的行号级 self、JFR 的 `jdk.ExecutionSample` 行号热点与 `allocations.top_sites`，以及**唯一一处 Mixin**
> （`ChunkStatusTasks#generateNoise` 统计区块生成，见 README「`/chunks` 的生成速率」）。
> 因此下文「区块生成速率恒为 null」「需要 Mixin」等判断只代表写作当时的状态。

## 1. 结论

三句话：

1. **spark 的官方 API 只有 6 个方法**（`tps/mspt/cpuProcess/cpuSystem/gc/placeholders`）——TPS/MSPT/GC 三项 Observa 已有（口径不同），CPU 两项虽是真缺口但用 JDK 十行代码就能补，`placeholders` 是给占位符系统用的。它真正独有的是"堆快照/健康报告/火焰图"，而这些**没有机器可读 API**，只有"命令文本 / 上传到 spark 查看器的 protobuf / 落盘 .hprof"三条出口。作为软依赖，能拿到的净新增数据≈0，还会带进第二套鉴权和一个默认开启的后台剖析器。
2. **ServerWarashi 的独有数据确实不可替代**（按区块的 tick 成本归因、ticket/加载器归属、弱加载巡检、watchdog ticket 诊断），但它 **ARR 许可 + 只有客户端地图 API**，服务端没有任何对外取数接口——所以它只能当"指路牌"，不能当数据源。
3. 真正值得做的，是把缺口分成三类分别处理：**① 自己用 JDK/JFR 就能补**（ping、CPU、磁盘、GC 事件、HPROF 直方图、行号级采样、分配调用站点），**② 必须自研 Mixin**（发包计数、按类别的 tick 成本、按区块归属），**③ 干脆不补、只在 `brief.md` 里指路**（per-chunk 成本归因、弱加载巡检、watchdog ticket → 去看 ServerWarashi 的 GUI）。

判定表：

| 问题 | 结论 |
|---|---|
| spark 软依赖能补什么？ | TPS/MSPT/CPU/GC 的**第二口径**（Observa 已有）；`/spark health` 的系统项（磁盘/交换/JVM 参数）需解析文本 |
| spark 的独有能力能通过 API 拿到吗？ | 不能。堆摘要/堆转储/火焰图/`/spark ping` 都不在 API 里 |
| spark 值得做软依赖吗？ | 不推荐主线。若做，只值"检测到就多一行 `/spark health show` 的指路"，不值得写桥接代码 |
| ServerWarashi 能补什么？ | per-chunk tick 成本归因、加载器/ticket 归属、弱加载区块、watchdog 崩溃附加信息 |
| ServerWarashi 能接吗？ | 不能取数（ARR + 无服务端 API）。只能 `ModList.isLoaded` 检测 + 文档指路 |
| 哪些缺口其实自己就能补？ | ping（公开 `latency()`）、CPU/磁盘/GC 事件（`com.sun.management`）、堆 dump（`HotSpotDiagnosticMXBean.dumpHeap`）、行号级采样与分配栈（已有 JFR 录制，只是摘要没展开） |
| 哪些缺口谁也帮不了，只能自己写 Mixin？ | 收发字节/包计数、按类别 tick 成本、按区块成本归属、区块生成速率 |

---

## 2. Observa 现在的数据边界（源码为证）

已有：`/manifest /health /metrics /entities /memory /chunks /snapshot /logs /blackbox /dump /brief(.md)` + 采样剖析 + JFR（5 个事件）+ 黑匣子。

缺口与源码位置：

| 缺口 | 证据 |
|---|---|
| 网络（延迟/收发字节/包速率） | `README.md:106` 的 `/network` 标 ⏳，调用回结构化 404 |
| 每类实例数 / 浅尺寸 | `src/main/java/com/alvar/observa/http/SnapshotJson.java:93` 直接写 `top_classes: null` |
| 该字段的解释还停留在 M3 之前 | `SnapshotJson.java:95` 的 note 说"需要 JFR 录制（planned, milestone M3）"——M3 已完成，但 JFR 的 `ObjectAllocationSample` 给的是**分配量**，不是**存活实例数**，这条 note 现在是错的，需要改口径 |
| 区块生成速率 | `SnapshotJson.java:186` `generated_per_s` 恒为 null |
| CPU / 磁盘 / 交换 / JVM 启动参数 | 无任何字段 |
| GC 事件级信息（cause、每代前后内存、暂停分布） | 只有 `gc_count` 与 `gc_pause_total_ms`（`SnapshotJson.java:160-161`） |
| 采样剖析的粒度 | 只有 `SimpleClassName.methodName`，无行号、无 native、无"只统计慢 tick"过滤 |
| 分配调用栈 | `.jfr` 里有 stack trace，但摘要只按类聚合字节数 |
| 按类别的 tick 成本（区块 tick/自然生成/实体/方块实体/任务/事件处理器） | 只有总 MSPT 与采样占比 |
| 按区块的成本与加载器归属、弱加载区块、watchdog ticket 诊断 | 完全没有；只有"按区块的实体计数" |

---

## 3. spark 解剖（本机实测）

### 3.1 事实

| 项 | 值 |
|---|---|
| 版本 | `spark-1.10.124-neoforge.jar`（Modrinth，2025-02-23 发布，NeoForge 1.21.1 回移植版） |
| 体积 | 3,642,581 B |
| 许可 | `META-INF/neoforge.mods.toml` → `license="GPLv3"` |
| 依赖 | `modId = "neoforge"`, `versionRange = "[20,)"` |
| 默认行为 | 首次启动生成 `config/spark/config.json`：`{"backgroundProfiler": true}` ——**后台剖析器默认常开** |
| 采样引擎 | 本机启动日志：`The async-profiler engine is not supported for your os/arch (windows11/amd64), so the built-in Java engine will be used instead.` → 在 Windows 上退化为内置 Java 采样器 |

### 3.2 API 只有统计量（javap 实测）

```
public interface me.lucko.spark.api.Spark {
  DoubleStatistic<CpuUsage> cpuProcess();
  DoubleStatistic<CpuUsage> cpuSystem();
  DoubleStatistic<TicksPerSecond> tps();
  GenericStatistic<DoubleAverageInfo, MillisPerTick> mspt();
  Map<String, GarbageCollector> gc();
  PlaceholderResolver placeholders();
}
```

- **没有** `ping()`、`heap()`、`profiler()`、`health()`。堆摘要、火焰图、玩家延迟都拿不到。
- 文档也印证：API 页只列 TPS / MSPT / CPU / GC 四类。
- `placeholders()` 是给 PlaceholderAPI 风格的占位符用的，需要额外解析器，和 JSON 目标不符。

### 3.3 丰富数据只有三条出口

1. **命令文本**：`/spark tps`（文本）、`/spark health show [--memory|--network]`、`/spark ping [--player]`、`/spark gc`、`/spark heapsummary`（上传查看器）。
2. **上传到 spark 查看器**：bytebin（`spark-usercontent.lucko.me`），protobuf 格式，需要外网 + 自己实现 protobuf 解析（schema 在 GPL 仓库里）。
3. **落盘文件**：`/spark heapdump [--compress gzip|xz|lzma]` → `.hprof`；`/spark profiler stop --save-to-file` → 配置目录下的二进制采样文件。

### 3.4 命令捕获这条路的实测结论（重要）

spark 把命令注册进原版 Brigadier（javap 证实 `NeoForgeServerSparkPlugin#onCommandRegister(RegisterCommandsEvent)` → `registerCommands(CommandDispatcher<CommandSourceStack>)`），所以**理论上**服务端内部可以 `performPrefixedCommand` 执行它。但本机实测：

| 试验 | 结果 |
|---|---|
| RCON 执行 `spark` | 返回过含 `⚡` 的用法文本（随后 RCON 客户端因 GBK 编码崩了，说明文本确实是 spark 的） |
| RCON 执行 `spark tps` / `spark health show --memory` / `spark ping` | **无任何输出**，服务器日志里也没有 |
| RCON 执行 `spark profiler start --timeout 5 --save-to-file` | 8 秒后 `config/spark/` 下**没有**产出 profile 文件 |

原因基本可以确定是 NeoForge 的 PermissionNode 体系对非玩家源不授予 `spark` 权限（`spark` 注册了 `PermissionNode<Boolean>`，并有 `onPermissionGather`），且拒绝时**静默**。

→ **结论：想靠"跑 spark 命令 + 读文本"来取数，必须自己构造一个高权限 `CommandSource` 并接住 `Component`，不能借 RCON/控制台源。** 这条路可行性未验证，且文本格式随版本漂移。

### 3.5 spark 与 Observa 的重叠

| spark 有 | Observa 已有 | 差异 |
|---|---|---|
| `tps()`（10s/5m/15m 窗口） | `/metrics.tps`（1s/10s/1m） | 窗口不同；两者口径不一致时会让 agent 困惑 |
| `mspt()`（mean + p95） | `p50/p95/p99 + avg_1m` | Observa 更细 |
| `cpuProcess()/cpuSystem()` | 无 | 真缺口（但自己 5 行代码可补，见 §7） |
| `gc()`（次数/平均频率/平均耗时） | `gc_count/gc_pause_total_ms` | spark 多"平均间隔/平均耗时"，仍无 cause |
| `/spark ping`（延迟） | 无 | 真缺口（但底层就是 `player.connection.latency()`，见 §7） |
| `heapsummary`（类实例数/大小） | `top_classes: null` | 真缺口（需自己解析 HPROF） |
| `health`（CPU/磁盘/交换/系统） | 无 | 真缺口（`OperatingSystemMXBean` 直接可补） |
| 火焰图（行号、native、alloc） | 只有帧名 | 真缺口；JFR `ExecutionSample` 可补行号级 |
| `tickmonitor`（按阈值只报慢 tick） | 无（有每 tick 时长但未事件化） | 可自己补 |

---

## 4. ServerWarashi 解剖（GitHub + README 实测）

| 项 | 值 |
|---|---|
| 仓库 | [MoePus/ServerWarashi](https://github.com/MoePus/ServerWarashi) |
| 目标 | Minecraft 1.21.1 **NeoForge** 服务端（与 Observa 同栈） |
| 许可 | **All Rights Reserved（ARR）**：不得复制、修改、再发布或再分发 |
| 命令权限 | `/warashi ...` 全部需要权限等级 `2`（OP） |
| 无兼容客户端时 | 报告**降级为聊天/控制台文本**（README 原文） |
| 唯一公开 API | `com.moepus.serverwarashi.api.client.map` → `MapOverlayApi.register(...)`，**仅客户端** |
| 服务端取数接口 | 源码树 `common/` 下是 command/compat/config/core/mixin/modules/network/report/util/watchdog，`report/` 只有 `ReportButtons`；**没有任何 JSON/HTTP/文件导出 API** |

它的独有数据（Observa 没有）：

1. **按类别的 tick 成本**：区块 tick、自然生成、实体、方块实体、服务器任务、事件处理器、GC 开销——带平均 MSPT、峰值、样本数与坐标。
2. **来源归属**：玩家或区块加载器（FTB Chunks、C2ME no-tick、兼容 ticket 可归因；不认识的记 unknown/system）。
3. **空间快照**：分析期间持续发布有界区块空间数据 + 附近实体/方块实体热点（每玩家上限 2048）。
4. **弱加载区块巡检**：后台分批扫描，估算主要实体/物品类型数量；地图显示热力；清理要二次确认、分批执行、不可撤销。
5. **加载器冻结**：保留原 ticket 但把有效 level 暂停为 33，使区块退出常规 ticking；策略存世界数据。
6. **实体加载保护**：单区块批次超过 5000 时，取最多的 3 类各留 100，其余**自动删除并广播**（默认开，可关）。
7. **watchdog 崩溃报告附加** "ServerWarashi Ticket Info"：各维度 ticket owner 坐标、覆盖区块数、方块实体与实体数。

其采样实现值得一提：1ms 独立线程读预分配的 `PerfWorkTracker`，JFR `ExecutionSample`/`NativeMethodSample` 只作 10ms 低频明细，**明确不回退到 `ThreadMXBean`/`getAllStackTraces()`**——这解释了它为什么能做到"按类别归因"，而 Observa 的 ThreadMXBean 采样天然做不到。

集成的现实：

| 路径 | 可行性 | 判断 |
|---|---|---|
| 调它的命令抓文本 | 纯服务端模式才有文本，且要 OP 源；报告结构面向 GUI，字段随版本变 | 脆弱，不建议 |
| 反射它的内部类取报告对象 | ARR 争议 + 内部字段随时改名 + 与"只读、可审计"的产品定位冲突 | 不建议 |
| 上游加一个服务端导出口 | 需要别人改代码 | 最健康，但不可控 |
| **只检测存在并指路** | `ModList.isLoaded("serverwarashi")` + `brief.md` 里一行"per-chunk 成本在 `/warashi perf open`" | **推荐** |

---

## 5. 六种软依赖接法（可行性矩阵）

| # | 接法 | 适用 | 优点 | 风险 | 建议 |
|---|---|---|---|---|---|
| 1 | 只做存在检测 + 版本 | 两者都适用 | 零耦合、零许可风险 | 无 | **默认方案** |
| 2 | compileOnly 依赖它的 API + 隔离桥接类 | spark | 编译期类型安全 | spark API 只给统计量→收益低；GPLv3 链接争议 | 暂不做 |
| 3 | 服务端内执行它的命令并捕获 `Component` | spark | 能拿到纯文本报告 | 需自建高权限 `CommandSource`；文本格式漂移；RCON 路已验证不通 | 只有 §7 都做完还缺时才考虑 |
| 4 | 反射其内部类 | spark / ServerWarashi | 能拿到最全的数据 | 版本耦合、许可争议、随时崩 | 不做 |
| 5 | 文件通道（`.hprof`、`--save-to-file`） | spark | 干净，不链接它的代码 | `.hprof` 解析要自己写；spark 的 save-to-file 需要权限 | `.hprof` 通道可以，但**自己 dump 更简单**（§7） |
| 6 | 带外 Web（spark 查看器 / bytebin protobuf） | spark | 拿到完整火焰图数据 | 需要外网、要 protobuf schema、把服务器数据传出去 | 不做（违背"只在本机"的设计） |

---

## 6. 缺口 → 谁补 → 推荐

| 缺口 | Observa 现状 | spark 能给 | ServerWarashi 能给 | 推荐 | 量级 |
|---|---|---|---|---|---|
| 玩家延迟 | 无 | 能（文本/无 API） | 无 | **自己补**：`player.connection.latency()`（public，javap 实证） | 小 |
| 收发字节/包速率 | 无（`/network` 404） | 无 | 无 | **自己补**：Mixin `Connection#channelRead0`/`doSend` 计数 | 中 |
| 每类实例数/浅尺寸 | `top_classes: null` | 能（`heapsummary` 上传查看器） | 无 | **自己补**：`HotSpotDiagnosticMXBean.dumpHeap` + 自写 HPROF 直方图 | 中 |
| 泄漏候选（长期存活对象） | 无 | 部分（查看器 heap dump） | 无 | **自己补**：JFR `jdk.OldObjectSample` | 小 |
| CPU / 磁盘 / 交换 / JVM 参数 | 只有 heap+thread | 能（`health show` 文本、`cpuSystem()` API） | 无 | **自己补**：`com.sun.management.OperatingSystemMXBean` | 小 |
| GC 事件级（cause/每代占用/暂停分布） | 只有次数与总时长 | 能（`gcmonitor` 文本、`gc()` 统计） | 无 | **自己补**：`GarbageCollectorMXBean#getLastGcInfo` + `NotificationEmitter` | 小 |
| 行号级 / 异步采样 | 只有帧名 | 能（火焰图，需查看器） | 无 | **自己补**：JFR `jdk.ExecutionSample`（带行号） | 中小 |
| 分配调用站点 | 摘要只有类 | 能（`--alloc` 火焰图） | 无 | **自己补**：把已有 `.jfr` 里的 stackTrace 按站点聚合 | 小 |
| 慢 tick 事件流 | 有数据无事件 | 能（`tickmonitor`） | 部分（报告里有峰值） | **自己补**：对已有每 tick 时长做阈值事件 | 小 |
| 按类别的 tick 成本 | 无 | 部分（火焰图能看，但无分类） | **核心能力** | 自研 Mixin（或等上游 API）——高风险 | 大 |
| 按区块成本 + 加载器归属 | 只有区块实体计数 | 无 | **核心能力** | **不做，指路 ServerWarashi** | 0 |
| 弱加载区块巡检 | 无 | 无 | **核心能力** | **不做，指路** | 0 |
| watchdog 崩溃附加 ticket 信息 | 黑匣子只抓异常与快照 | 无 | **核心能力** | **不做，指路** | 0 |
| 实体自动清理类操作 | 不做（只读边界） | 无 | 有（entity load guard） | **不做**：违反 Observa 只读承诺 | 0 |
| 区块生成速率 | `generated_per_s: null` | 无 | 无 | 自己补（NeoForge 区块事件） | 中 |
| TPS/MSPT 交叉校验 | 自有 | 能（不同窗口） | 无 | 可选：manifest 里标注"spark 也在测"，人可对照 | 小 |

---

## 7. "自己补"的技术依据（JDK 21 javap 实证）

| 需求 | API | 证据 |
|---|---|---|
| 玩家延迟 | `ServerPlayer.connection.latency()` | `ServerCommonPacketListenerImpl`：`private int latency;` + `public int latency();`（spark 自己就是用它，见 `NeoForgePlayerPingProvider.poll()` 字节码） |
| 进程/系统 CPU | `com.sun.management.OperatingSystemMXBean#getProcessCpuLoad()/getCpuLoad()/getProcessCpuTime()` | javap 输出含 `public abstract double getCpuLoad();` `public abstract double getProcessCpuLoad();` `public abstract long getProcessCpuTime();` |
| 物理内存/交换 | 同接口 `getFreeMemorySize()/getTotalMemorySize()/getFreeSwapSpaceSize()/getTotalSwapSpaceSize()` | javap 输出 |
| 磁盘 | `java.nio.file.FileStore#getUsableSpace/getTotalSpace` | JDK 标准 API |
| GC 事件级 | `com.sun.management.GarbageCollectorMXBean#getLastGcInfo()` + `NotificationEmitter` | javap 输出 `public abstract com.sun.management.GcInfo getLastGcInfo();` |
| 堆转储 | `com.sun.management.HotSpotDiagnosticMXBean#dumpHeap(String, boolean)` | javap 输出 `public abstract void dumpHeap(java.lang.String, boolean) throws IOException;` |
| 类直方图 | 需自己解析 `.hprof`（HEADER/HEAP_DUMP_SEGMENT 里的 CLASS_DUMP + INSTANCE_DUMP） | 格式公开；只需读类名、实例数、浅尺寸 |
| 行号级采样 | JFR `jdk.ExecutionSample`（含 stackTrace + lineNumber） | JFR 事件；Observa 已会录 JFR，只是没开这个事件 |
| 分配站点 | JFR `jdk.ObjectAllocationSample` 的 `stackTrace` 字段 | 已有录制里就有，只是 `JfrService.summarize()` 按类聚合了 |
| 泄漏候选 | JFR `jdk.OldObjectSample` | JFR 事件 |

**关键结论：spark 最"独家"的四件事——ping、CPU、GC 事件、堆 dump——全部能用 JDK 公开 API 自己做，且 spark 自己也是这么做的（ping 的字节码就是 `connection.latency()`）。** 真正的门槛只剩"HPROF 解析器"和"行号级火焰图渲染"，而这两件都不需要任何第三方依赖。

---

## 8. 如果将来一定要做软依赖：最小契约

不做代码，只定契约（避免以后出现"混数据"）：

1. `GET /observa/v1/deps`（或 `manifest.deps`）：
   ```json
   {"spark":{"loaded":true,"version":"1.10.124","api":"stats-only"},
    "serverwarashi":{"loaded":true,"version":"x.y.z","api":"none","hint":"/warashi perf open"}}
   ```
   只放"装载事实 + 能做什么"，**不放它们的任何数值**。
2. `brief.md` 加一节「邻家工具（仅在检测到出现）」：说明 per-chunk 成本归因、弱加载巡检、watchdog ticket 去 ServerWarashi 的 GUI 看；spark 的火焰图/堆摘要在其查看器里看。
3. **禁止事项**（写进文档，作为设计约束）：
   - 不把 spark 的 tps/mspt 混进 Observa 的 `tps/mspt`（两个窗口口径不同，混了 agent 会算错）。
   - 不做聊天/控制台文本解析当 API（格式漂移 + 静默失败，本机已验证 RCON 路完全不产出文本）。
   - 不反射第三方内部类。
   - 不把服务器数据上传到第三方服务（spark 查看器/bytebin）。

---

## 9. 许可与分发

| 项 | 现状 | 影响 |
|---|---|---|
| spark | GPLv3（`mods.toml` 明写） | 不得打包/复制其代码；`compileOnly` 链接是否构成衍生有争议（FSF 认为动态链接算）。最安全：不链接、只用文件通道或干脆自己做 |
| ServerWarashi | **ARR** | 不得复制/修改/再分发；连"照着它的实现改一版"都不行。只能检测存在 + 指路 |
| Observa | 仓库里**没有 LICENSE 文件** | 公开前必须先定许可（建议 MIT 或 Apache-2.0）；定了才能谈"与 GPL 组件共存"的表述 |

---

## 10. 待验证清单（如果哪天要推进）

| # | 实验 | 目的 | 成本 |
|---|---|---|---|
| 1 | 用真客户端（OP）在游戏里跑 `/spark tps`、`/spark health show --memory`，看实际文本 | 判断文本解析是否可行、字段是否稳定 | 10 分钟 |
| 2 | 装 spark 后在 4.2 GB 堆上跑 `spark heapdump`，量 STW 时长与文件体积 | 判断"堆 dump 作为诊断动作"是否可接受 | 10 分钟 |
| 3 | 自己构造 OP `CommandSource` 捕获 `Component`，跑 `/spark tps` | 验证 §5 接法 3 | 半天 |
| 4 | 装 ServerWarashi（1.21.1 NeoForge），纯服务端模式跑 `/warashi perf analyze start 10` | 确认是否只有聊天文本（决定"指路"是否够用） | 15 分钟 |
| 5 | 在 `run/mods` 同时放 spark + Observa，空载对比 p99 | 量化"双采样"叠加开销 | 10 分钟 |

本轮已做但未完成的：spark 装进 `run/mods` 后启动正常（日志有 `spark 1.10.124`、`Starting background profiler...`、Java 引擎降级提示），但 RCON 源执行命令无输出 → 已回退并删除 jar。

---

## 11. 建议（按 60/30/10 分配）

- **60%：自己用 JDK/JFR 补** —— `/network` 的延迟（`latency()`）、CPU/磁盘/GC 事件、`top_classes`（`dumpHeap` + HPROF 解析）、行号级 `ExecutionSample`、分配站点聚合、tick 阈值事件。这些不需要任何新依赖，且都和 Observa"只读 + 机器优先"的定位一致。
- **30%：需要 Mixin 才能补** —— 发包计数/带宽（`/network` 的另一半）、按类别的 tick 成本、按区块归属、区块生成速率。这才是真正的硬骨头，应作为 M7 的主体，并明确"高风险、可能做不完"。
- **10%：不补，只指路** —— per-chunk 成本归因、弱加载巡检、watchdog ticket、实体清理类操作。方法：`ModList.isLoaded("serverwarashi")` + `brief.md` 一行说明，零耦合、零许可风险。

一句话：**spark 的软依赖换不来新数据（它的 API 就是 Observa 已有的那几项），ServerWarashi 的软依赖只能换来一行指路；把它们当成"邻居"而不是"数据源"，才是和 Observa 的定位一致的做法。**
