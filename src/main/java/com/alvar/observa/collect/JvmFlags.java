package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.sun.management.OperatingSystemMXBean;

/**
 * 启动参数体检：把 JVM 的 {@code input_args} 读回来，对照 Minecraft 服务端的实际需求逐条挑错。
 *
 * <p>为什么值得单独一个端点：这张单子上的每一条都是「配置写错了，但服务器照样正常启动」的错误。
 * 它们不会在启动时报警，也不会让 MXBean 出现任何异常值，只会在压测、OOM 或某天突然卡顿的那一下
 * 以 P0 的形式出现——而那时能回溯的信息已经没了：</p>
 * <ul>
 *   <li>没有 {@code -Xms}：堆在在线期间反复向 OS 要页、反复扩缩，表现为周期性 MSPT 尖峰，</li>
 *   <li>没有 {@code -XX:MaxMetaspaceSize}：mixin/ASM 生成的类元数据泄漏没有任何压力信号，要等整机内存被吃光才暴露，</li>
 *   <li>没有 {@code -XX:+HeapDumpOnOutOfMemoryError}：唯一一次 OOM 现场不会被保存，之后所有排查只能靠猜，</li>
 *   <li>没有 {@code -Xlog:gc*}：事后只剩一个「累计 GC 时间」计数，回答不了「卡的那一下是哪一种 GC」。</li>
 * </ul>
 *
 * <p>这些问题的共同点是<b>只能从启动参数看出来</b>，运行时的数字全是「正常」的。所以本端点做的是静态体检：
 * 纯字符串解析，不引入任何参数解析库（参数语法只到 {@code -Xmx4G} 这一层），也不改任何东西。
 * 参数是进程启动时固化的，运行中改启动脚本不会影响这里读到的值（见 {@code notes}）。</p>
 *
 * <p>返回结构：{@code input_args}（原样）、{@code heap}（解析出的三个上限，缺失给 null）、
 * {@code gc}（收集器家族 + 原始 MXBean 名）、{@code checks}（{@code id/severity/message/fix}）、
 * {@code ok}（没有任何 error/warn 才是 true）、{@code notes}。</p>
 */
public final class JvmFlags {

    /** -Xmx 超过物理内存的这个比例就 warn：再高就没有空间留给磁盘缓存和别的进程了。 */
    private static final double XMX_PHYSICAL_WARN_RATIO = 0.75;

    /** 建议 -Xmx 时按物理内存的这个比例算，留出 OS 和其它进程的余量。 */
    private static final double XMX_SUGGEST_RATIO = 0.6;

    private static final String XMX_PREFIX = "-Xmx";
    private static final String XMS_PREFIX = "-Xms";
    private static final String MAX_METASPACE_PREFIX = "-XX:MaxMetaspaceSize=";
    private static final String HEAPDUMP_PATH_PREFIX = "-XX:HeapDumpPath=";
    private static final String HEAPDUMP_ON_OOM = "-XX:+HeapDumpOnOutOfMemoryError";
    private static final String XLOG_PREFIX = "-Xlog:";
    private static final String XLOGC_PREFIX = "-Xloggc:";

    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    private JvmFlags() {
    }

    /** 现算一份启动参数体检。不做缓存：这些字段在进程生命周期内不会变，但缓存只会让「刚重启过」的错觉更难排查。 */
    public static JsonObject json() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        List<String> args = runtime.getInputArguments();

        JsonObject data = new JsonObject();

        JsonArray inputArgs = new JsonArray();
        for (String arg : args) {
            inputArgs.add(arg);
        }
        data.add("input_args", inputArgs);

        Long xmx = null;
        Long xms = null;
        Long maxMetaspace = null;
        String heapDumpPath = null;
        boolean heapDumpOnOom = false;
        boolean xlogEnabled = false;
        List<String> xlogFiles = new ArrayList<>();

        // 单趟扫描。同名参数重复出现时以最后一个为准——这正是 JVM 自己的规则（后写的覆盖先写的），
        // 如果按第一个算，脚本里「前面默认值 + 后面实际值」的写法会被误判成默认值。
        for (String arg : args) {
            if (arg.startsWith(XMX_PREFIX)) {
                xmx = parseSize(arg.substring(XMX_PREFIX.length()));
            } else if (arg.startsWith(XMS_PREFIX)) {
                xms = parseSize(arg.substring(XMS_PREFIX.length()));
            } else if (arg.startsWith(MAX_METASPACE_PREFIX)) {
                maxMetaspace = parseSize(arg.substring(MAX_METASPACE_PREFIX.length()));
            } else if (arg.startsWith(HEAPDUMP_PATH_PREFIX)) {
                heapDumpPath = unquote(arg.substring(HEAPDUMP_PATH_PREFIX.length())).trim();
            } else if (arg.equals(HEAPDUMP_ON_OOM)) {
                heapDumpOnOom = true;
            } else if (arg.startsWith(XLOG_PREFIX)) {
                xlogEnabled = true;
                String file = xlogFile(arg);
                if (file != null && !file.isBlank()) {
                    xlogFiles.add(file);
                }
            } else if (arg.startsWith(XLOGC_PREFIX)) {
                // JDK 9 起 -Xloggc 已被 -Xlog 取代，但老启动脚本/旧教程里还大量存在。
                // 不识别它就会漏掉「GC 日志配了、但配的是过期写法」这类问题。
                xlogEnabled = true;
                String file = unquote(arg.substring(XLOGC_PREFIX.length())).trim();
                if (!file.isEmpty()) {
                    xlogFiles.add(file);
                }
            }
        }
        // -XX:HeapDumpPath= （空值）等价于没配：JVM 会退回工作目录，按缺失处理才能给出可执行的建议。
        if (heapDumpPath != null && heapDumpPath.isEmpty()) {
            heapDumpPath = null;
        }

        // 物理内存先算出来，下面 xms 的建议要用它做一致性修正；拿不到给 -1，后面所有用到它的检查都跳过。
        OperatingSystemMXBean os = osBean();
        long physical = os == null ? -1 : os.getTotalMemorySize();

        JsonObject heap = new JsonObject();
        // 缺失给 null 而不是 0：调用方要能区分「没配」和「配成 0」，这是两种完全不同的建议。
        addNullable(heap, "xmx_bytes", xmx);
        addNullable(heap, "xms_bytes", xms);
        addNullable(heap, "max_metaspace_bytes", maxMetaspace);
        data.add("heap", heap);

        data.add("gc", gc());

        Path gameDir = gameDir();
        Path cwd = cwd();
        String logFile = gameDir.resolve("logs").resolve("gc.log").toString();
        String dumpFile = gameDir.resolve("logs").resolve("heapdump.hprof").toString();
        String gcLogFix = "-Xlog:gc*:file=" + logFile + ":time,uptime,level,tags:filecount=5,filesize=10m";

        JsonArray checks = new JsonArray();

        // 1. 缺 -Xms。堆从很小的初始值开始按需扩，扩容本身会触发额外的分配失败/GC；
        //    这类抖动是「服务器人一多就开始周期性卡」的经典形态，而 /memory 只能看到平均值。
        if (xms == null) {
            // 建议值必须和 check 6 一致：-Xmx 本身超了物理内存 75% 时，不该在这里再劝人 -Xms 也设成那个超限值，
            // 否则两条 fix 互相打架（一条说 15G，一条说 9G），调用方只能猜。
            long sameBytes = xmx == null
                    ? 4 * GIB
                    : (physical > 0 && xmx > physical * XMX_PHYSICAL_WARN_RATIO ? suggestXmx(physical) : xmx);
            String same = sizeArg(sameBytes);
            check(checks, "xms_missing", "warn",
                    "-Xms is not set, so the heap starts small and grows while players are online. "
                            + "Every growth asks the OS for pages and can trigger an extra allocation stall; the symptom is "
                            + "periodic MSPT spikes long before the heap is actually full, which is a P0 you cannot reproduce offline.",
                    "-Xms" + same + " -Xmx" + same
                            + " (use the same value for both so the heap is fully committed at startup)");
        }

        // 2. 缺 -XX:MaxMetaspaceSize。Metaspace 默认无上限，而 mod/mixin/ASM/JFR 都会生成类；
        //    一旦泄漏，GC 指标全程健康，唯一信号是整机越来越慢（swap）。
        if (maxMetaspace == null) {
            check(checks, "max_metaspace_missing", "warn",
                    "-XX:MaxMetaspaceSize is not set, so Metaspace is unbounded. Class metadata from mods, mixins and "
                            + "ASM/JFR-generated classes can grow without any pressure signal: GC stays healthy and the only symptom is "
                            + "the whole machine swapping until the process dies. That P0 looks like 'server lag' but is really metaspace growth.",
                    "-XX:MaxMetaspaceSize=512m (use 1g for large modpacks). The JVM then fails fast with "
                            + "OutOfMemoryError: Metaspace, a traceable crash instead of a machine-wide stall");
        }

        // 3a. 缺 -XX:HeapDumpPath。开了 dump 也没用：落点在工作目录，而模组服的「工作目录」
        //     往往是启动器/启动脚本所在目录，事后既难找，也不在 run 目录的备份范围里。
        if (heapDumpPath == null) {
            check(checks, "heapdump_path_missing", "warn",
                    "-XX:HeapDumpPath is not set. Even with -XX:+HeapDumpOnOutOfMemoryError the dump goes to the process "
                            + "working directory (usually the launcher or start-script directory), where it is hard to find afterwards "
                            + "and is not covered by backups or cleanup of the run directory.",
                    "-XX:HeapDumpPath=" + dumpFile + " (create " + gameDir.resolve("logs")
                            + " first; the JVM never creates missing directories)");
        // 3b. 配了但不在 gameDir 之下。相对路径按进程工作目录解析（不是 gameDir），这是最常见的坑；
        //     目录不可写时 JVM 只打一行警告就不写 dump，等于 OOM 现场直接丢失。
        } else if (!underGameDir(heapDumpPath, gameDir, cwd)) {
            check(checks, "heapdump_path_outside_gamedir", "warn",
                    "-XX:HeapDumpPath points outside the game directory: " + heapDumpPath + ". If that directory is not "
                            + "writable the JVM only logs a warning and writes no dump at all; if it is writable the dump ends up outside "
                            + "everything that is backed up and cleaned together with the run directory.",
                    "-XX:HeapDumpPath=" + dumpFile + " (a relative path would also resolve against "
                            + cwd + ", not against the game directory)");
        }

        // 4. -Xlog 未设置 / 没给 file= / file= 不在 gameDir 下。GC 日志是唯一能回答
        //    「卡的那一下是哪一种 GC、多久、回收前后堆多满」的证据，缺了它 P0 只能靠猜。
        if (!xlogEnabled) {
            check(checks, "xlog_missing", "info",
                    "-Xlog is not set, so no GC log is written. After a stall the only GC data left is the cumulative "
                            + "counter on /memory: you cannot tell which collector paused, for how long, how full the heap was before/after, "
                            + "or whether it was an allocation failure. The GC log is what turns 'the server lagged at 21:40' into a root cause.",
                    gcLogFix);
        } else if (xlogFiles.isEmpty()) {
            check(checks, "xlog_no_file", "info",
                    "-Xlog is set but has no file= target, so GC output goes to stdout. That usually lands in "
                            + "logs/latest.log, which is truncated at every restart: the log you need disappears exactly when the crash happens.",
                    gcLogFix);
        } else {
            for (String file : xlogFiles) {
                if (!underGameDir(file, gameDir, cwd)) {
                    check(checks, "xlog_outside_gamedir", "warn",
                            "-Xlog writes its GC log to " + file + ", outside the game directory. GC logs are crash evidence: "
                                    + "outside the run directory they are missed by backup/cleanup, and a non-writable target makes the JVM "
                                    + "drop that log configuration (or refuse to start) at launch.",
                            gcLogFix);
                    break;
                }
            }
        }

        // 5. 缺 -XX:+HeapDumpOnOutOfMemoryError。这不是性能项而是取证项：OOM 只发生一次，
        //    没有那个开关，之后永远没有堆快照来回答「谁在占内存 / 是泄漏还是不够」。
        if (!heapDumpOnOom) {
            check(checks, "heapdump_on_oom_missing", "warn",
                    "-XX:+HeapDumpOnOutOfMemoryError is not set: when the heap runs out the JVM prints one "
                            + "OutOfMemoryError stack and stops or stalls. No heap snapshot is written, so 'who was holding the memory' "
                            + "and 'leak or just too small' stay unanswerable.",
                    "-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=" + dumpFile);
        }

        // 6. -Xmx 超过物理内存 75%。堆上限只是「允许用多少」，真正提交时 OS 留给磁盘缓存/其它进程的
        //    内存就不够了，机器开始 swap：MSPT 从个位数跳到数百毫秒，而 JVM 内部指标全都健康。
        //    OperatingSystemMXBean 拿不到时跳过（不能拿 0 当分母算出个假百分比）。
        if (xmx == null) {
            check(checks, "xmx_missing", "info",
                    "-Xmx is not set, so max heap is the JVM default of 1/4 physical memory. That number silently differs "
                            + "per host, which makes 'works on my machine' heap problems unpredictable and hard to reproduce.",
                    "-Xmx" + sizeArg(suggestXmx(physical)) + " -Xms" + sizeArg(suggestXmx(physical))
                            + " (pin both so the heap is explicit and identical across hosts)");
        } else if (physical > 0 && xmx > physical * XMX_PHYSICAL_WARN_RATIO) {
            check(checks, "xmx_over_75pct_physical", "warn",
                    String.format(Locale.ROOT,
                            "-Xmx=%s is %.1f%% of physical memory (%s), above the %.0f%% safety line. Once the heap is actually "
                                    + "used the OS has no room left for disk cache and other processes: the machine starts swapping and every "
                                    + "tick stalls for hundreds of ms while the JVM's own metrics look fine.",
                            human(xmx), xmx * 100.0 / physical, human(physical), XMX_PHYSICAL_WARN_RATIO * 100.0),
                    "-Xmx" + sizeArg(suggestXmx(physical)) + " (about 60% of physical memory) and set -Xms to the same value. "
                            + "A larger -Xmx only moves the shortage from the heap to the host");
        }

        data.add("checks", checks);

        // ok 只看 error/warn：info 是「可以更安全」的建议，不该让体检结果变红，否则一条 xlog 缺失
        // 就能把真正的 P0（堆/元空间配错）淹掉。
        boolean ok = true;
        for (int i = 0; i < checks.size(); i++) {
            String severity = checks.get(i).getAsJsonObject().get("severity").getAsString();
            if ("error".equals(severity) || "warn".equals(severity)) {
                ok = false;
                break;
            }
        }
        data.addProperty("ok", ok);

        JsonArray notes = new JsonArray();
        notes.add("-Xlog and -XX:HeapDumpPath are startup-time settings: editing the launch script while the server runs "
                + "changes nothing, and everything here is read back from the arguments this process was started with");
        notes.add("-XX:HeapDumpPath only names the target; the JVM does not create missing directories, so create the "
                + "logs directory before relying on it");
        notes.add("heap bytes are parsed from the literal launch args (k/m/g suffixes, a bare number is bytes); an absent "
                + "-Xmx reports null instead of guessing the JVM default (the effective max is on /memory as heap.max_bytes)");
        data.add("notes", notes);
        return data;
    }

    /** 解析 JVM 的 size 语法：整数 + 可选 k/m/g/t 后缀（大小写均可），裸数字按字节。解析不了给 null，不猜。 */
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
                // 没有后缀：JVM 把整个数字当字节数。
            }
        }
        if (multiplier != 1L) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            long number = Long.parseLong(value.trim());
            // 0 表示「没有上限」（MaxMetaspaceSize 的 -1/0 语义），按未配置处理，否则会算出 0 字节的假上限。
            return number <= 0 ? null : number * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** gc 字段：家族名给一个稳定可判定的值，collectors 保留 MXBean 原始名（agent 要看细节时用）。 */
    private static JsonObject gc() {
        JsonArray collectors = new JsonArray();
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = bean.getName();
            names.add(name);
            collectors.add(name);
        }
        JsonObject gc = new JsonObject();
        gc.addProperty("name", gcFamily(names));
        gc.add("collectors", collectors);
        return gc;
    }

    /** 把 HotSpot 的组合名（"G1 Young Generation"、"PS MarkSweep"…）归到一个家族名，避免调用方要背命名。 */
    private static String gcFamily(List<String> names) {
        String all = String.join(" ", names).toLowerCase(Locale.ROOT);
        if (all.contains("g1")) {
            return "g1";
        }
        if (all.contains("zgc")) {
            return "zgc";
        }
        if (all.contains("shenandoah")) {
            return "shenandoah";
        }
        if (all.contains("epsilon")) {
            return "epsilon";
        }
        if (all.contains("ps ") || all.contains("parallel")) {
            return "parallel";
        }
        if (all.contains("cms") || all.contains("concurrent mark sweep")) {
            return "cms";
        }
        if (all.contains("copy") || all.contains("mark sweep compact") || all.contains("marksweepcompact")) {
            return "serial";
        }
        return names.isEmpty() ? "unknown" : String.join(", ", names);
    }

    /**
     * 从 {@code -Xlog:gc*:file=<path>:time,...} 里抠出 file= 的值。
     *
     * <p>不能直接 {@code split(":")}：选项之间用冒号分隔，但 Windows 绝对路径的盘符
     * （{@code file=D:\logs\gc.log}）也是冒号。这里只在「不是开头盘符」的冒号处截断，
     * 否则 {@code D:\logs\gc.log} 会被截成 {@code D}，然后把一条正常的配置误报成「不在 gameDir 下」。
     */
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
     * 参数里的路径是否落在 gameDir 之下。
     *
     * <p>相对路径按<b>进程工作目录</b>解析而不是 gameDir——这是本类要抓的重点：启动脚本里写
     * {@code -XX:HeapDumpPath=logs/heapdump.hprof} 看起来像在 run 目录下，实际落在启动器的当前目录，
     * 事后在 run/logs 里当然找不到。解析不了的路径（非法字符等）一律按「不在」处理。
     */
    private static boolean underGameDir(String raw, Path gameDir, Path cwd) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            Path path = Paths.get(value);
            Path absolute = path.isAbsolute() ? path : cwd.resolve(path);
            return absolute.normalize().startsWith(gameDir);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 游戏目录：dump/gc 日志要落在「和存档一起被备份/清理」的地方，而不是 JVM 的工作目录。 */
    private static Path gameDir() {
        try {
            Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            if (dir != null) {
                return dir.toAbsolutePath().normalize();
            }
        } catch (Throwable ignored) {
            // 单测/静态初始化场景下没有 FML 环境，退回当前目录（和 SystemStats 的做法一致）。
        }
        return cwd();
    }

    private static Path cwd() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static OperatingSystemMXBean osBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof OperatingSystemMXBean sun ? sun : null;
    }

    /** 建议的 -Xmx：物理内存的 60% 向下取整到 1 GiB（JVM 不接受小数，只能给整 G 的具体数字）。 */
    private static long suggestXmx(long physicalBytes) {
        if (physicalBytes <= 0) {
            return 4 * GIB;
        }
        long target = (long) (physicalBytes * XMX_SUGGEST_RATIO);
        return Math.max(GIB, (target / GIB) * GIB);
    }

    /** 把字节数写成可直接粘进 fix 的 JVM 参数值：能整除就给 G/M 后缀，否则给字节。 */
    private static String sizeArg(long bytes) {
        if (bytes >= GIB && bytes % GIB == 0) {
            return bytes / GIB + "G";
        }
        if (bytes >= MIB && bytes % MIB == 0) {
            return bytes / MIB + "M";
        }
        return Long.toString(bytes);
    }

    /** 给 message 用的可读大小；和 sizeArg 分开，因为一句话里 4.0 GB 比 4G 更好读。 */
    private static String human(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) GIB);
        }
        if (bytes >= MIB) {
            return String.format(Locale.ROOT, "%.0f MB", bytes / (double) MIB);
        }
        return bytes + " B";
    }

    private static void addNullable(JsonObject object, String key, Long value) {
        object.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    private static void check(JsonArray checks, String id, String severity, String message, String fix) {
        JsonObject check = new JsonObject();
        check.addProperty("id", id);
        check.addProperty("severity", severity);
        check.addProperty("message", message);
        check.addProperty("fix", fix);
        checks.add(check);
    }
}
