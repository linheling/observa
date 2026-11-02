package com.alvar.observa.http;

import com.alvar.observa.api.Snapshot;
import com.alvar.observa.collect.GcWatch;
import com.alvar.observa.collect.HeapService;
import com.alvar.observa.collect.MemoryDetail;
import com.alvar.observa.collect.MetricsService;
import com.alvar.observa.collect.OffHeap;
import com.alvar.observa.collect.ProfilerService;
import com.alvar.observa.collect.RetainedService;
import com.alvar.observa.config.ObservaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 快照 → JSON 的视图层。
 *
 * <p>字段名自带单位是刻意设计：agent 不用查文档就能判断 21.4 是毫秒还是刻。
 */
public final class SnapshotJson {

    /** 「最近发生过 GC」的判定窗：MXBean 采样精度是 1 s，取 3 s 留两次采样的余量。 */
    private static final long GC_RECENT_MS = 3_000L;

    private SnapshotJson() {
    }

    public static JsonObject meta(Snapshot snapshot) {
        JsonObject meta = new JsonObject();
        meta.addProperty("collected_at_ms", snapshot.collectedAtMs());
        meta.addProperty("age_ms", Math.max(0, System.currentTimeMillis() - snapshot.collectedAtMs()));
        meta.addProperty("server_tick", snapshot.serverTick());
        return meta;
    }

    public static JsonObject metrics(Snapshot s, int tpsWindow) {
        JsonObject data = meta(s);

        Snapshot.TickStats tick = s.tick();
        JsonObject tps = new JsonObject();
        tps.addProperty("1s", tick.tps1s());
        tps.addProperty("10s", tick.tps10s());
        tps.addProperty("1m", tick.tps1m());
        data.add("tps", tps);

        JsonObject mspt = new JsonObject();
        mspt.addProperty("now_ms", tick.msptNowMs());
        mspt.addProperty("avg_1m_ms", tick.msptAvg1m());
        mspt.addProperty("p50_ms", tick.p50Ms());
        mspt.addProperty("p95_ms", tick.p95Ms());
        mspt.addProperty("p99_ms", tick.p99Ms());
        mspt.addProperty("sample_ticks", tick.sampleTicks());
        data.add("mspt", mspt);

        data.add("memory", memory(s.memory()));
        data.add("entities", entitySummary(s.entities()));
        data.add("chunks", chunkView(s.chunks()));
        data.add("players", playerView(s.players(), 0));
        data.addProperty("headline", headline(tick, tpsWindow));
        data.add("flags", flags(s, tpsWindow));
        return data;
    }

    public static JsonObject entities(Snapshot s, String by, int top) {
        JsonObject data = meta(s);
        Snapshot.EntityStats entities = s.entities();
        data.addProperty("by", by);
        data.addProperty("total", entities.total());
        data.addProperty("item_entities", entities.itemEntities());
        data.addProperty("xp_orbs", entities.xpOrbs());
        data.addProperty("loaded_entity_types", entities.loadedEntityTypes());

        JsonArray topArray = new JsonArray();
        switch (by) {
            case "dimension" -> appendCounts(topArray, entities.byDimension(), top, "dimension");
            case "chunk" -> {
                for (int i = 0; i < Math.min(top, entities.byChunk().size()); i++) {
                    Snapshot.ChunkCount chunk = entities.byChunk().get(i);
                    JsonObject o = new JsonObject();
                    o.addProperty("dimension", chunk.dimension());
                    o.addProperty("x", chunk.x());
                    o.addProperty("z", chunk.z());
                    o.addProperty("count", chunk.count());
                    topArray.add(o);
                }
            }
            default -> appendCounts(topArray, entities.byType(), top, "id");
        }
        data.add("top", topArray);
        data.addProperty("truncated", truncated(entities, by, top));
        JsonArray notes = new JsonArray();
        notes.add("counts cover loaded chunks only: entities in unloaded chunks are invisible to any server-side scan");
        data.add("notes", notes);
        return data;
    }

    public static JsonObject memory(Snapshot s) {
        JsonObject data = meta(s);
        data.add("memory", memory(s.memory()));
        // 池/收集器/上一次 GC 是现算的（不再是快照的一部分），因为它们只在被读时才有人关心。
        data.add("detail", MemoryDetail.detail());
        // 堆外拆分与「最近一次 GC 窗口」：回答「堆只用了 700 MB，为什么进程占了 12 GB」和「刚才那阵卡顿是不是 GC」。
        data.add("off_heap", OffHeap.json());
        data.add("gc_recent", GcWatch.json());
        data.addProperty("top_classes", (String) null);
        JsonArray notes = new JsonArray();
        notes.add("top_classes is null on purpose: live instance counts come from /heap/histogram, "
                + "while a JFR recording only gives allocation volume (how much was allocated, not what is still alive)");
        notes.add("detail.pools and detail.collectors explain which generation is busy; see detail.collectors[].last_gc");
        notes.add("off_heap splits the non-heap side (direct/mapped buffers, code cache, threads, NMT when enabled); "
                + "nmt is unavailable unless the server started with -XX:NativeMemoryTracking=summary");
        notes.add("gc_recent is a window over the last minute (collections/pause_ms/per_collector); "
                + "evacuation/humongous/MMU counts come from the gc log and are absent if -Xlog:gc is not configured");
        data.add("notes", notes);
        return data;
    }

    public static JsonObject chunks(Snapshot s) {
        JsonObject data = meta(s);
        data.add("chunks", chunkView(s.chunks()));
        JsonArray notes = new JsonArray();
        notes.add("generated_total counts chunks that entered the NOISE generation step since this process started; "
                + "chunks read back from disk are not counted (they are already FULL and skip generation)");
        notes.add("generated_per_s is the average over generated_window_seconds (60s); it is null until the window has samples. "
                + "For a longer span, take generated_total twice and divide by elapsed seconds");
        data.add("notes", notes);
        return data;
    }

    public static JsonObject full(Snapshot s) {
        JsonObject data = meta(s);
        Snapshot.TickStats tick = s.tick();
        JsonObject tps = new JsonObject();
        tps.addProperty("1s", tick.tps1s());
        tps.addProperty("10s", tick.tps10s());
        tps.addProperty("1m", tick.tps1m());
        data.add("tps", tps);

        JsonObject mspt = new JsonObject();
        mspt.addProperty("now_ms", tick.msptNowMs());
        mspt.addProperty("avg_1m_ms", tick.msptAvg1m());
        mspt.addProperty("p50_ms", tick.p50Ms());
        mspt.addProperty("p95_ms", tick.p95Ms());
        mspt.addProperty("p99_ms", tick.p99Ms());
        data.add("mspt", mspt);

        data.add("memory", memory(s.memory()));
        data.add("entities", entitySummary(s.entities()));

        JsonObject entityTop = new JsonObject();
        JsonArray topTypes = new JsonArray();
        appendCounts(topTypes, s.entities().byType(), 10, "id");
        entityTop.add("top_types", topTypes);
        JsonArray topChunks = new JsonArray();
        for (int i = 0; i < Math.min(5, s.entities().byChunk().size()); i++) {
            Snapshot.ChunkCount c = s.entities().byChunk().get(i);
            JsonObject o = new JsonObject();
            o.addProperty("dimension", c.dimension());
            o.addProperty("x", c.x());
            o.addProperty("z", c.z());
            o.addProperty("count", c.count());
            topChunks.add(o);
        }
        entityTop.add("top_chunks", topChunks);
        data.add("entity_breakdown", entityTop);

        data.add("chunks", chunkView(s.chunks()));
        data.add("players", playerView(s.players(), 10));
        data.add("flags", flags(s, 10));
        return data;
    }

    // ------------------------------------------------------------------ 片段

    private static JsonObject memory(Snapshot.MemoryStats m) {
        JsonObject o = new JsonObject();
        o.addProperty("heap_used_bytes", m.heapUsedBytes());
        o.addProperty("heap_committed_bytes", m.heapCommittedBytes());
        o.addProperty("heap_max_bytes", m.heapMaxBytes());
        o.addProperty("nonheap_used_bytes", m.nonHeapUsedBytes());
        o.addProperty("gc_count", m.gcCount());
        o.addProperty("gc_pause_total_ms", m.gcPauseTotalMs());
        o.addProperty("thread_count", m.threadCount());
        if (m.heapMaxBytes() > 0) {
            o.addProperty("heap_used_pct", Math.round(m.heapUsedBytes() * 10000.0 / m.heapMaxBytes()) / 100.0);
        }
        return o;
    }

    private static JsonObject entitySummary(Snapshot.EntityStats e) {
        JsonObject o = new JsonObject();
        o.addProperty("total", e.total());
        o.addProperty("item_entities", e.itemEntities());
        o.addProperty("xp_orbs", e.xpOrbs());
        o.addProperty("loaded_entity_types", e.loadedEntityTypes());
        JsonObject perDimension = new JsonObject();
        for (Map.Entry<String, Integer> entry : e.byDimension().entrySet()) {
            perDimension.addProperty(entry.getKey(), entry.getValue());
        }
        o.add("by_dimension", perDimension);
        return o;
    }

    private static JsonObject chunkView(Snapshot.ChunkStats c) {
        JsonObject o = new JsonObject();
        o.addProperty("loaded", c.loaded());
        o.addProperty("generated_per_s", c.generatedPerSecond());
        o.addProperty("generated_total", c.generatedTotal());
        o.addProperty("generated_window_seconds", c.generatedWindowSeconds());
        return o;
    }

    private static JsonObject playerView(Snapshot.PlayerStats p, int nameLimit) {
        JsonObject o = new JsonObject();
        o.addProperty("online", p.online());
        JsonArray names = new JsonArray();
        int limit = nameLimit <= 0 ? p.names().size() : Math.min(nameLimit, p.names().size());
        for (int i = 0; i < limit; i++) {
            names.add(p.names().get(i));
        }
        o.add("names", names);
        return o;
    }

    private static void appendCounts(JsonArray out, Map<String, Integer> counts, int top, String keyName) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        for (int i = 0; i < Math.min(top, entries.size()); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            JsonObject o = new JsonObject();
            o.addProperty(keyName, entry.getKey());
            o.addProperty("count", entry.getValue());
            out.add(o);
        }
    }

    private static boolean truncated(Snapshot.EntityStats e, String by, int top) {
        return switch (by) {
            case "dimension" -> e.byDimension().size() > top;
            case "chunk" -> e.byChunk().size() > top;
            default -> e.byType().size() > top;
        };
    }

    /** 一句话诊断，省得 agent 自己从四个数字推断。 */
    private static String headline(Snapshot.TickStats tick, int window) {
        Double tps = switch (window) {
            case 1 -> tick.tps1s();
            case 60 -> tick.tps1m();
            default -> tick.tps10s();
        };
        if (tps == null) {
            return "warming_up: not enough tick samples yet";
        }
        if (tps >= 19.5) {
            return "healthy: tick rate is at full speed";
        }
        if (tps >= 15.0) {
            return String.format("degraded: %.1f tps — server is falling behind", tps);
        }
        return String.format("critical: %.1f tps — severe lag, check /entities and /profiler", tps);
    }

    /**
     * 读这份数据之前应该知道的事实。
     *
     * <p>以前这里只有 mspt/tps 三条阈值告警，所以报告里说它「永远是空的」——阈值确实很少触发。
     * 现在补上三条描述「数据本身可不可信」的标记，它们才是 agent 最该先看的：</p>
     * <ul>
     *   <li>{@code stale}：快照比采集间隔晚太多，通常是服务器停了 tick（单人按 ESC）或卡住了；</li>
     *   <li>{@code during_dump}：堆 dump 正在跑，这一秒的 MSPT 偏高是采集自己造成的；</li>
     *   <li>{@code during_profiler}：采样剖析在跑，数字同样是带代价的。</li>
     *   <li>{@code during_gc}：最近 3 秒内观测到 GC 计数变化（精度受 1 秒采样间隔限制）。</li>
     * </ul>
     */
    private static JsonArray flags(Snapshot s, int tpsWindow) {
        Snapshot.TickStats tick = s.tick();
        JsonArray flags = new JsonArray();
        if (tick.p99Ms() > 50.0) {
            flags.add(String.format("mspt_spiky: p99=%.1fms over 50ms budget", tick.p99Ms()));
        } else if (tick.msptAvg1m() > 50.0) {
            flags.add(String.format("mspt_sustained: avg=%.1fms over 50ms budget", tick.msptAvg1m()));
        }
        if (tick.tps1m() != null && tick.tps1m() < 19.0) {
            flags.add(String.format("tps_low: 1m=%.2f", tick.tps1m()));
        }

        long ageMs = Math.max(0L, System.currentTimeMillis() - s.collectedAtMs());
        long interval = Math.max(1000, ObservaConfig.METRICS_INTERVAL_MS.get());
        if (ageMs > interval * 5) {
            flags.add(String.format("stale: sample is %.1fs old — the server thread is not ticking "
                    + "(paused, frozen or shutting down); numbers below describe that moment", ageMs / 1000.0));
        }
        if (HeapService.instance().running() || RetainedService.instance().running()) {
            flags.add("during_dump: a heap dump is in progress — mspt here includes the dump's pause");
        }
        if (ProfilerService.get() != null && ProfilerService.get().status() != null) {
            flags.add("during_profiler: a sampling profiler is running — overhead is included");
        }
        long sinceGc = MetricsService.msSinceLastGcChange();
        if (sinceGc <= GC_RECENT_MS) {
            flags.add(String.format("during_gc: a collection was observed %.1fs ago — this sample may include "
                    + "its pause (MXBean reports counts, not pause timestamps)", sinceGc / 1000.0));
        }
        return flags;
    }
}
