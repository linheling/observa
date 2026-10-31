# Observa 诊断 SOP

目标：agent 或人拿着症状进来，**两次请求内**定位到"卡在哪一类问题"，再决定要不要深挖。

所有请求都需要 header：`Authorization: Bearer <token>`（`config/observa/token.txt`）。

```bash
BASE=http://127.0.0.1:8724/observa/v1
H="Authorization: Bearer $(cat config/observa/token.txt)"
```

## 00. 第一次接手：读入职说明，不要瞎猜

```bash
curl -s -H "$H" $BASE/brief.md        # 任务 + 当前状态 + 端点表 + 工具配方 + 判读口径
```

`brief.md` 的「你的任务」一节来自 `<gameDir>/observa/TASK.md`（人写的）。没有文件系统时，
`$BASE/brief` 给同样内容的 JSON。日志里进存档时会打印这两条，路径/地址直接可抄：

```
[observa] agent brief: <gameDir>\observa\agent.md
[observa] agent brief over http: http://127.0.0.1:8724/observa/v1/brief.md
```

## 0. 60 秒体检

```bash
curl -s -H "$H" $BASE/health      # 服务活着吗？端口对不对？
curl -s -H "$H" $BASE/snapshot    # 一次拿全貌
```

看三个地方，按顺序：

1. `age_ms` > `metrics.interval_ms * 2` → 采集卡住了，别看后面的数，先查服务器是不是卡死。
2. `tps.1m` / `headline` / `flags` → 是不是真的慢。
3. `mspt.avg_1m_ms` vs `mspt.p99_ms` → **持续性**还是**尖峰**，这两类的处置完全不同。

## 1. 症状 → 路径

| 症状 | 先看 | 判读 | 下一步 |
|---|---|---|---|
| 玩家喊卡，TPS 掉 | `GET /metrics?window=10` | `headline` = degraded/critical；`tps.1m < 19` | 看 `mspt.p99` 与 `avg_1m` 的差距 → 走 1a 或 1b |
| 1a 持续高（avg 与 p99 都 > 45ms） | `GET /entities?by=type&top=10` | `total` 暴涨 / `item_entities` 上万 / 某类型独占 | 实体堆积；按类型定位是刷怪塔、掉落物还是插件刷的 |
| 1b 尖峰（p99 ≫ avg，avg 正常） | `GET /profiler/start`（M2） | 定期 GC、周期任务、区块加载 | 抓 30s 采样看热点方法 |
| 偶发瞬卡，TPS 看着正常 | `/metrics?window=1` | `tps.1s` 掉但 `1m` 正常 = 刚发生 | 立刻 `/snapshot` 留存；开黑匣子回溯（M4） |
| 内存涨 / 疑似泄漏 | `GET /memory` | `heap_used_pct` 持续爬、`gc_pause_total_ms` 涨但 `heap_used` 不回落 | `/heap/histogram` 看 `by_mod` → 隔 10 分钟两次 → `/heap/histogram/compare` 看 delta → `/heap/retained` 看谁能救（§13.1） |
| 区块相关卡顿 | `GET /chunks` + `/entities?by=chunk&top=5` | `loaded` 异常高；单区块实体数惊人 | 查加载器（chunkloader / 传送门 / 视距） |
| 玩家掉线、网络差 | `GET /network`（M2） | 包速率、玩家延迟 | — |
| 崩服 / 异常刷屏 | `GET /logs?since=300&level=ERROR`（M4）+ `GET /blackbox` | 去重后的错误计数与首次出现时间 | 拿黑匣子里的环形缓冲回放卡顿前 5 分钟 |

## 2. 判读口径（容易读错的地方）

- **`tps.1s` 是瞬时值**：正常的 GC 停顿也会让它抖一下；判"服务器完了"看 `1m`。
- **`tps.20` 不等于不卡**：一次 300ms 的瞬卡在 1m 均值里看不出来，但 `p99` 会留下痕迹。**瞬卡看 p99，持续卡看 avg。**
- **`mspt.now_ms` 是上一 tick 的耗时**，不是平均值；单看它容易被一个空闲 tick 骗过。
- **`null` 不是 0**：`generated_per_s: null` 表示速率窗口还没攒够样本，不是"零生成"；`generated_total` 才是硬计数。
- **实体只统计已加载区块**：卸载区块里的实体不在 `total` 里。数值下降可能是区块卸载，不一定是实体被清。
- **`sample_ticks`**：`< 1200` 说明服务器刚起不到一分钟，分位数还没稳定，别据此下结论。

## 3. 请求模板

PowerShell：

```powershell
$h = @{ Authorization = "Bearer $(Get-Content config\observa\token.txt)" }
$b = "http://127.0.0.1:8724/observa/v1"
$m = Invoke-RestMethod -Headers $h "$b/metrics?window=10"
$m.data.headline
$m.data.flags
Invoke-RestMethod -Headers $h "$b/entities?by=type&top=10" | ConvertTo-Json -Depth 6
```

只想看该看的部分（省 token）：

```bash
curl -s -H "$H" "$BASE/metrics?window=10" \
  | jq '{headline: .data.headline, flags: .data.flags, tps: .data.tps,
         mspt: {avg: .data.mspt.avg_1m_ms, p99: .data.mspt.p99_ms},
         entities: .data.entities.total, items: .data.entities.item_entities}'
```

## 4. 排障顺序（避免白跑）

1. `/health` 确认接口活着、端口对（端口可能因占用自动 +1）。
2. `/snapshot` 拿全貌，先看 `flags`——`flags` 非空时它已经指出问题了，不用自己推断。
3. 只有 `flags` 为空但玩家仍在抱怨时，才需要 `/profiler` 这类重手段（而且此时往往说明卡顿是间歇性的：用长窗口 + 黑匣子，别用短剖析）。
4. 复现类问题：先确认黑匣子开着（`blackbox.enabled`），复现后直接读落盘文件（`GET /blackbox`），不要靠「再卡一次试试」。

## 5. 采样剖析怎么用（M2 已实现）

```bash
# 1. 开 20 秒、5ms 间隔的采样（间隔越小越能抓到短热点，但样本噪声也越大）
curl -s -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"mode":"sample","seconds":20,"interval_ms":5}' $BASE/profiler/start
# 2. 20 秒后（或提前 stop）取报告；stop 返回 409 表示到点已自动结束，改查 report/{id}
curl -s -H "$H" $BASE/profiler/status
curl -s -H "$H" $BASE/profiler/report/sample-1 | jq '.data | {idle_pct, busy_samples, top: .top[:8]}'
```

读法（顺序很重要）：

1. **先看 `idle_pct`**。>90% 说明服务器大部分时间在等 tick：卡顿是间歇性的，短剖析抓不到，换黑匣子/长窗口。接近 0% 说明主线程一直饱和：Top 列表就是答案。
2. **看 `top[].self_pct`**：时间真正停在哪个方法。`PalettedContainer.get` 55% 这种就是「读了太多方块」。
3. **看 `top[].total_pct`**：从哪进来的。`FillCommand.fillBlocks` total 98% + self 1.5% 说明它是发起者，不是耗时点。
4. **`tree` 只保留了通往热点的路径**：从 `MinecraftServer$$Lambda.run` 顺着 children 走，就是"谁调了谁"。深度截断会置 `tree_truncated: true`；节点预算耗尽也会（此时优先相信 `top`，树被剪了）。
5. **`busy_samples < 200` 别下结论**（`busy_samples=0` 的结果必然是空 `top`，`notes` 里会明说）：200 个样本时 1% 只有 2 个样本，噪声极大。要么录更久，要么把 `interval_ms` 降到 1–2ms。
6. **JDK 帧默认不显示**：看到 `HashMap.get` 这类热点被吞了，把 `profiler.include_jdk_frames` 打开再录一次。

## 6. 已知热点自检（验证剖析器本身没坏）

怀疑「剖析器是不是指错了」时，用 `scripts/m2_accept.py`：它用两个命令方块每 tick 重写 32×32×32 区域造出确定的方块写入负载，再断言 Top-3 命中 `updateShape`/`setBlockState` 之类且调用链回到 `FillCommand`。前置：`enable-command-block=true`（默认 `false`，命令方块静默不执行）。

## 7. JFR 怎么用（M3 已实现）

采样剖析告诉你「卡在哪个方法」，JFR 告诉你「为什么」——分配压力、GC 暂停、锁竞争、异常。

```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"mode":"jfr","seconds":30}' $BASE/profiler/start
curl -s -H "$H" $BASE/profiler/report/jfr-1   | jq '.data.summary | {events, gc, allocations: .allocations.top[:5], exceptions: .exceptions.top[:5]}'
```

读法：

1. **先看 `gc`**：`longest_pause_ms` 超过 100ms 就是玩家能感觉到的顿卡；`total_pause_ms / duration_ms` 高于 10% 说明堆压力大。cause 全是 `G1 Evacuation Pause` 时，问题在**年轻代晋升太快**，去看 `allocations`。
2. **`allocations.top`**：分配最多的类。实测 `fill` 压测里 `BlockPos` 42%、`CollectingNeighborUpdater$ShapeUpdate` 19.8%——方块更新链每格都 new 一个 `BlockPos`，这解释了为什么一次 `/fill` 能把 GC 触发 12 次。
3. **`exceptions.top` 先看栈再下结论**：JFR 记的是「抛出」，包括被内部 catch 的。`NoSuchMethodError` 来自 JDK 方法句柄 bootstrap 重试、`SocketTimeoutException` 来自 RCON/HTTP 超时，都不是业务 bug。
4. **`monitor_enter` / `thread_park`**：`monitor_enter` 有量说明有锁竞争（常见于 mod 自己的同步容器）；`thread_park` 的量主要反映线程池空闲（2445 次 / 220s 在空转服务器上很常见），单独看没有意义，要看 `top` 里是哪个类在 park。
5. **摘要读不够就下原件**：`config/observa/jfr/jfr-N.jfr` 用 `jfr print --events jdk.ObjectAllocationSample --stack-depth 10 <file>` 看完整调用栈。

## 8. 黑匣子怎么用（M4 已实现，M8 加了卡死检测）

黑匣子是给「**已经卡过、但没抓住**」的场景准备的：环形缓冲一直在，触发时自动落盘，不用守着看。

默认配置：MSPT > 50ms 持续 5s 自动落盘（`blackbox.mspt_threshold_ms` / `sustain_seconds`）。

```bash
curl -s -H "$H" $BASE/blackbox                      # 历次落盘：file/bytes/modified_at/reason
# 复现前手动打点，把「操作前」和「操作后」分成两份
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"label":"before-raid"}' $BASE/dump
```

单份文件（`.json.gz`）的读法：

1. **`trigger.detail`**：触发那一刻的 `mspt_now_ms`；`sustain_seconds` 说明它已经卡了多久。
2. **`ring`**：触发前的帧序列。判读看形状而不是单点——`mspt_now` 阶梯上升 + `heap_used` 同步上升 ⇒ 泄漏；`mspt_now` 尖刺但 `heap` 平稳 ⇒ 一次性事件（区块生成、实体爆发）；`gc_count` 在尖刺前后跳 ⇒ GC 的锅。
3. **`snapshot`**：触发瞬间的全量快照，用 `flags` 和 `entities.entity_breakdown` 找堆积。
4. **`errors` + `log_tail`**：卡顿前后有没有 WARN/ERROR（`Can't keep up` 会出现在这里，顺便能确认卡了多久）。
5. **`snapshot_age_ms`**：如果它比 `metrics.interval_ms` 大很多，说明采集本身被卡住了——那卡的就不只是 tick。

配合 `/logs` 看实时异常：

```bash
curl -s -H "$H" "$BASE/logs?since=300&level=WARN&limit=20"   | jq '.data.top[] | {level, logger, count, message, last_at}'
```

读法：**按 `count` 降序**先看重复出现的；`count=1` 的 `WARN` 多数是启动期噪声（命令歧义提示、`online-mode=false` 警告）。`logger` 字段能直接指出是哪个 mod 在报。

### 8.1 `reason="stall"`：tick 停了，不是采集坏了

`ring` 尾部连续出现 `frozen: true` 就是服务器线程停摆的证据（心跳线程每 500ms 补一帧，
`since_tick_ms` 一路涨）。要点：

- `trigger.detail.since_tick_ms` = 落盘瞬间停了多久；`frozen_frames` = 这段停摆补了多少帧
- `snapshot.mspt` 与 `snapshot_age_ms` 是**停摆前最后一次成功快照**；`snapshot_age_ms` 接近停摆时长是正常的
- `mspt` 触发（reason=`mspt`）和 `stall` 触发是两条独立路径：前者看「每 tick 太慢」，后者看「tick 根本不发生」
- 自动落盘前有启动宽限（`min_server_ticks`、`grace_seconds`）。`/blackbox` 的 `stats.suppressed_by_grace` 会告诉你有没有被压掉过；要开局数据就手动 `POST /dump`

### 8.2 工具自己会不会骗你：三个计数先看

| 计数 | 位置 | 含义 |
|---|---|---|
| `entries_retained` / `entries_dropped` / `log_bytes_skipped` | `/logs` 响应 | 保留了几条、被 5000 上限挤掉几条、因落后 64MB 跳读多少字节 |
| `stats.suppressed_by_grace` / `suppressed_by_cooldown` | `/blackbox` 响应 | 自动落盘被宽限/60s 冷却压掉几次 |
| `stats.heartbeat_running` / `stats.ring_size` | `/blackbox` 响应 | 停摆检测本身是否在跑、环形缓冲多长 |

只要这三个数非零，就说明「你看到的不是全部」，先读原始日志或手动补一份 dump，
不要拿被截断的视图下结论。

### 8.3 ESC 暂停不是卡死

单机（集成服务器）按 ESC 会停 tick，行为和卡死一模一样，但原因是玩家主动暂停。判定：

- `/health` 的 `paused` 为 `true` → 整段停 tick 都不用查（`/metrics` 也会附 note 说明 `age_ms` 为什么在涨）
- `/blackbox` 的 `stats.suppressed_by_pause > 0` 说明确有停摆被暂停逻辑压掉过
- 想要「暂停期间也落一份」：直接 `POST /dump`，手动落盘不受暂停/宽限/冷却影响

调试入口（`[debug] allow_selftest=true` 时）：`POST /selftest/pause {"paused":true}` 模拟暂停、
`POST /selftest/stall {"ms":6000}` 真阻塞服务器线程。两者配合可以在不改客户端的情况下回归这条逻辑。

## 9. 两条请求定位卡顿（SOP 的浓缩版）

```bash
# 1. 现在卡不卡、卡在哪一类
curl -s -H "$H" "$BASE/metrics?window=60" | jq '.data | {headline, flags, tps, mspt}'
# 2a. 持续卡：抓 20 秒采样剖析 → Top self + 调用链
curl -s -X POST -H "$H" -H 'Content-Type: application/json' -d '{"mode":"sample","seconds":20,"interval_ms":5}' $BASE/profiler/start
# 2b. 间歇卡：直接读最近一份黑匣子
curl -s -H "$H" $BASE/blackbox | jq '.data.dumps[0]'
```

## 10. 服务器上不让你开端口？走隧道（M6）

条件：服务端装了 Observa、**你的客户端也装了**、你在服务器上的权限 ≥ `tunnel.permission_level`
（默认 2 = OP）。

```bash
# 客户端日志里先确认这两行（端口以日志为准）
#   [observa] tunnel endpoint on http://127.0.0.1:8724/observa/v1/brief.md (client side)
#   [observa] tunnel handshake: server version 0.1.0 permitted=true (...)
TOKEN=$(cat config/observa/token.txt)          # 客户端自己那份 token
BASE=http://127.0.0.1:8724/observa/v1
curl -s -H "$H" "$BASE/health" | jq '.data'    # 看到 via:"tunnel" 就是服务端数据
```

- 端点用法与单机完全一致；`/profiler/report/*` 走隧道会回 **501**（报告太大/需落盘路径），
  要看完整报告得在服务器本机取。
- `/profiler/start`、`/profiler/stop` **不**通过隧道开放：远程启停录制会写服务器磁盘，先不提供。
- 断线后客户端端点回 `503 no_tunnel`；权限是**每次请求实时判**的，`deop` 立刻 403、`op` 回来立刻 200。
- 同机既开服又开客户端时两边各占一个端口（默认 8724 起，后启动的自动 +1），以日志和
  `brief.md` 里的「实际端口」为准。

## 11. 主机是不是瓶颈（`/system`）

```bash
curl -s -H "$H" $BASE/system
```

- `cpu.process_load_pct` 高而 `system_load_pct` 低 → 就是 MC 在吃 CPU；两者都高 → 机器上还有别的东西。
- `memory.physical_used_pct` 接近 100% 且 `swap_used_pct` 在涨 → 堆没事也会因为换页卡；
  这种卡在 `/metrics` 里表现为 mspt 尖刺但 GC 计数不动。
- `disk.free_bytes` 掉得比日志增长快 → 有别的进程在写盘（黑匣子、`.hprof`、崩溃报告、备份）。
- 隔几秒取两次自己算差，比高频轮询准；`null` 表示该平台取不到，不是 0。

## 12. 网络（`/network`）

```bash
curl -s -H "$H" "$BASE/network?top=10"
```

- `ping_ms` 来自服务端 keep-alive 往返（和 `/spark ping` 同源）。单个玩家高而其他低 → 那名玩家链路问题。
- `sent.bytes_per_s` 高：服务器在往他推东西（区块、实体、时间同步）。挂机也会推——别以为 0 才正常。
- `packets` 是**帧数**不是逻辑包数：一个包拆成多帧会多算，每 tick 还有 2 帧 bundle 分隔符。
  判断「谁在刷包」看数量级和相对变化，不要拿它跟抓包软件的包数对账。
- `packets_per_s`/`bytes_per_s` 需要两次采集；玩家刚进来那次是 0。
- 服务端视角看不到「客户端到 ISP」那一段，要那段只能在客户端机器上抓。

## 13. 堆里谁占着（`/heap/histogram`）

`/memory` 只说总量，`/profiler`（JFR）只说分配速率。要知道「谁占着不放」必须数存活对象。

```bash
curl -s -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"live":true,"top":25}' $BASE/heap/histogram/start
curl -s -H "$H" $BASE/heap/histogram        # state: running -> done
```

- **判泄漏**：隔 10 分钟抓两次，看同一类（比如某个 mod 的 `ChunkCache$Entry`）实例数是否单调涨。
- `live:true` 会触发一次 full GC + 停顿（350 MB 堆实测约 0.4 秒），别在玩家投诉卡顿的当下点。
- 浅尺寸是估算（按压缩指针折算、8 B 对齐，与 `jcmd` 差 1–2%），也**不是 retained size**：
  它回答「这类对象本身占多少」，不回答「删掉它能不能救内存」——后者用 `/heap/retained`（§13.1）。
- `skipped_records` 是非堆记账记录（栈帧/栈轨迹），几十到几百条都正常。
- 需要精确数字时，拿 `keep_file:true` 留下的 `.hprof` 用 JDK 自带 `jcmd`/MAT 复核。

### 13.1 哪个 mod 占着、什么在涨、删掉谁能救（按这个顺序问）

```bash
# 1) 哪个 mod：同一份直方图里就有 by_mod[]，每个类行还带 mod_id
curl -s -H "$H" "$BASE/heap/histogram?top=25" | jq '.data.by_mod[0:5], .data.mod_mapping'

# 2) 什么在涨：隔几分钟各抓一次（结果保留最近 5 份），再比
curl -s -X POST -H "$H" -d '{"live":true}' $BASE/heap/histogram/start
curl -s -H "$H" "$BASE/heap/histogram/compare?top=20" | jq '.data.mods[0:5], .data.classes[0:5]'

# 3) 删掉它能救多少：建引用图 + 支配树（比直方图贵，几分钟一次就够）
curl -s -X POST -H "$H" -d '{"live":true}' $BASE/heap/retained/start
curl -s -H "$H" $BASE/heap/retained | jq '.data.total_retained_bytes, .data.classes[0:5]'
```

- `by_mod` 是「类属于谁」，不是「谁 new 的」：模组服务器里吃内存的常是 vanilla/库类
  （`CompoundTag`/`ItemStack`/`PalettedContainer`/`fastutil`/Netty）。问「谁在 new」看 JFR 的
  `allocations.top_sites[].site_mod_id`，问「谁持有」看 `/heap/retained` 的 `dominated_by_class`。
- `mod_mapping.mods_indexed: 0` 说明这次归因完全不可用（开发环境 `mods/` 是空的）。
- `/heap/retained` 的 `classes[].retained_bytes` 是「删掉这类对象能释放多少」，按支配树分区、
  互不重叠，相加 ≈ `total_retained_bytes`；`instances_counted` 是被计入的最上层对象数，不是实例总数。
- `unreachable_pct` 高只是这份 dump 里混了垃圾（JVM 可能跳过 pre-dump GC），retained 只算可达集，
  数字不受影响；把它当「这份 dump 有多脏」看。
- 三者的成本不同：`/heap/histogram` 秒级，`/heap/histogram/compare` 免 dump，`/heap/retained`
  要多花几秒建图（4.1 M 对象实测 7.2 s 分析 + 一次 STW dump）。**先看 by_mod，再比 delta，
  最后才上 retained**——前两步便宜得多。

## 14. 行号级热点（剖析 `lines[]` / JFR `execution_samples`）

- 采样剖析报告里 `lines[]` 是「同一帧按源码行再分一次 self 样本」。`PalettedContainer.get:158` 与
  `:202` 是两个不同问题，方法名级的 `top` 会把它们混成一条。
- JFR 的 `execution_samples` 是 JFR 自己每 10ms 的执行采样，粒度和行号与 `lines[]` 独立——
  两边指到同一行时，基本可以确定就是那一行。
- 行号是**编译后**的实际行；混淆/内联过的代码可能指到调用方或 `-1`（表示无行号信息）。
- `allocations.top_sites[]` 给「类 <- 分配点（方法:行）」。看到某类占了 70% 时，先确认是一个分配点
  还是几十个分配点，处理方式完全不同。

## 15. 区块生成速率（`/chunks`）

```bash
curl -s -H "$H" $API/chunks
# {"chunks":{"loaded":1570,"generated_per_s":3.24,"generated_total":81,"generated_window_seconds":60},...}
```

读法：

- `generated_total` 是**硬计数**（自进程启动），`generated_per_s` 是 60 秒窗口平均——跑图时数值会忽高忽低，看总量更稳
- 要算「这 10 分钟生成了多少」：`(后一次 generated_total - 前一次 generated_total) / 600`
- `generated_per_s: null` 表示窗口还没攒够样本，不是零生成
- 生成是突发负载：一边跑图一边看 `/metrics` 的 `p99` 与 `/network` 的帧率，能区分「生成吃主线程」还是「发包吃带宽」
- 只统计 `generateNoise` 步：磁盘读回的区块不算；如果你刚 `/forceload` 一片**已生成过**的区域，`generated_total` 不会涨（这是对的）
