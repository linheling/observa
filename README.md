# Observa

让 agent（DSH / 脚本 / 你自己的 CI 探针）读 Minecraft 服务器运行时状态的观测模组。类似 spark，但输出是**给机器看的紧凑 JSON**：字段自带单位、Top-N 在服务端算好、每个错误都带 `next` 提示告诉你下一步调哪里。

- 目标版本：**1.21.1 / NeoForge 21.1.x**（JDK 21）
- 只读：没有任何端点会改世界、改玩家、执行游戏命令
- 只监听 `127.0.0.1`，所有请求要 `Authorization: Bearer <token>`

## 当前状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架、配置、HTTP 引导、鉴权、错误信封 | ✅ 已实测 |
| M1 | TickMeter + 实体/内存/区块/玩家采集，`/metrics /entities /memory /chunks /snapshot` | ✅ 已实测 |
| M2 | 采样剖析器（ThreadMXBean）+ `/profiler/start\|stop\|status\|report` | ✅ 已实测（含已知热点自检） |
| M3 | JFR 编程式录制 + 摘要解析 + `.jfr` 落盘 | ✅ 已实测 |
| M4 | 黑匣子环形缓冲 + 自动/手动/崩溃/停服落盘 + `/logs` `/blackbox` | ✅ 已实测 |
| M5 | 入职说明 `brief.md` + `agent.md` + `TASK.md` 任务注入 | ✅ 已实测 |
| M6 | 客户端隧道：把玩家与服务器的 MC 连接当传输通道（不开端口） | ✅ 已实测（真客户端） |
| M7 | 主机侧补全：`/system` `/network` GC 明细 `/heap/histogram`、剖析行号、JFR 执行采样 | ✅ 已实测（含真客户端 + 隧道） |

未实现的端点在 `/manifest` 里如实标成 `"status": "planned"`——不会假装可用。

## 安装

产物：`build/libs/observa-0.1.0.jar`（`gradlew build` 生成）。

- **专用服务器**：放进服务端 `mods/`，客户端不用装。
- **单机**：放进客户端 `mods/`。集成服务器跑在客户端进程里，所以必须装在客户端侧。
- 服务端第一次启动会在 `config/observa/token.txt` 生成 token；改 `config/observa-common.toml` 可调端口与开关。

```toml
[http]
  enabled = true
  port = 8724            # 占用时自动 +1 重试（最多 10 次），实际端口见日志
  token = ""             # 留空 = 首次启动生成到 config/observa/token.txt
  rate_limit_per_second = 20

[metrics]
  interval_ms = 1000     # 聚合间隔
  ring_seconds = 300     # 黑匣子回溯窗口（M4 使用）
  top_n_default = 10
  top_n_max = 100        # 请求参数 top 的上限
```

`bind` 地址是硬编码 `127.0.0.1`，**不是**配置项：防止有人把它改成 `0.0.0.0` 把观测面暴露到公网。

## 让 agent 自己入职（一次 GET 就够）

进存档 / 开服时 Observa 会自动生成一份「入职说明」，并在日志里打印地址：

```
[observa] agent brief: D:\...\observa\agent.md
[observa] agent brief over http: http://127.0.0.1:8724/observa/v1/brief.md
```

两种拿法，内容一致：

| 方式 | 位置 | 适合 |
|---|---|---|
| 文件 | `<gameDir>/observa/agent.md` | agent 只有文件系统（默认入口，就是日志里那条路径） |
| HTTP | `GET /observa/v1/brief.md`（Markdown）/ `GET /observa/v1/brief`（JSON） | agent 已经能发请求 |

brief 里写着：这是什么东西、**当前这一刻的健康状态**、按「我想回答什么 → 调哪个端点」排的表、
三条最常用工具配方（采样/JFR、黑匣子、/dump、/logs）、判读口径（`null≠0`、只算已加载区块、
剖析分母是 `busy_samples`、`busy=0` 报告必空、暂停菜单冻结 tick）、排错表、落盘保留位置。

**「任务」由你写**：`<gameDir>/observa/TASK.md`（首次自动生成模板）。用自然语言写「想让 agent
查什么」，Observa 会把它原样嵌进 brief 的「你的任务」一节——改完不用重启，下次 GET 就是新内容：

```markdown
- 玩家说主城附近走两步就卡，先看 /entities?by=chunk&top=20 有没有掉落物堆积。
- 昨晚 23:40 卡过一次，看 GET /blackbox 最近一份落盘的 ring 与 errors。
- 结论要带证据（端点 + 字段值），不要改世界、不要执行游戏命令。
```

brief 里**不含 token 值**（token 等于密码，不写日志也不写这个文件），只给 token 文件的绝对路径。

## 快速开始

```bash
TOKEN=$(cat config/observa/token.txt)          # Windows: type config\observa\token.txt
BASE=http://127.0.0.1:8724/observa/v1
curl -s -H "Authorization: Bearer $TOKEN" $BASE/manifest
curl -s -H "Authorization: Bearer $TOKEN" $BASE/metrics
```

PowerShell：

```powershell
$h = @{ Authorization = "Bearer $(Get-Content config\observa\token.txt)" }
Invoke-RestMethod -Headers $h http://127.0.0.1:8724/observa/v1/metrics
```

## 端点

| 方法 | 路径 | 状态 | 用途 |
|---|---|---|---|
| GET | `/manifest` | ✅ | 端点清单、限额、约定、鉴权方式 |
| GET | `/health` | ✅ | 存活、uptime、实际端口、`paused`（单机按 ESC 时为 true） |
| GET | `/metrics?window=1\|10\|60` | ✅ | TPS / MSPT 分位、堆、实体与区块总量、`headline` 一句话诊断、`flags` |
| GET | `/entities?by=type\|dimension\|chunk&top=10` | ✅ | 实体总数、掉落物、经验球、按类型/维度/区块 Top-N |
| GET | `/memory` | ✅ | 堆与非堆、各内存池、各收集器与上一次 GC 的逐池回收明细、类加载 |
| GET | `/system` | ✅ | 进程/整机 CPU、物理内存与 swap、进程 uptime/线程、游戏目录所在卷的磁盘 |
| GET | `/network?top=10` | ✅ | 每玩家 ping（keep-alive RTT）、收发包数/字节数与每秒速率、总计 |
| GET | `/heap/histogram?top=25` | ✅ | 最近一次堆直方图：存活实例数与估算浅尺寸、按类与按包聚合（没有就回 `state:"idle"`） |
| POST | `/heap/histogram/start` | ✅ | 异步触发一次 dump+解析：`{"live":true,"keep_file":false,"top":25}`，已有一份在跑时回 409 |
| GET | `/chunks` | ✅ | 已加载区块数 + `generated_total`/`generated_per_s`（新生成，不含磁盘读回） |
| GET | `/snapshot` | ✅ | 以上全量合一（一次请求拿全貌，省往返） |
| GET | `/logs?since=60&level=ERROR&limit=20` | ✅ | 跟随 `logs/latest.log`，按签名去重的 WARN/ERROR/FATAL 与计数（INFO 不保留，`level=INFO` 回 400） |
| POST | `/profiler/start` | ✅ | 开一次录制：`{"mode":"sample"\|"jfr","seconds":15,"interval_ms":5}` |
| POST | `/profiler/stop` | ✅ | 提前结束录制并返回报告（到点自动结束则回 409，改查 `/profiler/report/{id}`） |
| GET | `/profiler/status` | ✅ | 是否在录、已录多久、`recent_reports` |
| GET | `/profiler/report/{id}` | ✅ | 取历史报告（内存里保留最近 5 份） |
| POST | `/dump` | ✅ | 立刻落一份黑匣子（`{"label":"before-restart"}`） |
| POST | `/selftest/stall` | 调试 | `{"ms":6000}`：故意阻塞服务器线程，用来验证冻结帧与 `stall` 落盘（仅 `[debug] allow_selftest=true`） |
| POST | `/selftest/pause` | 调试 | `{"paused":true\|false}`：强制「玩家暂停」状态，用来验证 ESC 暂停不会被误报成 stall（同上，仅调试开关打开时） |
| GET | `/blackbox?limit=10` | ✅ | 历次自动/手动落盘清单（时间、原因、大小、路径） |
| GET | `/brief.md` | ✅ | 入职说明（Markdown）：任务、当前状态、端点表、工具配方、判读口径 |
| GET | `/brief` | ✅ | 同一份入职说明的 JSON 形态（`state`/`first_calls`/`recipes`/`rules`） |
| GET（隧道） | *同上，但打到玩家本机的客户端端点* | ✅ | M6：客户端也装同一个 jar 时，在玩家机器上用同一套路径读服务端数据；`/health` 带 `via:"tunnel"` 与 `player` |

## 响应契约

成功响应统一信封：

```json
{"schema_version": 1, "generated_at": "2026-10-25T15:49:19.5Z", "data": { ... }}
```

失败响应带自救提示：

```json
{"error": {"code": "bad_request", "detail": "window must be 1, 10 or 60",
           "next": ["GET /observa/v1/metrics?window=10"]}}
```

约定（`/manifest` 里也有一份，供 agent 自描述）：

- 单位写进字段名：`*_ms`、`*_bytes`、`*_per_s`、`*_pct`
- `null` = **尚未采集**，不要当 0 读。例如 `/chunks` 的 `generated_per_s` 在速率窗口还没攒够 1 秒时是 `null`
- Top-N 服务端算好；`truncated: true` 表示还有更多没回来
- `age_ms` 是快照年龄：超过 `interval_ms` 的两倍说明采集卡住了，先查 `/health`

### 几个刻意的设计

**MSPT 量的是 tick 内部耗时，不是两次 tick 的间隔。** 专用服务器会 sleep 到 50ms 节奏，用间隔算 MSPT 会让健康服务器永远显示 50ms。`TickMeter` 记 `ServerTickEvent.Pre→Post` 的墙钟差。

**TPS 用「已完成 tick 的时间戳」数出来，不用 1000/MSPT 推算。** 后者把休眠算进去，而且服务器追赶进度（catch-up）时反而显示正常——那正是最需要报警的时刻。

**实体计数只覆盖已加载区块。** 这是任何服务端扫描的物理上限。实测时踩到过：`summon` 到未加载区块坐标会报告成功，但实体挂在 pending 里，`@e` 与 Observa 都看不到——所以 `/entities` 的 `notes` 里明写了这条。

## 系统、网络与堆（M7）

三个端点补的是「游戏内看不到、但排障必须有」的主机侧数据。

### `/system`

`cpu.process_load_pct`（本进程）与 `cpu.system_load_pct`（整机，Windows 上同样有效，取不到时是 `null` 而不是 0）、物理内存与 swap 的绝对值和占用比、进程 `pid/uptime_ms/threads/peak_threads`、以及**游戏目录所在卷**的磁盘容量。

```bash
curl -s -H "$H" $API/system
```

```json
{"cpu":{"process_load_pct":4.34,"system_load_pct":9.7,"process_cpu_time_ms":87921,"processors":24,"system_load_avg_1m":null,"os":"Windows 11","arch":"amd64"},
 "memory":{"physical_free_bytes":6615601152,"physical_total_bytes":16890322944,"swap_free_bytes":59695996928,"swap_total_bytes":77896474624,"physical_used_pct":60.83,"swap_used_pct":23.36},
 "process":{"pid":10924,"uptime_ms":106147,"threads":70,"peak_threads":77},
 "disk":{"path":"D:\C\MC\test\run","total_bytes":592323801088,"free_bytes":331975024640,"used_pct":43.95,"fs":"Data"}}
```

- 每秒采样一次会把 CPU 曲线变成噪声；**隔几秒取两次**自己算差，比高频轮询稳。
- `disk.free_bytes` 掉得比日志增长快 = 有别的东西在写盘（黑匣子/`.hprof`/崩溃报告）。

### `/network`

每个在线玩家的 ping 与收发量。ping 直接取服务端 keep-alive 的 RTT（`ServerPlayer.connection.latency()`，和 `/spark ping` 同一个数）；包数/字节数由一个插进玩家 Netty 管道的 `ChannelDuplexHandler` 统计（名称 `observa_traffic_out` / `observa_traffic_in`），在压缩之后、不带长度前缀，即真正上线的 payload 字节。

```bash
curl -s -H "$H" "$API/network?top=10"
```

```json
{"captured_at_ms":1792960593240,
 "totals":{"sent_packets":38864,"sent_bytes":1241440,"received_packets":148,"received_bytes":2495},
 "players":[{"name":"Dev","uuid":"380df991-f603-344c-a090-369bad2a924a","ping_ms":0,
   "sent":{"packets":38864,"bytes":1241440,"packets_per_s":457.1,"bytes_per_s":4667.9},
   "received":{"packets":148,"bytes":2495,"packets_per_s":1.0,"bytes_per_s":25.8}}],
 "online":1,
 "notes":["ping_ms comes from the server's own keep-alive RTT, ..."]}
```

- **`packets` 是帧数不是逻辑包数**：统计点贴在压缩边界，一个包被拆成多帧会多算，且每 tick 还有 2 帧 bundle 分隔符（约 40 帧/秒的固定底噪）。本机单客户端实测约 465 帧/s、4.8 KB/s 出、1 帧/s 入——所以**看带宽请用 `bytes_per_s`**，帧数只用来横向比「谁更吵」。
- `*_per_s` 需要两次采样才有值：玩家刚进来那次是 0，下一个刷新周期才是真速率。
- `ping_ms` 为 `null` 表示这条连接还没完成一次往返，不是「0 延迟」。
- `top` 按发送字节数排序。**一个玩家在挂机看天，`sent_bytes` 也会稳定增长**——那是区块/时间/实体同步，不是他在下载东西。
- 这是服务端视角，观测不到客户端到 ISP 的往返；要那一段只能靠客户端侧的 `/network`（`via:"tunnel"` 时同样返回服务端视角，隧道不改变统计位置）。

### `/heap/histogram`

JFR 的分配采样回答「谁在一直 `new`」，但回答不了「谁占着内存不放」。存活实例数只能从堆快照里数。

```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json'   -d '{"live":true,"top":25}' $API/heap/histogram/start      # 202，异步
curl -s -H "$H" $API/heap/histogram                          # 轮询：state=running|done|failed|idle
```

- **成本**：`live:true` 会触发一次 full GC + dump，实测 350 MB 堆 `dump_ms=392`、`parse_ms=575`。dump 期间是 STW（约 0.4 秒），所以别在玩家投诉卡顿的**那一刻**去点它。
- **默认不保留文件**（`keep_file:false`）：解析完就删 `.hprof`，`config/observa/heap/` 最多留 3 份（设 `keep_file:true` 时会留）。这个文件等于一次完整内存快照，和 `.jfr` 不同，它对磁盘很重（本机 350 MB/次）。
- **浅尺寸是估算**，但按运行时真实引用宽度折算（压缩指针 4 B 引用 / 12 B 头），与 `jcmd GC.class_histogram` 的同类字节数差 1–2%：实测 `byte[]` 54,782,440 vs 54,864,432、`Object[]` 17,917,608 vs 17,926,536、`HashMap$Node` 7,775,456 vs 7,780,192。实例数是逐类精确的（15/15 类名一致、14 项完全相等，最大差 1）。
- 这不是 **retained size**（不含被引用对象），所以「谁占得多」看它，「删掉这个能不能救内存」还得看支配树（本模组不做）。
- `skipped_records`：解析时跳过的非堆记录（栈帧/栈轨迹等记账项），几十到几百条都正常，不影响实例统计。
- 与 `jcmd` 的两个已知口径差：`java.lang.Class` 在 HPROF 里用 `CLASS_DUMP` 描述，不会写成 2 万多条 `INSTANCE_DUMP`；JVM 内部 filler 数组在 HPROF 里被编码成 `int[]`。

### `/chunks` 的生成速率（M9）

`/chunks` 现在回答两个不同的问题：**现在加载了多少区块**（`loaded`）与**这次开服以来新造了多少地形**（`generated_total` / `generated_per_s`）。

- 计数点：整个模组**唯一一处 Mixin**，挂在 `ChunkStatusTasks#generateNoise` 头部（`observa.mixins.json`，由 `neoforge.mods.toml` 的 `[[mixins]]` 注册）
- 为什么钩这一步：从磁盘读出的区块在存档里已经是 FULL，加载时不会重跑噪声生成；只有第一次到达某片区域、需要现造地形才进 NOISE。因此 `generated_total` = 新生成，不是「加载过」
- `generated_per_s` 是 60 秒窗口平均（`generated_window_seconds`），窗口不足 1 秒时给 `null`
- 想算「这 10 分钟生了多少」：隔 10 分钟取两次 `generated_total` 相减，别拿瞬时速率乘时间

实测：`forceload add 5000 5000 5007 5007`（64 个新区块）→ `generated_total` 81、`generated_per_s` 3.24、`loaded` 841→1570；`forceload remove` 再 `add` 同一区域 → `generated_total` 仍是 81（磁盘读回不重复计数）。

## 剖析（M2）

采样剖析器跑在独立线程 `observa-profiler`，按 `interval_ms` 对**服务器线程**做 `ThreadMXBean.getThreadInfo(depth)` 抓栈，不 STW（不用 `getAllStackTraces`，那会停顿）。默认 `interval_ms=5`、`sample_depth=256`。

```bash
# 开 15 秒采样；到点自己结束
curl -s -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"mode":"sample","seconds":15,"interval_ms":5}' $API/profiler/start
curl -s -H "$H" $API/profiler/status            # running / remaining_ms / recent_reports
curl -s -H "$H" $API/profiler/report/sample-1   # 报告
```

报告形状：

```json
{"id":"sample-1","mode":"sample","thread":"Server thread","frame_format":"SimpleClassName.methodName",
 "started_at_ms":1792944773346,"duration_ms":8006,"interval_ms":5,
 "samples":1601,"idle_samples":0,"busy_samples":1601,"idle_pct":0.0,
 "top":[{"frame":"BlockBehaviour$BlockStateBase.updateShape","self_pct":37.50,"total_pct":37.56,
         "self_samples":600,"total_samples":601}],
 "tree":[{"frame":"MinecraftServer$$Lambda.run","self_pct":0.0,"total_pct":100.0,"children":[...]}],
 "lines":[{"frame":"PalettedContainer.get","line":158,"self_pct":29.98,"self_samples":480}],
 "top_truncated":true,"tree_truncated":true,
 "notes":["busy_samples = samples taken inside MinecraftServer.tickServer; ..."]}
```

口径（都在 `notes` 里重复了一遍）：

- **`idle_pct`**：抓到的栈里没有 `MinecraftServer.tickServer` 就算「在等下一 tick」。所以百分比的分母是 `busy_samples`，不是 `samples`。空载服务器实测 `idle_pct≈95%`；满载（`fill` 压测）实测 `0.0%`。
- **`self_pct` vs `total_pct`**：`self` 是停在该帧自己的时间，`total` 含被调用者。Top 之间 `total` 可以互相重叠（同一帧被不同路径调用），**合计不封顶 100% 是正常的**。
- **`tree` 只留通往热点的路径**：先按大预算建完整调用树，再整枝剪掉「不通往 top 里任何一帧」的分支，热点帧自身的子树保留。实测同为 `fill` 热点，带全量分叉的树有 150 节点/12.8 KB，剪枝后同样是 37 层深的完整链、体积 10 KB 出头。
- **JDK 帧默认剔除**（`profiler.include_jdk_frames=false`）：`java.*`/`javax.*`/`jdk.*`/`sun.*`/`com.sun.*`。开成 `true` 能看到 `HashMap.get`、`Inflater` 之类，但也会把 `Thread.run` 与方法句柄层一起放进来。
- **`$$Lambda$12/0x0000020a...` 里的地址被剥掉**，否则每次启动同一方法名都不同，报告无法跨次比对。
- **`lines` 是行号级归因**：对 `top` 里出现的帧，额外按「帧 + 源码行」再数一次 self 样本。同一方法在不同行上热，通常是两个不同的问题（`PalettedContainer.get:158` 是数组索引、`:202` 可能是校验）。行号来自 `StackTraceElement`，是**实际编译后的行**，不是源码的「函数开头」。
- **空报告要看得懂**：`busy_samples=0` 时 `top`/`tree` 必然是空的（整段录制里服务器都在等 tick），报告的 `notes` 会明确写这一条——别把它当成剖析器坏了。

### 造一个「已知热点」做自检

`scripts/m2_accept.py` 是可复现的端到端验收：两个 repeating command block 每 tick 交替 `/fill` 同一块 32×32×32 区域（stone ↔ air），把主线程稳定压在几十 ms/tick，然后开 8 秒采样、断言 Top-3 命中方块写入路径且调用链回到 `FillCommand`。

```bash
python scripts/m2_accept.py 8
```

前置：`run/server.properties` 里 **`enable-command-block=true`**（默认是 `false`，命令方块完全不执行，`LastExecution` 照样更新，非常有欺骗性）；并且要 `/forceload add` 才能让无玩家时区块真正 ticking。

## JFR 剖析（M3）

`{"mode":"jfr"}` 打开一条**编程式 JFR 录制**。只开六个事件——全量 JFR 的元数据事件会把文件撑大十倍，且对判读没有帮助。

| 事件 | 设置 | 用途 |
|---|---|---|
| `jdk.ObjectAllocationSample` | throttle 200/s | 谁在分配、估算字节数 |
| `jdk.GarbageCollection` | 默认 | GC 次数、总/最长暂停、cause |
| `jdk.JavaExceptionThrow` | 带栈 | 异常类型 Top（含被内部捕获的） |
| `jdk.JavaMonitorEnter` | 阈值 10ms | 锁竞争 |
| `jdk.ThreadPark` | 阈值 10ms | 谁在等、等了多久 |
| `jdk.ExecutionSample` | period 10ms | 行号级热点：`Class.method:line`，采样剖析的 ThreadMXBean 路只看得到方法名 |

```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"mode":"jfr","seconds":20}' $API/profiler/start
curl -s -H "$H" $API/profiler/status          # data.jfr: running / remaining_ms
curl -s -H "$H" $API/profiler/report/jfr-1    # 摘要；.jfr 原件留在 config/observa/jfr/
```

报告形状（摘要，不是事件流）：

```json
{"id":"jfr-1","mode":"jfr","duration_ms":12004,"file":".../jfr/jfr-1.jfr","file_bytes":329058,
 "summary":{"events":4388,
   "allocations":{"total_bytes_est":11603290320,"sample_count":1896,
     "top":[{"class":"net.minecraft.core.BlockPos","bytes_est":4873937822,"pct":42.01}]},
   "gc":{"count":12,"total_pause_ms":3623.19,"longest_pause_ms":387.26,
         "causes":{"G1 Evacuation Pause":12}},
   "exceptions":{"count":24,"top":[{"class":"java.net.SocketTimeoutException","count":22}]},
   "execution_samples":{"sample_count":294,
     "top":[{"frame":"ServerChunkCache.getChunk:153","self_pct":22.11,"self_samples":65}]},
   "allocations":{"top_sites":[{"class":"net.minecraft.core.BlockPos",
     "site":"BlockPos$MutableBlockPos.immutable:630","bytes_est":16470000000,"pct":74.6}]},
   "monitor_enter":{"count":0,"total_ms":0.0,"top":[]}}}
```

口径：

- **分配字节是采样加权估算**，`pct` 用于排序而不是算账。要看精确的用 `jfr print` 扫原件。
- **`exceptions` 包含「抛出后被内部 catch」的异常**。实测两个典型噪声：`NoSuchMethodError`（JDK 方法句柄 bootstrap 重试，栈全在 `java.lang.invoke.InvokerBytecodeGenerator`）、`SocketTimeoutException`（RCON/HTTP 超时）。判读先看栈来源。
- **摘要是「事实源」的派生视图**：`.jfr` 原件不删（`config/observa/jfr/jfr-N.jfr`，保留最近 5 份）；解析失败的摘要带 `error` + `error_stack`，`GET /profiler/report/{id}` 会**重新解析一次**，所以解析逻辑修好后不必重录。
- **开销**：空载 30s 不录 `p50 0.42 / p95 0.88 / p99 1.15 ms`；录制中 `p50 0.38 / p95 0.94 / p99 1.36 ms`——同一个噪声量级。
- **`execution_samples` 与 `allocations.top_sites` 给到行号**：前者是 JFR 自己每 10ms 抓的执行采样，`frame` 形如 `ServerChunkCache.getChunk:153`；后者把分配点（`class <- 方法:行`）单独列出——同一类如果由多个分配点产生，光看类名会误判。
- **类名归一化**：数组的 JVM 描述符翻成可读形式（`[J` → `long[]`、`[Lfoo.Bar;` → `foo.Bar[]`），隐藏类/lambda 名字里的每进程唯一地址（`$$Lambda.0x000001cf...`）剥掉——否则同一份报告无法跨次比对。
- 实现踩坑：`RecordedEvent.getValue(...)` 在游戏内会对某些字段抛 `ClassCastException: String cannot be cast to [C`（同一个文件在独立 JVM 里能正常解析）。所以全部改用类型化访问器（`getClass/getLong/getDuration/getString`）并逐事件兜底，跳过的条数写进 `events_skipped`。

## 黑匣子与日志（M4）

三件事：常驻环形缓冲、自动触发落盘、崩溃/停服兜底。

- **环形缓冲**：每份刷新（默认 1s）推进一帧压缩快照，容量 600 帧。只存判读需要的字段——存整份快照会把黑匣子自己要盯的内存吃掉。
- **卡死也照常记录（心跳）**：帧采样除了 tick 回调，还有一条每 500ms 的守护线程 `observa-heartbeat`。tick 停了它照样推进**冻结帧**（`frozen: true` + `since_tick_ms`），所以「服务器卡死的那几秒」不会正好变成记录空窗。
- **自动触发 `mspt`**：`mspt_now > blackbox.mspt_threshold_ms`（默认 50）持续 `blackbox.sustain_seconds`（默认 5）秒 → 立刻落盘，之后 60s 冷却。持续卡顿不会把磁盘写满。
- **自动触发 `stall`**：tick 超过 `blackbox.freeze_after_ms`（默认 2000ms）没动、且停摆超过 `sustain_seconds` → 落一份 `reason="stall"`，`trigger.detail` 里有 `since_tick_ms`、`last_tick_at`、`frozen_frames`、停摆时的 `server_tick`。这类卡顿以前根本不会落盘（tick 回调是采样入口，它哑了采样也哑了）。
- **启动宽限**：世界加载阶段 MSPT 天然爆表，所以自动落盘要同时满足 `blackbox.min_server_ticks`（默认 400 tick）与 `blackbox.grace_seconds`（默认 60s，从服务器启动算）。被宽限压掉的次数在 `/blackbox` 的 `stats.suppressed_by_grace` 里可见——不会静默。
- **单机 ESC 暂停不算卡死**：集成服务器按 ESC 时 tick 也会停，但那是玩家主动暂停。`PauseState` 同时看 `Minecraft.isPaused()`（客户端探针）与 `MinecraftServer.isPaused()`，命中时不落 `stall`（`stats.suppressed_by_pause` 计数），`/health` 报 `paused:true`，`/metrics` 会附一条说明（`age_ms` 变大是因为数据停在暂停那一刻）。
- **手动**：`POST /dump {"label":"before-restart"}`。
- **兜底**：`Thread.setDefaultUncaughtExceptionHandler`（server 线程未捕获异常 → `reason="uncaught"`，**同步**写，因为进程随时会消失）；`blackbox.on_shutdown=true` 时正常停服也写一份。
- **自测**：`[debug] allow_selftest=true` 后可以 `POST /selftest/stall {"ms":6000}` 让服务器线程真的睡 6 秒，用来验证冻结帧与 `stall` 落盘。默认关闭，它会让服务器真卡一下。

落盘位置 `<gameDir>/observa/blackbox/<yyyyMMdd-HHmmss>-<reason>.json.gz`，保留最近 10 份。内容：

| 字段 | 内容 |
|---|---|
| `trigger` | 阈值、持续秒数、宽限配置、`detail`（触发原因的具体数字）、`ring_size` |
| `snapshot` | 触发时刻的完整 `/snapshot` 视图 |
| `ring` | 触发前的轻量帧：`mspt_now/avg/p95/p99`、`tps_1m`、堆、GC 次数、实体/区块/玩家数，外加 `frozen` 与 `since_tick_ms` |
| `stats` | 黑匣子自身状态：`in_grace`、`paused`、两类落盘计数、被宽限/冷却/暂停压掉的次数、心跳是否在跑 |
| `log_watch` | 日志跟随者状态：保留/丢弃条数、跳读字节、读到的偏移 |
| `errors` | 最近 20 条 WARN/ERROR，带栈 |
| `log_tail` | 最近 200 行原始日志 |
| `snapshot_age_ms` / `build_ms` | 数据新鲜度、构建耗时（`stall` 时 `snapshot_age_ms` 会等于停摆时长，这是对的） |

```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"label":"spike"}' $API/dump
curl -s -H "$H" $API/blackbox
curl -s -H "$H" "$API/logs?since=300&level=ERROR&limit=20"
```

`/logs` 的实现：守护线程 `observa-logwatch` 增量跟随 `logs/latest.log`（只读新增字节，日志长到几十 MB 也不会让请求变慢），解析 `[时间] [线程/级别] [logger]: 消息` 与续行栈，按**级别 + logger + 归一化消息（数字→`#`）+ 栈前 3 行**归并，给出 `count`、`first_at`、`last_at`、样本栈。

**容量与诚实性**（旧版本在这里丢过数据，M8 修）：保留最近 **5000** 条 WARN+ 事件（原来是 400，一次世界加载就能挤满）；单次最多读 4 MB，读不完就留到下一次 poll，只有 backlog 超过 64 MB 才允许跳读（跳过的字节计入 `log_bytes_skipped`，不再静默丢弃）。响应里的 `entries_retained` / `entries_dropped` / `log_bytes_skipped` 三个数就是「你看到的是不是全部」的答案。

口径：

- **只覆盖本次进程启动以来的日志**（跟随者从零点开始读）；更早的翻 `logs/*.log.gz`。
- **`level` 是下限**：`WARN` 会同时返回 ERROR/FATAL。
- **INFO 不保留**：跟随者只收 WARN/ERROR/FATAL，所以 `level=INFO` 返回 400（说明去读原始文件），而不是静默给你降级后的结果。
- **`entries_dropped > 0`**：确实超过 5000 条被挤掉过，只能靠黑匣子里的 `log_tail` 或原始文件补历史。
- **`reason="stall"` 的落盘**里 `ring` 尾部是连续的 `frozen: true` 帧；连续冻结帧越多说明停摆越久。`since_tick_ms` 是落盘时的停摆时长。
- **`WARN` 噪声多**：MC 的「Can't keep up」、命令参数歧义提示、NeoForge 的一次性警告都在这里。按 `count` 降序后先看 ≥2 次的。
- **崩溃写不出文件的情况**：JVM native crash（SIGSEGV）时 Java 侧 handler 不会执行，只能靠 `hs_err_pid*.log`。黑匣子兜的是「Java 异常 / 停服 / 卡顿」三类。

## 跨机器：把游戏连接当隧道（M6）

场景：服务器不是你的机器，你也没有它的 shell / 端口权限，但你人在游戏里。
Observa 可以把**玩家与服务器的这条 MC 连接**当隧道：服务端一个端口都不开，数据只发给
已经连上、并且权限达标的玩家。

### 三步

1. **服务端**装 Observa（jar 进服务器 `mods/`）。
2. **你自己的客户端**也装同一个 jar（进客户端 `mods/`）。客户端侧会在本机开一个只绑
   `127.0.0.1` 的端点。
3. 你在服务器上达到 `tunnel.permission_level`（默认 `2` = OP；服主可在
   `config/observa-common.toml` 的 `[tunnel]` 里调整，`0` 表示任何在线玩家都能读）。

进服后客户端日志会出现两行，游戏里也会有一条聊天提示：

```
[observa] tunnel endpoint on http://127.0.0.1:8724/observa/v1/brief.md (client side)
[observa] tunnel handshake: server version 0.1.0 permitted=true (tunnel allowed: read-only Observa data)
```

### agent 怎么用

和你单机时**完全一样**：同一套端点、同一个 `config/observa/token.txt`（客户端自己那份）、
同一份 `brief.md`。区别只有一个——数据来自服务端：

```bash
TOKEN=$(cat config/observa/token.txt)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8724/observa/v1/health
# {"data":{"status":"ok","via":"tunnel","player":"<你的名字>","server_tick":...,"server_http_port":8725}}
```

`via: "tunnel"` 与 `player` 字段就是「这份数据确实是从服务器那头读的」的证据；直连自己机器上的服务端时 `via` 是 `"direct"` 且没有 `player`，两者可用来判断你现在读的是哪一头。

### 端口

默认还是 `8724`。同机同时开服又开客户端时，后启动的一方会自动 `+1` 并写日志
（本机实测：客户端占 8724、服务器退到 8725，再启动客户端时反过来）。**以日志里的端口为准**；
经隧道读 `/brief.md` 时，里面的端口按「发起调用的那一侧」生成，所以照抄不会错。

### 隧道支持哪些端点

| 支持 | 不支持（回 501/404 并说明原因） |
|---|---|
| `/health` `/metrics` `/entities` `/memory` `/chunks` `/snapshot` `/system` `/network` `/heap/histogram` | `/profiler/report/{id}` — 报告可能超过单包且需要落盘路径，请到服务器本机取 |
| `/logs` `/blackbox` `/dump` `/profiler/status` `/brief` `/brief.md` `/heap/histogram/start` | `/profiler/start` `/profiler/stop` — 不在玩家机器上远程启停录制（避免远程把服务器磁盘写满） |
| `/manifest`（精简版：只列隧道支持项） | — |

### 实现要点（为什么这样设计）

- **分块**：单包字符串上限 32767 字符，服务端把响应切成 24000 字符一块（`seq/total`），
  客户端拼回。实测 45.8 KB 的 `brief.md`（长 `TASK.md`）一次请求正常重组。
- **权限实时判定**：服务端每次请求都重新 `hasPermissions(...)`。实测 `deop` 之后下一次请求
  立刻 403，`op` 回来立刻 200——不存在「握手时缓存了权限」的漏洞。
- **客户端不硬拦**：客户端只在「没连上服务器」时回 503；权限由服务端判定并透传它的 403
  （否则 OP 在进服后才授予时会一直 403，本机测试就踩过这个坑）。

### 局限（如实写）

- 只在你**已连接**时可用；断线后客户端端点回 503 `no_tunnel`。
- 传输走原版自定义 payload 通道，超大文件（`.jfr`、几 MB 的报告）不通过隧道传。
- 隧道不改变「只读」边界：`/dump` 也只在服务器上写黑匣子目录，不改世界。

## 开发

```bash
./gradlew runServer          # 首次会准备 NeoForm 产物；本机已有缓存，约 10s 起服
./gradlew build              # 产物 build/libs/observa-0.1.0.jar
./gradlew runClientJoin      # 启动客户端并自动连 127.0.0.1:25565（测隧道用）
./gradlew runClientSingle    # 启动客户端并直接进 run/saves/pause-test（测 ESC 暂停用）
```

后两个 run 配置在 `build.gradle` 的 `runs {}` 里（`clientJoin` / `clientSingle`）。`runClientSingle`
需要先有存档：把 `run/observa-dev` 复制成 `run/saves/pause-test` 即可（或改 `programArguments` 里的存档名）。

`run/` 下是开发用服务器（`eula.txt` 已置 true、`online-mode=false`、RCON 25575）。构造测试场景：

```bash
python scripts/rcon.py 'summon minecraft:zombie ~ ~ ~' 'list'
```

注意：`~ ~ ~` 是控制台位置（世界出生点）。绝对坐标可能落在未加载区块，实体不会真正进入世界——排查这类「明明 summon 成功却查不到」的问题时，先确认目标区块已加载。

## 已验证的事实（本机实测，非推测）

- `com.sun.net.httpserver` 在 FML 启动层可用，不需要退到 Netty（这是计划里最大的技术风险，已排除）
- 端口 8724 绑定成功；token 自动生成；401/404/405 请求都走结构化 JSON
- 空载服务器：`mspt p50≈0.2ms p99≈0.6ms`，TPS 20.0，841 个已加载区块
- 大 `fill`（4×2.8 万方块）后 `p99` 从 3.5ms 升到 8.2ms——分位数确实在记录尖峰
- `summon` 3 只僵尸 + 1 个掉落物 + 2 个盔甲架后：`total=8`、`item_entities=1`，`by=type|dimension|chunk` 三种切分自洽
- **剖析开销**：空载 60 秒不录 vs 60 秒 5ms 采样，`p50` 0.49 → 0.46 ms、`p95` 0.77 → 0.75 ms、`p99` 1.06 → 1.12 ms——开销在噪声量级里（<0.2% 的 tick 预算）
- **剖析空载真实性**：同一空载服务器录 75 秒，`idle_pct=95.04%`（`samples=15001, busy=744`），Top 都是 `ServerFunctionManager.executeTagFunctions`、`NeoForgeEventHandler.preServerTick` 这类真·每 tick 杂项，连 Observa 自己的 `MetricsService.onTickPre` 2.82% 都如实出现
- **剖析命中已知热点**：`fill` 压测（mspt 60ms+、tps≈7）下 `busy_samples=1601/1601`，Top-1 `BlockBehaviour$BlockStateBase.updateShape` self 37.5%，调用树从 `MinecraftServer$$Lambda.run` 一路展开 37 层到 `FillCommand.fillBlocks → Level.setBlock → NeighborUpdater.executeShapeUpdate → updateShape`，与预期路径完全一致（`scripts/m2_accept.py` 全绿）
- **报告体积**：剪枝后 25 个热点 + 完整 37 层热点链 = 10.3 KB；计划期拍的 8 KB 太紧，实际预算改为 16 KB
- **JFR 摘要可用**：`fill` 压测 10s 的录制 266 KB / 3358 事件，`BlockPos` 分配占 24.3%、`CollectingNeighborUpdater$ShapeUpdate` 22.7%、`server.level.Ticket` 22.0%，GC 15 次共暂停 3.6s（最长 560ms，其中 5 次是 `G1 Humongous Allocation`）——与同一负载下 MSPT 60ms+ 的现象对得上（样本见 `docs/examples/jfr-report.json`）
- **JFR 开销**：空载 30s 不录 p50 0.42/p95 0.88/p99 1.15 ms vs 录制中 p50 0.38/p95 0.94/p99 1.36 ms
- **黑匣子自动触发**：`fill` 压测下 MSPT 408ms 持续 5s 后第 6 秒落盘 `20261026-003718-mspt-408ms.json.gz`（3.0 KB，含 7 帧环形缓冲、12 条异常、200 行日志）
- **黑匣子停服兜底**：`on_shutdown=true` 时 `-shutdown.json.gz` 在 `Stopping server` 之前写完（2.9 KB）
- **`/logs` 归并**：启动后 120s 内 11 条 WARN 按签名归成 11 组（命令参数歧义/bind 警告各计 1 次），`level` 下限与 `since` 窗口生效
- **`/dump` 延迟**：构建 + gzip 全量（含 200 行日志、20 条异常）实测 `build_ms=2`
- **入职链接**：进存档时同步写 `observa/agent.md`（5.1 KB）与 `TASK.md` 模板，日志打印文件路径与 `http://127.0.0.1:8724/observa/v1/brief.md`；`GET /brief.md` 5523 B `text/markdown`，`GET /brief` 为同内容 JSON
- **任务嵌入**：`TASK.md` 改写为两行自然语言后，`brief.md` 的「你的任务」一节原样出现（模板未改写时 `task` 为 `null` 并提示怎么写）
- **隧道端到端（真客户端）**：开发客户端 `--quickPlayMultiplayer` 连本机开发服，客户端端点绑 8724（服务器因占用退到 8725）；`health` 返回 `via:"tunnel"`、`player:"Dev"`；`metrics/entities/snapshot/logs/blackbox/profiler/status/brief` 全 200 且与服务器本机读到的数值一致（压测时两边同为 `tps 9.0 / mspt 109.52`）
- **隧道写操作**：`POST /dump {"label":"tunnel-final"}` 在服务端落盘 `20261026-034732-manual-tunnel-final.json.gz`（9.1 KB）
- **隧道分块**：把 `TASK.md` 写到 30 KB 后，经隧道取 `brief.md` 得到 45.8 KB、400 条任务行完整（2 块重组）
- **隧道权限**：`deop Dev` 后下一次请求立即 403（`this player is below tunnel.permission_level 2`），`op Dev` 后立即 200——实时判定，不缓存
- **隧道不支持项**：`GET /profiler/report/*` 回 501 `not_supported_over_tunnel` 并给出「到服务器本机取」的 next
- **`/network`（真客户端）**：1 名玩家在线、服务器 20 TPS 时出方向约 465 帧/s、4.8 KB/s，入方向约 1 帧/s；`kill @e` 后瞬间抬到 2192 帧/s（死亡动画与掉落物同步），30 秒后回落到 1243 帧/s——计数器确实跟着真实流量走，方向不串（出/入是两个独立处理器）
- **`/system` 主机侧数据**：进程 CPU 4.34%、整机 9.7%、24 核、物理内存 16.89 GB（用 60.83%）、swap 77.9 GB、D: 592 GB 用 43.95%——Windows 上整机 CPU 也能取到（早前注释说恒 `null` 是错的，已改）
- **`/memory` 明细**：`pools` 给出 Metaspace 106 MB 等分池用量，`collectors[]` 带 `last_gc` 的逐池 `before/after/reclaimed`
- **行号级剖析**：`fill` 压测 8s@5ms，`lines` 直指 `PalettedContainer.get:158` 29.98%、`Level.getChunk:202` 23.36%、`LevelChunkSection.setBlockState:61` 7.18%——方法名相同但行号区分了「同一方法里的不同热点」
- **JFR 执行采样**：同一负载 8s 录制 294 个执行样本，Top `ServerChunkCache.getChunk:153` 22.11%、`handleNeighborChanged:695` 14.97%；分配点 `BlockPos <- BlockPos$MutableBlockPos.immutable:630` 占 74.6%（16.47 GB）
- **堆直方图**：350 MB 堆 → `dump_ms=392`、`parse_ms=575`、4,852,926 实例、232,546,088 B、14,284 类、skipped 541；浅尺寸与 `jcmd GC.class_histogram` 差 1–2%，实例数逐类对齐
- **空世界重启冒烟**：清掉开发世界重新生成后 11 个端点全 200，`/health` 的 `via` 为 `"direct"`，`/dump` 落盘 3.6 KB，无历史测试残留刷屏
- **`/logs` 不再截断（M8）**：向 `latest.log` 追加 700 条合成 ERROR 后，`events_matched=699`、`entries_retained=712`（旧上限 400 时同一份日志只剩 328 条），`entries_dropped=0`、`log_bytes_skipped=0`
- **卡死时环形缓冲照常增长（M8）**：`POST /selftest/stall {"ms":6000}` 让服务器线程停 6 秒，期间心跳线程推进了 4 帧 `frozen:true`（`since_tick_ms` 2077→3078），`/dump` 的 payload 里可直接看到
- **`stall` 自动落盘（M8）**：启动 64 秒后再来一次 6 秒停摆 → `20261026-053814-stall-5s.json.gz`（4683 B），`trigger.detail{since_tick_ms:5277, frozen_frames:8, server_tick:1155, last_tick_at:...}`，ring 尾部 8 帧全是 `frozen:true`，`snapshot_age_ms=5928`（正好等于停摆时长——旧数据不再冒充新鲜数据）
- **启动宽限生效（M8）**：启动后 30 秒时同样的 6 秒停摆**没有**落盘，`/blackbox` 的 `stats.suppressed_by_grace=1`、`in_grace=true`；64 秒后同样操作立刻落盘——世界加载期不再 4/4 假阳性
- **`mspt` 触发未被宽限误伤（M8）**：grace 过期后用命令方块 `fill` 压测 9 秒，仍自动落盘 `-mspt-96ms.json.gz`
- **`/logs?level=INFO`** → 400，`detail` 明说只保留 WARN/ERROR/FATAL 并指向原始日志（不再静默降级）
- **区块生成计数（M9）**：`forceload` 64 个新区块后 `generated_total=81`、`generated_per_s=3.24`、`loaded` 841→1570；同一区域重载后 `generated_total` 不涨——磁盘读回不算生成
- **ESC 暂停不误报（M9，真客户端）**：单机进存档（集成服 8725）按 ESC → `/health` 立即 `paused:true`，停 87 秒（grace 已过期）期间 `stall_dumps=0`、`suppressed_by_pause=1`，没有落盘；再按 ESC 恢复后 `paused:false`；此时用 `/selftest/stall` 真卡 6 秒 → 正常落 `20261027-000224-stall-5s.json.gz`
- **暂停状态可见性（M9）**：ESC 期间 `/metrics` 带 `paused:true` 和一条解释 note；`/blackbox` 的 `stats` 有 `paused`、`suppressed_by_pause`、`pause_forced`

## 已知边界

- `/heap/histogram` 是**快照**不是时间序列：要看「是不是泄漏」得隔一段时间手动触发两次，比较同一类的实例数
- `live:true` 的 dump 会在 JVM 里触发一次 full GC 并 STW 约 0.4 秒（350 MB 堆实测）；`keep_file:false` 时 `.hprof` 解析完即删
- 浅尺寸是估算（引用宽度按 VM 选项折算、8 B 对齐），不是 retained size；`skipped_records` 是非堆记账记录，可忽略
- 隧道只在你已连接、且权限达标时可用；断线后客户端端点回 503 `no_tunnel`，`.jfr` 原件不通过隧道传
- 同机既开服又开客户端会各自占一个端口（默认 8724 起步自动 +1），以日志/`brief.md` 为准
- JFR 摘要只覆盖上表五个事件；要看完整事件流用 `.jfr` 原件
- **区块生成计数只统计「现造地形」**：Mixin 挂在 `ChunkStatusTasks#generateNoise`（噪声生成步），从磁盘读回的区块已经是 FULL 状态、不会再走这一步，所以不会重复计数；`generated_per_s` 是 60 秒窗口平均，跑图时是突发值。
- ESC 暂停期间所有指标都会停在暂停那一刻（`age_ms` 持续变大）；这是集成服务器的行为，不是采集故障
- `/logs` 只保留最近 5000 条 WARN+；INFO 需要读 `logs/latest.log`。`entries_dropped>0` 或 `log_bytes_skipped>0` 时说明这份视图不完整
- 启动宽限（`min_server_ticks` / `grace_seconds`）会压掉世界加载期的自动落盘——那是刻意的，需要开局数据时手动 `POST /dump`
- 未做客户端 UI（不需要：HTTP 就够了）
- JVM native crash（SIGSEGV）写不出落盘文件，只能靠崩溃报告；这条写进 M4 文档
- 只有服务器线程能安全读实体表，HTTP 线程一律读不可变快照——后续加端点必须守这条
