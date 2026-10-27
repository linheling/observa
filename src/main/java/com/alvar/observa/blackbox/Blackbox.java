package com.alvar.observa.blackbox;

import com.alvar.observa.Observa;
import com.alvar.observa.api.Snapshot;
import com.alvar.observa.collect.MetricsService;
import com.alvar.observa.collect.PauseState;
import com.alvar.observa.config.ObservaConfig;
import com.alvar.observa.http.Json;
import com.alvar.observa.http.SnapshotJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * 黑匣子：环形缓冲 + 自动触发落盘 + 崩溃/停服兜底。
 *
 * <p>落盘内容是 {@code <gameDir>/observa/blackbox/<yyyyMMdd-HHmmss>-<reason>.json.gz}：
 * 触发时刻的完整快照 + 前 {@code ring} 帧轻量指标 + 最近 20 条异常 + 最近 200 行原始日志。
 * 用 gzip 是因为日志尾巴占绝大部分体积，压缩后单份通常只有几十 KB。
 *
 * <p>写盘永远在独立线程（{@code observa-blackbox}）上做：黑匣子最可能在「服务器已经卡住」时
 * 触发，此时在主线程做 gzip 会把 5 秒的卡顿变成 6 秒。
 */
public final class Blackbox {

    private static final int KEEP_FILES = 10;
    private static final int RING_CAPACITY = 600;
    private static final int DUMP_ERRORS = 20;
    private static final int DUMP_RAW_LINES = 200;
    private static final long COOLDOWN_MS = 60_000L;
    private static final long HEARTBEAT_MS = 500L;

    private static final Blackbox INSTANCE = new Blackbox();

    private final RingBuffer ring = new RingBuffer(RING_CAPACITY);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "observa-blackbox");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong dumpSeq = new AtomicLong();

    private volatile boolean crashHandlerInstalled;
    private volatile long overSinceMs = -1L;
    private volatile long lastAutoDumpMs = -1L;

    /** 服务器启动时刻（墙钟）；宽限按它算。 */
    private volatile long serverStartedAtMs = -1L;
    /** 心跳：距上一次 tick 完成过了多久还不算冻住。 */
    private volatile long lastPushMs = -1L;
    /** 当前这段「tick 停摆」的起点（最后一次 tick 的时间戳），-1 表示没在停摆。 */
    private volatile long stallSinceMs = -1L;
    private volatile long lastStallDumpMs = -1L;
    private volatile boolean graceEpisode;
    /** 当前这一轮「玩家主动暂停」是否已经计入抑制次数（避免每 500ms 累加一次）。 */
    private volatile boolean pauseEpisode;
    private volatile Thread heartbeat;

    private final AtomicLong suppressedGrace = new AtomicLong();
    private final AtomicLong suppressedCooldown = new AtomicLong();
    private final AtomicLong suppressedPause = new AtomicLong();
    private final AtomicLong msptDumps = new AtomicLong();
    private final AtomicLong stallDumps = new AtomicLong();

    private Blackbox() {
    }

    public static Blackbox instance() {
        return INSTANCE;
    }

    public static Path dir() {
        return FMLPaths.GAMEDIR.get().resolve("observa").resolve("blackbox");
    }

    // ------------------------------------------------------------------ 采集侧

    /** 每份刷新的快照都会走到这里（服务器线程）。开销：一次 push + 两次比较。 */
    public void onSnapshot(Snapshot snapshot, long atMs) {
        if (snapshot == null) {
            return;
        }
        long lastTick = MetricsService.lastTickEndMs();
        long sinceTick = lastTick <= 0 ? 0L : Math.max(0L, atMs - lastTick);
        ring.push(RingBuffer.frameOf(snapshot, atMs, sinceTick));
        lastPushMs = atMs;
        if (!ObservaConfig.BLACKBOX_ENABLED.get()) {
            return;
        }
        double threshold = ObservaConfig.BLACKBOX_MSPT_THRESHOLD_MS.get();
        long sustainMs = ObservaConfig.BLACKBOX_SUSTAIN_SECONDS.get() * 1000L;
        double mspt = snapshot.tick().msptNowMs();
        long now = System.currentTimeMillis();
        if (mspt <= threshold) {
            overSinceMs = -1L;
            return;
        }
        if (overSinceMs < 0) {
            overSinceMs = now;
        }
        boolean sustained = now - overSinceMs >= sustainMs;
        // 光「超阈值够久」还不够：世界加载一开始 MSPT 天然爆表，没有宽限就是 4/4 假阳性。
        if (sustained && inGrace(snapshot, now)) {
            if (!graceEpisode) {
                graceEpisode = true;
                suppressedGrace.incrementAndGet();
            }
            return;
        }
        graceEpisode = false;
        // 环形缓冲至少要有几帧才算「有一段可以回看的窗口」，否则触发时 ring 是空的。
        boolean enough = ring.size() >= Math.max(2L, sustainMs / 1000L);
        boolean cooled = lastAutoDumpMs < 0 || now - lastAutoDumpMs >= COOLDOWN_MS;
        if (sustained && enough && cooled) {
            lastAutoDumpMs = now;
            // 触发后重新计时：否则持续卡顿会按 COOLDOWN 反复落盘，把磁盘写满。
            overSinceMs = now;
            msptDumps.incrementAndGet();
            JsonObject detail = new JsonObject();
            detail.addProperty("mspt_now_ms", round(mspt));
            detail.addProperty("threshold_ms", threshold);
            detail.addProperty("sustained_seconds", sustainMs / 1000);
            detail.addProperty("ring_size", ring.size());
            // 文件名只带量级，完整数字进 payload：文件名短才好在目录里扫。
            dumpAsync("mspt", Math.round(mspt) + "ms", detail);
        } else if (sustained && !cooled) {
            suppressedCooldown.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ 心跳（tick 停摆检测）

    /** 由 {@code Observa#onServerStarted} 调用，记下宽限的起点。 */
    public void onServerStarted(long atMs) {
        serverStartedAtMs = atMs;
        stallSinceMs = -1L;
        lastPushMs = -1L;
        graceEpisode = false;
    }

    /**
     * 启动心跳：每 {@link #HEARTBEAT_MS} 检查一次 tick 是否还在动。
     *
     * <p>为什么需要这条线程：帧采样原本只挂在 tick 完成的回调上，而「服务器卡死」恰恰意味着
     * 那个回调不再发生——黑匣子会正好在最需要它的时刻停止记录（实测 ring_size 掉到 2）。
     * 心跳独立于 tick，卡死时照常推进冻结帧，并在停摆超过 {@code sustain_seconds} 时落盘。</p>
     */
    public synchronized void startWatch() {
        if (heartbeat != null && heartbeat.isAlive()) {
            return;
        }
        Thread t = new Thread(this::heartbeatLoop, "observa-heartbeat");
        t.setDaemon(true);
        t.start();
        heartbeat = t;
    }

    public synchronized void stopWatch() {
        Thread t = heartbeat;
        heartbeat = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void heartbeatLoop() {
        while (heartbeat != null && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                heartbeatTick();
            } catch (Throwable t) {
                Observa.LOGGER.warn("[observa] heartbeat failed", t);
            }
        }
    }

    private void heartbeatTick() {
        long now = System.currentTimeMillis();
        long lastTick = MetricsService.lastTickEndMs();
        if (lastTick <= 0 || serverStartedAtMs <= 0) {
            return;
        }
        long sinceTick = now - lastTick;
        if (sinceTick < ObservaConfig.BLACKBOX_FREEZE_AFTER_MS.get()) {
            // tick 还在动，这不是停摆；清掉状态好让下一段停摆从头计时。
            stallSinceMs = -1L;
            graceEpisode = false;
            pauseEpisode = false;
            return;
        }
        long interval = Math.max(200L, ObservaConfig.METRICS_INTERVAL_MS.get());
        if (lastPushMs > 0 && now - lastPushMs < interval) {
            return;
        }
        Snapshot last = MetricsService.snapshot();
        ring.push(RingBuffer.frozen(last, now, sinceTick));
        lastPushMs = now;
        if (!ObservaConfig.BLACKBOX_ENABLED.get()) {
            return;
        }
        if (PauseState.isPaused()) {
            // 集成服务器 ESC 暂停：tick 确实停了，但这是玩家主动停的，不是卡死。
            // 帧继续推进（同一次会话里回看仍然连续），只是不落 stall。
            stallSinceMs = -1L;
            if (!pauseEpisode) {
                pauseEpisode = true;
                suppressedPause.incrementAndGet();
            }
            return;
        }
        pauseEpisode = false;
        long sustainMs = ObservaConfig.BLACKBOX_SUSTAIN_SECONDS.get() * 1000L;
        if (sinceTick < sustainMs) {
            return;
        }
        if (stallSinceMs < 0) {
            stallSinceMs = lastTick;
        }
        if (inGrace(last, now)) {
            if (!graceEpisode) {
                graceEpisode = true;
                suppressedGrace.incrementAndGet();
            }
            return;
        }
        graceEpisode = false;
        boolean cooled = lastStallDumpMs < 0 || now - lastStallDumpMs >= COOLDOWN_MS;
        if (!cooled) {
            suppressedCooldown.incrementAndGet();
            return;
        }
        lastStallDumpMs = now;
        stallSinceMs = -1L;
        stallDumps.incrementAndGet();
        JsonObject detail = new JsonObject();
        detail.addProperty("since_tick_ms", sinceTick);
        detail.addProperty("last_tick_at", Instant.ofEpochMilli(lastTick).toString());
        detail.addProperty("ring_size", ring.size());
        detail.addProperty("server_tick", last == null ? -1L : last.serverTick());
        detail.addProperty("sustain_seconds", sustainMs / 1000);
        detail.addProperty("frozen_frames", frozenFrames());
        dumpAsync("stall", (sustainMs / 1000) + "s", detail);
    }

    /** 启动宽限：tick 数不够或墙钟还太早，都不算「卡顿」。 */
    private boolean inGrace(Snapshot last, long now) {
        if (serverStartedAtMs <= 0) {
            return true;
        }
        long graceMs = ObservaConfig.BLACKBOX_GRACE_SECONDS.get() * 1000L;
        if (graceMs > 0 && now - serverStartedAtMs < graceMs) {
            return true;
        }
        return last != null && last.serverTick() < ObservaConfig.BLACKBOX_MIN_SERVER_TICKS.get();
    }

    private long frozenFrames() {
        JsonArray arr = ring.toJson(600);
        long frozen = 0;
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            if (o.has("frozen") && o.get("frozen").getAsBoolean()) {
                frozen++;
            }
        }
        return frozen;
    }

    /** 自身运行统计：暴露给 {@code /blackbox}，用来解释「为什么这次没落盘」。 */
    public JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("ring_size", ring.size());
        o.addProperty("server_uptime_ms", serverStartedAtMs <= 0 ? null : Math.max(0L, System.currentTimeMillis() - serverStartedAtMs));
        o.addProperty("min_server_ticks", ObservaConfig.BLACKBOX_MIN_SERVER_TICKS.get());
        o.addProperty("grace_seconds", ObservaConfig.BLACKBOX_GRACE_SECONDS.get());
        o.addProperty("freeze_after_ms", ObservaConfig.BLACKBOX_FREEZE_AFTER_MS.get());
        o.addProperty("in_grace", inGrace(MetricsService.snapshot(), System.currentTimeMillis()));
        o.addProperty("mspt_dumps", msptDumps.get());
        o.addProperty("stall_dumps", stallDumps.get());
        o.addProperty("suppressed_by_grace", suppressedGrace.get());
        o.addProperty("suppressed_by_cooldown", suppressedCooldown.get());
        o.addProperty("suppressed_by_pause", suppressedPause.get());
        o.addProperty("paused", PauseState.isPaused());
        o.addProperty("pause_forced", PauseState.forced());
        o.addProperty("heartbeat_running", heartbeat != null && heartbeat.isAlive());
        return o;
    }

    // ------------------------------------------------------------------ 落盘

    public void dumpAsync(String reason, String label) {
        dumpAsync(reason, label, null);
    }

    public void dumpAsync(String reason, String label, JsonObject detail) {
        writer.execute(() -> {
            try {
                write(reason, label, detail);
            } catch (Throwable t) {
                Observa.LOGGER.error("[observa] blackbox dump failed (reason={})", reason, t);
            }
        });
    }

    /** 同步写一份；返回文件路径。调用方负责选择线程（HTTP 请求或写盘线程）。 */
    public Path write(String reason, String label) throws IOException {
        return write(reason, label, null);
    }

    public Path write(String reason, String label, JsonObject detail) throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        long now = System.currentTimeMillis();
        String stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeReason = sanitize(reason);
        String safeLabel = label == null || label.isBlank() ? "" : "-" + sanitize(label);
        Path file = dir.resolve(stamp + "-" + safeReason + safeLabel + ".json.gz");

        long startedAt = System.currentTimeMillis();
        long bytes = writeGzip(file, buildPayload(reason, label, detail, now, startedAt));
        trim();
        Observa.LOGGER.info("[observa] blackbox written {} ({} bytes, reason={})", file, bytes, reason);
        return file;
    }

    private JsonObject buildPayload(String reason, String label, JsonObject detail, long now, long startedAt) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", Json.SCHEMA_VERSION);
        root.addProperty("written_at", Instant.ofEpochMilli(now).toString());
        root.addProperty("reason", reason);
        root.addProperty("label", label);

        JsonObject trigger = new JsonObject();
        trigger.addProperty("mspt_threshold_ms", ObservaConfig.BLACKBOX_MSPT_THRESHOLD_MS.get());
        trigger.addProperty("sustain_seconds", ObservaConfig.BLACKBOX_SUSTAIN_SECONDS.get());
        trigger.addProperty("dump_seq", dumpSeq.incrementAndGet());
        trigger.addProperty("ring_capacity", RING_CAPACITY);
        trigger.addProperty("ring_size", ring.size());
        trigger.addProperty("freeze_after_ms", ObservaConfig.BLACKBOX_FREEZE_AFTER_MS.get());
        trigger.addProperty("min_server_ticks", ObservaConfig.BLACKBOX_MIN_SERVER_TICKS.get());
        trigger.addProperty("grace_seconds", ObservaConfig.BLACKBOX_GRACE_SECONDS.get());
        if (detail != null) {
            trigger.add("detail", detail);
        }
        root.add("trigger", trigger);

        Snapshot snapshot = MetricsService.snapshot();
        if (snapshot != null) {
            root.add("snapshot", SnapshotJson.full(snapshot));
            root.addProperty("snapshot_age_ms", Math.max(0L, now - snapshot.collectedAtMs()));
        } else {
            root.add("snapshot", null);
            root.addProperty("snapshot_age_ms", (Long) null);
        }

        int ringSeconds = ObservaConfig.RING_SECONDS.get();
        int intervalMs = Math.max(50, ObservaConfig.METRICS_INTERVAL_MS.get());
        int frames = Math.max(1, ringSeconds * 1000 / intervalMs);
        root.add("ring", ring.toJson(frames));
        root.add("stats", stats());
        root.add("log_watch", LogWatch.instance().stats());
        root.add("errors", LogWatch.instance().recentErrors(DUMP_ERRORS));
        root.add("log_tail", LogWatch.instance().rawTail(DUMP_RAW_LINES));
        root.addProperty("build_ms", System.currentTimeMillis() - startedAt);
        return root;
    }

    private static long writeGzip(Path file, JsonObject payload) throws IOException {
        // 先写临时文件再原子移动：进程被杀在写盘中间时，目录里不会留下半截 .json.gz
        // 让下一次运维误以为是完整的一份。
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(tmp), 8192);
             Writer out = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
            Json.GSON.toJson(payload, out);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Files.size(file);
    }

    /** 只保留最近 {@link #KEEP_FILES} 份，超出按修改时间删最旧。 */
    private static void trim() {
        try {
            List<Path> files = listFiles();
            for (int i = KEEP_FILES; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            Observa.LOGGER.warn("[observa] cannot trim blackbox dir", e);
        }
    }

    /** 由新到旧排序的落盘文件。 */
    private static List<Path> listFiles() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json.gz")).forEach(files::add);
        }
        files.sort(Comparator.comparingLong(Blackbox::mtime).reversed());
        return files;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public JsonObject history(int limit) {
        JsonArray arr = new JsonArray();
        List<Path> files;
        try {
            files = listFiles();
        } catch (IOException e) {
            files = List.of();
        }
        int shown = 0;
        for (Path p : files) {
            if (shown++ >= Math.max(1, limit)) {
                break;
            }
            JsonObject o = new JsonObject();
            String name = p.getFileName().toString();
            o.addProperty("file", name);
            o.addProperty("path", p.toString());
            o.addProperty("bytes", safeSize(p));
            o.addProperty("modified_at", Instant.ofEpochMilli(mtime(p)).toString());
            o.addProperty("reason", reasonOf(name));
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.addProperty("dir", dir().toString());
        data.addProperty("count", files.size());
        data.addProperty("keep", KEEP_FILES);
        data.add("dumps", arr);
        data.add("stats", stats());
        JsonArray notes = new JsonArray();
        notes.add("auto dumps trigger when mspt stays above blackbox.mspt_threshold_ms for blackbox.sustain_seconds");
        notes.add("reason=stall means ticks stopped: a heartbeat thread keeps pushing frozen ring frames so the window survives a freeze");
        notes.add("auto dumps are suppressed during startup grace (blackbox.min_server_ticks / blackbox.grace_seconds) and for 60s after the previous one");
        notes.add("integrated-server pause (ESC menu) is not a freeze: stats.paused is true and stats.suppressed_by_pause counts the episode");
        notes.add("manual dumps: POST /dump with {\"label\":\"...\"}");
        notes.add("each file = snapshot + ring frames + last 20 errors + last 200 log lines, gzipped");
        data.add("notes", notes);
        return data;
    }

    private static String reasonOf(String name) {
        String base = name.substring(0, name.length() - ".json.gz".length());
        int dash = base.indexOf('-');
        // 名字形如 20261026-003512-mspt-label：跳过日期与时间两段
        String[] parts = base.split("-");
        return parts.length >= 3 ? parts[2] : "unknown";
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    // ------------------------------------------------------------------ 崩溃 / 停服兜底

    /** 崩溃兜底：链到既有 handler 上，不抢占原处理。只装一次。 */
    public synchronized void installCrashHandler() {
        if (crashHandlerInstalled) {
            return;
        }
        crashHandlerInstalled = true;
        Observa.LOGGER.info("[observa] uncaught-exception handler installed (crash dumps go to {})", dir());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                // 同步写：进程可能马上就要没了，不能交给后台线程。
                write("uncaught", thread == null ? "unknown-thread" : thread.getName());
            } catch (Throwable t) {
                Observa.LOGGER.error("[observa] crash dump failed", t);
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** 停服兜底（开关 {@code blackbox.on_shutdown}）。 */
    public void onShutdownCheck() {
        if (!ObservaConfig.BLACKBOX_ON_SHUTDOWN.get()) {
            return;
        }
        try {
            write("shutdown", null);
        } catch (Throwable t) {
            Observa.LOGGER.error("[observa] shutdown dump failed", t);
        }
    }

    public void stop() {
        // 只停日志跟随者，不关写盘线程池：单机模式下退出存档会触发一次停服，
        // 再次进存档时 ServerStartedEvent 会重新走到这里——线程池关了就没法再落盘。
        LogWatch.instance().stop();
    }
}
