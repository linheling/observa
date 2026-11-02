package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * retained size（支配树）分析的作业壳：dump → {@link HeapGraph} → JSON。
 *
 * <p>和 {@link HeapService} 的分工：那个回答「哪个类占着多少浅字节」，这个回答
 * 「删掉谁能掉多少内存、是被谁持有的」。两者都要 dump 堆，但后者还需要建整张对象图，
 * 所以额外占用几十到上百 MB 临时内存、几百毫秒到几秒的解析时间——只适合被显式触发。</p>
 *
 * <p>.hprof 一律解析完就删：这份分析的产物是图上的数字，不是文件。要留原件请用
 * {@code /heap/histogram/start} 的 {@code keep_file:true}。</p>
 */
public final class RetainedService {

    /** 单次 dump 里最多报多少行（按 mod 汇总用全量，不受此限）。 */
    private static final int CLASS_ROW_LIMIT = 60;
    private static final int MOD_ROW_LIMIT = 20;
    private static final String PATH = "/heap/retained";
    private static final String HISTOGRAM_PATH = "/heap/histogram";

    private static final RetainedService INSTANCE = new RetainedService();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "observa-retained");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger seq = new AtomicInteger();
    private volatile Job job;

    /**
     * 一次分析的状态。
     *
     * @param classes      每个类的 retained 汇总（全量，json() 再截断）
     * @param crossCheck   仅自检模式：用同一份 dump 跑一遍直方图解析，把两者总量对齐（null 表示没跑）
     */
    public record Job(String id, String state, long startedAtMs, long dumpMs, long parseMs, long analyzeMs,
                      long fileBytes, String error, long objects, long edges, int classes, int roots,
                      long layoutMismatches, long unreachable, long unreachableBytes, long shallowBytes,
                      long dumpShallowBytes, long droppedEdges, List<HeapGraph.ClassRow> classRows,
                      String crossCheck, int maxObjects, boolean lean, Limit limit, GcImpact impact) {
    }

    /**
     * 预检就拒绝时的结构化信息（{@code state=failed}，{@code error} 是同一件事的文字版）。
     *
     * <p>把这些数字塞进响应，是为了让调用方不必先读日志才知道「该加多少堆 / 该把上限改成多少」。</p>
     *
     * @param objects       数到的对象数（{@code exact=false} 表示是提前中止时的下界）
     * @param maxObjects    这次实际生效的上限
     * @param requiredBytes 按每对象 160 B 估算，建图大概要这么多临时堆
     * @param exact         objects 是否精确
     */
    public record Limit(long objects, int maxObjects, long requiredBytes, boolean exact) {
    }

    private RetainedService() {
    }

    public static RetainedService instance() {
        return INSTANCE;
    }

    public Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("observa").resolve("retained");
    }

    /** 启动一次分析；已在跑时返回 false（调用方回 409）。 */
    public synchronized boolean start(boolean live) {
        return start(live, false, 0);
    }

    /**
     * 启动一次分析。
     *
     * @param crossCheck 自检用：再用直方图解析同一份 dump，核对「对象数/浅字节」两套口径是否一致
     */
    public synchronized boolean start(boolean live, boolean crossCheck) {
        return start(live, crossCheck, 0);
    }

    /**
     * 启动一次分析。
     *
     * @param crossCheck 自检用：再用直方图解析同一份 dump，核对「对象数/浅字节」两套口径是否一致
     * @param maxObjects 显式放宽对象数上限（0 = 默认，见 {@link HeapGraph#effectiveObjectLimit(int)}）；
     *                   仍受「可用堆的 40%」约束，超了会在预检阶段被拒绝而不是 OOM
     */
    public synchronized boolean start(boolean live, boolean crossCheck, int maxObjects) {
        return start(live, crossCheck, maxObjects, false);
    }

    /**
     * 带 lean 开关的启动版本。
     *
     * @param lean {@code true} 时按每对象 96 B 而不是 160 B 算预算（允许的图大约 1.6 倍），
     *             代价是按类聚合的 retained 精度下降，见 {@link HeapGraph#LEAN_BYTES_PER_OBJECT}
     */
    public synchronized boolean start(boolean live, boolean crossCheck, int maxObjects, boolean lean) {
        Job current = job;
        if (current != null && "running".equals(current.state())) {
            return false;
        }
        String id = "retained-" + seq.incrementAndGet();
        Job running = new Job(id, "running", System.currentTimeMillis(), 0, 0, 0, 0, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null, maxObjects, lean, null, null);
        job = running;
        worker.submit(() -> run(running, live, crossCheck, maxObjects, lean));
        return true;
    }

    public synchronized boolean running() {
        Job current = job;
        return current != null && "running".equals(current.state());
    }

    private void run(Job running, boolean live, boolean crossCheck, int maxObjects, boolean lean) {
        Path dir = dir();
        Path hprof = dir.resolve(running.id() + ".hprof");
        long dumpMs = 0;
        long parseMs = 0;
        long analyzeMs = 0;
        long bytes = 0;
        GcImpact impact = null;
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(hprof);
            long t0 = System.nanoTime();
            GcImpact.Snapshot before = GcImpact.snapshot();
            HeapHistogram.dumpTo(hprof, live);
            dumpMs = (System.nanoTime() - t0) / 1_000_000L;
            impact = GcImpact.measure(dumpMs, before, GcImpact.snapshot());
            bytes = Files.exists(hprof) ? Files.size(hprof) : 0;
            long t1 = System.nanoTime();
            HeapGraph.Result result = HeapGraph.analyze(hprof, maxObjects, lean);
            analyzeMs = (System.nanoTime() - t1) / 1_000_000L;
            parseMs = 0; // 三趟都在 analyze 里，dump_ms/analyze_ms 已经把时间分干净了
            String crossCheckNote = crossCheck ? crossCheck(hprof, result) : null;
            Job done = new Job(running.id(), "done", running.startedAtMs(), dumpMs, parseMs, analyzeMs,
                    bytes, null, result.objects(), result.edges(), result.classes(), result.roots(),
                    result.layoutMismatches(), result.unreachable(), result.unreachableBytes(),
                    result.shallowBytes(), result.dumpShallowBytes(), result.droppedEdges(),
                    result.classRows(), crossCheckNote, maxObjects, lean, null, impact);
            job = done;
            Observa.LOGGER.info("[observa] retained {} done: {} objects, {} edges, {} classes, {} roots, "
                            + "retained {} bytes of {} dump bytes, layout_mismatches {}, dropped_edges {}, "
                            + "unreachable {} ({} bytes), dump {}ms, analyze {}ms",
                    running.id(), result.objects(), result.edges(), result.classes(), result.roots(),
                    result.shallowBytes(), result.dumpShallowBytes(), result.layoutMismatches(),
                    result.droppedEdges(), result.unreachable(), result.unreachableBytes(), dumpMs, analyzeMs);
            if (crossCheckNote != null) {
                Observa.LOGGER.info("[observa] retained {} cross-check: {}", running.id(), crossCheckNote);
            }
            if (crossCheck) {
                Observa.LOGGER.info("[observa] retained {} partition-check: {}",
                        running.id(), partitionCheck(result));
            }
        } catch (HeapGraph.TooLargeException tooLarge) {
            Limit limit = new Limit(tooLarge.objects(), tooLarge.limit(), tooLarge.requiredBytes(),
                    tooLarge.exact());
            Job failed = new Job(running.id(), "failed", running.startedAtMs(), dumpMs, parseMs, analyzeMs,
                    bytes, tooLarge.getMessage(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null,
                    maxObjects, lean, limit, impact);
            job = failed;
            Observa.LOGGER.warn("[observa] retained {} rejected by preflight: {} (needs ~{} MB, limit {} objects)",
                    running.id(), tooLarge.getMessage(), tooLarge.requiredBytes() / (1024 * 1024),
                    tooLarge.limit());
        } catch (Throwable t) {
            Job failed = new Job(running.id(), "failed", running.startedAtMs(), dumpMs, parseMs, analyzeMs,
                    bytes, String.valueOf(t), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null,
                    maxObjects, lean, null, impact);
            job = failed;
            Observa.LOGGER.warn("[observa] retained {} failed", running.id(), t);
        } finally {
            try {
                Files.deleteIfExists(hprof);
            } catch (IOException e) {
                Observa.LOGGER.warn("[observa] cannot delete {}: {}", hprof, e.toString());
            }
        }
    }

    /**
     * 用同一份 dump 跑一遍直方图解析，和对象图的总量对齐。
     *
     * <p>两条链路读同一个文件、各自独立算「有多少对象、多少浅字节」，数字对不上就说明有一边的
     * 记录解析错位了——这是不可能靠肉眼看出来、但一出错就全错的错误，所以留成自检项。</p>
     */
    private static String crossCheck(Path hprof, HeapGraph.Result graph) {
        try {
            HeapHistogram.Result histogram = HeapHistogram.parse(hprof, 0);
            long objectsDelta = graph.objects() - histogram.totalInstances();
            long bytesDelta = graph.dumpShallowBytes() - histogram.totalShallowBytes();
            return "histogram instances=" + histogram.totalInstances()
                    + " shallow_bytes=" + histogram.totalShallowBytes()
                    + " | graph objects=" + graph.objects()
                    + " dump_bytes=" + graph.dumpShallowBytes()
                    + " | delta objects=" + objectsDelta + " bytes=" + bytesDelta
                    + (Math.abs(bytesDelta) > Math.max(1024, histogram.totalShallowBytes() / 100)
                    || objectsDelta != 0
                    ? " (MISMATCH: the two readers disagree, one of them is misaligned)"
                    : " (ok)");
        } catch (Throwable t) {
            return "cross-check failed: " + t;
        }
    }

    // ------------------------------------------------------------------ 查询

    public JsonObject json() {
        Job j = job;
        JsonObject data = new JsonObject();
        data.addProperty("dir", dir().toString());
        if (j == null) {
            data.addProperty("state", "idle");
            data.addProperty("tip", "POST " + PATH + " to dump the heap and build the object graph; "
                    + "this needs extra heap and pauses the JVM once");
            return data;
        }
        data.addProperty("id", j.id());
        data.addProperty("state", j.state());
        data.addProperty("started_at_ms", j.startedAtMs());
        if (j.maxObjects() > 0) {
            data.addProperty("max_objects", j.maxObjects());
        }
        data.addProperty("mode", j.lean() ? "lean" : "full");
        data.addProperty("object_limit", HeapGraph.effectiveObjectLimit(j.maxObjects(),
                j.lean() ? HeapGraph.leanBytesPerObject() : HeapGraph.bytesPerObject()));
        data.add("impact", j.impact() == null ? GcImpact.unavailable() : j.impact().json());
        if (j.limit() != null) {
            Limit limit = j.limit();
            JsonObject o = new JsonObject();
            o.addProperty("objects", limit.objects());
            o.addProperty("exact", limit.exact());
            o.addProperty("max_objects", limit.maxObjects());
            o.addProperty("required_bytes", limit.requiredBytes());
            o.addProperty("required_mb", limit.requiredBytes() / (1024 * 1024));
            data.add("too_large", o);
            JsonArray next = new JsonArray();
            next.add("GET " + HISTOGRAM_PATH
                    + " returns live objects per class without building the object graph");
            if (!j.lean()) {
                next.add("retry with mode=lean: it budgets 96 instead of 160 bytes per object, so the same "
                        + "heap holds about 1.6x more objects (retained precision per class drops)");
            }
            next.add("or raise -Xmx: the graph needs at least " + (limit.requiredBytes() / (1024 * 1024))
                    + " MB of free heap for what has been counted so far (" + limit.objects()
                    + " objects" + (limit.exact() ? "" : ", a lower bound") + "), then retry; "
                    + "max_objects cannot exceed 40% of the heap, so raising -Xmx is the real fix");
            data.add("next", next);
        }
        if (j.dumpMs() > 0) {
            data.addProperty("dump_ms", j.dumpMs());
        }
        if (j.analyzeMs() > 0) {
            data.addProperty("analyze_ms", j.analyzeMs());
        }
        if (j.fileBytes() > 0) {
            data.addProperty("file_bytes", j.fileBytes());
        }
        if (j.error() != null) {
            data.addProperty("error", j.error());
        }
        if (!"done".equals(j.state())) {
            if ("running".equals(j.state())) {
                data.addProperty("next", "poll GET " + PATH + " in a few seconds; the dump pauses the JVM");
            }
            return data;
        }

        data.addProperty("objects", j.objects());
        data.addProperty("edges", j.edges());
        data.addProperty("classes", j.classes());
        data.addProperty("roots", j.roots());
        data.addProperty("total_retained_bytes", j.shallowBytes());
        data.addProperty("dump_shallow_bytes", j.dumpShallowBytes());
        if (j.layoutMismatches() > 0) {
            data.addProperty("layout_mismatches", j.layoutMismatches());
        }
        if (j.droppedEdges() > 0) {
            data.addProperty("dropped_edges", j.droppedEdges());
        }
        if (j.unreachable() > 0) {
            data.addProperty("unreachable_objects", j.unreachable());
            data.addProperty("unreachable_bytes", j.unreachableBytes());
            data.addProperty("unreachable_pct", pct(j.unreachableBytes(), j.dumpShallowBytes()));
        }
        if (j.crossCheck() != null) {
            data.addProperty("cross_check", j.crossCheck());
        }

        long total = j.shallowBytes();
        JsonArray classes = new JsonArray();
        int emitted = 0;
        for (HeapGraph.ClassRow row : j.classRows()) {
            if (emitted++ >= CLASS_ROW_LIMIT) {
                break;
            }
            JsonObject o = new JsonObject();
            o.addProperty("class", row.className());
            o.addProperty("mod_id", ModIndex.get().modOfClass(row.className()).modId());
            o.addProperty("retained_bytes", row.retainedBytes());
            o.addProperty("pct", pct(row.retainedBytes(), total));
            o.addProperty("instances_counted", row.instances());
            o.addProperty("dominated_by_class", row.dominatedByClass());
            o.addProperty("dominated_by_mod_id",
                    HeapGraph.GC_ROOT_NAME.equals(row.dominatedByClass())
                            ? "gc_root"
                            : ModIndex.get().modOfClass(row.dominatedByClass()).modId());
            classes.add(o);
        }
        data.add("classes", classes);
        data.addProperty("classes_reported", Math.min(CLASS_ROW_LIMIT, j.classRows().size()));
        data.addProperty("classes_with_retained", j.classRows().size());
        data.addProperty("classes_truncated", j.classRows().size() > CLASS_ROW_LIMIT);

        // 按 mod 汇总（全量；这里才是「哪个 mod 真的占着内存」的答案）
        Map<String, long[]> perMod = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (HeapGraph.ClassRow row : j.classRows()) {
            ModIndex.ModRef ref = ModIndex.get().modOfClass(row.className());
            long[] slot = perMod.computeIfAbsent(ref.modId(), k -> new long[3]);
            slot[0] += row.retainedBytes();
            slot[1] += row.instances();
            slot[2]++;
            names.putIfAbsent(ref.modId(), ref.displayName());
        }
        List<Map.Entry<String, long[]>> modRows = new ArrayList<>(perMod.entrySet());
        modRows.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        JsonArray mods = new JsonArray();
        for (int i = 0; i < Math.min(MOD_ROW_LIMIT, modRows.size()); i++) {
            Map.Entry<String, long[]> e = modRows.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("mod_id", e.getKey());
            o.addProperty("mod_name", names.getOrDefault(e.getKey(), e.getKey()));
            o.addProperty("retained_bytes", e.getValue()[0]);
            o.addProperty("pct", pct(e.getValue()[0], total));
            o.addProperty("instances_counted", e.getValue()[1]);
            o.addProperty("classes", (int) e.getValue()[2]);
            mods.add(o);
        }
        data.add("by_mod", mods);
        data.addProperty("mods_reported", Math.min(MOD_ROW_LIMIT, modRows.size()));
        data.addProperty("mods_total", modRows.size());
        data.add("mod_mapping", ModIndex.get().stats());

        JsonArray notes = new JsonArray();
        notes.add("retained_bytes = shallow bytes that become unreachable when this class's objects go away "
                + "(dominator subtree); it is not the same as shallow bytes");
        notes.add("class rows partition the heap: once an object is counted, objects below it in the dominator "
                + "tree are not counted again, so the rows never overlap and add up to about the whole heap "
                + "(exactly so when classes_truncated is false; check it before summing)");
        notes.add("instances_counted is how many objects of that class were counted as topmost, not how many exist");
        notes.add("roots are the HPROF ROOT_* records plus class static fields; weak/soft-referenced objects "
                + "reachable only through those are not treated as roots");
        notes.add("dominated_by_class is the dominator of the counted objects: read it as 'held by'");
        notes.add("dump_shallow_bytes is every object found in the dump (including garbage); total_retained_bytes "
                + "is what the live set actually holds, so a large unreachable_pct means the JVM skipped the "
                + "pre-dump GC and the dump contained garbage (the retained numbers ignore it)");
        if (j.impact() != null && j.impact().note() != null) {
            notes.add(j.impact().note());
        }
        notes.add("this analysis builds the whole object graph: about "
                + HeapGraph.effectiveObjectLimit(j.maxObjects(),
                        j.lean() ? HeapGraph.leanBytesPerObject() : HeapGraph.bytesPerObject()) / 1000
                + "k objects of headroom on this JVM (" + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                + " MB heap) and one JVM pause for the dump; add ?mode=lean for ~1.6x headroom at lower "
                + "per-class precision, or raise -Xmx if a run reports 'heap graph too large'");
        data.add("notes", notes);
        return data;
    }

    /**
     * 自检：各类 retained 相加是否等于可达堆。
     *
     * <p>这是「支配分区」这个说法的唯一验证手段——如果汇总逻辑退化成「同类祖先覆盖」或把某条路径
     * 重复累加，这里立刻会看到偏差；同时它也在验证 CSR 压缩后没有把边接错地方。</p>
     */
    private static String partitionCheck(HeapGraph.Result result) {
        long sum = 0;
        for (HeapGraph.ClassRow row : result.classRows()) {
            sum += row.retainedBytes();
        }
        long total = result.shallowBytes();
        long delta = sum - total;
        return "sum(retained)=" + sum + " total_retained=" + total + " delta=" + delta
                + " classes=" + result.classRows().size()
                + (delta == 0 ? " (ok)" : " (MISMATCH: the class rollup does not partition the heap)");
    }

    private static double pct(long part, long total) {        return total <= 0 ? 0.0 : Math.round(part * 10000.0 / total) / 100.0;
    }
}
