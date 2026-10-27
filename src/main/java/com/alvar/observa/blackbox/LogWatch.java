package com.alvar.observa.blackbox;

import com.alvar.observa.Observa;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志尾巴：增量跟随 {@code logs/latest.log}，把 WARN/ERROR 按「签名」归并计数。
 *
 * <p>为什么不挂 Log4j appender：这需要编译期依赖 log4j-core 内部 API，且不同启动方式下
 * appender 能否加进 root logger 取决于 FML 的初始化时序。读文件只依赖 {@code java.nio}，
 * 在任何部署形态下行为一致——代价是有一秒级延迟，判读场景可以接受。
 *
 * <p>为什么自己维护偏移量而不是每次全量重扫：日志文件在长时间运行下会长到几十 MB，
 * 每次请求重读会让 {@code /logs} 的延迟随运行时间线性增长。
 */
public final class LogWatch {

    private static final LogWatch INSTANCE = new LogWatch();

    /**
     * 保留多少条 WARN+ 事件。
     *
     * <p>原来是 400：一次世界加载就能产出几百条 WARN，缓冲区立刻被最老的（也是最有用的）
     * 那批挤满，{@code /logs} 于是只剩最近一小段。5000 条约占几 MB，对专用服是零头。</p>
     */
    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_RAW_LINES = 200;
    /** 单次最多读多少字节：4 MB 足够吞下一次世界加载的日志洪水，又不至于让一次 poll 变慢。 */
    private static final long MAX_READ_BYTES_PER_POLL = 4L * 1024 * 1024;
    /**
     * 真落后到这么多才允许跳读。
     *
     * <p>旧实现在「本 poll 读不完」时就跳到文件尾，等于随手丢数据且不记账。现在只有
     * backlog 超过 64 MB（正常服务器几分钟都到不了）才跳，并把跳过的字节数报出来。</p>
     */
    private static final long MAX_BACKLOG_BYTES = 64L * 1024 * 1024;
    private static final long POLL_MS = 1000L;

    /** {@code [2610月2026 00:30:04.125] [Server thread/WARN] [net.minecraft.X/]: message} */
    private static final Pattern LINE = Pattern.compile(
            "^\\[([^\\]]*)\\] \\[([^/\\]]+)/([A-Z]+)\\] (?:\\[([^\\]]+)\\] ?: ?)?(.*)$");

    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})");

    public record Entry(long atMs, String level, String thread, String logger, String message, String stack) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Deque<String> rawLines = new ArrayDeque<>();

    private volatile boolean running;
    private Thread thread;
    private Path file;
    private long offset;
    private String leftover = "";
    private String pendingHeading;
    private String pendingStack;
    private long pendingAtMs;
    /** 因为容量上限被挤掉的事件数；只增不减，用来解释「为什么只看到 328/604」。 */
    private long droppedEntries;
    /** 因为落后超过 {@link #MAX_BACKLOG_BYTES} 而整段跳过的字节数。 */
    private long skippedBytes;

    private LogWatch() {
    }

    public static LogWatch instance() {
        return INSTANCE;
    }

    public synchronized void start(Path logsDir) {
        if (running) {
            return;
        }
        Path candidate = logsDir.resolve("latest.log");
        if (!Files.isRegularFile(candidate)) {
            Observa.LOGGER.info("[observa] no {} yet; /logs will stay empty until the log exists", candidate);
        }
        this.file = candidate;
        this.offset = 0L;
        this.running = true;
        Thread t = new Thread(this::loop, "observa-logwatch");
        t.setDaemon(true);
        t.start();
        this.thread = t;
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    private void loop() {
        while (running) {
            try {
                poll();
            } catch (Throwable t) {
                Observa.LOGGER.debug("[observa] log watch poll failed", t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void poll() throws IOException {
        Path current = file;
        if (current == null || !Files.isRegularFile(current)) {
            return;
        }
        long length = Files.size(current);
        if (length < offset) {
            // 日志被轮转/截断：从头再来，别把新文件当成旧文件的尾部。
            offset = 0L;
            leftover = "";
        }
        if (length == offset) {
            return;
        }
        long backlog = length - offset;
        if (backlog > MAX_BACKLOG_BYTES) {
            // 只在这里跳，并且把跳过的字节记账：正常情况下 `length - offset > 单次上限`
            // 应该分多次读完，而不是直接放弃中间那段。
            long jump = backlog - MAX_BACKLOG_BYTES;
            offset += jump;
            skippedBytes += jump;
            // 跳跃点可能落在半行中间，丢掉残留的前缀，避免拼出一行假日志。
            leftover = "";
            backlog = MAX_BACKLOG_BYTES;
        }
        long toRead = Math.min(backlog, MAX_READ_BYTES_PER_POLL);
        byte[] buffer = new byte[(int) toRead];
        try (RandomAccessFile raf = new RandomAccessFile(current.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buffer);
        }
        offset += toRead;
        consume(new String(buffer, StandardCharsets.UTF_8));
    }

    private void consume(String chunk) {
        String data = leftover + chunk;
        int newline = data.lastIndexOf('\n');
        if (newline < 0) {
            leftover = data;
            return;
        }
        leftover = data.substring(newline + 1);
        for (String line : data.substring(0, newline).split("\n")) {
            consumeLine(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
    }

    private void consumeLine(String line) {
        Matcher m = LINE.matcher(line);
        if (m.matches()) {
            flushPending();
            String level = m.group(3);
            String logger = m.group(4) == null ? "?" : m.group(4);
            if (logger.endsWith("/")) {
                // log4j 的 %logger 后面会跟一个斜杠（"net.minecraft.X/"），别带进 JSON。
                logger = logger.substring(0, logger.length() - 1);
            }
            String message = m.group(5) == null ? "" : m.group(5);
            long at = parseTime(m.group(1));
            keepRaw(line);
            if (!isInteresting(level)) {
                return;
            }
            pendingHeading = level + "\u0000" + m.group(2) + "\u0000" + logger + "\u0000" + message;
            pendingAtMs = at;
            pendingStack = "";
            return;
        }
        // 栈帧续行（log4j 用 tab 缩进，混排时也见过 "at "/"Caused by:"）
        if (pendingHeading != null && isStackLine(line)) {
            if (pendingStack.length() < 2048) {
                pendingStack += (pendingStack.isEmpty() ? "" : "\n") + line.trim();
            }
        }
        keepRaw(line);
    }

    private void keepRaw(String line) {
        rawLines.addLast(line);
        while (rawLines.size() > MAX_RAW_LINES) {
            rawLines.removeFirst();
        }
    }

    private void flushPending() {
        String heading = pendingHeading;
        if (heading == null) {
            return;
        }
        String[] parts = heading.split("\u0000", -1);
        entries.addLast(new Entry(pendingAtMs, parts[0], parts[1], parts[2], parts[3], pendingStack));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
            droppedEntries++;
        }
        pendingHeading = null;
        pendingStack = "";
    }

    private static boolean isInteresting(String level) {
        return "WARN".equals(level) || "ERROR".equals(level) || "FATAL".equals(level);
    }

    private static boolean isStackLine(String line) {
        String t = line.trim();
        return line.startsWith("\t")
                || t.startsWith("at ")
                || t.startsWith("Caused by:")
                || t.startsWith("... ")
                || t.startsWith("Suppressed:");
    }

    /** 日志前缀的日期格式随 locale 变（本机是 {@code 2610月2026}），只取时分秒，日期用当天。 */
    private static long parseTime(String prefix) {
        Matcher m = TIME.matcher(prefix);
        long now = System.currentTimeMillis();
        if (!m.find()) {
            return now;
        }
        try {
            LocalTime time = LocalTime.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)) * 1_000_000);
            LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
            long candidate = LocalDateTime.of(today, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (candidate > now + 60_000L) {
                // 跨零点：日志里的 23:59 属于昨天，别把它算到未来。
                candidate -= 24L * 60L * 60L * 1000L;
            }
            return candidate;
        } catch (RuntimeException e) {
            return now;
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 归并后的视图：按「级别 + logger + 归一化消息 + 栈首行」分组，按次数降序。 */
    public synchronized JsonObject query(int sinceSeconds, String minLevel, int limit) {
        long cutoff = sinceSeconds <= 0 ? 0L : System.currentTimeMillis() - sinceSeconds * 1000L;
        int minRank = rank(minLevel);
        Map<String, Group> groups = new LinkedHashMap<>();
        int matched = 0;
        for (Entry e : entries) {
            if (e.atMs() < cutoff || rank(e.level()) < minRank) {
                continue;
            }
            matched++;
            String key = key(e);
            groups.computeIfAbsent(key, k -> new Group(e)).add(e);
        }
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparingLong((Group g) -> g.count).reversed());

        JsonArray arr = new JsonArray();
        for (int i = 0; i < sorted.size() && i < Math.max(1, limit); i++) {
            arr.add(sorted.get(i).toJson());
        }

        JsonObject data = new JsonObject();
        data.addProperty("since_seconds", Math.max(0, sinceSeconds));
        data.addProperty("min_level", minLevel == null ? "ERROR" : minLevel.toUpperCase(Locale.ROOT));
        data.addProperty("events_matched", matched);
        data.addProperty("groups", groups.size());
        data.add("top", arr);
        data.addProperty("log_file", file == null ? null : file.toString());
        data.addProperty("entries_retained", entries.size());
        data.addProperty("entries_dropped", droppedEntries);
        data.addProperty("log_bytes_skipped", skippedBytes);
        JsonArray notes = new JsonArray();
        notes.add("dedupe key = level + logger + message with digits replaced by # + first 3 stack lines");
        notes.add("only WARN/ERROR/FATAL are retained; INFO is not (read the raw file for INFO)");
        notes.add("the log is followed incrementally; events older than the first poll after boot are not kept");
        notes.add("entries_dropped > 0 means older matches were evicted by the 5000-entry cap; "
                + "log_bytes_skipped > 0 means the follower once fell more than 64MB behind and jumped ahead");
        notes.add("this view is for triage; the raw file is still the source of truth");
        data.add("notes", notes);
        return data;
    }

    /** 跟随者的自述：黑匣子 payload 与 {@code /blackbox} 用它解释数据缺口。 */
    public synchronized JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("log_file", file == null ? null : file.toString());
        o.addProperty("file_bytes", fileBytes());
        o.addProperty("read_offset", offset);
        o.addProperty("entries_retained", entries.size());
        o.addProperty("entries_cap", MAX_ENTRIES);
        o.addProperty("entries_dropped", droppedEntries);
        o.addProperty("bytes_skipped", skippedBytes);
        o.addProperty("raw_lines_retained", rawLines.size());
        o.addProperty("running", running);
        return o;
    }

    private long fileBytes() {
        Path f = file;
        if (f == null) {
            return -1L;
        }
        try {
            return Files.size(f);
        } catch (IOException e) {
            return -1L;
        }
    }

    /** 黑匣子落盘用：最近 {@code max} 条归并后的异常。 */
    public synchronized JsonArray recentErrors(int max) {
        flushPending();
        JsonArray arr = new JsonArray();
        int start = Math.max(0, entries.size() - max);
        int i = 0;
        for (Entry e : entries) {
            if (i++ < start) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("at", Instant.ofEpochMilli(e.atMs()).toString());
            o.addProperty("level", e.level());
            o.addProperty("thread", e.thread());
            o.addProperty("logger", e.logger());
            o.addProperty("message", e.message());
            if (e.stack() != null && !e.stack().isEmpty()) {
                o.addProperty("stack", e.stack());
            }
            arr.add(o);
        }
        return arr;
    }

    /** 黑匣子落盘用：最近 {@code max} 行原始日志（含 INFO），拼出触发前后的上下文。 */
    public synchronized JsonArray rawTail(int max) {
        flushPending();
        JsonArray arr = new JsonArray();
        int skip = Math.max(0, rawLines.size() - max);
        int i = 0;
        for (String line : rawLines) {
            if (i++ < skip) {
                continue;
            }
            arr.add(line);
        }
        return arr;
    }

    private static int rank(String level) {
        if (level == null) {
            return 2;
        }
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "INFO" -> 1;
            case "WARN" -> 2;
            case "ERROR" -> 3;
            case "FATAL" -> 4;
            default -> 2;
        };
    }

    private static String key(Entry e) {
        String message = e.message().replaceAll("\\d+", "#");
        String stack = e.stack() == null ? "" : e.stack();
        StringBuilder head = new StringBuilder();
        int lines = 0;
        for (String line : stack.split("\n")) {
            if (lines++ >= 3) {
                break;
            }
            head.append(line).append('|');
        }
        return e.level() + '\u0000' + e.logger() + '\u0000' + message + '\u0000' + head;
    }

    private static final class Group {
        final String level;
        final String logger;
        final String message;
        final String stack;
        final List<Long> times = new ArrayList<>();

        Group(Entry first) {
            this.level = first.level();
            this.logger = first.logger();
            this.message = first.message();
            this.stack = first.stack();
        }

        void add(Entry e) {
            times.add(e.atMs());
        }

        long count;

        JsonObject toJson() {
            count = times.size();
            JsonObject o = new JsonObject();
            o.addProperty("level", level);
            o.addProperty("logger", logger);
            o.addProperty("message", message);
            o.addProperty("count", count);
            long first = times.get(0);
            long last = times.get(times.size() - 1);
            for (long t : times) {
                first = Math.min(first, t);
                last = Math.max(last, t);
            }
            o.addProperty("first_at", Instant.ofEpochMilli(first).toString());
            o.addProperty("last_at", Instant.ofEpochMilli(last).toString());
            if (stack != null && !stack.isEmpty()) {
                o.addProperty("stack", stack);
            }
            return o;
        }
    }
}
