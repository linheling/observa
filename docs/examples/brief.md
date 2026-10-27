# Observa —— 你的入职说明

> 这是 Minecraft 服务器进程内的只读观测接口，由 Observa 0.1.0 提供。
> 接口只绑 `127.0.0.1`，只读（不会改世界、不会执行游戏命令），所有请求要 Bearer token。

| 项 | 值 |
|---|---|
| 接口根地址 | `http://127.0.0.1:8724/observa/v1` |
| 实际端口 | `8724`（不是 8724 时说明默认端口被占用，以本文件为准） |
| token 文件 | `D:\C\MC\test\run\config\observa\token.txt` |
| 生成时间 | `2026-10-26T16:06:06.905780500Z` |
| 限速 | 全局 20 请求/秒，超了回 429；批量信息用 `/snapshot` 一次拿全 |

## 你的任务

- 玩家说主城附近走两步就卡，先看 /entities?by=chunk&top=20 有没有掉落物堆积。
- 结论要带证据（端点 + 字段值），不要改世界。

## 第一步：拿到 token 并发一个请求

```bash
TOKEN=$(cat "D:\C\MC\test\run\config\observa\token.txt")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8724/observa/v1/manifest
```
（上面是 Windows 路径；git-bash / WSL 里换成 `/d/...` / `/mnt/d/...` 形式。）

PowerShell：

```powershell
$H = @{ Authorization = "Bearer $(Get-Content 'D:\C\MC\test\run\config\observa\token.txt')" }
Invoke-RestMethod -Headers $H http://127.0.0.1:8724/observa/v1/manifest
```

## 现在这一刻的状态

| 项 | 值 |
|---|---|
| headline | healthy: tick rate is at full speed |
| flags | （无） |
| TPS（1s/10s/1m） | 20.0 / 20.0 / 20.0 |
| MSPT now / 1m avg / p95 / p99 | 0.13 / 0.68 / 0.76 / 5.1 ms |
| 堆 | 335 / 4028 MiB，GC 56 次（累计暂停 235 ms） |
| 实体 / 区块 / 玩家 | 0 / 841 / 0 |
| 快照年龄 | 698 ms（远大于 `metrics.interval_ms` 说明采集被卡住了） |

## 数据在哪：按「我想回答什么」选端点

| 我想回答 | 调什么 |
|---|---|
| 接口是否活着、端口对不对 | `GET /health` |
| 现在整体健康吗、是什么病 | `GET /metrics?window=60` → `headline` + `flags` |
| 一次拿全貌（省往返） | `GET /snapshot` |
| 实体/掉落物/经验球堆在哪 | `GET /entities?by=type|dimension|chunk&top=20` |
| 内存/GC 有没有压力 | `GET /memory`（分池 + 各收集器上一次 GC 的逐池回收） |
| 谁占着内存不放（存活实例） | `POST /heap/histogram/start {"live":true,"top":25}` → 轮询 `GET /heap/histogram`（有 STW，约 0.4s） |
| CPU/物理内存/磁盘是不是瓶颈 | `GET /system` |
| 玩家延迟、谁在刷包/吃带宽 | `GET /network?top=10` |
| 已加载区块数 / 这片时间新生成了多少地形 | `GET /chunks`（`generated_total` 是硬计数，`generated_per_s` 是 60s 平均） |
| 谁在吃主线程时间（热点方法 + 调用链） | `POST /profiler/start {"mode":"sample","seconds":20,"interval_ms":5}` → `GET /profiler/report/sample-1` |
| 为什么 GC 频繁 / 谁在分配 / 哪一行热 / 锁竞争 | `POST /profiler/start {"mode":"jfr","seconds":30}` → `GET /profiler/report/jfr-1`（含 `execution_samples` 行号级热点与 `allocations.top_sites`） |
| 刚卡那一下发生了什么（事后回溯） | `GET /blackbox` → 读最近一份 `.json.gz` |
| 现在打一份快照到磁盘 | `POST /dump {"label":"before-restart"}` |
| 最近的 WARN/ERROR 和次数 | `GET /logs?since=300&level=WARN&limit=20`（只保留 WARN/ERROR/FATAL） |
| 录制状态 / 历史报告 id | `GET /profiler/status` |

## 工具怎么用（三条最常用）

```bash
# 1) 采样剖析：录 20s，到点自动结束；提前停用 /profiler/stop（回 409 表示已自动结束，改查 report）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"mode":"sample","seconds":20,"interval_ms":5}' http://127.0.0.1:8724/observa/v1/profiler/start
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8724/observa/v1/profiler/report/sample-1

# 2) 黑匣子：列历史，或立刻打一份
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8724/observa/v1/blackbox
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"label":"spike"}' http://127.0.0.1:8724/observa/v1/dump

# 3) 日志归并：按 count 降序看重复出现的 WARN/ERROR
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8724/observa/v1/logs?since=300&level=ERROR"
```

## 判读口径（这几条最容易读错）

- **`null` ≠ 0**：`null` 表示尚未采集/不适用，别当 0 参与判断。
- **实体只统计已加载区块**：这是服务端扫描的物理上限；`/entities` 的 `notes` 里写着。
- **剖析的百分比分母是 `busy_samples`**：`idle_pct` 高说明服务器多数时间在等 tick，短录制抓不到间歇卡顿——换黑匣子/长窗口。
- **`total_pct` 可以互相重叠**：同一帧被不同路径调用会重复计入，合计超过 100% 正常。
- **`busy_samples=0` 的报告必然是空 `top`**：那不是剖析器坏了，是整段录制都在空转。
- **MSPT 是 tick 内部耗时**，不是两次 tick 的间隔；TPS 用已完成 tick 的时间戳数出来，catch-up 时不会假装正常。
- **单机打开暂停菜单会冻结集成服务器**：指标会停在打开那一刻。

- **ESC 暂停不是卡死**：单机按 ESC 时集成服务器停 tick，`/health.paused` 为 `true`，黑匣子不会落 `stall`（`stats.suppressed_by_pause` 计数）。想让暂停也留证据就手动 `POST /dump`。
- **`/chunks` 的生成计数只算「现造地形」**：Mixin 挂在噪声生成步，磁盘读回的区块不计数；`generated_per_s: null` 是窗口还没攒够，不是零生成。算长区间用两次 `generated_total` 相减。
- **`/network` 的 `packets` 是帧数不是逻辑包数**：一个包拆成多帧会多算，每 tick 还有 2 帧 bundle 分隔符；字节数是压缩后的载荷，按字节看带宽更可靠。
- **`/heap/histogram` 的浅尺寸是估算**（按压缩指针折算、8 B 对齐，与 `jcmd` 差 1–2%），且不是 retained size；`live:true` 会触发一次 full GC + 停顿约 0.4 秒。
- **`/logs` 只保留 WARN/ERROR/FATAL**（最近 5000 条）：`level=INFO` 会回 400，INFO 请读 `logs/latest.log`。响应里的 `entries_dropped` / `log_bytes_skipped` 非零说明这份视图不完整。
- **`reason="stall"` 的落盘才代表卡死**：`ring` 尾部连续 `frozen:true` 且 `trigger.detail.since_tick_ms` 是该次停摆时长；`snapshot.mspt` 是停摆前最后一帧，`snapshot_age_ms` 接近停摆时长是正常的。
- **自动落盘有启动宽限**（`blackbox.min_server_ticks` / `grace_seconds`）：`/blackbox` 的 `stats.suppressed_by_grace` 说明有没有被压掉；要开局数据就手动 `POST /dump`。

## 排错

| 症状 | 大概率原因 | 下一步 |
|---|---|---|
| 401 `unauthorized` | 没带 token / token 错 | 读上表里的 token 文件 |
| 404 `not_found` | 路径写错 | `GET /manifest` 列出全部端点 |
| 429 `rate_limited` | 超过 20 请求/秒 | 等一下；批量用 `/snapshot` |
| 503 `not_ready` | 第一份快照还没生成 | 等 1 秒再试 |
| `/health.paused=true` 且 `age_ms` 一直涨 | 单机开着暂停菜单（ESC） | 回到游戏；暂停期间数据停在那一刻，不是采集坏了 |
| 连接被拒 | 游戏没进存档 / 服务未启动 / 端口不同 | 看日志 `[observa] listening on ...`，或读 `observa/agent.md` |

## 落盘与保留位置

- 黑匣子：`D:\C\MC\test\run\observa\blackbox`（保留最近 10 份 `.json.gz`）
- JFR 原件：`D:\C\MC\test\run\config\observa\jfr`（保留最近 5 份 `.jfr`）
- 堆快照：`D:\C\MC\test\run\config\observa\heap`（`keep_file:true` 时才留，最多 3 份 `.hprof`）
- 本文件：`D:\C\MC\test\run\observa\agent.md`
- 任务文件：`D:\C\MC\test\run\observa\TASK.md`（改它，agent 下次读 brief 就拿到新任务）
