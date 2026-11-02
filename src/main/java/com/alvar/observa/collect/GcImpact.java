package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 dump（或任何 STW 采集）自身造成的可观测开销。
 *
 * <p>为什么值得单独记：{@code /heap/histogram} 只报 {@code dump_ms}/{@code parse_ms}，
 * 但真正让玩家卡住的是 dump 触发的回收——{@code HeapDumpInitiatedGC} 之后跟着一次
 * {@code Pause Full}，那几百毫秒的停顿是采集自己造成的，会被误当成「服务器本来就有问题」。
 * 把「代价」和「结论」放在同一个响应里，才能回答「这次分析值不值」。</p>
 *
 * <p>数据只来自 {@link GarbageCollectorMXBean} 的累计值和 {@link MemoryUsage}，
 * 不依赖任何 JVM 参数，也不额外触发一次回收。</p>
 *
 * @param pauseMs            dump 本身的耗时（由调用方传入，就是 {@code dump_ms}）
 * @param collections        这次采集期间新增的回收次数（所有收集器求和）
 * @param pauseTotalMs       这次采集期间新增的回收耗时（所有收集器求和）
 * @param pressuredCollectors 期间真的跑过的「非年轻代」收集器名字；
 *                           非空即说明这次采集触发了 full/old 回收
 * @param heapUsedBeforeBytes dump 前已用堆
 * @param heapUsedAfterBytes  dump 后已用堆
 */
public record GcImpact(long pauseMs, int collections, long pauseTotalMs,
                       List<String> pressuredCollectors,
                       long heapUsedBeforeBytes, long heapUsedAfterBytes) {

    /** 采集前的一个采样点。 */
    public record Snapshot(long collections, long pauseTotalMs, Map<String, Long> perCollector,
                           long heapUsedBytes) {
    }

    public static Snapshot snapshot() {
        long collections = 0;
        long pauseTotalMs = 0;
        Map<String, Long> per = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0L, gc.getCollectionCount());
            long time = Math.max(0L, gc.getCollectionTime());
            collections += count;
            pauseTotalMs += time;
            per.put(gc.getName(), count);
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new Snapshot(collections, pauseTotalMs, per, Math.max(0L, heap.getUsed()));
    }

    /** 用前后两个采样点算差值；{@code pauseMs} 是调用方测到的 dump 耗时。 */
    public static GcImpact measure(long pauseMs, Snapshot before, Snapshot after) {
        int collections = (int) Math.max(0L, after.collections() - before.collections());
        long pauseTotalMs = Math.max(0L, after.pauseTotalMs() - before.pauseTotalMs());
        Set<String> pressured = new LinkedHashSet<>();
        for (Map.Entry<String, Long> e : after.perCollector().entrySet()) {
            long delta = e.getValue() - before.perCollector().getOrDefault(e.getKey(), e.getValue());
            if (delta > 0 && !isYoung(e.getKey())) {
                pressured.add(e.getKey());
            }
        }
        return new GcImpact(pauseMs, collections, pauseTotalMs, List.copyOf(pressured),
                before.heapUsedBytes(), after.heapUsedBytes());
    }

    /**
     * 年轻代收集器的名字：G1 Young Generation / PS Scavenge / ParNew / Copy / DefNew。
     * 其余（G1 Old Generation / MarkSweepCompact / ZGC / Shenandoah…）都算「有停顿代价」的那种。
     */
    private static boolean isYoung(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("young") || n.contains("scavenge") || n.contains("parnew")
                || n.contains("copy") || n.contains("defnew") || n.contains("g1 young");
    }

    /** 这次采集是否触发了 full/old 回收。 */
    public boolean fullGcTriggered() {
        return !pressuredCollectors.isEmpty();
    }

    /** dump 后堆用量变化（负数 = 采集顺手回收了一些）。 */
    public long heapUsedDeltaBytes() {
        return heapUsedAfterBytes - heapUsedBeforeBytes;
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("pause_ms", pauseMs);
        o.addProperty("gc_collections", collections);
        o.addProperty("gc_pause_ms", pauseTotalMs);
        o.addProperty("full_gc_triggered", fullGcTriggered());
        JsonArray names = new JsonArray();
        for (String n : pressuredCollectors) {
            names.add(n);
        }
        o.add("full_gc_collectors", names);
        o.addProperty("heap_used_before_bytes", heapUsedBeforeBytes);
        o.addProperty("heap_used_after_bytes", heapUsedAfterBytes);
        o.addProperty("heap_used_delta_bytes", heapUsedDeltaBytes());
        return o;
    }

    /** 给 {@code notes} 用的一句话，只在真的发生了额外回收时才值得说。 */
    public String note() {
        if (collections == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("this collection caused ").append(collections).append(" GC(s), ")
                .append(pauseTotalMs).append(" ms total");
        if (fullGcTriggered()) {
            sb.append(" (full: ").append(String.join(", ", pressuredCollectors)).append(')');
        }
        sb.append("; that pause is the cost of measuring, not a pre-existing problem");
        return sb.toString();
    }

    /** 空值占位：采集还没完成时别让 {@code impact} 字段凭空消失。 */
    public static JsonObject unavailable() {
        JsonObject o = new JsonObject();
        o.addProperty("available", false);
        o.addProperty("tip", "impact is measured around the dump and appears when it finishes");
        return o;
    }

    /** 只给 {@code /heap/dumps} 列表用的瘦身版。 */
    public JsonObject brief() {
        JsonObject o = new JsonObject();
        o.addProperty("pause_ms", pauseMs);
        o.addProperty("gc_collections", collections);
        o.addProperty("gc_pause_ms", pauseTotalMs);
        o.addProperty("full_gc_triggered", fullGcTriggered());
        return o;
    }

    static List<String> copy(List<String> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }
}
