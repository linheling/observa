package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.RandomAccessFile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC 的近 60 秒行为：窗口增量、单次最大暂停、以及 GC 日志里那些 MXBean 根本不提供的证据。
 *
 * <p>为什么不能只用 {@link GarbageCollectorMXBean}：它只有两个字段，而且是<b>进程启动以来的累计值</b>——
 * {@code collectionCount} 和 {@code collectionTime}。这带来两个盲区，恰好是排障最痛的地方：</p>
 * <ul>
 *   <li><b>看不见「最近」</b>：累计 40 秒 GC 可能全发生在开服前十分钟，也可能就是刚刚那波卡顿；
 *       累计值分不清。本类因此自己每 1 秒采一次累计值，用首尾样本做差得到窗口增量。</li>
 *   <li><b>看不见 GC 的类型和细节</b>：MXBean 说不出这次是 Young 还是 Full、是不是
 *       {@code Evacuation Failure}、有没有 {@code To-space exhausted}、有没有 Humongous 大对象分配、
 *       G1 的 MMU（MaxGCPauseMillis）目标有没有被违反。这些在 JVM 里是 GC 线程内部的事件，
 *       <b>只写进 GC 日志</b>（{@code -Xlog:gc*}），没有任何 MXBean/通知接口暴露它们。
 *       所以要想回答「卡的那一下到底是哪种回收、为什么」，除了读 gc log 没有第二条路。</li>
 * </ul>
 *
 * <p>两条永远要写在报告里的限制：</p>
 * <ul>
 *   <li>{@code max_pause_ms} 只能近似——MXBean 给的是累计耗时，没有单次暂停的时长。
 *       本类取「相邻两个 1 秒样本之间累计耗时增量」的最大值；如果一次暂停正好跨过采样边界，
 *       会被拆到两个样本里，读数偏小。有 GC 日志时 {@code gc_log.max_pause_ms} 才是精确值。</li>
 *   <li>gc log 只读文件末尾 256 KiB、只认一小撮稳定格式的行（Pause 行、To-space、Evacuation Failure、
 *       Humongous、MMU）。JDK 版本/日志装饰器会改格式，所以所有正则都是<b>尽力而为</b>：
 *       匹配不上就跳过，绝不因为一行日志长得不一样就报错。</li>
 * </ul>
 *
 * <p>线程模型：第一次请求时惰性启动一个 daemon 采样线程，每 1 秒采一次，环形缓冲 120 条（= 60 秒），
 * 与服务器线程完全无关；HTTP 线程只读 synchronized 复制出来的快照。GC 日志解析结果缓存 5 秒，
 * 避免每个请求都去读盘。</p>
 */
public final class GcWatch {

    /** 窗口上界：60 秒。环形缓冲按 1 秒一条正好 120 条。 */
    private static final int WINDOW_SECONDS = 60;
    private static final int SAMPLE_INTERVAL_MS = 1000;
    private static final int CAPACITY = WINDOW_SECONDS;

    /** gc log 只读末尾这么多字节：排障关心的是「最近」，而 256 KiB 已覆盖数万行。 */
    private static final int GC_LOG_TAIL_BYTES = 256 * 1024;
    private static final long GC_LOG_CACHE_MS = 5_000L;

    /** pauses 数组的上限：日志里可能有几万次 Young GC，全量返回会把响应撑爆。 */
    private static final int MAX_PAUSES = 100;

    private static final String NO_GC_LOG_REASON = "no -Xlog:gc file in the JVM arguments";
    private static final String NEXT_XLOG = "add -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags";

    /**
     * Pause 行：{@code GC(12) Pause Young (Normal) (G1 Evacuation Pause) 123M->45M(256M) 12.345ms}。
     *
     * <p>先锚定 {@code Pause <kind>}，再用惰性 {@code .*?} 跳到「数字+ms」——中间的堆变化
     * （{@code 123M->45M(256M)}）里也有数字，但只有暂停时长后面紧跟 {@code ms}，
     * 所以这个锚点比直接抓最后一个数字稳。</p>
     */
    private static final Pattern PAUSE_LINE = Pattern.compile(
            "Pause (Young|Full|Remark|Cleanup|Initial Mark)\\b.*?([0-9]+(?:\\.[0-9]+)?)ms");

    /** 采样与环形缓冲的锁；ring 的读写都在它下面，代价是每 1 秒一次的微秒级竞争。 */
    private static final Object LOCK = new Object();
    private static final Sample[] SAMPLES = new Sample[CAPACITY];
    private static int head;
    private static int size;
    private static volatile boolean samplerStarted;

    /** GC 日志解析缓存（只缓存「读盘+解析」的结果，渲染 JSON 每次现做，避免共享可变对象）。 */
    private static volatile long gcLogParsedAtMs = -1L;
    private static volatile ParsedLog gcLogCache;

    private GcWatch() {
    }

    /**
     * 现算一份 GC 观察报告。第一次调用会启动后台采样线程，所以尽早调用（例如 mod 初始化时）
     * 能让窗口更早变成真正的 60 秒。
     */
    public static JsonObject json() {
        try {
            ensureSampler();
            List<Sample> samples = snapshotSamples();

            long firstMs = samples.get(0).atMs();
            long lastMs = samples.get(samples.size() - 1).atMs();
            double windowSeconds = round1(Math.max(0L, lastMs - firstMs) / 1000.0);

            long collections = 0;
            long pauseMs = 0;
            long maxPauseMs = 0;
            // collector 名 -> {collections, pause_ms}；用 LinkedHashMap 保持 MXBean 返回顺序，输出稳定。
            Map<String, long[]> perCollector = new LinkedHashMap<>();

            for (int i = 1; i < samples.size(); i++) {
                Sample before = samples.get(i - 1);
                Sample after = samples.get(i);
                long stepPauseMs = 0;
                for (int c = 0; c < after.names().length; c++) {
                    String name = after.names()[c];
                    long deltaCount = after.counts()[c] - before.countOf(name);
                    long deltaTime = after.times()[c] - before.timeOf(name);
                    // 计数器只增不减；负数意味着换了收集器/被重置，按 0 处理而不是把总量算成负的。
                    deltaCount = Math.max(0L, deltaCount);
                    deltaTime = Math.max(0L, deltaTime);
                    long[] slot = perCollector.computeIfAbsent(name, key -> new long[2]);
                    slot[0] += deltaCount;
                    slot[1] += deltaTime;
                    collections += deltaCount;
                    pauseMs += deltaTime;
                    stepPauseMs += deltaTime;
                }
                maxPauseMs = Math.max(maxPauseMs, stepPauseMs);
            }

            JsonObject data = new JsonObject();
            data.addProperty("window_s", windowSeconds);
            data.addProperty("collections", collections);
            data.addProperty("pause_ms", pauseMs);
            data.addProperty("max_pause_ms", (double) maxPauseMs);

            JsonArray collectors = new JsonArray();
            for (Map.Entry<String, long[]> entry : perCollector.entrySet()) {
                JsonObject collector = new JsonObject();
                collector.addProperty("name", entry.getKey());
                collector.addProperty("collections", entry.getValue()[0]);
                collector.addProperty("pause_ms", entry.getValue()[1]);
                collectors.add(collector);
            }
            data.add("per_collector", collectors);

            data.add("gc_log", gcLog());

            JsonArray notes = new JsonArray();
            notes.add("window_s is the measured span between the oldest and newest 1 s sample (capped at 60 s); "
                    + "it only reaches 60 s after a minute of uptime, and the deltas are JVM-wide totals between those samples");
            notes.add("max_pause_ms is approximated as the largest increase of cumulative GC time between two adjacent "
                    + "1 s samples: the MXBean API exposes totals only, so a pause that starts and ends inside one sample "
                    + "can be under-reported; gc_log.max_pause_ms is exact when a GC log is written");
            notes.add("gc_log reads only the last 256 KiB and matches a tolerant subset of lines; unknown lines are "
                    + "skipped, and the humongous/evacuation/to-space counters count matched lines rather than unique "
                    + "GC cycles (one failure can be printed on several lines)");
            notes.add("humongous and evacuation counters only appear when the -Xlog configuration includes the gc+heap "
                    + "tag (included in -Xlog:gc*); a zero count with a plain -Xlog:gc is not evidence that none happened");
            data.add("notes", notes);
            return data;
        } catch (Throwable t) {
            return degraded(t);
        }
    }

    // ------------------------------------------------------------------ 采样

    private static void ensureSampler() {
        if (samplerStarted) {
            return;
        }
        synchronized (LOCK) {
            if (samplerStarted) {
                return;
            }
            samplerStarted = true;
            Thread thread = new Thread(GcWatch::sampleLoop, "observa-gc-watch");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    /** 采样循环：任何一次采样失败都不能让线程退出，否则窗口会永远停在某个时刻。 */
    private static void sampleLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pushSample(takeSample(System.currentTimeMillis()));
            } catch (Throwable ignored) {
                // 单次采样失败（MXBean 临时不可用等）就跳过这一秒，下一秒继续。
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Sample takeSample(long atMs) {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        int count = beans.size();
        String[] names = new String[count];
        long[] counts = new long[count];
        long[] times = new long[count];
        for (int i = 0; i < count; i++) {
            GarbageCollectorMXBean bean = beans.get(i);
            names[i] = bean.getName();
            counts[i] = Math.max(0L, bean.getCollectionCount());
            times[i] = Math.max(0L, bean.getCollectionTime());
        }
        return new Sample(atMs, names, counts, times);
    }

    private static void pushSample(Sample sample) {
        synchronized (LOCK) {
            SAMPLES[head] = sample;
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) {
                size++;
            }
        }
    }

    /**
     * 复制当前窗口（旧 → 新）。
     *
     * <p>如果调用得比采样线程还早（size == 0），就在这里同步采一次：返回 window_s=0 的空窗口
     * 虽然「诚实」，但会让第一次请求看起来像坏了；先给一个基线样本，第二次请求起就有增量。
     */
    private static List<Sample> snapshotSamples() {
        synchronized (LOCK) {
            if (size == 0) {
                try {
                    pushSample(takeSample(System.currentTimeMillis()));
                } catch (Throwable ignored) {
                    // 实在采不到就只能返回空窗口，下面按 0 处理。
                }
            }
            if (size == 0) {
                return List.of(new Sample(System.currentTimeMillis(), new String[0], new long[0], new long[0]));
            }
            List<Sample> samples = new ArrayList<>(size);
            int start = (head - size + CAPACITY) % CAPACITY;
            for (int i = 0; i < size; i++) {
                samples.add(SAMPLES[(start + i) % CAPACITY]);
            }
            return samples;
        }
    }

    /** 一秒一个样本：只存名字和累计值，不存 JSON（120 条 × 2 个收集器的开销可以忽略）。 */
    private record Sample(long atMs, String[] names, long[] counts, long[] times) {

        long countOf(String name) {
            for (int i = 0; i < names.length; i++) {
                if (name.equals(names[i])) {
                    return counts[i];
                }
            }
            return 0L;
        }

        long timeOf(String name) {
            for (int i = 0; i < names.length; i++) {
                if (name.equals(names[i])) {
                    return times[i];
                }
            }
            return 0L;
        }
    }

    // ------------------------------------------------------------------ GC 日志

    /** 从启动参数找 GC 日志目标；找不到时给出原因（未配置 / 配了但没有 file=）。 */
    private static JsonObject gcLog() {
        try {
            LogTarget target = findGcLog();
            if (target.path() == null) {
                return gcLogUnavailable(target.reason(), null);
            }
            long now = System.currentTimeMillis();
            ParsedLog cached = gcLogCache;
            if (cached != null && cached.path().equals(target.path())
                    && gcLogParsedAtMs > 0 && now - gcLogParsedAtMs < GC_LOG_CACHE_MS) {
                return renderGcLog(cached);
            }
            ParsedLog parsed = parseGcLog(target.path());
            gcLogParsedAtMs = System.currentTimeMillis();
            gcLogCache = parsed;
            return renderGcLog(parsed);
        } catch (Throwable t) {
            return gcLogUnavailable("GC log could not be read: " + t, null);
        }
    }

    /**
     * {@code -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags}（JDK 9+）与
     * {@code -Xloggc:logs/gc.log}（JDK 8 老写法）两种都认。
     *
     * <p>file= 的值不能直接按冒号 split：Windows 盘符 {@code file=D:\logs\gc.log} 里也有冒号，
     * 按冒号切会把路径截成 {@code D}。这里只在「不是开头盘符」的冒号处截断，
     * 后面的 {@code :time,uptime,...} 装饰器就自然被丢掉了。</p>
     */
    private static LogTarget findGcLog() {
        String path = null;
        boolean gcLoggingConfigured = false;
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg == null) {
                    continue;
                }
                if (arg.startsWith("-Xlog:")) {
                    String lower = arg.toLowerCase(Locale.ROOT);
                    if (!lower.contains("gc")) {
                        continue;
                    }
                    gcLoggingConfigured = true;
                    String file = xlogFile(arg);
                    if (file != null && !file.isBlank()) {
                        path = file.trim();
                    }
                } else if (arg.startsWith("-Xloggc:")) {
                    gcLoggingConfigured = true;
                    String file = unquote(arg.substring("-Xloggc:".length())).trim();
                    if (!file.isEmpty()) {
                        path = file;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到参数就当没配置。
        }
        if (path == null) {
            return new LogTarget(null, gcLoggingConfigured
                    ? "GC logging is enabled but has no file= target, so the output goes to stdout (usually logs/latest.log)"
                    : NO_GC_LOG_REASON);
        }
        try {
            Path resolved = Paths.get(path);
            if (!resolved.isAbsolute()) {
                // 相对路径按进程工作目录解析，而不是游戏目录——GC 日志落点跟 launch 脚本有关。
                String cwd = System.getProperty("user.dir", ".");
                resolved = Paths.get(cwd).resolve(resolved);
            }
            return new LogTarget(resolved.toAbsolutePath().normalize().toString(), null);
        } catch (RuntimeException e) {
            return new LogTarget(null, "GC log path in the JVM arguments is not a valid path: " + path);
        }
    }

    /** 从 {@code -Xlog:...:file=<path>[:decorators]} 里抠 file= 的值（保留 Windows 盘符）。 */
    private static String xlogFile(String arg) {
        int at = arg.indexOf("file=");
        if (at < 0) {
            return null;
        }
        String value = arg.substring(at + "file=".length());
        if (!value.isEmpty() && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            char quote = value.charAt(0);
            int end = value.indexOf(quote, 1);
            return unquote(end > 0 ? value.substring(0, end + 1) : value);
        }
        for (int i = 0; i < value.length(); i++) {
            boolean driveColon = i == 1 && Character.isLetter(value.charAt(0));
            if (value.charAt(i) == ':' && !driveColon) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            char last = raw.charAt(raw.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    /**
     * 读文件末尾 ≤256 KiB 并解析。所有正则都是「匹配不上就跳过」；
     * 只有文件读不了才算失败（返回 parsed=false + reason）。
     */
    private static ParsedLog parseGcLog(String path) {
        Path file;
        try {
            file = Paths.get(path);
        } catch (RuntimeException e) {
            return ParsedLog.failed(path, "GC log path is not a valid path: " + path);
        }
        if (!Files.isRegularFile(file)) {
            return ParsedLog.failed(path, "GC log file does not exist: " + path);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0L, length - GC_LOG_TAIL_BYTES);
            int readLength = (int) Math.min(Integer.MAX_VALUE, length - from);
            byte[] buffer = new byte[readLength];
            raf.seek(from);
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            if (from > 0) {
                // 从文件中间 seek 必然截断第一行，把它丢掉，否则会把半行当成完整事件解析。
                int firstNewline = text.indexOf('\n');
                if (firstNewline >= 0) {
                    text = text.substring(firstNewline + 1);
                }
            }
            return parseGcLogText(path, text, readLength);
        } catch (Exception e) {
            return ParsedLog.failed(path, "GC log could not be read: " + e);
        }
    }

    private static ParsedLog parseGcLogText(String path, String text, long tailBytes) {
        int parsedEvents = 0;
        double maxPauseMs = 0.0;
        int violations = 0;
        String lastViolation = null;
        boolean mmuTagSeen = false;
        int evacuationFailures = 0;
        int toSpaceExhausted = 0;
        int humongous = 0;
        List<Pause> pauses = new ArrayList<>();

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean recognized = false;

            Matcher pause = PAUSE_LINE.matcher(line);
            if (pause.find()) {
                try {
                    double ms = Double.parseDouble(pause.group(2));
                    pauses.add(new Pause(ms, pause.group(1)));
                    maxPauseMs = Math.max(maxPauseMs, ms);
                    recognized = true;
                } catch (NumberFormatException ignored) {
                    // 极端长度的数字解析失败就跳过，不影响其它行。
                }
            }

            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("[gc,mmu]")) {
                mmuTagSeen = true;
            }
            if (lower.contains("to-space exhausted")) {
                toSpaceExhausted++;
                recognized = true;
            }
            if (lower.contains("evacuation failure")) {
                evacuationFailures++;
                recognized = true;
            }
            if (lower.contains("humongous")) {
                humongous++;
                recognized = true;
            }
            if (lower.contains("mmu target violated") || lower.contains("gctimeratio")) {
                violations++;
                lastViolation = line;
                recognized = true;
            }
            if (recognized) {
                parsedEvents++;
            }
        }

        // 只保留最近的 MAX_PAUSES 条：日志按时间正序，截尾部即可；老的暂停对「刚才那一下」没价值。
        List<Pause> recentPauses = pauses.size() > MAX_PAUSES
                ? List.copyOf(pauses.subList(pauses.size() - MAX_PAUSES, pauses.size()))
                : List.copyOf(pauses);

        return new ParsedLog(path, true, null, parsedEvents, maxPauseMs, violations, lastViolation,
                mmuTagSeen, evacuationFailures, toSpaceExhausted, humongous, tailBytes, recentPauses);
    }

    private static JsonObject renderGcLog(ParsedLog log) {
        if (!log.parsed()) {
            return gcLogUnavailable(log.reason(), log.path());
        }
        JsonObject out = new JsonObject();
        out.addProperty("available", true);
        out.addProperty("file", log.path());
        out.addProperty("parsed_events", log.parsedEvents());
        out.addProperty("max_pause_ms", round1(log.maxPauseMs()));
        out.add("mmu", mmu(log));
        out.addProperty("evacuation_failures", log.evacuationFailures());
        out.addProperty("to_space_exhausted", log.toSpaceExhausted());
        out.addProperty("humongous", log.humongous());

        JsonArray pauses = new JsonArray();
        for (Pause pause : log.pauses()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("ms", round1(pause.ms()));
            entry.addProperty("kind", pause.kind());
            pauses.add(entry);
        }
        out.add("pauses", pauses);
        out.addProperty("tail_bytes", log.tailBytes());
        return out;
    }

    /**
     * MMU（MaxGCPauseMillis 目标）违规：只有 G1 有这个概念，而且只写进 gc,mmu 标签的日志行。
     *
     * <p>不把「0 次违规」当成绝对结论：如果日志没开 gc,mmu 标签，那些行根本不会出现。
     * 所以额外回传 {@code tag_seen}，让读的人能区分「真的没违规」和「日志里没有这类行」。</p>
     */
    private static JsonObject mmu(ParsedLog log) {
        JsonObject mmu = new JsonObject();
        boolean g1 = isG1();
        if (!g1 && log.mmuViolations() == 0 && log.lastViolation() == null) {
            mmu.addProperty("available", false);
            mmu.addProperty("reason", "MMU targets are G1-specific and this JVM does not use the G1 collector");
            return mmu;
        }
        mmu.addProperty("available", true);
        mmu.addProperty("violations", log.mmuViolations());
        if (log.lastViolation() == null) {
            mmu.add("last_violation", JsonNull.INSTANCE);
        } else {
            mmu.addProperty("last_violation", log.lastViolation());
        }
        mmu.addProperty("tag_seen", log.mmuTagSeen());
        return mmu;
    }

    private static boolean isG1() {
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                String name = bean.getName();
                if (name != null && name.contains("G1")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到 collector 名就不声称 MMU 可用。
        }
        return false;
    }

    private static JsonObject gcLogUnavailable(String reason, String path) {
        JsonObject log = new JsonObject();
        log.addProperty("available", false);
        log.addProperty("reason", reason);
        if (path != null) {
            log.addProperty("file", path);
        }
        JsonArray next = new JsonArray();
        next.add(NEXT_XLOG);
        log.add("next", next);
        return log;
    }

    // ------------------------------------------------------------------ 兜底

    /** 采样/解析整体失败时的占位结构：字段齐全，绝不抛异常。 */
    private static JsonObject degraded(Throwable t) {
        JsonObject data = new JsonObject();
        data.addProperty("window_s", 0.0);
        data.addProperty("collections", 0);
        data.addProperty("pause_ms", 0);
        data.addProperty("max_pause_ms", 0.0);
        data.add("per_collector", new JsonArray());
        data.add("gc_log", gcLogUnavailable("gc watch failed: " + t, null));
        JsonArray notes = new JsonArray();
        notes.add("GC window collection failed; counters are placeholders");
        data.add("notes", notes);
        return data;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** GC 日志目标：path 为 null 时 reason 说明为什么没有。 */
    private record LogTarget(String path, String reason) {
    }

    /** 一次解析的不可变结果（缓存这个，而不是缓存 JsonObject，避免多 HTTP 线程共享可变对象）。 */
    private record ParsedLog(String path, boolean parsed, String reason,
                             int parsedEvents, double maxPauseMs, int mmuViolations, String lastViolation,
                             boolean mmuTagSeen, int evacuationFailures, int toSpaceExhausted, int humongous,
                             long tailBytes, List<Pause> pauses) {

        static ParsedLog failed(String path, String reason) {
            return new ParsedLog(path, false, reason, 0, 0.0, 0, null, false, 0, 0, 0, 0L, List.of());
        }
    }

    private record Pause(double ms, String kind) {
    }
}
