package com.alvar.observa.http;

import com.alvar.observa.Observa;
import com.alvar.observa.api.Snapshot;
import com.alvar.observa.blackbox.Blackbox;
import com.alvar.observa.config.ObservaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 入职说明（onboarding brief）：进存档/开服时自动生成，交给 agent 读一遍就能开始干活。
 *
 * <p>两种形态，内容一致：
 * <ul>
 *   <li>{@code GET /observa/v1/brief.md}：Markdown，agent 一次 GET 就能读完；</li>
 *   <li>{@code <gameDir>/observa/agent.md}：同一份文字落到磁盘，agent 只有文件系统也能拿到（含端点）。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>把 token 值写进 brief 或日志——token 是等价的密码，写进日志等于公开。
 * brief 里只给 token 文件的绝对路径，agent 自己去读。
 *
 * <p>「任务是什么」由人写：{@code <gameDir>/observa/TASK.md} 存在就原样嵌进 brief 的「你的任务」一节，
 * 不存在就生成一份带说明的模板，让使用者知道该写在哪里。
 */
public final class Brief {

    private static final String TASK_TEMPLATE = """
            # 任务（写给你的 agent 看的）

            在这里写清楚你想让 agent 排查/关注什么。Observa 会把本文件原样嵌进 `agent.md` 与
            `/observa/v1/brief.md` 的「你的任务」一节，所以用自然语言写就行。

            示例：

            - 玩家反馈「主城附近一走过去就卡」，先确认是不是实体/方块实体堆积（`/entities?by=chunk&top=20`）。
            - 怀疑某个刷怪塔在刷掉落物：注意 `/entities` 的 `item_entities`。
            - 昨晚 23:40 左右卡过一次，去看 `GET /blackbox` 最近一份落盘文件的 `ring` 与 `errors`。
            - 只要结论和证据，不要改世界、不要执行游戏命令。
            """;

    private Brief() {
    }

    /** 进入存档/开服时调用：写 {@code observa/agent.md}（含 TASK.md 模板），并在日志里打印可点的 URL。 */
    public static void publish(int port) {
        Path dir = FMLPaths.GAMEDIR.get().resolve("observa");
        try {
            Files.createDirectories(dir);
            Path task = dir.resolve("TASK.md");
            if (!Files.exists(task)) {
                Files.writeString(task, TASK_TEMPLATE, StandardCharsets.UTF_8);
            }
            Path agent = dir.resolve("agent.md");
            Files.writeString(agent, markdown(port), StandardCharsets.UTF_8);
            Observa.LOGGER.info("[observa] agent brief: {}", agent.toAbsolutePath());
            Observa.LOGGER.info("[observa] agent brief over http: {}", url(port));
        } catch (IOException e) {
            Observa.LOGGER.warn("[observa] cannot write agent brief under {}", dir, e);
        }
    }

    public static String url(int port) {
        return "http://127.0.0.1:" + port + HttpService.PREFIX + "/brief.md";
    }

    public static Path taskFile() {
        return FMLPaths.GAMEDIR.get().resolve("observa").resolve("TASK.md");
    }

    public static Path tokenFile() {
        return FMLPaths.CONFIGDIR.get().resolve("observa").resolve("token.txt");
    }

    /** JFR 原件目录（与 JfrService 的落盘位置一致）。 */
    public static Path jfrDir() {
        return FMLPaths.CONFIGDIR.get().resolve("observa").resolve("jfr");
    }

    // ------------------------------------------------------------------ Markdown

    public static String markdown(int port) {
        Snapshot s = com.alvar.observa.collect.MetricsService.snapshot();
        String base = "http://127.0.0.1:" + port + HttpService.PREFIX;
        StringBuilder b = new StringBuilder(8192);

        b.append("# Observa —— 你的入职说明\n\n");
        b.append("> 这是 Minecraft 服务器进程内的只读观测接口，由 Observa ")
                .append(Observa.modVersion()).append(" 提供。\n")
                .append("> 接口只绑 `127.0.0.1`，只读（不会改世界、不会执行游戏命令），所有请求要 Bearer token。\n\n");

        b.append("| 项 | 值 |\n|---|---|\n");
        b.append("| 接口根地址 | `").append(base).append("` |\n");
        b.append("| 实际端口 | `").append(port).append("`（不是 8724 时说明默认端口被占用，以本文件为准） |\n");
        b.append("| token 文件 | `").append(tokenFile().toAbsolutePath()).append("` |\n");
        b.append("| 生成时间 | `").append(Instant.now()).append("` |\n");
        b.append("| 限速 | 全局 ").append(ObservaConfig.RATE_LIMIT_PER_SECOND.get())
                .append(" 请求/秒，超了回 429；批量信息用 `/snapshot` 一次拿全 |\n\n");

        b.append("## 你的任务\n\n");
        String task = readTask();
        b.append(task.isBlank()
                ? "（`observa/TASK.md` 还是空的。用户没写任务时，默认按「先看有没有异常，再按症状查」的流程走，见文末。）\n"
                : task);
        b.append("\n\n");

        b.append("## 第一步：拿到 token 并发一个请求\n\n");
        b.append("```bash\n");
        b.append("TOKEN=$(cat \"").append(tokenFile().toAbsolutePath()).append("\")\n");
        b.append("curl -s -H \"Authorization: Bearer $TOKEN\" ").append(base).append("/manifest\n");
        b.append("```\n");
        b.append("（上面是 Windows 路径；git-bash / WSL 里换成 `/d/...` / `/mnt/d/...` 形式。）\n\n");
        b.append("PowerShell：\n\n```powershell\n");
        b.append("$H = @{ Authorization = \"Bearer $(Get-Content '").append(tokenFile().toAbsolutePath()).append("')\" }\n");
        b.append("Invoke-RestMethod -Headers $H ").append(base).append("/manifest\n");
        b.append("```\n\n");

        b.append("## 现在这一刻的状态\n\n");
        if (s == null) {
            b.append("（还没有第一份快照，通常再等 1 秒就有；`GET /health` 可以先确认接口活着。）\n\n");
        } else {
            b.append("| 项 | 值 |\n|---|---|\n");
            b.append("| headline | ").append(headlineText(s)).append(" |\n");
            b.append("| flags | ").append(flagsText(s)).append(" |\n");
            b.append("| TPS（1s/10s/1m） | ").append(s.tick().tps1s()).append(" / ")
                    .append(s.tick().tps10s()).append(" / ").append(s.tick().tps1m()).append(" |\n");
            b.append("| MSPT now / 1m avg / p95 / p99 | ").append(s.tick().msptNowMs()).append(" / ")
                    .append(s.tick().msptAvg1m()).append(" / ").append(s.tick().p95Ms()).append(" / ")
                    .append(s.tick().p99Ms()).append(" ms |\n");
            b.append("| 堆 | ").append(mib(s.memory().heapUsedBytes())).append(" / ")
                    .append(mib(s.memory().heapMaxBytes())).append(" MiB，GC ").append(s.memory().gcCount())
                    .append(" 次（累计暂停 ").append(s.memory().gcPauseTotalMs()).append(" ms） |\n");
            b.append("| 实体 / 区块 / 玩家 | ").append(s.entities().total()).append(" / ")
                    .append(s.chunks().loaded()).append(" / ").append(s.players().online()).append(" |\n");
            b.append("| 快照年龄 | ").append(System.currentTimeMillis() - s.collectedAtMs())
                    .append(" ms（远大于 `metrics.interval_ms` 说明采集被卡住了） |\n\n");
        }

        b.append("## 数据在哪：按「我想回答什么」选端点\n\n");
        b.append("| 我想回答 | 调什么 |\n|---|---|\n");
        rows(b, "接口是否活着、端口对不对", "`GET /health`");
        rows(b, "现在整体健康吗、是什么病", "`GET /metrics?window=60` → `headline` + `flags`");
        rows(b, "一次拿全貌（省往返）", "`GET /snapshot`");
        rows(b, "实体/掉落物/经验球堆在哪", "`GET /entities?by=type|dimension|chunk&top=20`");
        rows(b, "内存/GC 有没有压力", "`GET /memory`（分池 + 各收集器上一次 GC 的逐池回收）");
        rows(b, "谁占着内存不放（存活实例）",
                "`POST /heap/histogram/start {\"live\":true,\"top\":25}` → 轮询 `GET /heap/histogram`（有 STW，约 0.4s）");
        rows(b, "哪个 mod 占着内存、哪些类是它的",
                "`GET /heap/histogram` → 顶层 `by_mod[]`（按字节降序）+ 每个类行的 `mod_id`");
        rows(b, "什么在涨（两次直方图之差）",
                "隔几分钟各 `POST /heap/histogram/start` 一次 → `GET /heap/histogram/compare?top=20`（默认比最近两份）");
        rows(b, "删掉谁能掉多少内存 / 谁持有它（retained size）",
                "`POST /heap/retained/start {\"live\":true}` → 轮询 `GET /heap/retained`（`classes[].retained_bytes` + `dominated_by_class`；有 STW，分析约数秒）");
        rows(b, "CPU/物理内存/磁盘是不是瓶颈", "`GET /system`");
        rows(b, "玩家延迟、谁在刷包/吃带宽", "`GET /network?top=10`");
        rows(b, "已加载区块数 / 这片时间新生成了多少地形", "`GET /chunks`（`generated_total` 是硬计数，`generated_per_s` 是 60s 平均）");
        rows(b, "谁在吃主线程时间（热点方法 + 调用链）",
                "`POST /profiler/start {\"mode\":\"sample\",\"seconds\":20,\"interval_ms\":5}` → `GET /profiler/report/sample-1`");
        rows(b, "为什么 GC 频繁 / 谁在分配 / 哪一行热 / 锁竞争",
                "`POST /profiler/start {\"mode\":\"jfr\",\"seconds\":30}` → `GET /profiler/report/jfr-1`（含 `execution_samples` 行号级热点与 `allocations.top_sites`）");
        rows(b, "刚卡那一下发生了什么（事后回溯）", "`GET /blackbox` → 读最近一份 `.json.gz`");
        rows(b, "现在打一份快照到磁盘", "`POST /dump {\"label\":\"before-restart\"}`");
        rows(b, "最近的 WARN/ERROR 和次数", "`GET /logs?since=300&level=WARN&limit=20`（只保留 WARN/ERROR/FATAL）");
        rows(b, "录制状态 / 历史报告 id", "`GET /profiler/status`");
        b.append("\n");

        b.append("## 工具怎么用（三条最常用）\n\n");
        b.append("```bash\n");
        b.append("# 1) 采样剖析：录 20s，到点自动结束；提前停用 /profiler/stop（回 409 表示已自动结束，改查 report）\n");
        b.append("curl -s -X POST -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' \\\n");
        b.append("  -d '{\"mode\":\"sample\",\"seconds\":20,\"interval_ms\":5}' ").append(base).append("/profiler/start\n");
        b.append("curl -s -H \"Authorization: Bearer $TOKEN\" ").append(base).append("/profiler/report/sample-1\n\n");
        b.append("# 2) 黑匣子：列历史，或立刻打一份\n");
        b.append("curl -s -H \"Authorization: Bearer $TOKEN\" ").append(base).append("/blackbox\n");
        b.append("curl -s -X POST -H \"Authorization: Bearer $TOKEN\" -H 'Content-Type: application/json' \\\n");
        b.append("  -d '{\"label\":\"spike\"}' ").append(base).append("/dump\n\n");
        b.append("# 3) 日志归并：按 count 降序看重复出现的 WARN/ERROR\n");
        b.append("curl -s -H \"Authorization: Bearer $TOKEN\" \"").append(base).append("/logs?since=300&level=ERROR\"\n");
        b.append("```\n\n");

        b.append("## 判读口径（这几条最容易读错）\n\n");
        b.append("- **`null` ≠ 0**：`null` 表示尚未采集/不适用，别当 0 参与判断。\n");
        b.append("- **实体只统计已加载区块**：这是服务端扫描的物理上限；`/entities` 的 `notes` 里写着。\n");
        b.append("- **剖析的百分比分母是 `busy_samples`**：`idle_pct` 高说明服务器多数时间在等 tick，短录制抓不到间歇卡顿——换黑匣子/长窗口。\n");
        b.append("- **`total_pct` 可以互相重叠**：同一帧被不同路径调用会重复计入，合计超过 100% 正常。\n");
        b.append("- **`busy_samples=0` 的报告必然是空 `top`**：那不是剖析器坏了，是整段录制都在空转。\n");
        b.append("- **MSPT 是 tick 内部耗时**，不是两次 tick 的间隔；TPS 用已完成 tick 的时间戳数出来，catch-up 时不会假装正常。\n");
        b.append("- **单机打开暂停菜单会冻结集成服务器**：指标会停在打开那一刻。\n\n");

        b.append("- **ESC 暂停不是卡死**：单机按 ESC 时集成服务器停 tick，`/health.paused` 为 `true`，"
                + "黑匣子不会落 `stall`（`stats.suppressed_by_pause` 计数）。想让暂停也留证据就手动 `POST /dump`。\n");
        b.append("- **`/chunks` 的生成计数只算「现造地形」**：Mixin 挂在噪声生成步，磁盘读回的区块不计数；"
                + "`generated_per_s: null` 是窗口还没攒够，不是零生成。算长区间用两次 `generated_total` 相减。\n");
        b.append("- **`/network` 的 `packets` 是帧数不是逻辑包数**：一个包拆成多帧会多算，每 tick 还有 2 帧 bundle 分隔符；字节数是压缩后的载荷，按字节看带宽更可靠。\n");
        b.append("- **`/heap/histogram` 的浅尺寸是估算**（按压缩指针折算、8 B 对齐，与 `jcmd` 差 1–2%），且不是 retained size；`live:true` 会触发一次 full GC + 停顿约 0.4 秒。\n");
        b.append("- **`by_mod` 靠 `mods/` 里的 jar 内的 `neoforge.mods.toml` 建映射**：不在 `mods/` 的代码一律归 `unknown`，"
                + "`mod_mapping.mods_indexed: 0` 说明这次归因完全不可用（开发环境 `runServer` 的 `mods/` 就是空的）。"
                + "`mod_id` 说的是「这个类属于谁」，`site_mod_id` 才说「这行代码是谁在分配」——查归属看后者。\n");
        b.append("- **`/heap/histogram/compare` 比的是两次独立 dump**：中间有 GC 就会抖动，看 `mods[].delta_bytes` 的符号和量级，别逐字节当真；"
                + "两次间隔太短会回 `not_enough_history`。\n");
        b.append("- **`/heap/retained` 才是 retained size**（直方图只有浅尺寸）：`classes[].retained_bytes` 是「删掉这类对象能释放多少」，"
                + "各类按支配树分区、互不重叠，相加精确等于 `total_retained_bytes`（`classes_truncated:false` 时；只报前 60 类，"
                + "所以先看这个标志再求和）；`instances_counted` 是被计入的最上层对象数（不是实例总数），"
                + "`dominated_by_class` 说这些字节挂在谁身上。`dropped_edges` 非 0 是正常的（指向 `Class` 镜像等只有元数据、"
                + "没有实例记录的对象，被压掉不参与建图）。它要建引用图 + 支配树，比直方图贵（4.1M 对象约 6-7 秒分析），"
                + "且同样有 `live:true` 的 full GC 停顿。`unreachable_pct` 高只是 dump 里混了垃圾（JVM 跳过了 pre-dump GC），retained 数字不受影响。\n");
        b.append("- **`/logs` 只保留 WARN/ERROR/FATAL**（最近 5000 条）：`level=INFO` 会回 400，INFO 请读 `logs/latest.log`。"
                + "响应里的 `entries_dropped` / `log_bytes_skipped` 非零说明这份视图不完整。\n");
        b.append("- **`reason=\"stall\"` 的落盘才代表卡死**：`ring` 尾部连续 `frozen:true` 且 `trigger.detail.since_tick_ms` 是该次停摆时长；"
                + "`snapshot.mspt` 是停摆前最后一帧，`snapshot_age_ms` 接近停摆时长是正常的。\n");
        b.append("- **自动落盘有启动宽限**（`blackbox.min_server_ticks` / `grace_seconds`）：`/blackbox` 的 `stats.suppressed_by_grace` 说明有没有被压掉；要开局数据就手动 `POST /dump`。\n\n");

        b.append("## 排错\n\n");
        b.append("| 症状 | 大概率原因 | 下一步 |\n|---|---|---|\n");
        b.append("| 401 `unauthorized` | 没带 token / token 错 | 读上表里的 token 文件 |\n");
        b.append("| 404 `not_found` | 路径写错 | `GET /manifest` 列出全部端点 |\n");
        b.append("| 429 `rate_limited` | 超过 ")
                .append(ObservaConfig.RATE_LIMIT_PER_SECOND.get()).append(" 请求/秒 | 等一下；批量用 `/snapshot` |\n");
        b.append("| 503 `not_ready` | 第一份快照还没生成 | 等 1 秒再试 |\n");
        b.append("| `/health.paused=true` 且 `age_ms` 一直涨 | 单机开着暂停菜单（ESC） | 回到游戏；暂停期间数据停在那一刻，不是采集坏了 |\n");
        b.append("| 连接被拒 | 游戏没进存档 / 服务未启动 / 端口不同 | 看日志 `[observa] listening on ...`，或读 `observa/agent.md` |\n\n");

        b.append("## 落盘与保留位置\n\n");
        b.append("- 黑匣子：`").append(Blackbox.dir()).append("`（保留最近 10 份 `.json.gz`）\n");
        b.append("- JFR 原件：`").append(jfrDir()).append("`（保留最近 5 份 `.jfr`）\n");
        b.append("- 堆快照：`").append(FMLPaths.CONFIGDIR.get().resolve("observa").resolve("heap"))
                .append("`（`keep_file:true` 时才留，最多 3 份 `.hprof`）\n");
        b.append("- 本文件：`").append(FMLPaths.GAMEDIR.get().resolve("observa").resolve("agent.md")).append("`\n");
        b.append("- 任务文件：`").append(taskFile()).append("`（改它，agent 下次读 brief 就拿到新任务）\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ JSON

    public static JsonObject json(int port) {
        Snapshot s = com.alvar.observa.collect.MetricsService.snapshot();
        String base = "http://127.0.0.1:" + port + HttpService.PREFIX;
        JsonObject data = new JsonObject();
        data.addProperty("brief_url", url(port));
        data.addProperty("base_url", base);
        data.addProperty("brief_file", FMLPaths.GAMEDIR.get().resolve("observa").resolve("agent.md").toString());
        data.addProperty("task_file", taskFile().toString());
        data.addProperty("token_file", tokenFile().toAbsolutePath().toString());
        data.addProperty("mod_version", Observa.modVersion());
        data.addProperty("generated_at", Instant.now().toString());
        data.addProperty("auth", "Authorization: Bearer <token from token_file>");
        data.addProperty("rate_limit_per_second", ObservaConfig.RATE_LIMIT_PER_SECOND.get());

        String task = readTask();
        data.addProperty("task", task.isBlank() ? null : task);

        if (s != null) {
            JsonObject state = new JsonObject();
            state.addProperty("headline", headlineText(s));
            state.add("flags", flagsArray(s));
            state.addProperty("mspt_now_ms", s.tick().msptNowMs());
            state.addProperty("mspt_p99_ms", s.tick().p99Ms());
            state.addProperty("tps_1m", s.tick().tps1m());
            state.addProperty("entities", s.entities().total());
            state.addProperty("item_entities", s.entities().itemEntities());
            state.addProperty("chunks", s.chunks().loaded());
            state.addProperty("players", s.players().online());
            state.addProperty("snapshot_age_ms", System.currentTimeMillis() - s.collectedAtMs());
            data.add("state", state);
        } else {
            data.add("state", null);
        }

        JsonArray start = new JsonArray();
        start.add("GET " + base + "/health");
        start.add("GET " + base + "/manifest");
        start.add("GET " + base + "/snapshot");
        data.add("first_calls", start);

        JsonArray recipes = new JsonArray();
        recipes.add(recipe("is it healthy now", "GET", "/metrics?window=60", "read headline + flags"));
        recipes.add(recipe("where are entities piling up", "GET", "/entities?by=chunk&top=20", "item_entities, top chunks"));
        recipes.add(recipe("who eats the main thread", "POST", "/profiler/start",
                "{\"mode\":\"sample\",\"seconds\":20,\"interval_ms\":5} then GET /profiler/report/sample-1"));
        recipes.add(recipe("why GC pressure", "POST", "/profiler/start",
                "{\"mode\":\"jfr\",\"seconds\":30} then read summary.gc + summary.allocations"));
        recipes.add(recipe("what happened during the last stall", "GET", "/blackbox", "read newest .json.gz ring[]"));
        recipes.add(recipe("recent warnings/errors", "GET", "/logs?since=300&level=WARN", "grouped by count"));
        recipes.add(recipe("is the host the bottleneck", "GET", "/system", "cpu.process_load_pct, memory.physical_used_pct, disk"));
        recipes.add(recipe("who is lagging / who floods packets", "GET", "/network?top=10", "ping_ms, sent.bytes_per_s"));
        recipes.add(recipe("who holds memory (live objects)", "POST", "/heap/histogram/start",
                "{\"live\":true,\"top\":25} then poll GET /heap/histogram (STW ~0.4s)"));
        recipes.add(recipe("which mod holds the memory", "GET", "/heap/histogram",
                "by_mod[] sorted by bytes; mod_mapping says how attribution was built"));
        recipes.add(recipe("what is growing", "GET", "/heap/histogram/compare?top=20",
                "run /heap/histogram/start twice minutes apart, then read mods[].delta_bytes"));
        recipes.add(recipe("how much would deleting X free", "POST", "/heap/retained/start",
                "{\"live\":true} then poll GET /heap/retained: classes[].retained_bytes, dominated_by_class"));
        recipes.add(recipe("snapshot to disk now", "POST", "/dump", "{\"label\":\"...\"}"));
        data.add("recipes", recipes);

        JsonArray rules = new JsonArray();
        rules.add("read-only: nothing here mutates the world, players, or runs game commands");
        rules.add("null means not collected; never treat it as 0");
        rules.add("entity counts cover loaded chunks only");
        rules.add("profiler percentages use busy_samples as the denominator");
        rules.add("open the pause menu in single player and the integrated server stops ticking");
        data.add("rules", rules);
        return data;
    }

    private static JsonObject recipe(String question, String method, String path, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("question", question);
        o.addProperty("method", method);
        o.addProperty("path", HttpService.PREFIX + path);
        o.addProperty("note", note);
        return o;
    }

    // ------------------------------------------------------------------ helpers

    private static void rows(StringBuilder b, String question, String call) {
        b.append("| ").append(question).append(" | ").append(call).append(" |\n");
    }

    private static String readTask() {
        try {
            Path task = taskFile();
            if (!Files.isRegularFile(task)) {
                return "";
            }
            String text = Files.readString(task, StandardCharsets.UTF_8).trim();
            // 模板本身不算任务：第一行标题写死了「写给你的 agent 看的」，正文含「示例」。
            if (text.startsWith("# 任务（写给你的 agent 看的）") && text.contains("在这里写清楚")) {
                return "";
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    private static String headlineText(Snapshot s) {
        JsonObject metrics = SnapshotJson.metrics(s, 10);
        return metrics.has("headline") ? metrics.get("headline").getAsString() : "unknown";
    }

    private static JsonArray flagsArray(Snapshot s) {
        JsonObject metrics = SnapshotJson.metrics(s, 10);
        return metrics.has("flags") ? metrics.getAsJsonArray("flags") : new JsonArray();
    }

    private static String flagsText(Snapshot s) {
        JsonArray flags = flagsArray(s);
        if (flags.isEmpty()) {
            return "（无）";
        }
        StringBuilder b = new StringBuilder();
        for (var flag : flags) {
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append('`').append(flag.getAsString()).append('`');
        }
        return b.toString();
    }

    private static String mib(long bytes) {
        return String.valueOf(Math.round(bytes / 1024.0 / 1024.0));
    }

    /** 供测试/清单用的端点摘要。 */
    public static List<String> endpointSummary() {
        return List.of(
                HttpService.PREFIX + "/brief.md",
                HttpService.PREFIX + "/brief");
    }
}
