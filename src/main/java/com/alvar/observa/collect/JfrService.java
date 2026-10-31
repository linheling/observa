package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.alvar.observa.config.ObservaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 编程式 JFR 录制。
 *
 * <p>和采样剖析互补：采样回答「时间花在哪个方法」，JFR 回答「谁在分配、GC 为什么停、
 * 哪个锁在排队、异常从哪儿抛」。JFR 的产物是二进制 {@code .jfr}，agent 读不了，
 * 所以这里在录制结束后把它解析成一份纯 JSON 摘要（Top-N 聚合），同时保留原始文件路径，
 * 需要细节的人可以自己跑 {@code jfr print}。
 *
 * <p>事件只开这几类（开销可控，信息量最大）：
 * <ul>
 *   <li>{@code jdk.ObjectAllocationSample}：按类聚合分配字节数（throttle 限速），并归到 mod</li>
 *   <li>{@code jdk.GarbageCollection}：GC 次数、暂停总时长、最长一次、cause 分布</li>
 *   <li>{@code jdk.JavaExceptionThrow}：按异常类聚合（带栈，够定位）</li>
 *   <li>{@code jdk.JavaMonitorEnter} / {@code jdk.ThreadPark}：锁竞争与阻塞（阈值 10ms，滤掉噪声）</li>
 *   <li>{@code jdk.ExecutionSample}：CPU 热点（带行号），同样归到 mod</li>
 *   <li>{@code jdk.OldObjectSample}：活过多次 GC 的对象 —— 「谁的长寿对象在攒」，这是直方图给不了的
 *       那半个问题（直方图说现在有多少，它说这些是多久前、谁分配的）</li>
 * </ul>
 *
 * <h2>为什么 OldObjectSample 用动态字段而不是类型化访问器</h2>
 * <p>这个事件由 HotSpot 的元数据定义，字段名跨 JDK 版本有过变化（{@code object} / {@code objectClass}、
 * {@code allocationTime} 的类型也在变）。硬写字段名等于把 JDK 版本钉死，所以这里走
 * {@link jdk.jfr.EventType#getFields()} 自己发现字段：类名字段认 {@code java.lang.Class} 类型的，
 * 字节数字段认名字里带 {@code heap}/{@code size} 的。发现不到的字段不影响其它事件，缺失的量给 0。</p>
 */
public final class JfrService {

    private static final int MAX_HISTORY = 5;
    private static final int TOP_N = 10;
    /** 聚合键里「类」与「分配点」的分隔符；类名与帧名里都不会出现它。 */
    private static final String SITE_SEP = " <- ";
    /** 单个录制文件上限：JFR 自己会循环覆盖，不会把磁盘吃满。 */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private static final JfrService INSTANCE = new JfrService();

    private final Path dir = FMLPaths.CONFIGDIR.get().resolve("observa").resolve("jfr");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "observa-jfr");
        t.setDaemon(true);
        return t;
    });
    private final Deque<JfrReport> history = new ArrayDeque<>();

    private Recording active;
    private String activeId;
    private long activeStartedAt;
    private int activeSeconds;
    private int nextId = 1;

    private JfrService() {
    }

    public static JfrService instance() {
        return INSTANCE;
    }

    /** JFR 摘要 + 原始文件路径。{@code summary} 是已经解析好的 JSON。 */
    public record JfrReport(String id, long startedAtMs, long durationMs, String file, long fileBytes,
            JsonObject summary) {
    }

    public synchronized JsonObject start(int seconds) throws IOException {
        if (active != null) {
            throw new IllegalStateException("a JFR recording is already running: " + activeId);
        }
        Files.createDirectories(dir);
        String id = "jfr-" + nextId++;
        Path file = dir.resolve(id + ".jfr");
        Recording recording = new Recording();
        recording.setName("observa-" + id);
        recording.setToDisk(true);
        recording.setMaxSize(MAX_FILE_BYTES);
        recording.setMaxAge(Duration.ofMinutes(5));
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "200/s");
        // 10ms 采样：JFR 自带的 ExecutionSample 带行号，且由 JVM 在安全点之外采样，
        // 比自己 getThreadInfo 的 5ms 采样更准（不会把栈截断在 64 层）。
        recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.JavaExceptionThrow").withStackTrace();
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10));
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(10));
        // 长寿命对象：需要栈才回答得了「谁分配的」，所以必须 withStackTrace。
        recording.enable("jdk.OldObjectSample").withStackTrace();
        recording.start();

        active = recording;
        activeId = id;
        activeStartedAt = System.currentTimeMillis();
        activeSeconds = seconds;

        int clamped = Math.min(seconds, 3600);
        scheduler.schedule(() -> finish(id, file), clamped, TimeUnit.SECONDS);

        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("mode", "jfr");
        data.addProperty("seconds", clamped);
        data.addProperty("file", file.toString());
        data.addProperty("started_at_ms", activeStartedAt);
        JsonArray notes = new JsonArray();
        notes.add("recording stops automatically after 'seconds'; then GET /profiler/report/{id} for the JSON summary");
        notes.add("the raw .jfr file stays on disk for 'jfr print' / JMC");
        data.add("notes", notes);
        return data;
    }

    /** 提前结束当前录制并返回摘要；没有录制时抛 IllegalStateException。 */
    public JsonObject stop() {
        String id;
        Path file;
        synchronized (this) {
            if (active == null) {
                throw new IllegalStateException("no JFR recording is running");
            }
            id = activeId;
            file = dir.resolve(id + ".jfr");
        }
        return finish(id, file);
    }

    /** 到点或手动结束时都走这里：停录 → 落盘 → 解析成摘要 → 进历史。 */
    private JsonObject finish(String id, Path file) {
        Recording recording;
        long startedAt;
        int seconds;
        synchronized (this) {
            if (active == null || !id.equals(activeId)) {
                // 已经被 stop() 处理过（或已换了一轮录制）：直接返回历史里的结果。
                for (JfrReport report : history) {
                    if (report.id().equals(id)) {
                        return reportJson(report);
                    }
                }
                throw new IllegalStateException("recording not found: " + id);
            }
            recording = active;
            startedAt = activeStartedAt;
            seconds = activeSeconds;
            active = null;
            activeId = null;
        }
        long durationMs = System.currentTimeMillis() - startedAt;
        try {
            recording.stop();
            recording.dump(file);
            recording.close();
        } catch (Throwable t) {
            Observa.LOGGER.warn("JFR recording {} failed to dump: {}", id, t.toString());
        }

        long bytes = 0;
        try {
            bytes = Files.exists(file) ? Files.size(file) : 0;
        } catch (Exception ignored) {
            // 统计用，失败无所谓
        }

        JsonObject summary;
        try {
            summary = summarize(file, durationMs);
        } catch (Throwable t) {
            // 解析失败也要给出可用信息：文件在，能不能读另说。
            summary = parseError(t);
        }

        JfrReport report = new JfrReport(id, startedAt, durationMs, file.toString(), bytes, summary);
        synchronized (this) {
            history.addFirst(report);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
        JsonObject data = reportJson(report);
        data.addProperty("seconds_requested", seconds);
        return data;
    }

    public synchronized JsonObject report(String id) {
        JfrReport found = null;
        for (JfrReport candidate : history) {
            if (candidate.id().equals(id)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        if (found.summary().has("error")) {
            // 上次解析失败：.jfr 才是事实源，取报告时再试一次。
            try {
                JsonObject retry = summarize(Path.of(found.file()), found.durationMs());
                JfrReport refreshed = new JfrReport(found.id(), found.startedAtMs(),
                        found.durationMs(), found.file(), found.fileBytes(), retry);
                history.remove(found);
                history.addFirst(refreshed);
                return reportJson(refreshed);
            } catch (Throwable t) {
                // 仍然失败：把原始错误留在报告里，别把 .jfr 路径弄丢。
            }
        }
        return reportJson(found);
    }

    public synchronized JsonObject status() {
        JsonObject data = new JsonObject();
        data.addProperty("running", active != null);
        data.addProperty("id", active == null ? null : activeId);
        data.addProperty("mode", "jfr");
        if (active != null) {
            data.addProperty("elapsed_ms", System.currentTimeMillis() - activeStartedAt);
            data.addProperty("remaining_ms", Math.max(0, (activeStartedAt + activeSeconds * 1000L) - System.currentTimeMillis()));
        } else {
            data.add("elapsed_ms", JsonNull.INSTANCE);
            data.add("remaining_ms", JsonNull.INSTANCE);
        }
        JsonArray files = new JsonArray();
        for (JfrReport report : history) {
            files.add(report.id());
        }
        data.add("recent_reports", files);
        return data;
    }

    public synchronized void stopOnShutdown() {
        if (active == null) {
            return;
        }
        try {
            finish(activeId, dir.resolve(activeId + ".jfr"));
        } catch (Throwable t) {
            Observa.LOGGER.warn("JFR shutdown stop failed: {}", t.toString());
        }
    }

    private static JsonObject reportJson(JfrReport report) {
        JsonObject data = new JsonObject();
        data.addProperty("id", report.id());
        data.addProperty("mode", "jfr");
        data.addProperty("started_at_ms", report.startedAtMs());
        data.addProperty("duration_ms", report.durationMs());
        data.addProperty("file", report.file());
        data.addProperty("file_bytes", report.fileBytes());
        data.add("summary", report.summary());
        JsonArray notes = new JsonArray();
        notes.add("summary is aggregated from the .jfr file; raw events stay in the file for 'jfr print'");
        notes.add("allocation weights are JFR-sampled estimates, not exact byte counts");
        notes.add("mod_id attributes a class to a mod; site_mod_id attributes the allocating frame — "
                + "use site_mod_id to catch 'vanilla class allocated by a mod'");
        notes.add("old_objects comes from jdk.OldObjectSample: only objects that survived young GCs are sampled "
                + "(bytes_est is last known heap usage, not retained size)");
        data.add("notes", notes);
        return data;
    }

    // ---------------------------------------------------------------- 解析

    private static JsonObject parseError(Throwable t) {
        JsonObject summary = new JsonObject();
        summary.addProperty("error", "failed to parse recording: " + t);
        JsonArray frames = new JsonArray();
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 8); i++) {
            frames.add(stack[i].toString());
        }
        summary.add("error_stack", frames);
        return summary;
    }

    private static JsonObject summarize(Path file, long durationMs) throws Exception {
        if (!Files.exists(file)) {
            JsonObject empty = new JsonObject();
            empty.addProperty("error", "recording file missing");
            return empty;
        }

        Map<String, long[]> allocations = new HashMap<>();   // class -> {bytes, count}
        Map<String, Long> exceptionCounts = new HashMap<>(); // class -> count
        Map<String, long[]> monitor = new HashMap<>();       // class -> {totalNanos, count}
        Map<String, long[]> park = new HashMap<>();          // class -> {totalNanos, count}
        Map<String, long[]> execSelf = new HashMap<>();      // method:line -> {selfSamples}
        Map<String, long[]> execTotal = new HashMap<>();     // method:line -> {totalSamples}
        Map<String, long[]> allocSites = new HashMap<>();    // "class <- site" -> {bytes, count}
        Map<String, long[]> oldObjects = new HashMap<>();    // "class <- site" -> {count, bytesEst}
        Set<String> oldFields = new LinkedHashSet<>();       // 这个 JDK 的 OldObjectSample 到底有哪些字段
        long oldSamples = 0;
        Map<String, Integer> gcCauses = new LinkedHashMap<>();
        long gcCount = 0;
        long gcPauseNanos = 0;
        long gcLongestNanos = 0;
        long events = 0;
        long fieldErrors = 0;

        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                events++;
                // 用类型化访问器（getClass/getLong/getDuration/getString）而不是 getValue：
                // 实测 getValue 在游戏内环境会对某些字段抛 ClassCastException（String→char[]），
                // 同一个文件在独立 JVM 里却能正常解析——环境相关，所以这里逐字段兜底，
                // 宁可退化成 "unknown"，也不要因为一个字段丢掉整份录制。
                try {
                    switch (event.getEventType().getName()) {
                        case "jdk.ObjectAllocationSample" -> {
                            String cls = className(event.getClass("objectClass"));
                            long weight = event.getLong("weight");
                            long[] slot = allocations.computeIfAbsent(cls, k -> new long[2]);
                            slot[0] += weight;
                            slot[1]++;
                            // 分配点：谁在 new 这个类。只看栈顶游戏帧，栈顶是 JDK 帧时往下找一层。
                            String site = allocSite(event);
                            if (site != null) {
                                long[] at = allocSites.computeIfAbsent(cls + SITE_SEP + site, k -> new long[2]);
                                at[0] += weight;
                                at[1]++;
                            }
                        }
                        case "jdk.ExecutionSample" -> {
                            collectExecutionSample(event, execSelf, execTotal);
                        }
                        case "jdk.GarbageCollection" -> {
                            gcCount++;
                            long pause = nanos(event.getDuration("sumOfPauses"));
                            long longest = nanos(event.getDuration("longestPause"));
                            gcPauseNanos += pause;
                            gcLongestNanos = Math.max(gcLongestNanos, longest);
                            String cause = safeString(event, "cause");
                            gcCauses.merge(cause, 1, Integer::sum);
                        }
                        case "jdk.JavaExceptionThrow" ->
                                exceptionCounts.merge(className(event.getClass("thrownClass")), 1L, Long::sum);
                        case "jdk.JavaMonitorEnter" -> {
                            String cls = className(event.getClass("monitorClass"));
                            long[] slot = monitor.computeIfAbsent(cls, k -> new long[2]);
                            slot[0] += nanos(event.getDuration());
                            slot[1]++;
                        }
                        case "jdk.ThreadPark" -> {
                            String cls = className(event.getClass("parkedClass"));
                            long[] slot = park.computeIfAbsent(cls, k -> new long[2]);
                            slot[0] += nanos(event.getDuration());
                            slot[1]++;
                        }
                        case "jdk.OldObjectSample" -> {
                            oldSamples++;
                            collectOldObject(event, oldObjects, oldFields);
                        }
                        default -> {
                            // 其它事件（例如 JFR 自身的元数据）不计入摘要
                        }
                    }
                } catch (Throwable t) {
                    fieldErrors++;
                }
            }
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("events", events);
        summary.addProperty("duration_ms", durationMs);
        if (fieldErrors > 0) {
            // 如实报告：有多少事件因为字段读不出来被跳过，别让调用方以为数据完整。
            summary.addProperty("events_skipped", fieldErrors);
        }

        // ---- 分配：按字节降序，给 Pct 方便直接判读
        long allocTotal = allocations.values().stream().mapToLong(s -> s[0]).sum();
        JsonObject alloc = new JsonObject();
        alloc.addProperty("total_bytes_est", allocTotal);
        alloc.addProperty("sample_count", allocations.values().stream().mapToLong(s -> s[1]).sum());
        JsonArray allocTop = new JsonArray();
        allocations.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("class", e.getKey());
                    row.addProperty("mod_id", ModIndex.get().modOfClass(e.getKey()).modId());
                    row.addProperty("bytes_est", e.getValue()[0]);
                    row.addProperty("pct", allocTotal == 0 ? 0.0 : round2(e.getValue()[0] * 100.0 / allocTotal));
                    allocTop.add(row);
                });
        alloc.add("top", allocTop);

        // 同一个类可能有很多分配点：只给类名的话 agent 无从下手，这里给到"哪一行 new 的"，
        // 而且把类与分配点分别归到 mod —— 跨过「对象在 vanilla 类里、但由某个 mod 造」的鸿沟。
        JsonArray siteTop = new JsonArray();
        allocSites.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    String key = e.getKey();
                    int sep = key.indexOf(SITE_SEP);
                    String cls = sep < 0 ? key : key.substring(0, sep);
                    String site = sep < 0 ? null : key.substring(sep + SITE_SEP.length());
                    JsonObject row = new JsonObject();
                    row.addProperty("class", cls);
                    row.addProperty("class_mod_id", ModIndex.get().modOfClass(cls).modId());
                    row.addProperty("site", site == null ? null : simple(site));
                    row.addProperty("site_mod_id", site == null ? ModIndex.UNKNOWN.modId() : modOfLocation(site));
                    row.addProperty("bytes_est", e.getValue()[0]);
                    row.addProperty("samples", e.getValue()[1]);
                    row.addProperty("pct", allocTotal == 0 ? 0.0 : round2(e.getValue()[0] * 100.0 / allocTotal));
                    siteTop.add(row);
                });
        alloc.add("top_sites", siteTop);
        summary.add("allocations", alloc);

        // ---- CPU 热点（JFR 自己的 ExecutionSample，带行号）
        long execSamples = execSelf.values().stream().mapToLong(s -> s[0]).sum();
        JsonObject exec = new JsonObject();
        exec.addProperty("sample_count", execSamples);
        JsonArray execTop = new JsonArray();
        execSelf.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("frame", simple(e.getKey()));
                    row.addProperty("mod_id", modOfLocation(e.getKey()));
                    row.addProperty("self_pct", execSamples == 0 ? 0.0 : round2(e.getValue()[0] * 100.0 / execSamples));
                    row.addProperty("self_samples", e.getValue()[0]);
                    long[] total = execTotal.get(e.getKey());
                    row.addProperty("total_samples", total == null ? 0 : total[0]);
                    execTop.add(row);
                });
        exec.add("top", execTop);
        summary.add("execution_samples", exec);

        // ---- 长寿命对象（OldObjectSample）：活过多次 GC 的对象，按「类 + 分配点」聚合
        JsonObject old = new JsonObject();
        old.addProperty("count", oldSamples);
        JsonArray oldFieldNames = new JsonArray();
        oldFields.forEach(oldFieldNames::add);
        old.add("fields", oldFieldNames);
        JsonArray oldTop = new JsonArray();
        oldObjects.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    String key = e.getKey();
                    int sep = key.indexOf(SITE_SEP);
                    String cls = sep < 0 ? key : key.substring(0, sep);
                    String site = sep < 0 ? null : key.substring(sep + SITE_SEP.length());
                    JsonObject row = new JsonObject();
                    row.addProperty("class", cls);
                    row.addProperty("mod_id", ModIndex.get().modOfClass(cls).modId());
                    row.addProperty("site", site == null ? null : simple(site));
                    row.addProperty("site_mod_id", site == null ? ModIndex.UNKNOWN.modId() : modOfLocation(site));
                    row.addProperty("count", e.getValue()[0]);
                    row.addProperty("bytes_est", e.getValue()[1]);
                    oldTop.add(row);
                });
        old.add("top", oldTop);
        if (oldSamples == 0) {
            // 采样器只在 GC 时挑对象，短录制很可能一条都没有：这不是坏了。
            old.addProperty("tip", "OldObjectSample only samples during young GCs; record longer or induce GC pressure");
        }
        summary.add("old_objects", old);

        // ---- GC
        JsonObject gc = new JsonObject();
        gc.addProperty("count", gcCount);
        gc.addProperty("total_pause_ms", round2(gcPauseNanos / 1_000_000.0));
        gc.addProperty("longest_pause_ms", round2(gcLongestNanos / 1_000_000.0));
        JsonObject causes = new JsonObject();
        gcCauses.forEach(causes::addProperty);
        gc.add("causes", causes);
        summary.add("gc", gc);

        // ---- 异常
        JsonObject exceptions = new JsonObject();
        exceptions.addProperty("count", exceptionCounts.values().stream().mapToLong(Long::longValue).sum());
        exceptions.add("top", topCounts(exceptionCounts));
        summary.add("exceptions", exceptions);

        // ---- 锁与阻塞（都是"被阻塞多久"，不是"持有多久"）
        summary.add("monitor_enter", durationSummary(monitor));
        summary.add("thread_park", durationSummary(park));
        return summary;
    }

    // ------------------------------------------------------------------ 栈帧归属

    /**
     * JFR 的 {@code jdk.ExecutionSample} 每 10ms 一拍，栈里第一个"游戏帧"记一次 self，
     * 其余每个不同的游戏帧记一次 total。口径和采样剖析一致（父帧 total 含子帧），
     * 但用 JFR 自己的采样器：不受 {@code getThreadInfo} 的栈深上限影响。
     */
    private static void collectExecutionSample(RecordedEvent event, Map<String, long[]> self, Map<String, long[]> total) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            return;
        }
        List<RecordedFrame> frames = stack.getFrames();
        boolean leafDone = false;
        Set<String> seen = new HashSet<>();
        for (RecordedFrame frame : frames) {
            String location = location(frame);
            if (location == null) {
                continue;
            }
            if (!leafDone) {
                self.computeIfAbsent(location, k -> new long[1])[0]++;
                leafDone = true;
            }
            if (seen.add(location)) {
                total.computeIfAbsent(location, k -> new long[1])[0]++;
            }
        }
    }

    /** 分配事件里最先出现的那层游戏帧：回答「这个类是哪个方法 new 出来的」。 */
    private static String allocSite(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            String location = location(frame);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    /**
     * 把 JFR 帧写成 {@code 全限定类名.method:line}。JDK 帧按配置剔除（和采样剖析同一个开关），
     * native 帧没有行号也没有 Java 类名，直接跳过。
     *
     * <p>内部保留全限定名（归因到 mod 需要包名），输出时由 {@link #simple(String)} 剥成短名——
     * 这样「谁在分配」既能查 mod，又不给 agent 塞一屏包名。</p>
     */
    private static String location(RecordedFrame frame) {
        if (!frame.isJavaFrame()) {
            return null;
        }
        RecordedMethod method = frame.getMethod();
        if (method == null) {
            return null;
        }
        String cls = method.getType().getName();
        if (!ObservaConfig.PROFILER_INCLUDE_JDK_FRAMES.get() && isJdkClass(cls)) {
            return null;
        }
        int line = frame.getLineNumber();
        return cls + "." + method.getName() + (line > 0 ? ":" + line : "");
    }

    /** 输出用的短帧名：只留简单类名，方法名与行号原样。 */
    private static String simple(String location) {
        int colon = location.indexOf(':');
        String head = colon < 0 ? location : location.substring(0, colon);
        int dot = head.lastIndexOf('.');
        if (dot < 0) {
            return location;
        }
        String cls = head.substring(0, dot);
        int clsDot = cls.lastIndexOf('.');
        String simpleCls = clsDot < 0 ? cls : cls.substring(clsDot + 1);
        return simpleCls + "." + head.substring(dot + 1) + (colon < 0 ? "" : location.substring(colon));
    }

    /** 帧 / 分配点属于哪个 mod：location 里带全限定类名，正好能查。 */
    private static String modOfLocation(String location) {
        if (location == null) {
            return ModIndex.UNKNOWN.modId();
        }
        int colon = location.indexOf(':');
        String head = colon < 0 ? location : location.substring(0, colon);
        int dot = head.lastIndexOf('.');
        if (dot < 0) {
            return ModIndex.UNKNOWN.modId();
        }
        return ModIndex.get().modOfClass(head.substring(0, dot)).modId();
    }

    /**
     * 一条 OldObjectSample。
     *
     * <p>字段名靠 {@link jdk.jfr.EventType#getFields()} 现场发现，不写死：类型为
     * {@code java.lang.Class} 的字段当「对象是谁」，名字里带 heap/size 的数值字段当字节数估算
     * （{@code lastKnownHeapUsage} 只是这个对象最后一次被看到时的堆占用，不是 retained size）。
     * 读不出来的字段跳过——一个字段读失败不该杀掉整份录制。</p>
     */
    private static void collectOldObject(RecordedEvent event, Map<String, long[]> out, Set<String> fields) {
        String cls = null;
        long bytes = 0;
        for (ValueDescriptor field : event.getEventType().getFields()) {
            String name = field.getName();
            if (fields.size() < 32) {
                fields.add(name + ":" + field.getTypeName());
            }
            try {
                if ("java.lang.Class".equals(field.getTypeName())) {
                    if (cls == null && event.getValue(name) instanceof RecordedClass recorded) {
                        cls = readableClassName(recorded.getName());
                    }
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("heap") || lower.contains("size")) {
                    if (event.getValue(name) instanceof Number number) {
                        bytes = Math.max(bytes, number.longValue());
                    }
                }
            } catch (Throwable ignored) {
                // 字段读不出：字段清单里已经记过它存在，这里只跳过一个数
            }
        }
        if (cls == null) {
            return;
        }
        String site = allocSite(event);
        String key = site == null ? cls : cls + SITE_SEP + site;
        long[] slot = out.computeIfAbsent(key, k -> new long[2]);
        slot[0]++;
        slot[1] += bytes;
    }

    private static boolean isJdkClass(String cls) {
        return cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.")
                || cls.startsWith("sun.") || cls.startsWith("com.sun.");
    }

    private static JsonArray topCounts(Map<String, Long> counts) {
        JsonArray arr = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("class", e.getKey());
                    row.addProperty("count", e.getValue());
                    arr.add(row);
                });
        return arr;
    }

    private static JsonObject durationSummary(Map<String, long[]> byClass) {
        JsonObject out = new JsonObject();
        out.addProperty("count", byClass.values().stream().mapToLong(s -> s[1]).sum());
        out.addProperty("total_ms", round2(byClass.values().stream().mapToLong(s -> s[0]).sum() / 1_000_000.0));
        JsonArray top = new JsonArray();
        byClass.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("class", e.getKey());
                    row.addProperty("count", e.getValue()[1]);
                    row.addProperty("total_ms", round2(e.getValue()[0] / 1_000_000.0));
                    top.add(row);
                });
        out.add("top", top);
        return out;
    }

    private static String className(Object value) {
        if (value instanceof RecordedClass cls) {
            return readableClassName(cls.getName());
        }
        return String.valueOf(value);
    }

    /**
     * JFR 给数组类型报的是 JVM 描述符（{@code [J}、{@code [B}、{@code [Lfoo.Bar;}）。
     * 对读 JSON 的 agent 来说 {@code long[]} 才有意义，顺手翻译掉。
     */
    private static String readableClassName(String name) {
        if (name == null) {
            return null;
        }
        // lambda / 隐藏类的名字带每进程唯一的地址（$$Lambda.0x000001cf...），
        // 不剥掉的话同一份报告没法跨次比对（剖析器的帧名也做了同样处理）。
        int hidden = name.indexOf(".0x");
        if (hidden > 0) {
            name = name.substring(0, hidden);
        }
        int slash = name.indexOf("/0x");
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        if (!name.startsWith("[")) {
            return name;
        }
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        char primitive = dims < name.length() ? name.charAt(dims) : 'L';
        String base = switch (primitive) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> {
                String inner = name.substring(dims + 1);
                yield inner.endsWith(";") ? inner.substring(0, inner.length() - 1) : inner;
            }
            default -> name;
        };
        return base + "[]".repeat(dims);
    }

    private static long nanos(Duration duration) {
        return duration == null ? 0L : duration.toNanos();
    }

    /** 字段读不出来时退化成 {@code "unknown"}，不要因为一个字段丢掉整份录制。 */
    private static String safeString(RecordedEvent event, String field) {
        try {
            String value = event.getString(field);
            return value == null ? "unknown" : value;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

}
