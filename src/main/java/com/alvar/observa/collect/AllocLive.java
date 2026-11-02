package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「JFR 说谁分配了多少」（流量）与「堆直方图说谁还活着」（存量）按类名对齐。
 *
 * <p>为什么需要这个类：这两份数据本来就来自同一次取证，但以前是两个端点、两套口径，
 * 读的人只能靠直觉拼。实战里的误读是这样的——JFR 说 {@code reflex} 分配了 60.6 GB，
 * 直方图里它几乎不存在，于是要么得出「reflex 占了 60 GB」这种物理上不可能的结论，
 * 要么反过来把「分配多但都被回收」误当成泄漏。实际上「分配量大」和「存活量大」回答的是
 * 两个不同问题：
 * <ul>
 *   <li>分配大 + 存活占比低 → {@link #VERDICT_CHURN churn}：高频短命对象，是 GC 压力不是泄漏；</li>
 *   <li>分配出去的基本都还活着 → {@link #VERDICT_LEAK_CANDIDATE leak_candidate}：这才像泄漏；</li>
 *   <li>其它 → {@link #VERDICT_MIXED mixed}：数据不足或介于两者之间。</li>
 * </ul>
 *
 * <h2>为什么还要报对齐信息</h2>
 * <p>直方图是「某一刻」的快照，JFR 是「一段时间」的累计。两侧时间窗不重叠时，比值本身
 * 就不可比（对象可能是在 JFR 开始之前就攒下的）。所以输出里永远带
 * {@code alignment.skew_ms / overlaps / note}，让读的人先看证据能不能对得上，再看结论。</p>
 *
 * <h2>为什么分配侧不是全量</h2>
 * <p>JFR 报告只保留按字节排序的 Top-N 类（=10），而直方图保留前 3000 个类。两侧都可能有
 * 「只有一侧出现」的类，所以按类名做外连接，缺哪侧补 0，而不是内连接——内连接正好会把
 * 「JFR 说分配了很多、直方图里一个都不剩」这类最有价值的行（churn 的证据）丢光。</p>
 *
 * <p>本类只做数据加工，不碰 HTTP；路由由调用方注册。够 {@code GET /observa/v1/heap/alloc-live} 用。</p>
 */
public final class AllocLive {

    /** 存活/分配 低于这个比值：分配出去的对象基本活不过一次 GC，是「高频短命」。 */
    private static final double CHURN_LIVE_RATIO = 0.02;
    /** 存活/分配 高于这个比值：分配出去的绝大多数还留着，是「泄漏嫌疑」。 */
    private static final double LEAK_LIVE_RATIO = 0.5;
    /**
     * 「存活量可观」的下限。为什么是 1 MiB：几百 KB 的高存活比在启动阶段到处都是
     * （类加载、缓存预热），拿它报 leak_candidate 只会制造噪声。
     */
    private static final long LEAK_MIN_LIVE_BYTES = 1024L * 1024L;
    /** 「分配量大」的下限，同样 1 MiB：分配量太小的类，比值没有统计意义。 */
    private static final long CHURN_MIN_ALLOC_BYTES = 1024L * 1024L;
    /** 默认行数：一份看板 20 行够读，再多也没人看。 */
    private static final int DEFAULT_TOP = 20;
    private static final int MAX_TOP = 200;
    /** HTTP 前缀：本类只在 next 提示里拼路径，不注册路由。 */
    private static final String PREFIX = "/observa/v1";

    private static final String VERDICT_CHURN = "churn";
    private static final String VERDICT_LEAK_CANDIDATE = "leak_candidate";
    private static final String VERDICT_MIXED = "mixed";

    private AllocLive() {
    }

    /**
     * 对齐一份堆直方图与一份 JFR 报告。
     *
     * <p>两个 id 都可以省略：省略时取各自最新一份**已完成**的结果，这样「先 POST 一次
     * 合并采集，过一会儿来 GET 一次」就能拿到对齐结果，调用方不必记住 id。任一侧还没有
     * 完成的数据时返回 {@code {available:false, reason, next}}，而不是 null/异常——
     * 「还没采到」是正常状态，不是错误。</p>
     *
     * @param heapId 堆直方图 id；空则用最新一份
     * @param jfrId  JFR 报告 id；空则用最新一份
     * @param top    返回多少行（按 max(alloc_bytes_est, live_bytes) 降序）；{@code <=0} 用默认 20
     * @return 永远非 null
     */
    public static JsonObject join(String heapId, String jfrId, int top) {
        int limit = top <= 0 ? DEFAULT_TOP : Math.min(top, MAX_TOP);
        HeapService heapService = HeapService.instance();
        JfrService jfrService = JfrService.instance();

        // ---- 存活侧（left）：recentIds() 是「最新在前」，取第 0 个就是最新一份完成的直方图。
        // 不能用 HeapService.json() 的当前任务：它可能是 running/failed，而我们需要的是 done。
        String hid = isBlank(heapId) ? firstOrNull(heapService.recentIds()) : heapId.trim();
        if (hid == null) {
            return unavailable("no completed heap histogram yet; a dump takes a few seconds and briefly pauses the JVM",
                    "POST " + PREFIX + "/heap/histogram/start with {\"live\":true}",
                    "then GET " + PREFIX + "/heap/alloc-live");
        }
        HeapService.Job heap = heapService.byId(hid);
        if (heap == null) {
            return unavailable("no heap histogram with id " + hid,
                    "GET " + PREFIX + "/heap/histogram lists recent_histograms",
                    "POST " + PREFIX + "/heap/histogram/start with {\"live\":true}");
        }
        if (!"done".equals(heap.state())) {
            return unavailable("heap histogram " + hid + " is still " + heap.state() + "; wait for it to finish",
                    "GET " + PREFIX + "/heap/histogram");
        }

        // ---- 分配侧（right）：JfrService 没有 recentIds()，但 status() 的 recent_reports
        // 是同一份历史（addFirst），第 0 个就是最新一份已完成的报告。
        String jid = isBlank(jfrId) ? latestJfrId(jfrService) : jfrId.trim();
        if (jid == null) {
            return unavailable("no completed JFR recording yet; allocation data only exists after a recording finishes",
                    "POST " + PREFIX + "/profiler/start with {\"mode\":\"jfr\",\"seconds\":60}",
                    "GET " + PREFIX + "/profiler/status lists recent_reports");
        }
        JsonObject report = jfrService.report(jid);
        if (report == null) {
            return unavailable("no JFR report with id " + jid,
                    "GET " + PREFIX + "/profiler/status lists recent_reports",
                    "POST " + PREFIX + "/profiler/start with {\"mode\":\"jfr\",\"seconds\":60}");
        }
        // report 返回的是「报告外壳」：summary 里才是解析结果（allocations 在 summary 下面）。
        JsonObject summary = object(report, "summary");
        if (summary == null) {
            return unavailable("JFR report " + jid + " has no parsed summary",
                    "GET " + PREFIX + "/profiler/report/" + jid);
        }
        if (summary.has("error")) {
            return unavailable("JFR report " + jid + " could not be parsed: " + string(summary, "error"),
                    "GET " + PREFIX + "/profiler/report/" + jid,
                    "the raw .jfr file is kept on disk; 'jfr print' can still read it");
        }
        JsonObject allocations = object(summary, "allocations");
        if (allocations == null) {
            return unavailable("JFR report " + jid + " has no allocations section",
                    "GET " + PREFIX + "/profiler/report/" + jid);
        }

        // ---- 时间对齐
        long jfrStartedAt = number(report, "started_at_ms", 0L);
        long jfrDurationMs = Math.max(0L, number(report, "duration_ms", 0L));
        long jfrEndedAt = jfrStartedAt + jfrDurationMs;
        // 直方图的「快照时刻」是 dump 完成那一刻，不是任务创建时刻：dumpHeap 先做一次
        // full GC 再写文件，写出来的堆是结束时的状态，所以用 startedAtMs + dumpMs。
        // 这样算会让 skew 偏大几秒，但比「假装两个时间一样」诚实——宁可让 overlaps 判 false。
        long dumpedAt = heap.startedAtMs() + Math.max(0L, heap.dumpMs());
        long skewMs = dumpedAt - jfrStartedAt;
        boolean overlaps = dumpedAt >= jfrStartedAt && dumpedAt <= jfrEndedAt;

        // ---- 按类名外连接：两侧任意一侧有的类都要出现，缺的那侧补 0。
        Map<String, Row> byClass = new LinkedHashMap<>();
        for (HeapHistogram.Entry entry : heap.classes()) {
            Row row = byClass.computeIfAbsent(entry.className(), Row::new);
            row.liveInstances += entry.instances();
            row.liveBytes += entry.shallowBytes();
        }
        JsonArray allocTop = array(allocations, "top");
        if (allocTop != null) {
            for (JsonElement element : allocTop) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject allocRow = element.getAsJsonObject();
                String className = string(allocRow, "class");
                if (className == null) {
                    continue;
                }
                Row row = byClass.computeIfAbsent(className, Row::new);
                row.allocBytes += number(allocRow, "bytes_est", 0L);
                row.allocSamples += number(allocRow, "samples", 0L);
            }
        }

        // ---- 排序：谁在「分配 + 存活」里更显眼谁排前面。用 max 而不是 sum，
        // 否则同一个类会因为在两侧都出现而被重复加权，把真正的单侧异常压下去。
        List<Row> sorted = new ArrayList<>(byClass.values());
        sorted.sort((a, b) -> Long.compare(
                Math.max(b.allocBytes, b.liveBytes), Math.max(a.allocBytes, a.liveBytes)));

        JsonArray rows = new JsonArray();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Row row = sorted.get(i);
            JsonObject out = new JsonObject();
            out.addProperty("class", row.className);
            out.addProperty("mod_id", ModIndex.get().modOfClass(row.className).modId());
            out.addProperty("live_instances", row.liveInstances);
            out.addProperty("live_bytes", row.liveBytes);
            out.addProperty("alloc_bytes_est", row.allocBytes);
            out.addProperty("alloc_samples", row.allocSamples);
            // 每「一个分配采样事件」对应多少字节还活着：越小越说明分配的是短命对象。
            // 没有分配样本时给 0（字段保持存在，读的人不用判空）。
            out.addProperty("live_bytes_per_alloc",
                    row.allocSamples <= 0 ? 0L : row.liveBytes / row.allocSamples);
            out.addProperty("verdict", verdict(row.liveBytes, row.allocBytes));
            rows.add(out);
        }

        JsonObject heapSide = new JsonObject();
        heapSide.addProperty("id", heap.id());
        heapSide.addProperty("dumped_at_ms", dumpedAt);
        heapSide.addProperty("total_instances", heap.totalInstances());
        heapSide.addProperty("total_shallow_bytes", heap.totalShallowBytes());

        JsonObject jfrSide = new JsonObject();
        jfrSide.addProperty("id", jid);
        jfrSide.addProperty("started_at_ms", jfrStartedAt);
        jfrSide.addProperty("duration_ms", jfrDurationMs);
        jfrSide.addProperty("ended_at_ms", jfrEndedAt);

        JsonObject alignment = new JsonObject();
        alignment.addProperty("skew_ms", skewMs);
        alignment.addProperty("overlaps", overlaps);
        alignment.addProperty("note", overlaps
                ? "the heap dump falls inside the JFR window (skew " + skewMs
                        + " ms), so live and alloc describe the same period and the ratio is meaningful"
                : "the heap dump falls outside the JFR window (skew " + skewMs + " ms): live is one point in "
                        + "time while alloc covers [" + jfrStartedAt + ", " + jfrEndedAt
                        + "], so the ratio is only a rough magnitude; re-run start() so the dump happens "
                        + "while the recording is active");

        // totals 用「全量口径」而不是 rows 求和：rows 只是 top N，求和会小于总量并让人误判比例。
        JsonObject totals = new JsonObject();
        totals.addProperty("live_bytes", heap.totalShallowBytes());
        totals.addProperty("alloc_bytes_est", number(allocations, "total_bytes_est", 0L));

        JsonArray notes = new JsonArray();
        notes.add("live side = heap histogram (single point in time, shallow bytes); "
                + "alloc side = jdk.ObjectAllocationSample (weighted estimate over the recording window)");
        notes.add("verdict thresholds: churn when live_bytes/alloc_bytes_est < 2% and alloc_bytes_est >= 1 MiB; "
                + "leak_candidate when live_bytes/alloc_bytes_est > 50% and live_bytes >= 1 MiB; otherwise mixed");
        notes.add("a high live/alloc ratio can also mean the class grew before the JFR window started: "
                + "confirm real growth with GET " + PREFIX + "/heap/histogram/compare, do not read this ratio alone");
        notes.add("the alloc side is only the JFR top classes by bytes_est (10 per report), so rows are not "
                + "exhaustive; totals use the full recorded totals and are larger than the sum of the rows");
        notes.add("live_bytes_per_alloc = live_bytes / alloc_samples (bytes still alive per sampled allocation "
                + "event); 0 when this class had no allocation sample in the recording window");
        JsonObject confidence = object(allocations, "confidence");
        String confidenceLevel = confidence == null ? null : string(confidence, "level");
        if ("low".equals(confidenceLevel)) {
            notes.add("allocations.confidence=low (" + number(confidence, "samples", 0L)
                    + " samples): bytes_est is extrapolated from very few events, treat it as a hint, "
                    + "not as physical memory usage");
        }
        if (allocations.has("implausible") && allocations.get("implausible").isJsonPrimitive()
                && allocations.get("implausible").getAsBoolean()) {
            notes.add("allocations.implausible=true: " + string(allocations, "implausible_reason"));
        }
        if (heap.classesTruncated()) {
            notes.add("the histogram keeps per-class detail for its biggest classes only (totals are complete), "
                    + "so a small class can show live_bytes 0 even though it still exists");
        }

        JsonObject data = new JsonObject();
        data.addProperty("available", true);
        data.add("heap", heapSide);
        data.add("jfr", jfrSide);
        data.add("alignment", alignment);
        data.add("totals", totals);
        data.add("rows", rows);
        data.add("notes", notes);
        return data;
    }

    /**
     * 同时开一次堆直方图与一次 JFR 录制，让两者的时间窗重叠。
     *
     * <p>为什么必须由服务端一起开：分别 POST 两个端点的话，人很难保证 dump 落在录制窗口内
     * （直方图本身要几秒），而 {@link #join} 只有在两边重叠时才敢让人看比值。</p>
     *
     * <p><b>顺序是先 JFR、后 dump</b>：JFR 启动只要几毫秒，而 dump 要几百毫秒到几秒。
     * 反过来做（先 dump 再起 JFR）会让 dump 的那一刻正好落在录制窗口之外，
     * {@code alignment.skew_ms} 是负的、{@code overlaps=false}——这正是第一版实测踩到的。
     * JFR 先开着，dump 就在窗口内部。</p>
     *
     * <p>任一侧已在运行时不抛异常：这是「稍后再来」而不是错误，返回
     * {@code {started:false, reason}} 让调用方自己决定重试。堆已经开起来而 JFR 失败时，
     * 返回里带上 {@code heap_id}，调用方仍然能看到正在跑的那次 dump。</p>
     *
     * @param seconds JFR 录制秒数（1..3600）
     * @return 永远非 null
     */
    public static JsonObject start(int seconds) {
        int clamped = Math.max(1, Math.min(seconds, 3600));
        HeapService heap = HeapService.instance();
        if (heap.running()) {
            return startFailed("a heap histogram is already running; let it finish or poll it first",
                    null, "GET " + PREFIX + "/heap/histogram");
        }

        JsonObject recording;
        try {
            recording = JfrService.instance().start(clamped);
        } catch (IllegalStateException e) {
            return startFailed("a JFR recording is already running: " + e.getMessage(), null,
                    "POST " + PREFIX + "/profiler/stop to finish the running JFR recording",
                    "GET " + PREFIX + "/profiler/status for progress");
        } catch (Exception e) {
            return startFailed("could not start the JFR recording: " + e, null,
                    "GET " + PREFIX + "/profiler/status",
                    "POST " + PREFIX + "/profiler/start with {\"mode\":\"sample\"} if JFR is unavailable");
        }
        String jfrId = string(recording, "id");

        if (!heap.start(true, false, 30)) {
            // 抢跑失败：刚起的这段录制没人要了，收掉，别留一个空窗口。
            String stopped;
            try {
                stopped = String.valueOf(JfrService.instance().stop() != null);
            } catch (RuntimeException e) {
                stopped = "failed (" + e + ")";
            }
            return startFailed("a heap histogram is already running; the just-started JFR recording was stopped ("
                    + stopped + ")", null,
                    "GET " + PREFIX + "/heap/histogram and retry when it is done");
        }
        // start() 只回 boolean，id 得从 json() 里取：刚提交的任务一定已经是当前任务（同步赋值后才 submit），
        // 所以这里拿到的是本次 dump 的 id，不会误取上一份。
        String heapId = string(heap.json(), "id");

        JsonObject data = new JsonObject();
        data.addProperty("started", true);
        data.addProperty("heap_id", heapId);
        data.addProperty("jfr_id", jfrId);
        data.addProperty("seconds", clamped);
        data.addProperty("next", "wait for the dump and the recording to finish, then GET "
                + PREFIX + "/heap/alloc-live");
        return data;
    }

    // ------------------------------------------------------------------ 内部

    /** join 的一行：同一个类在两侧的数据合并后放在这里，缺少的一侧留 0。 */
    private static final class Row {
        private final String className;
        private long liveInstances;
        private long liveBytes;
        private long allocBytes;
        private long allocSamples;

        private Row(String className) {
            this.className = className;
        }
    }

    /**
     * 三档判读。阈值见类常量：churn 要求「分配够大且几乎不剩」，leak_candidate 要求
     * 「剩下大半且活着的量本身够大」，其它都落 mixed（包括没有分配侧数据的类）。
     */
    private static String verdict(long liveBytes, long allocBytes) {
        if (allocBytes <= 0) {
            // 没有分配侧数据（这一类不在 JFR top 里）：判不了短命还是泄漏，不要硬猜。
            return VERDICT_MIXED;
        }
        double aliveRatio = (double) liveBytes / allocBytes;
        if (allocBytes >= CHURN_MIN_ALLOC_BYTES && aliveRatio < CHURN_LIVE_RATIO) {
            return VERDICT_CHURN;
        }
        if (aliveRatio > LEAK_LIVE_RATIO && liveBytes >= LEAK_MIN_LIVE_BYTES) {
            return VERDICT_LEAK_CANDIDATE;
        }
        return VERDICT_MIXED;
    }

    private static JsonObject unavailable(String reason, String... nextSteps) {
        JsonObject data = new JsonObject();
        data.addProperty("available", false);
        data.addProperty("reason", reason);
        data.add("next", strings(nextSteps));
        return data;
    }

    private static JsonObject startFailed(String reason, String heapId, String... nextSteps) {
        JsonObject data = new JsonObject();
        data.addProperty("started", false);
        data.addProperty("reason", reason);
        if (heapId != null) {
            data.addProperty("heap_id", heapId);
        }
        data.add("next", strings(nextSteps));
        return data;
    }

    private static JsonArray strings(String[] values) {
        JsonArray arr = new JsonArray();
        for (String value : values) {
            arr.add(value);
        }
        return arr;
    }

    /** JFR 历史里最新一份已完成的报告 id；没有则 null。 */
    private static String latestJfrId(JfrService jfr) {
        JsonObject status = jfr.status();
        JsonArray recent = array(status, "recent_reports");
        if (recent == null || recent.isEmpty()) {
            return null;
        }
        JsonElement first = recent.get(0);
        return first.isJsonPrimitive() ? first.getAsString() : null;
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement element = parent.get(key);
        try {
            return element.isJsonNull() ? null : element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long number(JsonObject parent, String key, long fallback) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        JsonElement element = parent.get(key);
        if (element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
