package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Map;

import com.sun.management.GcInfo;

/**
 * 堆的细节：分代/内存池用量、每个收集器的累计次数与耗时、上一次 GC 的逐池前后对比。
 *
 * <p>{@code Snapshot.memory} 只回答「用了多少、总共 GC 了多久」，回答不了「是 Young 还是 Old 在忙」、
 * 「这次 GC 把哪个池清空了」。区分这两件事正是判断「内存不足」还是「分配太快」的关键：
 * old 池每次 GC 后仍在涨 = 有人在攒活对象；eden 池反复清空 = 分配压力大但没泄漏。</p>
 *
 * <p>全部现算。GC 之外的调用（{@link com.sun.management.GarbageCollectorMXBean#getLastGcInfo()}）
 * 在标准接口里没有，需要强转，拿不到时字段给 null。</p>
 */
public final class MemoryDetail {

    private MemoryDetail() {
    }

    public static JsonObject detail() {
        JsonObject data = new JsonObject();

        data.add("heap", usage(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()));
        data.add("non_heap", usage(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()));

        JsonArray pools = new JsonArray();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", pool.getName());
            p.addProperty("type", pool.getType() == MemoryType.HEAP ? "heap" : "non_heap");
            p.add("usage", usage(pool.getUsage()));
            p.add("peak", usage(pool.getPeakUsage()));
            // "survivor" 能在没泄漏时也持续增长，标出来避免误读成泄漏。
            p.addProperty("kind", classify(pool.getName()));
            pools.add(p);
        }
        data.add("pools", pools);

        JsonArray collectors = new JsonArray();
        long totalCount = 0;
        long totalMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject c = new JsonObject();
            c.addProperty("name", gc.getName());
            long count = Math.max(0, gc.getCollectionCount());
            long ms = Math.max(0, gc.getCollectionTime());
            totalCount += count;
            totalMs += ms;
            c.addProperty("count", count);
            c.addProperty("collection_time_ms", ms);
            if (gc instanceof com.sun.management.GarbageCollectorMXBean sun) {
                c.add("last_gc", lastGc(sun.getLastGcInfo()));
            } else {
                c.add("last_gc", com.google.gson.JsonNull.INSTANCE);
            }
            collectors.add(c);
        }
        data.add("collectors", collectors);

        JsonObject overall = new JsonObject();
        overall.addProperty("total_count", totalCount);
        overall.addProperty("total_collection_time_ms", totalMs);
        data.add("gc", overall);

        JsonObject classes = new JsonObject();
        classes.addProperty("loaded", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        classes.addProperty("total_loaded", ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
        classes.addProperty("unloaded", ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount());
        data.add("classes", classes);

        JsonArray notes = new JsonArray();
        notes.add("survivor pools grow while objects age; that is normal and not a leak");
        notes.add("last_gc shows per-pool usage before/after the most recent collection of that collector");
        notes.add("counts and times are JVM-wide since start, not slotted windows");
        data.add("notes", notes);
        return data;
    }

    private static JsonObject lastGc(GcInfo info) {
        if (info == null) {
            return null;
        }
        JsonObject gc = new JsonObject();
        gc.addProperty("id", info.getId());
        gc.addProperty("duration_ms", info.getDuration());
        gc.addProperty("started_at_ms", info.getStartTime());
        gc.addProperty("ended_at_ms", info.getEndTime());

        JsonArray before = new JsonArray();
        for (Map.Entry<String, MemoryUsage> e : info.getMemoryUsageBeforeGc().entrySet()) {
            before.add(poolDelta(e.getKey(), e.getValue(), info.getMemoryUsageAfterGc().get(e.getKey())));
        }
        gc.add("pools", before);
        return gc;
    }

    private static JsonObject poolDelta(String name, MemoryUsage before, MemoryUsage after) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("before_bytes", before == null ? -1 : before.getUsed());
        p.addProperty("after_bytes", after == null ? -1 : after.getUsed());
        if (before != null && after != null) {
            p.addProperty("reclaimed_bytes", before.getUsed() - after.getUsed());
        }
        return p;
    }

    private static JsonObject usage(MemoryUsage usage) {
        JsonObject u = new JsonObject();
        if (usage == null) {
            u.add("used_bytes", com.google.gson.JsonNull.INSTANCE);
            u.add("committed_bytes", com.google.gson.JsonNull.INSTANCE);
            u.add("max_bytes", com.google.gson.JsonNull.INSTANCE);
            u.add("init_bytes", com.google.gson.JsonNull.INSTANCE);
            u.add("used_pct", com.google.gson.JsonNull.INSTANCE);
            return u;
        }
        u.addProperty("used_bytes", usage.getUsed());
        u.addProperty("committed_bytes", usage.getCommitted());
        u.addProperty("max_bytes", usage.getMax() < 0 ? -1 : usage.getMax());
        u.addProperty("init_bytes", usage.getInit());
        long max = usage.getMax();
        if (max > 0) {
            u.addProperty("used_pct", Math.round(usage.getUsed() * 10000.0 / max) / 100.0);
        } else {
            u.add("used_pct", com.google.gson.JsonNull.INSTANCE);
        }
        return u;
    }

    /** 池名的粗分类，让 agent 不用背 HotSpot 的池命名（G1 Eden/Survivor/Old，ZGC 只有一块）。 */
    private static String classify(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("eden")) {
            return "young_eden";
        }
        if (lower.contains("survivor")) {
            return "young_survivor";
        }
        if (lower.contains("old") || lower.contains("tenured")) {
            return "old";
        }
        if (lower.contains("code cache") || lower.contains("codeheap")) {
            return "non_heap_code";
        }
        if (lower.contains("metaspace") || lower.contains("compressed class")) {
            return "non_heap_metaspace";
        }
        return lower.contains("zgc") || lower.contains("g1") ? "region_based" : "other";
    }
}
