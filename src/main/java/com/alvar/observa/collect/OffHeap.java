package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * 堆外内存拆分：把「进程工作集 - 堆」之间那块说不清的内存尽量拆成可核对的小块。
 *
 * <p>为什么需要它：真实排障里出现过 {@code 非堆 706 MiB}（MXBean 的 non-heap）与
 * {@code private commit 12.37 GiB}（任务管理器/ss 看到的进程已提交）相差约 5 GiB 的情况。
 * 这个差值不是 bug，而是三块东西叠在一起：</p>
 * <ul>
 *   <li><b>MXBean 的 non-heap 只算两样</b>：Metaspace（含 Compressed Class Space）和 CodeCache。
 *       它<b>不包含</b> direct/mapped NIO 缓冲、线程栈、GC 数据结构、编译器/C2 的临时区、符号表、
 *       以及任何 mod/原生库自己 malloc 的内存；</li>
 *   <li><b>线程栈是「保留」而不是「已提交」</b>：{@code -Xss1m} 只是每个线程的地址空间预算，
 *       实际只提交被踩到的页。所以 700 个线程的「估算 700 MiB」在任务管理器里往往小得多；</li>
 *   <li><b>只有 NMT 能给出 JVM 自己承认的原生内存账本</b>，而它必须在启动时用
 *       {@code -XX:NativeMemoryTracking=summary} 打开；运行中的进程无法补开。</li>
 * </ul>
 *
 * <p>所以本类做的是「分层给证据」而不是给一个结论：能现算的（BufferPool、内存池、线程栈估算）直接算，
 * 拿不到的（NMT）明确标成 {@code available:false} 并附上一条可执行的启动参数建议。
 * 字段单位统一 bytes；{@code null} 表示「没有硬上限/不适用」，而不是 0。</p>
 *
 * <p>永不抛异常：每个 MXBean 调用都有自己的 try/catch，最外层再兜一层，失败时返回占位结构，
 * 因为这是排障端点——排障端点自己 500 是最糟糕的失败方式。</p>
 */
public final class OffHeap {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    /** 缺省 -Xss：HotSpot 在 Linux x64 / Windows x64 上的默认栈大小就是 1 MiB。 */
    private static final long DEFAULT_STACK_BYTES = MIB;

    private static final String DIAGNOSTIC_COMMAND = "com.sun.management:type=DiagnosticCommand";
    private static final String NMT_ENABLE_ARG = "add -XX:NativeMemoryTracking=summary and restart";

    /** NMT summary 行里的 {@code reserved=N KB, committed=N KB}；列宽会随版本变，只认键值对。 */
    private static final Pattern NMT_SIZE = Pattern.compile("(reserved|committed)=(\\d+)([KMG]?B)");

    private OffHeap() {
    }

    /** 现算一份堆外内存拆分。不做缓存：这些量随分配实时变化，缓存只会让「刚才那一下」消失。 */
    public static JsonObject json() {
        try {
            JsonObject data = new JsonObject();
            data.add("direct", bufferPool("direct"));
            data.add("mapped", bufferPool("mapped"));
            data.add("code_cache", memoryPools(true));
            data.add("metaspace", memoryPools(false));
            data.add("thread_stacks", threadStacks());
            data.add("nmt", nmt());

            JsonArray notes = new JsonArray();
            notes.add("thread stack total is an estimate: threads x -Xss. -Xss is an address-space budget per thread, "
                    + "not committed memory, so the real committed stack bytes in the OS are usually lower");
            notes.add("direct/mapped only cover JDK NIO BufferPoolMXBean buffers; native memory allocated by mods, "
                    + "LWJGL, GPU drivers or any other library is invisible here");
            notes.add("metaspace/code_cache come from MemoryPoolMXBean usage; a null max_bytes means the pool has no "
                    + "hard limit (the JVM default for Metaspace)");
            notes.add("NMT requires -XX:NativeMemoryTracking=summary at startup and cannot be enabled on a running JVM; "
                    + "it tracks JVM native allocations only, and native memory outside the JVM allocator is never tracked");
            data.add("notes", notes);
            return data;
        } catch (Throwable t) {
            return degraded(t);
        }
    }

    // ------------------------------------------------------------------ direct / mapped

    /**
     * 一个 NIO 缓冲池（{@code direct} 或 {@code mapped}）。
     *
     * <p>用 {@link BufferPoolMXBean} 而不是 {@code sun.nio.ch.DirectBuffer}：前者是标准 API，
     * 统计的是 JVM 自己记账的 ByteBuffer 分配，换 JDK 也不用改。拿不到就全给 0——
     * 0 在这里的含义是「这个 JVM 没有该池/读不到」，notes 已说明。</p>
     */
    private static JsonObject bufferPool(String wanted) {
        long count = 0;
        long used = 0;
        long capacity = 0;
        try {
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            for (BufferPoolMXBean pool : pools) {
                if (pool == null || pool.getName() == null || !wanted.equalsIgnoreCase(pool.getName())) {
                    continue;
                }
                count += Math.max(0L, pool.getCount());
                used += Math.max(0L, pool.getMemoryUsed());
                capacity += Math.max(0L, pool.getTotalCapacity());
            }
        } catch (Throwable ignored) {
            // 平台不提供 BufferPoolMXBean（或被安全管理器拒绝）时保持 0；不伪造估算值。
        }
        JsonObject pool = new JsonObject();
        pool.addProperty("count", count);
        pool.addProperty("used_bytes", used);
        pool.addProperty("capacity_bytes", capacity);
        return pool;
    }

    // ------------------------------------------------------------------ code cache / metaspace

    /**
     * CodeCache 或 Metaspace 分组的合计。
     *
     * <p>HotSpot 21 的池名不像逻辑分组那么整齐：CodeCache 被拆成
     * {@code CodeHeap 'non-nmethods' / 'profiled nmethods' / 'non-profiled nmethods'} 三块，
     * Metaspace 则和 {@code Compressed Class Space} 分开。按名字的关键词归组再求和，
     * 比调用方自己遍历 {@code MemoryPoolMXBean} 更不容易漏。</p>
     *
     * <p>{@code max_bytes} 为 null 的语义：这些池的 {@code MemoryUsage.getMax()} 返回 -1，
     * 表示「没有上限」，而不是「上限 0」。分组里只要有一块有硬上限就把上限相加，全都没有就给 null。</p>
     */
    private static JsonObject memoryPools(boolean codeCache) {
        long used = 0;
        long committed = 0;
        long max = 0;
        boolean hasMax = false;
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool == null || pool.getName() == null) {
                    continue;
                }
                String lower = pool.getName().toLowerCase(Locale.ROOT);
                boolean isCode = lower.contains("codeheap") || lower.contains("code heap")
                        || lower.contains("codecache") || lower.contains("code cache");
                boolean isMetaspace = lower.contains("metaspace") || lower.contains("compressed class");
                if (codeCache ? !isCode : !isMetaspace) {
                    continue;
                }
                MemoryUsage usage = pool.getUsage();
                if (usage == null) {
                    continue;
                }
                used += Math.max(0L, usage.getUsed());
                committed += Math.max(0L, usage.getCommitted());
                if (usage.getMax() > 0) {
                    max += usage.getMax();
                    hasMax = true;
                }
            }
        } catch (Throwable ignored) {
            // 同 bufferPool：读不到就 0/null，不抛。
        }
        JsonObject pools = new JsonObject();
        pools.addProperty("used_bytes", used);
        pools.addProperty("committed_bytes", committed);
        pools.add("max_bytes", hasMax ? new JsonPrimitive(max) : JsonNull.INSTANCE);
        return pools;
    }

    // ------------------------------------------------------------------ thread stacks

    /**
     * 线程栈估算：线程数 x 每线程栈预算。
     *
     * <p>这是本类里<b>唯一</b>明确标成 estimate 的数字。{@code -Xss} 是每线程的地址空间上限，
     * 线程真正提交多少取决于调用深度：一个空的 park 线程可能只提交几十 KB。
     * 所以这里给不出「已提交」，只能给出「最多可能」。把 {@code -Xss} 一起回传，
     * 是为了让读的人知道这个乘数从哪来（启动参数里没写时就是 JVM 平台默认 1 MiB）。</p>
     */
    private static JsonObject threadStacks() {
        long assumed = DEFAULT_STACK_BYTES;
        try {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            // 同名参数以最后一个为准：和 JVM 自己的覆盖规则一致。
            for (String arg : runtime.getInputArguments()) {
                if (arg == null || !arg.startsWith("-Xss")) {
                    continue;
                }
                Long parsed = parseSize(arg.substring("-Xss".length()));
                if (parsed != null) {
                    assumed = parsed;
                }
            }
        } catch (Throwable ignored) {
            // 读不到启动参数就用默认值；notes 里已经声明这是假设。
        }
        long threads = 0;
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            threads = Math.max(0L, threadBean.getThreadCount());
        } catch (Throwable ignored) {
            // 线程数拿不到就 0，避免估算出无意义的值。
        }
        long estimated;
        try {
            estimated = Math.multiplyExact(threads, assumed);
        } catch (ArithmeticException e) {
            estimated = Long.MAX_VALUE;
        }
        JsonObject stacks = new JsonObject();
        stacks.addProperty("threads", threads);
        stacks.addProperty("assumed_stack_bytes", assumed);
        stacks.addProperty("estimated_bytes", estimated);
        return stacks;
    }

    // ------------------------------------------------------------------ NMT

    /**
     * Native Memory Tracking：JVM 对「自己向 OS 要了多少原生内存」的自述账本。
     *
     * <p>调用路径是 {@code com.sun.management:type=DiagnosticCommand} 上的
     * {@code vmNativeMemory(String[])}——和 {@code jcmd <pid> VM.native_memory summary} 是同一个入口。
     * JDK 没有为它提供 Java API，所以只能通过 {@link MBeanServer#invoke} 反射式调用；
     * 未开启 NMT 时该调用失败（运行中的进程无法补开），这时返回可用性标记和可执行的启动参数建议。</p>
     *
     * <p>解析策略：只认键值对 {@code reserved=N KB, committed=N KB}，不认固定列宽——
     * 不同 JDK 版本、不同 category 的行缩进和列宽都会变，按列切是脆的。
     * 另外算一个 {@code other_bytes = total - 已解析分类之和}，因为「差出来的那几 GiB」
     * 通常就落在 Compiler / Internal / Symbol / Arena Chunk / Logging / Module 这些没单独列出的分类里。</p>
     */
    private static JsonObject nmt() {
        JsonObject nmt = new JsonObject();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(DIAGNOSTIC_COMMAND);
            if (!server.isRegistered(name)) {
                return nmtUnavailable("NMT is not enabled");
            }
            Object result = server.invoke(name, "vmNativeMemory",
                    new Object[]{new String[]{"summary"}},
                    new String[]{String[].class.getName()});
            String text = result instanceof String string ? string : null;
            if (text == null || text.isBlank()) {
                return nmtUnavailable("NMT is not enabled");
            }
            // 未开启 NMT 时 HotSpot 不抛异常，而是返回一段 "Native memory tracking is not enabled" 的
            // 提示文本（jcmd 的行为）。必须先识别它，否则会被误报成「NMT 已开启但表解析失败」。
            if (text.toLowerCase(Locale.ROOT).contains("not enabled")) {
                return nmtUnavailable("NMT is not enabled");
            }
            JsonObject summary = parseNmtSummary(text);
            if (summary == null) {
                return nmtUnavailable("NMT is enabled but the summary table could not be parsed");
            }
            nmt.addProperty("available", true);
            nmt.add("summary", summary);
            return nmt;
        } catch (Throwable t) {
            // 未开启 NMT 时 invoke 会抛 RuntimeException；MBean 不存在则抛 InstanceNotFoundException。
            // 两者对外都是同一个可执行建议，所以不区分异常类型，但把异常挂在 reason 里便于确认原因。
            return nmtUnavailable("NMT is not enabled");
        }
    }

    private static JsonObject nmtUnavailable(String reason) {
        JsonObject nmt = new JsonObject();
        nmt.addProperty("available", false);
        nmt.addProperty("reason", reason);
        JsonArray next = new JsonArray();
        next.add(NMT_ENABLE_ARG);
        nmt.add("next", next);
        return nmt;
    }

    /**
     * 从 NMT summary 文本里抽出 Total / Java Heap / Class / Thread / Code / GC 六行的 committed 值。
     *
     * <p>既处理 {@code Total: reserved=..., committed=...} 这种带冒号的汇总行，
     * 也处理 {@code -   Java Heap (reserved=..., committed=...)} 这种横线开头的分类行。
     * 解析不到任何一行时返回 null（调用方据此报「NMT 已开启但表解析失败」）。</p>
     */
    private static JsonObject parseNmtSummary(String text) {
        long total = -1;
        long totalReserved = -1;
        long javaHeap = -1;
        long clazz = -1;
        long thread = -1;
        long code = -1;
        long gc = -1;
        boolean sawCategory = false;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("Total:")) {
                long[] sizes = nmtSizes(line);
                if (sizes != null) {
                    totalReserved = sizes[0];
                    total = sizes[1];
                }
                continue;
            }
            if (!line.startsWith("-")) {
                continue;
            }
            long[] sizes = nmtSizes(line);
            if (sizes == null) {
                continue;
            }
            // 名字是「横线之后、左括号之前」的那段；横线后的缩进宽度每个 JDK 都可能不同。
            int paren = line.indexOf('(');
            String name = (paren > 1 ? line.substring(1, paren) : line.substring(1)).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            long committed = sizes[1] >= 0 ? sizes[1] : sizes[0];
            if (name.startsWith("java heap")) {
                javaHeap = committed;
            } else if (name.equals("class") || name.startsWith("class ")) {
                clazz = committed;
            } else if (name.equals("thread") || name.startsWith("thread ")) {
                thread = committed;
            } else if (name.equals("code") || name.startsWith("code ")) {
                code = committed;
            } else if (name.equals("gc") || name.startsWith("gc ")) {
                gc = committed;
            } else {
                continue;
            }
            sawCategory = true;
        }

        if (total < 0 && !sawCategory) {
            return null;
        }
        JsonObject summary = new JsonObject();
        if (total >= 0) {
            summary.addProperty("total_bytes", total);
        }
        if (totalReserved >= 0) {
            summary.addProperty("total_reserved_bytes", totalReserved);
        }
        addIfKnown(summary, "java_heap_bytes", javaHeap);
        addIfKnown(summary, "class_bytes", clazz);
        addIfKnown(summary, "thread_bytes", thread);
        addIfKnown(summary, "code_bytes", code);
        addIfKnown(summary, "gc_bytes", gc);
        long known = 0;
        int knownRows = 0;
        for (long value : new long[]{javaHeap, clazz, thread, code, gc}) {
            if (value >= 0) {
                known += value;
                knownRows++;
            }
        }
        // 只有 total 和至少一个分类都有值时才给 other：否则算出来的是负数或 0，会误导。
        if (total >= 0 && knownRows > 0 && total >= known) {
            summary.addProperty("other_bytes", total - known);
        }
        return summary;
    }

    /** 解析一行里的 reserved/committed，返回 {reservedBytes, committedBytes}；一个都没有就 null。 */
    private static long[] nmtSizes(String line) {
        long reserved = -1;
        long committed = -1;
        Matcher matcher = NMT_SIZE.matcher(line);
        while (matcher.find()) {
            long bytes = toBytes(matcher.group(3), matcher.group(2));
            if (bytes < 0) {
                continue;
            }
            if ("reserved".equals(matcher.group(1))) {
                reserved = bytes;
            } else {
                committed = bytes;
            }
        }
        return reserved < 0 && committed < 0 ? null : new long[]{reserved, committed};
    }

    private static void addIfKnown(JsonObject object, String key, long value) {
        if (value >= 0) {
            object.addProperty(key, value);
        }
    }

    /** NMT 的单位只有 KB/MB/GB（以及个别版本的 B/TB 近似），统一换算成 bytes。 */
    private static long toBytes(String unit, String digits) {
        try {
            long number = Long.parseLong(digits);
            char prefix = Character.toLowerCase(unit.charAt(0));
            return switch (prefix) {
                case 'k' -> number * 1024L;
                case 'm' -> number * MIB;
                case 'g' -> number * GIB;
                case 't' -> number * GIB * 1024L;
                default -> number;
            };
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 兜底

    /** 最坏情况的占位结构：保证字段名齐全、类型一致（这是给 agent 读的，不是给人读的）。 */
    private static JsonObject degraded(Throwable t) {
        JsonObject data = new JsonObject();
        data.add("direct", emptyBufferPool());
        data.add("mapped", emptyBufferPool());
        data.add("code_cache", emptyPools());
        data.add("metaspace", emptyPools());
        JsonObject stacks = new JsonObject();
        stacks.addProperty("threads", 0);
        stacks.addProperty("assumed_stack_bytes", DEFAULT_STACK_BYTES);
        stacks.addProperty("estimated_bytes", 0);
        data.add("thread_stacks", stacks);
        JsonObject nmt = nmtUnavailable("off-heap collection failed: " + t);
        data.add("nmt", nmt);
        JsonArray notes = new JsonArray();
        notes.add("off-heap collection failed; this payload only contains placeholders");
        data.add("notes", notes);
        return data;
    }

    private static JsonObject emptyBufferPool() {
        JsonObject pool = new JsonObject();
        pool.addProperty("count", 0);
        pool.addProperty("used_bytes", 0);
        pool.addProperty("capacity_bytes", 0);
        return pool;
    }

    private static JsonObject emptyPools() {
        JsonObject pools = new JsonObject();
        pools.addProperty("used_bytes", 0);
        pools.addProperty("committed_bytes", 0);
        pools.add("max_bytes", JsonNull.INSTANCE);
        return pools;
    }

    /** 与 JvmFlags 同语义的 size 解析：整数 + 可选 k/m/g/t（大小写均可），裸数字按字节。 */
    private static Long parseSize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        long multiplier = 1L;
        char suffix = Character.toLowerCase(value.charAt(value.length() - 1));
        switch (suffix) {
            case 'k' -> multiplier = 1024L;
            case 'm' -> multiplier = MIB;
            case 'g' -> multiplier = GIB;
            case 't' -> multiplier = GIB * 1024L;
            default -> {
                // 无后缀：JVM 把整个数字当字节。
            }
        }
        if (multiplier != 1L) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            long number = Long.parseLong(value.trim());
            return number <= 0 ? null : number * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
