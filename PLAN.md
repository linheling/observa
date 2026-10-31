# Observa — 面向 DSH 的 Minecraft 运行时观测模组

> 状态：待确认后开工（v1 计划）
> 目标环境：Minecraft 1.21.1 / NeoForge 21.1.249 / JDK 21
> 工程位置：`D:\C\MC\test`（空目录，需脚手架）

---

## 1. 定位

给 DSH（以及任何愿意读 JSON 的 agent）一双能看进 Minecraft 运行时的眼睛。

与 spark 的差别不是功能多少，而是**消费方是谁**：

| | spark | Observa |
|---|---|---|
| 主要消费者 | 人（网页表格/火焰图） | agent（HTTP JSON） |
| 聚合位置 | 前端 | **服务端算好 Top-N** |
| 输出单位 | 页面文案 | 字段名自带单位（`_ms` / `_bytes` / `_per_s`） |
| 发现能力 | 人点页面 | `/manifest` 自描述契约 |
| 事后回溯 | 无 | **黑匣子环形缓冲** |

非目标（明确不做）：浏览器仪表盘、任意命令执行、指标写数据库、跨服聚合、自动改配置。

---

## 2. 硬约束

- 纯服务端逻辑；单机由集成服务器承载，客户端无需额外安装（但单机必须装在客户端 mods/，因为集成服务器在客户端进程内）。
- HTTP 只绑 `127.0.0.1`，不可配置为外网地址；token 鉴权。
- 常开路径开销 < 1% MSPT；剖析默认不启动。
- 零第三方依赖：JSON 用 MC 自带的 Gson，HTTP 用 JDK 内建 `com.sun.net.httpserver`（模块不可解析时回退 Netty，MC 已自带）。
- 不 Mixin 到世界逻辑；只在「发包统计 / 单 tick 耗时包络 / 崩溃钩子 / 区块队列长度」四处注入。

---

## 3. 架构

```
                    ┌──────────────────────────────┐
  NeoForge 事件 ────▶│  Collectors（常开，纯读）      │
  Mixin（4 处）─────▶│  tick / entity / net / chunk  │──▶ RingBuffer（5 min，1 s 粒度）
                    │  memory / 异常表             │        │
                    └──────────────────────────────┘        │
                                                            ▼
  POST /profiler/* ─▶ Profiler（按需）              Blackbox（阈值/崩溃自动落盘）
                        ├─ Sampler：ThreadMXBean 抓栈 → 折叠 → Top-N
                        └─ JFR：jdk.jfr API 编程录制 → .jfr 文件
                                        │
                                        ▼
                        HttpApi（JDK HttpServer，仅 loopback + token）
                                        │
                                        ▼
                              DSH：curl / web_fetch / 读文件
```

四个组件：**Collectors**（采集）、**RingBuffer + Blackbox**（回溯）、**Profiler**（深挖）、**HttpApi**（出口）。彼此只通过不可变快照对象耦合，任一组件关闭不影响其它。

---

## 4. HTTP 契约

统一前缀 `/observa/v1`。所有响应：

```json
{ "schema_version": 1, "generated_at": "2026-10-25T15:31:02.123Z", "server_tick": 183291, "data": { } }
```

约定：`generated_at` 为 UTC ISO-8601；数值保留 2 位小数；`null` 表示尚未采集（不要用 0 冒充）；错误响应 `{"error":{"code":"...","detail":"...","next":["..."]}}`。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/manifest` | 自描述：模组版本、schema 版本、端点清单、字段说明、能力开关、限制（`max_top_n`、`ring_seconds`） |
| GET | `/health` | 存活、当前 MSPT、采集是否在跑 |
| GET | `/metrics?window=60` | TPS / MSPT 分位（p50 p95 p99）/ 内存 / 实体总数 / 区块数 / 在线玩家 |
| GET | `/entities?by=type\|dimension\|chunk&top=10` | 实体计数排行（掉落物、经验球、方块实体分列） |
| GET | `/network?top=10` | 全局与 per-player 收发包数/字节、往返延迟、超时计数 |
| GET | `/memory?top=20` | 堆/非堆、GC 次数与耗时、**类实例/占用 Top-N**、分配速率 |
| GET | `/chunks` | 已加载区块、生成队列长度、每秒生成数 |
| GET | `/logs?since=60&level=ERROR` | 异常/ERROR 归并计数 + 最近样本堆栈（去重后给次数） |
| GET | `/snapshot` | 上述聚合一次返回（我排查时的默认入口） |
| GET | `/profiler/status` | 是否在录、模式、已跑秒数、预计产物路径 |
| POST | `/profiler/start` | body `{"mode":"sample"\|"jfr","seconds":30,"interval_ms":10}` |
| POST | `/profiler/stop` | 停止并返回摘要：Top-N 方法 + 产物路径 |
| GET | `/profiler/report/{id}` | 已完成的剖析报告 JSON（自研样本） |
| POST | `/dump` | body `{"label":"..."}`：立刻把环形缓冲 + 快照落盘，返回文件路径 |
| GET | `/blackbox` | 历次自动/手动落盘清单（时间、触发原因、文件、大小） |

`/metrics` 样例（字段即文档，agent 不需要再猜单位）：

```json
{ "schema_version": 1, "generated_at": "2026-10-25T15:31:02.123Z", "data": {
  "tps": { "1s": 19.87, "10s": 20.0, "1m": 19.98 },
  "mspt": { "now_ms": 21.4, "avg_1m_ms": 19.8, "p50_ms": 18.2, "p95_ms": 41.7, "p99_ms": 88.3 },
  "memory": { "heap_used_bytes": 1234567890, "heap_max_bytes": 2147483648, "gc_pause_1m_ms": 42 },
  "entities": { "total": 4821, "item_entities": 631, "per_dimension": { "minecraft:overworld": 4102 } },
  "chunks": { "loaded": 1296, "gen_queue": 3, "gen_per_s": 2.1 },
  "players": { "online": 3 }
} }
```

设计要点：**Top-N 一律服务端算好**（默认 10，上限 100）；异常值带标记（如 `"mspt": {"flagged": "p99 > 50ms"}`）；单次响应目标 < 8 KB。

---

## 5. 采集设计

| 数据 | 来源 | 频率 | 备注 |
|---|---|---|---|
| MSPT | Mixin 包 `MinecraftServer.tickServer` 前后计时 | 每 tick | 环形 6000 tick（≈5 min 精准）+ 60 s 1 s 粒度摘要 |
| TPS | 由 MSPT 滚动窗口推算（1s/10s/1m） | 每 tick | 不用 `getAverageTickTime` 的粗值 |
| 实体 | `LevelTickEvent.Post` 计数 + 每 20 tick 按类型遍历 | 20 tick | 掉落物/经验球单列；按 chunk 聚合只在 `/entities?by=chunk` 时现算 |
| 方块实体 | ticker 表长度（Mixin 读取） | 20 tick | 只报数，不遍历 |
| 网络 | Mixin `Connection` 收/发字节计数（per player） | 实时累加，1 s 汇总 | 延迟用玩家 `ping` 字段，不改协议 |
| 内存 | `MemoryMXBean` / `GarbageCollectorMXBean` | 1 s | 类直方图用 JFR `jdk.ObjectAllocationSample`（无 JFR 时降级为 `ClassLoadingMXBean` 计数） |
| 区块 | `ServerChunkCache` 队列长度（Mixin 只读字段）+ 生成计数 | 1 s | |
| 异常 | log4j Appender 拦 ERROR/WARN + 未捕获异常处理器 | 事件 | 堆栈按「首帧 + 消息」归并计数，避免刷屏 |

采样器开销控制：抓栈用 `ThreadMXBean.getThreadInfo(id, depth)` 取主线程 64 帧，10 ms 一次 → ~100 sample/s；不足 1% 单核。

---

## 6. 剖析

**模式 A：自研采样（默认，纯 JSON）**
- 定时抓服务端线程（及可选的 tick 工作线程）栈，栈帧自底向上折叠成调用树
- 输出 `self`（自身占用）与 `total`（含子调用）双榜，单位 `pct`
- 只保留 Top-N（默认 50），过滤 JDK 内部帧（可开关）

**模式 B：JFR（深挖）**
- `jdk.jfr.Recording` 编程启停，配置 `profile` 模板子集：`jdk.ExecutionSample`(1ms)、`jdk.ObjectAllocationSample`、`jdk.JavaMonitorEnter`、`jdk.GarbageCollection`
- 产物写 `<gameDir>/observa/reports/<ts>.jfr`，`/profiler/stop` 返回路径
- DSH 侧用 `jfr print --json --events jdk.ExecutionSample <file>` 转 JSON 解析（JDK 21 自带该工具）
- 同时用 `FlightRecorder.addPeriodicEvent` 把 `jdk.ExecutionSample` 摘要喂给 `/profiler/report`，两边都能读

**并发**：同一时刻只允许一个录制；`start` 时已在录则返回 409 + 当前状态。

---

## 7. 黑匣子

- 环形缓冲：60 s 粒度 1 份 × 300 帧 = 5 min（可配 `ring_seconds`）
- 触发条件（任一）：
  1. MSPT > 50 ms 连续 5 s（阈值可配）
  2. 服务端未捕获异常 / 崩溃
  3. 手动 `POST /dump`
- 落盘：`<gameDir>/observa/blackbox/<yyyyMMdd-HHmmss>-<reason>.json.gz`，内容 = 环形缓冲 + 触发时刻快照 + 最近 20 条异常 + 上下各 10 行日志尾巴
- 崩溃路径两重保障：`Thread.setDefaultUncaughtExceptionHandler` + `Runtime.addShutdownHook`（正常停服也写一份，开关可控）
- 保留最近 10 份，超出自动删最旧

---

## 8. 配置（`ModConfigSpec`，COMMON）

键名与默认值（沿用你 Footpaths 的键名风格）：

```toml
enabled = true
port = 8724                  # 占用则自动 +1 重试，最多 10 次，实际端口写日志
token = ""                   # 空 = 首次启动自动生成 32 位并回写本文件
metrics_interval_ms = 1000
ring_seconds = 300
top_n_default = 10
top_n_max = 100
blackbox_enabled = true
blackbox_mspt_threshold_ms = 50
blackbox_sustain_seconds = 5
blackbox_on_shutdown = false
profiler_max_seconds = 300
profiler_sample_depth = 256   # 栈深截断会造出假根，见 §10.1
profiler_include_jdk_frames = false
rate_limit_per_second = 20
tunnel_enabled = true               # 服务端是否允许隧道（M6）
tunnel_permission_level = 2         # 需要的最低权限等级（2=OP）；0=任何在线玩家
```

安全：`bind` 地址硬编码 `127.0.0.1`（不开放为配置项，避免被改成 `0.0.0.0`）；鉴权头 `Authorization: Bearer <token>`，缺失/错误返回 401 且不泄露 token 长度；请求体上限 4 KB；全局 20 req/s 限速；不实现 CORS、不实现命令执行。

---

## 9. 代码结构

```
com.alvar.observa
├── Observa.java              @Mod 入口、注册、生命周期
├── config/ObservaConfig.java
├── http/  HttpServerBootstrap, Router, AuthFilter, RateLimiter, JsonWriter
├── api/   MetricSnapshot, EntityStats, NetworkStats, MemoryStats, ChunkStats,
│          ProfilerReport, BlackboxEntry, Manifest（全部不可变 record）
├── collect/ TickMeter, EntityCollector, NetworkCollector, MemoryCollector,
│          ChunkCollector, LogCollector, ExceptionRegistry
├── profiler/ Sampler, StackFolder, JfrRecorder, ProfilerService
├── blackbox/ RingBuffer, BlackboxWriter, TriggerMonitor
├── mixin/  MinecraftServerTickMixin, ConnectionStatsMixin, ChunkQueueMixin,
│          CrashHookMixin
└── command/ ObservaCommand（/observa status|dump|profiler|blackbox，方便你自己在游戏里用）
```

---

## 10. 里程碑与验收

| 里程碑 | 内容 | 验收标准 | 状态 |
|---|---|---|---|
| **M0 骨架** | `mc_scaffold` 生成 NeoForge 1.21.1 工程；mod 入口 + 配置 + HTTP 引导；验证 `com.sun.net.httpserver` 在 FML 启动层可用 | `runServer` 起服后 `curl 127.0.0.1:8724/observa/v1/manifest` 返回 200 且 JSON 合法 | ✅ 2026-10-25 实测通过 |
| **M1 常开指标** | TickMeter + 实体/内存/区块/玩家采集；`/metrics /entities /memory /chunks /snapshot` | `runServer` 跑 5 min，`/metrics` 的 tps 与 `/forge tps` 同级误差 < 0.5；实测开销 < 1% | ✅ 已完成 |
| **M2 采样剖析** | Sampler + StackFolder + `/profiler/start\|stop\|status\|report`；人为造一个 20 ms/tick 的热点（命令方块 `fill` 循环） | 剖析结果 Top-1 命中该热点方法；报告 < 8 KB | ✅ 已完成（报告实际 10.3 KB，预算已修正为 16 KB） |
| **M3 JFR** | JfrRecorder + 报告落盘；`jfr print --json` 解析链路打通 | 录制 10 s 后产物可被 `jfr summary` 读取且事件数 > 0 | ✅ 已完成（多轮实测 10–12s 录制 266–332 KB / 3.3k–4.4k 事件 / 摘要含分配·GC·异常·锁·park） |
| **M4 黑匣子 + 文档** | RingBuffer + 触发 + 落盘 + `/blackbox`；异常归并；README + 诊断 SOP + curl 示例 | 触发阈值后 3 s 内出现 `.json.gz`；按 SOP 能在 2 次请求内定位 M2 的热点 | ✅ 已完成（自动触发实测 6s 落盘——5s 持续判定 + 1s 轮询；README/DIAGNOSIS 已补 M3/M4） |
| **M5 入职链接** | `Brief`：进存档自动写 `<gameDir>/observa/agent.md` + `TASK.md` 模板，日志打印 URL；端点 `GET /brief.md`（Markdown）与 `GET /brief`（JSON） | 日志两行、文件存在、`brief.md` 含任务/状态/端点/配方/口径 | ✅ 已完成（`brief.md` 5523 B，TASK.md 嵌入与模板判定实测通过；见 §10.1） |
| **M6 连接隧道** | 自定义 payload 请求/应答（分块 24000 字符）+ 客户端本地 127.0.0.1 端点；权限 `tunnel.permission_level` 默认 2；`Hello` 握手；`TunnelServer` 支持只读子集 | 真客户端 `--quickPlayMultiplayer` 连服后，在玩家本机 curl 到 `via:"tunnel"` 的服务端数据 | ✅ 已完成（端到端实测：9 个端点 200、`/dump` 服务端落盘、45.8 KB brief 分块重组、deop→403/op→200；见 §10.1） |
| **M7 主机侧补全** | `/system`（CPU/物理内存/swap/磁盘）、`/memory` 的 GC 明细、`/network`（ping + 收发帧/字节与速率）、`/heap/histogram`（HPROF dump + 自研流式解析）、剖析 `lines[]` 行号级 self、JFR `jdk.ExecutionSample` 与 `allocations.top_sites` | 端点返回真实值；堆直方图实例数与 `jcmd GC.class_histogram` 逐类对齐；行号级热点与已知 fill 路径一致 | ✅ 已完成（见 §10.2；`/network` 已用真客户端验证） |
| **M8 可靠性修复（观测工具自身）** | `/logs` 容量与诚实计数（5000 条、4MB/poll、64MB 才跳读，暴露 `entries_dropped`/`log_bytes_skipped`）；环形缓冲改「心跳推进冻结帧」，卡死时仍在记录并新增 `reason="stall"` 自动落盘；自动落盘加启动宽限（`min_server_ticks`/`grace_seconds`）与 `[debug] allow_selftest` + `POST /selftest/stall` 回归入口 | 700 条合成 ERROR 全量可见（712 retained）；6 秒停摆期间 4→8 帧 `frozen`；停摆 5 秒落 `stall` 且 `detail` 给出 `since_tick_ms`/`frozen_frames`；启动 30 秒内同样停摆不落盘（`suppressed_by_grace=1`），64 秒后落盘 | ✅ 已完成（见 §10.3） |
| **M9 暂停判定 + 区块生成** | 集成服务器 ESC 暂停不再误报 `stall`（客户端 `Minecraft.isPaused()` 探针 + `MinecraftServer.isPaused()`，`/health.paused`、`stats.suppressed_by_pause`）；唯一一处 Mixin 统计区块生成（`ChunkStatusTasks#generateNoise`），`/chunks` 增 `generated_total`/`generated_per_s`；`/selftest/pause` 回归入口 | ESC 暂停 87 秒（grace 已过）零落盘、`suppressed_by_pause=1`；恢复后真卡 6 秒照常落 `stall`；`forceload` 64 新区块 → `generated_total=81`、`per_s=3.24`；同区重载不重复计数 | ✅ 已完成（见 §10.4） |
| **M11 内存归因（mod 级）** | 类→mod 映射（扫 `mods/*.jar` 的 `neoforge.mods.toml`，按包名索引）→ `/heap/histogram` 的 `by_mod[]`/`mod_id`；直方图保留最近 5 份 + `/heap/histogram/compare` 差分；JFR `allocations.top_sites[].site_mod_id` 与 `summary.old_objects`（启用 `jdk.OldObjectSample`）；对象图 + CHK 支配树 + retained size（`/heap/retained`，含 `dominated_by_class`） | `by_mod` 能回答「哪个 mod 占着」；compare 能回答「什么在涨」；`/heap/retained` 的各类 retained 相加 ≈ 可达堆，且与直方图在同一次 dump 上对象数/浅字节一致 | ✅ 已完成（见 §10.5；自检 `delta objects=0 bytes=0 (ok)`） |
| **M10（可选）** | 与 spark 共存验证、`/observa` 命令完善、Windows 路径兼容、区块 tick 成本归类 | — | ⬜ 待做 |

### 10.1 已落地的实测证据

起服：`gradlew runServer`（NeoForm 产物本机已缓存，12s 内起服完成）。

| 项 | 实测 |
|---|---|
| JDK HttpServer 可用性 | ✅ 不需要退 Netty。`/manifest` 200 + 合法 JSON |
| 鉴权 | 无 token → 401 `unauthorized`；错误 token 同 |
| 错误信封 | 未知路径 → 404；`POST /metrics` → 405 带 `Allow`；`window=7` → 400 |
| 空载基线 | tps 20.0/20.0/20.0；mspt p50 0.2ms / p95 0.43 / p99 0.64；chunks 841；堆 287 MB |
| 尖峰记录 | 4 次大 `fill`（各 ~2.8 万方块）后 p99 升至 8.17ms，p95 0.87 |
| 实体计数 | 8 实体（4 僵尸 / 2 盔甲架 / 1 猪 / 1 掉落物），`item_entities=1`，type/dimension/chunk 三种切分自洽 |
| 一个坑 | `summon` 到未加载区块报成功但实体挂 pending：`@e` 与 Observa 都查不到 → 已在 `/entities` 的 `notes` 里写明「只统计已加载区块」 |
| 未能验证 | TPS<19 的 `flags` 分支：本机没能造出超过 50ms/ticks 的主线程停顿（`fill`/`forceload` 900 区块/`place structure` 都只到 p99≈14ms）。逻辑已写，需真负载复核 |

M2 追加证据（同一台机器）：

| 项 | 实测 |
|---|---|
| 已知热点自检 | `scripts/m2_accept.py` 全绿：两个 repeating command block 每 tick 交替 `fill` 32×32×32，mspt 60ms+/tps≈7；`busy_samples=1601/1601`，Top-1 `BlockBehaviour$BlockStateBase.updateShape` self 37.5%，树从 `MinecraftServer$$Lambda.run` 展开 37 层到 `FillCommand.fillBlocks → Level.setBlock → NeighborUpdater.executeShapeUpdate → updateShape` |
| 剖析开销 | 空载 60s 不录 vs 60s 5ms 采样：p50 0.49→0.46 ms、p95 0.77→0.75、p99 1.06→1.12 —— 噪声量级 |
| 空载真实性 | 75s 空载录制 `idle_pct=95.04%`（busy 744），Top 是 `ServerFunctionManager.executeTagFunctions`、`NeoForgeEventHandler.preServerTick`，连自己的 `MetricsService.onTickPre` 2.82% 都如实出现 |
| 报告体积 | 剪枝后 10.3 KB（25 热点 + 37 层链）；计划期 8 KB 上限没有实测依据，已改为 16 KB |
| 踩过的坑 | 命令方块因 `enable-command-block=false` 静默不执行（`LastExecution` 照样更新）；无玩家时区块只 loaded 不 tick，必须 `/forceload add`；`getThreadInfo(id,64)` 只抓最内 64 帧，实体 tick 栈更深会导致「每次最外层帧都不同」的假根 → `sample_depth` 改 256 |

M3 / M4 追加证据（同一台机器）：

| 项 | 实测 |
|---|---|
| JFR 可用性 | 编程式 `Recording` + `RecordingFile` 在游戏内可用（`jdk.jfr` 模块无需额外 JVM 参数）；12s 录制 329 KB / 4388 事件 |
| JFR 摘要 | `fill` 压测下 `BlockPos` 24.3%、`CollectingNeighborUpdater$ShapeUpdate` 22.7%、`server.level.Ticket` 22.0%；GC 15 次共 3.6s（最长 560ms，含 5 次 `G1 Humongous Allocation`）；与 MSPT 60ms+ 对得上（`docs/examples/jfr-report.json`） |
| JFR 开销 | 空载 30s 不录 p50 0.42/p95 0.88/p99 1.15 vs 录制中 p50 0.38/p95 0.94/p99 1.36 |
| JFR 踩坑 | `RecordedEvent.getValue(...)` 在游戏内对 GC 的 `cause` 抛 `ClassCastException: String cannot be cast to [C`，同文件在独立 JDK 21 里可正常解析 → 全改类型化访问器 + 逐事件兜底（`events_skipped`） |
| 黑匣子自动触发 | `fill` 压测：MSPT 408ms 持续 5s 后第 6 秒出现 `20261026-003718-mspt-408ms.json.gz`（3.0 KB；7 帧 ring、12 条异常、200 行日志、`build_ms=2`） |
| 黑匣子停服兜底 | `on_shutdown=true` → `20261026-003724-shutdown.json.gz` 在 `Stopping server` 之前写完 |
| 黑匣子崩溃兜底 | `Thread.setDefaultUncaughtExceptionHandler` 已装（日志可见 `uncaught-exception handler installed`）；**未做端到端验证**——没有可控的「server 线程未捕获异常」注入点 |
| 保留策略 | 目录内只留最近 10 份（超出按 mtime 删最旧）；落盘先写 `.tmp` 再原子 move，避免半截文件 |
| `/logs` 归并 | 启动后 120s 内 11 条 WARN 归成 11 组；`level` 为下限（WARN 含 ERROR/FATAL），`since` 窗口生效；logger 尾部斜杠已剔除 |
| 入职链接生成 | 进存档时日志两行：`[observa] agent brief: <path>` / `[observa] agent brief over http: http://127.0.0.1:8724/observa/v1/brief.md`；`run/observa/agent.md` 5135 B、`TASK.md` 645 B 同步生成 |
| brief 端点 | `GET /brief.md` → 200 `text/markdown` 5523 B；`GET /brief` → 200，含 `brief_url/base_url/task_file/token_file/state/first_calls/recipes/rules`；无 token 仍 401（与其它端点一致） |
| 任务嵌入 | `TASK.md` 写成两行自然语言后，`brief.md` 的「你的任务」一节原样出现该文本；模板状态（未改写）时 `task` 为 `null` 并提示怎么写 |
| 隧道端到端 | `runClientJoin`（`--quickPlayMultiplayer 127.0.0.1:25565`）连开发服：客户端端点 8724、服务器因占用退到 8725；`health` 回 `via:"tunnel"`/`player:"Dev"`；`metrics/entities/snapshot/logs/blackbox/profiler/status/brief` 全 200，且压测时与服务器本机读数一致（两边同为 `tps 9.0 / mspt 109.52`） |
| 隧道分块 | 30 KB 的 `TASK.md` → 经隧道取 `brief.md` 得 45,805 B、400 条任务行完整（2 块按 seq 重组）；端口在文本中按调用侧（客户端 8724）生成 |
| 隧道权限 | `deop Dev` 后下一次请求 403 `forbidden`（含 next），`op Dev` 后立即 200 —— 每请求实时 `hasPermissions` |
| 隧道边界 | `/profiler/report/*` → 501 `not_supported_over_tunnel`；未知路径 404；无 token 401；断线 503 `no_tunnel` |
| 空世界重启冒烟 | 删掉 `run/observa-dev` 重新生成世界后：11 个端点全 200（manifest/metrics/entities/memory/chunks/snapshot/logs/blackbox/profiler/status/brief.md/brief）、`/health` 回 `via` 为 `direct`、`/dump` 落盘 3610 B、日志无残留命令方块刷屏；jar `build/libs/observa-0.1.0.jar` 138,909 B |


---

### 10.2 M7 实测证据（主机侧补全）

- **`/system`**：进程 CPU 4.34%、整机 9.7%（Windows 上有效）、24 核、物理内存 16.89 GB（用 60.83%）、swap 77.9 GB、D: 592 GB 用 43.95%
- **`/memory` 明细**：`pools` 列出 Metaspace 106 MB 等分池，`collectors[]` 带 `last_gc` 的逐池 before/after/reclaimed
- **行号级剖析**：`fill` 压测 8s@5ms，`lines` 直指 `PalettedContainer.get:158` 29.98%、`Level.getChunk:202` 23.36%、`LevelChunkSection.setBlockState:61` 7.18%
- **JFR 执行采样**：同一负载 8s 录制 294 个 `ExecutionSample`，Top `ServerChunkCache.getChunk:153` 22.11%、`handleNeighborChanged:695` 14.97%；分配点 `BlockPos <- BlockPos$MutableBlockPos.immutable:630` 占 74.6%（16.47 GB）
- **堆直方图**：350 MB 堆 → `dump_ms=392`、`parse_ms=575`、4,852,926 实例、232,546,088 B（估算浅尺寸）、14,284 类、`skipped_records=541`（非堆记账记录）；实例数与 `jcmd` 15/15 类名一致、14 项完全相等，浅尺寸差 1–2%
- **`/network`（真客户端）**：1 名玩家在线时单方向约 465 帧/s、4.8 KB/s，入方向约 1 帧/s；`kill @e` 后瞬间冲到 2192 帧/s（死亡动画/掉落物同步）——帧数包含每 tick 2 个 bundle 分隔符，字节数为压缩后载荷

### 10.3 M8 实测证据（可靠性修复）

| 缺陷 | 修复 | 实测 |
|---|---|---|
| `/logs` 只拿到 328/604 条 ERROR | 上限 400→5000；单次读 512KB→4MB；只在 backlog>64MB 才跳读并记 `log_bytes_skipped`；`entries_dropped` 暴露淘汰 | 合成 700 条 ERROR → `events_matched=699`、`entries_retained=712`、两个缺口计数为 0 |
| 卡顿时环形缓冲反而变空（`ring_size: 2`） | 心跳线程 `observa-heartbeat`（500ms）在 tick 停摆时推进 `frozen` 帧；新增 `reason="stall"` 落盘 | 6 秒停摆 → ring 里 4–8 帧 `frozen:true`；`stall` dump 4683 B，`detail.since_tick_ms=5277`、`frozen_frames=8` |
| 世界加载期 50ms×5s 误报 4/4 | 启动宽限 `blackbox.min_server_ticks=400` + `grace_seconds=60`，宽限期的抑制计入 `stats.suppressed_by_grace` | 启动 30 秒时 6 秒停摆不落盘、`suppressed_by_grace=1`；64 秒后同样停摆立即落盘；`mspt` 触发在 grace 过后仍正常 |

### 10.4 M9 实测证据（暂停判定 + 区块生成）

| 项 | 实测 |
|---|---|
| Mixin 是否生效 | 服务端日志 `SpongePowered MIXIN ... Compatibility level set to JAVA_21`，无 apply 失败；jar 内确认 `observa.mixins.json` 与 `mixin/ChunkGenerationMixin.class` |
| 区块生成计数 | 新建区 `forceload add 5000 5000 5007 5007` → `loaded` 841→1570、`generated_total=81`、`generated_per_s=3.24`；`forceload remove` 再 `add` 同一区域 → `generated_total` 仍 81（磁盘读回不重复计数） |
| ESC 暂停不误报（真客户端） | 单机进存档（集成服 8725）按 ESC → `/health.paused=true`；停 87 秒（`in_grace=false`）期间 `stall_dumps=0`、`suppressed_by_pause=1`；ESC 恢复 → `paused=false` |
| 恢复后真卡仍落盘 | 恢复后 `POST /selftest/stall {"ms":6000}` → `20261027-000224-stall-5s.json.gz`（`since_tick_ms` 约 5.4s、`frozen_frames=8`） |
| 强制暂停通道 | `POST /selftest/pause {"paused":true}` 在专用服上同样压掉 `stall`（`suppressed_by_pause` 1→2），`{paused:false}` 后恢复 |

### 10.5 M11 实测证据（内存归因：mod / 差分 / retained）

起服用 `mc_smoke`（scenario `retained-crosscheck2`，`[debug] retained_on_start=true` 触发一次开局自检），证据目录 `.dsh/smoke/retained-crosscheck2-*/`。

| 项 | 实测 |
|---|---|
| 类→mod 映射 | 扫 `mods/*.jar` 的 `neoforge.mods.toml`，按**包名**索引（省内存，撞包记 `package_conflicts`）；`/heap/histogram` 的 `by_mod[]` + 每行 `mod_id`。开发环境 `mods/` 为空 → `mod_mapping.mods_indexed=0`，只退化成 minecraft/library/unknown（盲区已在文档写明） |
| 差分 | `/heap/histogram` 保留最近 5 份（`recent_histograms`）；`/heap/histogram/compare?from=&to=&top=` 给 `classes[].delta_bytes` 与 `mods[].delta_bytes`，历史不足/同一次运行 → 400 `not_enough_history` |
| JFR 归 mod | `allocations.top[].mod_id`（类属谁）+ `top_sites[].site_mod_id`（谁在分配，能抓 vanilla 类被 mod new）；`summary.old_objects` 来自新启用的 `jdk.OldObjectSample`（字段用 `getEventType().getFields()` 动态发现，不硬编码） |
| 对象图 / 支配树 | 190 MB dump：4,146,765 对象、8,299,351 条引用（另有 `dropped_edges=1,128,353` 指向只有元数据没有实例记录的 `Class` 镜像）、24,526 类、35,331 个 GC root；`dump_ms=412`、`analyze_ms=6385`、`layout_mismatches=0` |
| retained | 可达集 73,055,040 B；各类按**支配分区**汇总（一旦某对象被计入，其子树不再累加），`sum(retained)=73055040` 与 `total_retained=73055040` **精确相等**（`delta=0`，自检项 `partition-check`） |
| 交叉校验 | 同一次 dump 上直方图与对象图：`instances=4146765 shallow_bytes=189833328 \| objects=4146765 dump_bytes=189833328 \| delta objects=0 bytes=0 (ok)`——两条独立读取路径互为校验 |
| 垃圾混入 | `unreachable` 3,115,993 对象 / 116,568,208 B（61%）；JVM 日志 `GC locker is held; pre-heapdump GC was skipped`——`live:true` 不保证 dump 前做过 full GC，但 retained/可达统计不受影响，故如实报 `unreachable_pct` 而不重试 dump |
| 修掉的既有 bug | `HeapHistogram` 把 `instanceSize`（含继承链）当本类字段和、只减本类引用字段 → 深继承类每个引用多算 4 B（整堆差 5.6%）。改按父链累加 `hierarchyRefs()` 后与对象图逐字节一致 |

## 11. 风险与先验证项（M0 内消化）

1. **JDK HttpServer 在 FML 下能否用**：若 `jdk.httpserver` 未解析 → 改 Netty（MC 自带）。M0 第一件事验证。→ **✅ 已排除**：实测可用，无需 Netty。
2. **1.21.1 事件签名**：`LevelTickEvent.Post` / `EntityTickEvent.Post` 已知可用（Footpaths 已用）；`ServerTickEvent` 与 `ServerStartedEvent` 需实测。→ **✅ 已确认**：`ServerTickEvent.Pre/Post` 与 `ServerStartedEvent/ServerStoppingEvent` 均按预期触发；`ServerLevel#getEntities()` 返回 `LevelEntityGetter`，`getAll()` 可遍历。
3. **Mixin 注入点稳定性**：`MinecraftServer.tickServer` 在 1.21.1 签名需确认；`Connection` 的收发方法（`channelRead` / `send`）确认。
4. **线程直方图**：`ClassLoadingMXBean` 拿不到实例数，需 JFR；无 JFR 时降级并标注 `"unavailable": true`，不伪造 0。→ **部分排除**：M3 的 `.jfr` 已能给出分配热点（`jdk.ObjectAllocationSample`），但「按类实例数」仍需 heap histogram，当前用 JFR 的分配字节近似，字段名如实写 `bytes_est`。
5. **单机双启**：单机时服务器启停可能多次（退出生效），HTTP 端口复用/释放要幂等，避免第二次进存档时端口被占。
6. **端口冲突**：自动 +1 并写日志，`/manifest` 里返回实际端口。
7. **崩溃写盘的极限**：JVM native crash（SIGSEGV）写不出文件，只能靠崩溃报告 + 未来接 spark 的 heap dump；文档里写清边界。

---

## 12. 交付物

- `build/libs/observa-0.1.0.jar`
- `README.md`：安装、配置项全表、端点全表、单机/服务器差异
- `docs/DIAGNOSIS.md` 诊断 SOP：症状 → 先调哪个端点 → 看哪个字段 → 下一步深挖，附 curl / PowerShell 示例
- 示例：`docs/examples/*.json`（真实输出样本，供我后续对照 schema）

---

## 13. DSH 使用方式（SOP 骨架）

```bash
TOKEN=$(grep '^token' config/observa-common.toml | cut -d'"' -f2)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8724/observa/v1/snapshot
```

| 症状 | 第一站 | 判据 |
|---|---|---|
| 整体卡顿 | `/metrics?window=60` | p99 MSPT 明显高于 p50 → 尖刺；两者都高 → 持续负载 |
| 尖刺型卡顿 | `GET /blackbox` → 读最近一份 `.json.gz` 的 `ring` | 卡顿前 mspt/heap/gc 的形状（阶梯 vs 尖刺） |
| 分配压力 / GC 停顿 | `POST /profiler/start {"mode":"jfr","seconds":30}` → `summary.gc` + `summary.allocations` | 暂停占比、最长暂停、分配最多的类 |
| 内存上涨 | `/memory` + 黑匣子 `ring[].heap_used_bytes` | 堆阶梯上升且伴随 GC 次数增长 → 泄漏候选 |
| 实体堆积 | `/entities?top=20` | 单类型异常占比或掉落物破万 |
| 网络问题 | `/network` | 单玩家发送队列/超时异常 |
| 崩溃前状态 | `/blackbox` + `/logs?level=WARN` | 最近一份黑匣子的 `errors` / `log_tail` 对照时间线 |
