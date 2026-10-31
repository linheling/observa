package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 堆直方图：活着的对象按类计数 + 浅尺寸，外加按 mod 归集与跨次差分。
 *
 * <p>这是 JFR 补不上的那一块：{@code jdk.ObjectAllocationSample} 回答「谁 new 了多少」，
 * 但回答不了「现在堆里到底堆着谁」——泄漏的定义是后者（分配多但回收得掉不是泄漏）。</p>
 *
 * <p>两条路：</p>
 * <ul>
 *   <li>JFR 的 {@code jdk.OldObjectSample} 只有采样、且需要长时间录制才准；</li>
 *   <li>HPROF dump 是精确的，代价是一次 STW（本机满堆 351 MB 实测 0.39 秒）加一次解析。</li>
 * </ul>
 * <p>这里选 HPROF：dump 之后在独立线程解析，解析期间服务器照常跑；解析完默认删掉 .hprof
 * （几百 MB，落在存档旁边很容易被误提交）。想留原件就传 {@code keep_file=true}。</p>
 *
 * <h2>保留多少</h2>
 * <p>结果留最近 {@value #HISTORY_SIZE} 份（{@code GET /heap/histogram} 只回最新一份，
 * {@code GET /heap/histogram/compare} 能在任意两份之间做差分）。每份结果只存前
 * {@value #CLASS_DETAIL_LIMIT} 个类——堆的字节数高度集中，这已经覆盖 99.9% 以上的质量，
 * 而全量类表乘 5 份会把诊断工具自己变成内存压力源。按 mod 的汇总是在解析时用**全量**类表算的，
 * 不受这个截断影响。</p>
 */
public final class HeapService {

    /** 保留几份结果（只有 last 会被 GET 返回，留几份是为了做跨次差分）。 */
    private static final int HISTORY_SIZE = 5;
    /** 每份结果里保留多少个类，用于跨次差分（按 mod 汇总用的是全量，不受此限）。 */
    private static final int CLASS_DETAIL_LIMIT = 3000;
    /** .hprof 原件最多留几份（keep_file 时）。 */
    private static final int KEEP_FILES = 3;
    private static final String PATH = "/heap/histogram";

    private static final HeapService INSTANCE = new HeapService();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "observa-heap");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger seq = new AtomicInteger();
    /** 最近一次任务（含 running/failed），GET 直接回它。 */
    private volatile Job job;
    /** 已完成的历次结果，最新的在头部；只在 synchronized(this) 下改。 */
    private final Deque<Job> history = new ArrayDeque<>();

    /** 一次 dump+解析的任务状态；{@code done} 之前 top 为空。 */
    public record Job(String id, String state, long startedAtMs, long dumpMs, long parseMs,
                      long fileBytes, boolean fileKept, String file, String error,
                      long totalInstances, long totalShallowBytes, int classCount, int skippedRecords,
                      List<HeapHistogram.Entry> top, List<HeapHistogram.PackageEntry> packages,
                      List<HeapHistogram.Entry> classes, List<ModIndex.ModTotal> mods,
                      boolean classesTruncated) {
    }

    private HeapService() {
    }

    public static HeapService instance() {
        return INSTANCE;
    }

    public Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("observa").resolve("heap");
    }

    /** 启动一次 dump+解析；已有任务在跑时返回 false（调用方回 409）。 */
    public synchronized boolean start(boolean live, boolean keepFile, int topN) {
        Job current = job;
        if (current != null && "running".equals(current.state())) {
            return false;
        }
        String id = "heap-" + seq.incrementAndGet();
        Job running = new Job(id, "running", System.currentTimeMillis(), 0, 0, 0, keepFile, null, null,
                0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), false);
        job = running;
        worker.submit(() -> run(running, live, keepFile, topN));
        return true;
    }

    private void run(Job running, boolean live, boolean keepFile, int topN) {
        Path dir = dir();
        Path hprof = dir.resolve(running.id() + ".hprof");
        long dumpMs = 0;
        long parseMs = 0;
        long bytes = 0;
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(hprof); // dumpHeap 拒绝覆盖已有文件
            long t0 = System.nanoTime();
            HeapHistogram.dumpTo(hprof, live);
            dumpMs = (System.nanoTime() - t0) / 1_000_000L;
            bytes = Files.exists(hprof) ? Files.size(hprof) : 0;
            long t1 = System.nanoTime();
            HeapHistogram.Result result = HeapHistogram.parse(hprof, topN);
            parseMs = (System.nanoTime() - t1) / 1_000_000L;

            // 按 mod 汇总用**全量**类表（准确），逐类明细截到上限（省内存）。
            List<ModIndex.ModTotal> mods = ModIndex.get().rollup(result.all());
            List<HeapHistogram.Entry> all = result.all();
            boolean truncated = all.size() > CLASS_DETAIL_LIMIT;
            List<HeapHistogram.Entry> classes = truncated
                    ? List.copyOf(all.subList(0, CLASS_DETAIL_LIMIT))
                    : all;

            Job done = new Job(running.id(), "done", running.startedAtMs(), dumpMs, parseMs, bytes, keepFile,
                    hprof.toString(), null,
                    result.totalInstances(), result.totalShallowBytes(), result.classCount(), result.skippedRecords(),
                    result.top(), result.topPackages(), classes, mods, truncated);
            job = done;
            remember(done);
            Observa.LOGGER.info("[observa] heap histogram {} done: {} classes, {} instances, {} mods, dump {}ms, parse {}ms",
                    running.id(), result.classCount(), result.totalInstances(), mods.size(), dumpMs, parseMs);
        } catch (Throwable t) {
            Job failed = new Job(running.id(), "failed", running.startedAtMs(), dumpMs, parseMs, bytes, keepFile,
                    hprof.toString(), String.valueOf(t), 0, 0, 0, 0,
                    List.of(), List.of(), List.of(), List.of(), false);
            job = failed;
            Observa.LOGGER.warn("[observa] heap histogram {} failed", running.id(), t);
        } finally {
            // 原件默认不保留：这里就是它唯一的生命周期终点。
            if (!keepFile) {
                try {
                    Files.deleteIfExists(hprof);
                } catch (IOException e) {
                    Observa.LOGGER.warn("[observa] cannot delete {}: {}", hprof, e.toString());
                }
            }
            trim();
        }
    }

    private synchronized void remember(Job done) {
        history.addFirst(done);
        while (history.size() > HISTORY_SIZE) {
            history.removeLast();
        }
    }

    /** 只留最近 KEEP_FILES 份 .hprof；本次文件不在其中时不动它（keep_file 的语义是「留着」）。 */
    private void trim() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> hprofs = files.filter(p -> p.getFileName().toString().endsWith(".hprof"))
                    .sorted((a, b) -> Long.compare(mtime(b), mtime(a)))
                    .toList();
            for (int i = KEEP_FILES; i < hprofs.size(); i++) {
                Files.deleteIfExists(hprofs.get(i));
            }
        } catch (IOException e) {
            Observa.LOGGER.warn("[observa] cannot trim heap dir {}: {}", dir, e.toString());
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 查询

    public synchronized boolean running() {
        Job current = job;
        return current != null && "running".equals(current.state());
    }

    public synchronized Job byId(String id) {
        if (id == null) {
            return null;
        }
        for (Job candidate : history) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        // 还在跑的那份也认（compare 会拒，但 byId 自己不该撒谎）
        Job current = job;
        return current != null && current.id().equals(id) ? current : null;
    }

    /** 历史 id，最新在前。 */
    public synchronized List<String> recentIds() {
        List<String> ids = new ArrayList<>(history.size());
        for (Job done : history) {
            ids.add(done.id());
        }
        return ids;
    }

    public JsonObject json() {
        Job j = job;
        JsonObject data = new JsonObject();
        data.addProperty("dir", dir().toString());
        JsonArray recent = new JsonArray();
        for (String id : recentIds()) {
            recent.add(id);
        }
        data.add("recent_histograms", recent);
        data.add("mod_mapping", ModIndex.get().stats());

        if (j == null) {
            data.addProperty("state", "idle");
            data.addProperty("tip", "POST " + PATH + " to dump the heap and parse it; first call takes a few seconds");
            return data;
        }
        data.addProperty("id", j.id());
        data.addProperty("state", j.state());
        data.addProperty("started_at_ms", j.startedAtMs());
        if (j.dumpMs() > 0) {
            data.addProperty("dump_ms", j.dumpMs());
        }
        if (j.parseMs() > 0) {
            data.addProperty("parse_ms", j.parseMs());
        }
        if (j.fileBytes() > 0) {
            data.addProperty("file_bytes", j.fileBytes());
        }
        data.addProperty("file_kept", j.fileKept());
        if (j.fileKept() && j.file() != null) {
            // 只有真的留在磁盘上才给路径：keep_file=false 时文件已经删了，给路径等于让 agent 去猜
            data.addProperty("file", j.file());
        }
        if (j.error() != null) {
            data.addProperty("error", j.error());
        }
        if ("done".equals(j.state())) {
            data.addProperty("total_instances", j.totalInstances());
            data.addProperty("total_shallow_bytes", j.totalShallowBytes());
            data.addProperty("class_count", j.classCount());
            if (j.skippedRecords() > 0) {
                data.addProperty("skipped_records", j.skippedRecords());
            }

            // 按 mod 汇总：回答「哪个 mod 占着」。占比用本份 dump 的浅尺寸总量作分母。
            data.add("by_mod", modArray(j.mods(), j.totalShallowBytes()));

            JsonArray top = new JsonArray();
            for (HeapHistogram.Entry e : j.top()) {
                JsonObject row = new JsonObject();
                row.addProperty("class", e.className());
                row.addProperty("mod_id", ModIndex.get().modOfClass(e.className()).modId());
                row.addProperty("instances", e.instances());
                row.addProperty("shallow_bytes", e.shallowBytes());
                row.addProperty("pct", pct(e.shallowBytes(), j.totalShallowBytes()));
                top.add(row);
            }
            data.add("top", top);

            JsonArray pkgs = new JsonArray();
            for (HeapHistogram.PackageEntry e : j.packages()) {
                JsonObject row = new JsonObject();
                row.addProperty("package", e.packageName());
                row.addProperty("instances", e.instances());
                row.addProperty("shallow_bytes", e.shallowBytes());
                pkgs.add(row);
            }
            data.add("packages", pkgs);

            JsonArray notes = new JsonArray();
            notes.add("shallow_bytes is an estimate: object header counted as 12/16 bytes, referents not included");
            notes.add("counts are live objects only when the dump was taken with live=true");
            notes.add("dump pauses the JVM for the duration reported in dump_ms; parsing runs off-thread");
            notes.add("mod_id comes from matching the class package against the jars in mods/ (see mod_mapping); "
                    + "library and jdk rows are not any mod's fault");
            if (j.classesTruncated()) {
                notes.add("only the top " + CLASS_DETAIL_LIMIT + " classes are kept per dump for diffing; "
                        + "by_mod is computed from the full class list and is not truncated");
            }
            notes.add("compare two dumps with GET " + PATH + "/compare to see what is growing");
            data.add("notes", notes);
        } else if ("running".equals(j.state())) {
            data.addProperty("next", "poll GET " + PATH + " in a few seconds; the dump itself pauses the JVM");
        }
        return data;
    }

    /**
     * 两份 dump 的差分：{@code from} 的 id 省略时用次新的一份，{@code to} 省略时用最新的一份。
     * 按类的 delta 用截断后的类表（见类注释），按 mod 的 delta 用全量汇总。
     */
    public JsonObject compare(String fromId, String toId, int topN) {
        List<String> ids = recentIds();
        String from = fromId == null ? (ids.size() >= 2 ? ids.get(1) : null) : fromId;
        String to = toId == null ? (ids.isEmpty() ? null : ids.get(0)) : toId;

        JsonObject data = new JsonObject();
        if (from == null || to == null) {
            return null; // 调用方回 400：历史不够两份
        }
        Job a = byId(from);
        Job b = byId(to);
        if (a == null) {
            return null;
        }
        if (b == null) {
            return null;
        }
        if (!"done".equals(a.state()) || !"done".equals(b.state())) {
            return null;
        }
        if (a.id().equals(b.id())) {
            return null;
        }

        data.add("from", describe(a));
        data.add("to", describe(b));

        JsonObject delta = new JsonObject();
        delta.addProperty("total_shallow_bytes", b.totalShallowBytes() - a.totalShallowBytes());
        delta.addProperty("total_instances", b.totalInstances() - a.totalInstances());
        delta.addProperty("class_count", b.classCount() - a.classCount());
        data.add("delta", delta);

        // ---- 按类
        Map<String, long[]> perClass = new LinkedHashMap<>();
        for (HeapHistogram.Entry e : a.classes()) {
            long[] slot = perClass.computeIfAbsent(e.className(), k -> new long[4]);
            slot[0] = e.instances();
            slot[1] = e.shallowBytes();
        }
        for (HeapHistogram.Entry e : b.classes()) {
            long[] slot = perClass.computeIfAbsent(e.className(), k -> new long[4]);
            slot[2] = e.instances();
            slot[3] = e.shallowBytes();
        }
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(perClass.entrySet());
        rows.sort((l, r) -> Long.compare(r.getValue()[3] - r.getValue()[1], l.getValue()[3] - l.getValue()[1]));
        JsonArray classes = new JsonArray();
        for (int i = 0; i < Math.min(topN, rows.size()); i++) {
            Map.Entry<String, long[]> e = rows.get(i);
            long[] v = e.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("class", e.getKey());
            row.addProperty("mod_id", ModIndex.get().modOfClass(e.getKey()).modId());
            row.addProperty("from_instances", v[0]);
            row.addProperty("to_instances", v[2]);
            row.addProperty("delta_instances", v[2] - v[0]);
            row.addProperty("from_bytes", v[1]);
            row.addProperty("to_bytes", v[3]);
            row.addProperty("delta_bytes", v[3] - v[1]);
            classes.add(row);
        }
        data.add("classes", classes);

        // ---- 按 mod（全量，不受类明细截断影响）
        Map<String, ModDelta> perMod = new LinkedHashMap<>();
        for (ModIndex.ModTotal t : a.mods()) {
            ModDelta d = perMod.computeIfAbsent(t.modId(), k -> new ModDelta(t.displayName()));
            d.fromBytes = t.bytes();
            d.fromInstances = t.instances();
        }
        for (ModIndex.ModTotal t : b.mods()) {
            ModDelta d = perMod.computeIfAbsent(t.modId(), k -> new ModDelta(t.displayName()));
            d.toBytes = t.bytes();
            d.toInstances = t.instances();
        }
        List<Map.Entry<String, ModDelta>> modRows = new ArrayList<>(perMod.entrySet());
        modRows.sort((l, r) -> Long.compare(r.getValue().deltaBytes(), l.getValue().deltaBytes()));
        JsonArray mods = new JsonArray();
        for (int i = 0; i < Math.min(topN, modRows.size()); i++) {
            Map.Entry<String, ModDelta> e = modRows.get(i);
            ModDelta d = e.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("mod_id", e.getKey());
            row.addProperty("mod_name", d.name);
            row.addProperty("from_bytes", d.fromBytes);
            row.addProperty("to_bytes", d.toBytes);
            row.addProperty("delta_bytes", d.deltaBytes());
            row.addProperty("from_instances", d.fromInstances);
            row.addProperty("to_instances", d.toInstances);
            row.addProperty("delta_instances", d.toInstances - d.fromInstances);
            mods.add(row);
        }
        data.add("mods", mods);

        JsonArray notes = new JsonArray();
        notes.add("positive delta = grew between the two dumps; negative = shrank");
        notes.add("intervals differ if the two dumps are far apart; both are point-in-time, not averaged");
        notes.add("class rows cover the top " + CLASS_DETAIL_LIMIT + " classes of each dump, mod rows are complete");
        notes.add("a class that appears only in one dump shows 0 on the other side");
        data.add("notes", notes);
        return data;
    }

    private static final class ModDelta {
        private final String name;
        private long fromBytes;
        private long fromInstances;
        private long toBytes;
        private long toInstances;

        private ModDelta(String name) {
            this.name = name;
        }

        private long deltaBytes() {
            return toBytes - fromBytes;
        }
    }

    private static JsonObject describe(Job j) {
        JsonObject o = new JsonObject();
        o.addProperty("id", j.id());
        o.addProperty("started_at_ms", j.startedAtMs());
        o.addProperty("class_count", j.classCount());
        o.addProperty("total_instances", j.totalInstances());
        o.addProperty("total_shallow_bytes", j.totalShallowBytes());
        return o;
    }

    private static JsonArray modArray(List<ModIndex.ModTotal> totals, long totalBytes) {
        JsonArray arr = new JsonArray();
        for (ModIndex.ModTotal t : totals) {
            JsonObject row = new JsonObject();
            row.addProperty("mod_id", t.modId());
            row.addProperty("mod_name", t.displayName());
            row.addProperty("instances", t.instances());
            row.addProperty("shallow_bytes", t.bytes());
            row.addProperty("pct", pct(t.bytes(), totalBytes));
            row.addProperty("classes", t.classes());
            arr.add(row);
        }
        return arr;
    }

    private static double pct(long part, long total) {
        return total <= 0 ? 0.0 : Math.round(part * 10000.0 / total) / 100.0;
    }

    /** 最近几份结果的 id 集合（给测试/清单用）。 */
    public static Set<String> knownIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(INSTANCE.recentIds()));
    }
}
