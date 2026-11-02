package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历次 dump 的按 mod 内存趋势：回答「哪个 mod 在持续涨」。
 *
 * <h2>为什么单点直方图和两点 compare 不够</h2>
 * <p>{@code GET /heap/histogram} 只给最新一份的静态快照，{@code GET /heap/histogram/compare}
 * 只能两两相减。两点比较分不清三种情况：偶发的一次性分配（涨了又回落）、GC 没来得及回收的
 * 锯齿、以及真正持续增长的泄漏。要看趋势至少要一串按时间排列的样本，而且样本之间的时间间隔
 * 必须能换算成「每分钟涨多少字节」，否则「涨了 80 MB」在 10 分钟窗口和 3 天窗口里是完全不同的
 * 结论。所以这里在每次 dump 完成后推一条 by_mod 聚合样本，保留最近 {@value #CAPACITY} 次。</p>
 *
 * <h2>为什么不做后台定时 dump</h2>
 * <p>HPROF dump 会 stop-the-world，对线上服务器本身就是一次可见的停顿；诊断工具不应该自己
 * 制造被测现象。采样节奏完全由人工 {@code POST /heap/histogram/start} 决定，所以返回里用
 * {@code cadence: "per dump"} 明确写死，避免 agent 把「样本稀疏」误判成「没有变化」。</p>
 *
 * <h2>存储取舍</h2>
 * <p>每份样本只存总量和 by_mod 汇总（不存逐类明细）：逐类表动辄几十 MB，720 份就是灾难；
 * 而趋势问题关心的是 mod 粒度。单份样本是 {@link ModIndex.ModTotal} 的不可变拷贝，
 * 与 {@link HeapService} 内部 Job 互不共享，dump 完成后也不受后续清理影响。</p>
 *
 * <h2>线程模型与输出约定</h2>
 * <p>写入在 dump 工作线程，读取在 HTTP 线程，全部方法 {@code synchronized}；临界区里没有
 * IO，锁竞争可以忽略。对外 payload 字段一律 snake_case 英文；时间统一用 epoch 毫秒。
 * {@code json} 永不抛出：内部异常降级成一份带 note 的空结果，不把路由打成 500。</p>
 */
public final class HeapTimeseries {

    /** 环形缓冲容量：按人工 dump 的频率，覆盖几天到一两周。 */
    private static final int CAPACITY = 720;

    /** {@code limit <= 0} 时的默认返回样本数。 */
    private static final int DEFAULT_LIMIT = 50;

    private static final HeapTimeseries INSTANCE = new HeapTimeseries();

    /** 一条样本：一次 dump 完成时的总量 + by_mod 汇总。 */
    private record Sample(long atMs, long totalShallowBytes, int classCount, long instances,
                          List<ModIndex.ModTotal> mods) {
    }

    /** 某个 mod 在窗口内的点（构造时按新→旧追加，输出前反转成旧→新）。 */
    private static final class ModSeries {
        private final String modId;
        private final String modName;
        private final List<long[]> points = new ArrayList<>(); // {at_ms, bytes}

        private ModSeries(String modId, String modName) {
            this.modId = modId;
            this.modName = modName;
        }
    }

    private final Sample[] samples = new Sample[CAPACITY];
    private int head;
    private int size;

    private HeapTimeseries() {
    }

    public static HeapTimeseries instance() {
        return INSTANCE;
    }

    /**
     * 记录一次 dump 的聚合结果。任何异常都在内部吞掉：一次诊断样本失败不应该让 dump 线程报错。
     *
     * @param atMs              dump 完成时刻（epoch 毫秒）
     * @param totalShallowBytes 本次 dump 的浅尺寸总量
     * @param classCount        直方图中出现过的类数量
     * @param instances         存活实例总数
     * @param mods              按 mod 的汇总；可为 {@code null}（此时 mods 段记为空）
     */
    public synchronized void record(long atMs, long totalShallowBytes, int classCount, long instances,
                                    List<ModIndex.ModTotal> mods) {
        try {
            List<ModIndex.ModTotal> copy;
            if (mods == null || mods.isEmpty()) {
                copy = List.of();
            } else {
                List<ModIndex.ModTotal> tmp = new ArrayList<>(mods.size());
                for (ModIndex.ModTotal t : mods) {
                    if (t != null) {
                        tmp.add(t);
                    }
                }
                copy = List.copyOf(tmp);
            }
            samples[head] = new Sample(atMs, totalShallowBytes, classCount, instances, copy);
            head = (head + 1) % samples.length;
            if (size < samples.length) {
                size++;
            }
        } catch (Throwable ignored) {
            // 记录失败不影响 dump 主流程
        }
    }

    public synchronized int size() {
        return size;
    }

    /**
     * 按时间窗口导出趋势。
     *
     * @param windowSeconds 只看 {@code at_ms >= now - windowSeconds*1000} 的样本；负数按 0 处理
     * @param limit         最多返回多少条样本（按时间新→旧）；{@code <= 0} 时用 {@value #DEFAULT_LIMIT}
     */
    public synchronized JsonObject json(int windowSeconds, int limit) {
        int window = Math.max(0, windowSeconds);
        try {
            return buildJson(window, limit);
        } catch (Throwable t) {
            // 不把内部异常抛给 HTTP 层
            JsonObject data = new JsonObject();
            data.addProperty("window_s", window);
            data.addProperty("samples", 0);
            data.addProperty("cadence", "per dump");
            data.add("series", new JsonArray());
            data.add("totals", new JsonArray());
            JsonArray notes = new JsonArray();
            notes.add("timeseries unavailable: " + t);
            data.add("notes", notes);
            return data;
        }
    }

    // ------------------------------------------------------------------ 组装

    private JsonObject buildJson(int windowSeconds, int limit) {
        long now = System.currentTimeMillis();
        long from = now - windowSeconds * 1000L;

        int start = (head - size + samples.length) % samples.length;
        List<Sample> picked = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Sample s = samples[(start + i) % samples.length];
            if (s != null && s.atMs() >= from) {
                picked.add(s);
            }
        }
        // 新→旧，再按 limit 截断
        picked.sort(Comparator.comparingLong(Sample::atMs).reversed());
        int cap = limit > 0 ? limit : DEFAULT_LIMIT;
        if (picked.size() > cap) {
            picked = new ArrayList<>(picked.subList(0, cap));
        }

        JsonObject data = new JsonObject();
        data.addProperty("window_s", windowSeconds);
        data.addProperty("samples", picked.size());
        data.addProperty("cadence", "per dump");

        if (picked.isEmpty()) {
            data.add("series", new JsonArray());
            data.add("totals", new JsonArray());
            JsonArray notes = new JsonArray();
            if (size == 0) {
                notes.add("no dump samples yet; call POST /heap/histogram/start a few times");
            } else {
                notes.add("no dump sample within window_s; widen the window or start another dump");
            }
            data.add("notes", notes);
            return data;
        }

        // ---- 按 mod 聚合点列：先按新→旧扫一遍，再反转
        Map<String, ModSeries> byMod = new LinkedHashMap<>();
        JsonArray totals = new JsonArray();
        for (Sample s : picked) {
            JsonObject total = new JsonObject();
            total.addProperty("at_ms", s.atMs());
            total.addProperty("total_bytes", s.totalShallowBytes());
            totals.add(total);

            for (ModIndex.ModTotal t : s.mods()) {
                ModSeries series = byMod.computeIfAbsent(t.modId(), k -> new ModSeries(k, t.displayName()));
                series.points.add(new long[]{s.atMs(), t.bytes()});
            }
        }
        List<ModSeries> rows = new ArrayList<>(byMod.values());
        for (ModSeries series : rows) {
            Collections.reverse(series.points);
        }
        // 按窗口内最后一个（即最新）样本的 bytes 降序
        rows.sort((a, b) -> {
            long ra = a.points.get(a.points.size() - 1)[1];
            long rb = b.points.get(b.points.size() - 1)[1];
            int cmp = Long.compare(rb, ra);
            return cmp != 0 ? cmp : a.modId.compareTo(b.modId);
        });

        JsonArray seriesArray = new JsonArray();
        for (ModSeries series : rows) {
            seriesArray.add(seriesJson(series));
        }
        data.add("series", seriesArray);
        data.add("totals", totals);

        JsonArray notes = new JsonArray();
        notes.add("cadence is one sample per dump; a dump pauses the JVM");
        data.add("notes", notes);
        return data;
    }

    private static JsonObject seriesJson(ModSeries series) {
        JsonObject row = new JsonObject();
        row.addProperty("mod_id", series.modId);
        row.addProperty("mod_name", series.modName);

        JsonArray points = new JsonArray();
        for (long[] p : series.points) {
            JsonObject point = new JsonObject();
            point.addProperty("at_ms", p[0]);
            point.addProperty("bytes", p[1]);
            points.add(point);
        }
        row.add("points", points);

        long[] first = series.points.get(0);
        long[] last = series.points.get(series.points.size() - 1);
        long deltaBytes = last[1] - first[1];
        row.addProperty("delta_bytes", deltaBytes);

        if (series.points.size() < 2) {
            // 只有一个点：无从谈变化，比率类字段给 0 而不是 null，方便 agent 直接比较
            row.addProperty("delta_pct", 0.0);
            row.addProperty("growth_bytes_per_min", 0.0);
        } else {
            if (first[1] == 0L) {
                row.add("delta_pct", JsonNull.INSTANCE);
            } else {
                row.addProperty("delta_pct", round2(deltaBytes * 100.0 / first[1]));
            }
            long deltaMs = last[0] - first[0];
            row.addProperty("growth_bytes_per_min",
                    deltaMs < 1000L ? 0.0 : round2((double) deltaBytes * 60_000.0 / deltaMs));
        }
        return row;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
