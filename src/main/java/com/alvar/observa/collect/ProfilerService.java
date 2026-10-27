package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.alvar.observa.config.ObservaConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采样剖析器：以固定间隔抓「服务器线程」的调用栈，聚合出自耗时与调用树。
 *
 * <p>为什么只抓服务器线程：{@code getAllStackTraces()} 每次要暂停所有线程（毫秒级），
 * 在 100Hz 采样下会自己制造卡顿。{@code getThreadInfo(id, depth)} 只读一个线程，
 * 开销足够低，才能做到"边跑边测而不干扰被测对象"。
 *
 * <p>两条统计纪律，否则报告会骗人：
 * <ol>
 *   <li><b>空闲样本不计入分母</b>：服务器大部分时间停在 {@code Unsafe.park} 等下一 tick，
 *       把它算进去会让 Top-1 永远是 park，真正的热点被稀释到 1% 以下。</li>
 *   <li><b>同一帧在一次采样里只算一次 total</b>：递归方法会在栈里出现多次，
 *       不去重会算出 119% 这种不可能的占比。</li>
 * </ol>
 */
public final class ProfilerService {

    private static final int MAX_TOP = 25;
    private static final int MAX_LINE_HITS = 15;
    private static final int MAX_TREE_NODES = 100;
    private static final int MAX_TREE_DEPTH = 40;
    private static final int MAX_HISTORY = 5;
    /** 树里低于这个占比的叶子会被剪掉，避免把节点预算花在 1% 以下的噪声上。 */
    private static final double TREE_MIN_PCT = 3.0;
    /** 只保留占比够大的根：真正被截断的浅栈会制造一堆 0.x% 的假根。 */
    private static final double TREE_ROOT_MIN_PCT = 5.0;

    private static volatile ProfilerService instance;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "observa-profiler");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Report> history = new LinkedHashMap<>();
    private final Map<String, Report> results = new HashMap<>();
    private final Set<String> seenFrames = new HashSet<>();

    private volatile long serverThreadId = -1L;
    private volatile String serverThreadName = "?";
    private volatile Session session;
    private int nextId = 1;

    public ProfilerService() {
        instance = this;
    }

    /** 由 tick 钩子调用：记下真正的服务器线程 id（集成服务器也叫 "Server thread"）。 */
    public static void captureServerThread() {
        ProfilerService s = instance;
        if (s == null || s.serverThreadId > 0) {
            return;
        }
        Thread current = Thread.currentThread();
        if (current.getName().contains("Server thread") || current.getName().startsWith("Server")) {
            s.serverThreadId = current.threadId();
            s.serverThreadName = current.getName();
        }
    }

    public static ProfilerService get() {
        return instance;
    }

    /** 一次录制会话：只在采样线程里写，stop 后只读。 */
    private static final class Session {
        final String id;
        final long startedAtMs = System.currentTimeMillis();
        final long durationMs;
        final int intervalMs;
        volatile ScheduledFuture<?> sampler;
        volatile ScheduledFuture<?> watchdog;
        volatile boolean finished;

        final Map<String, AtomicLong> self = new HashMap<>();
        final Map<String, AtomicLong> total = new HashMap<>();
        final Map<String, Map<String, AtomicLong>> parentOf = new HashMap<>();
        /** frame → (行号 → 次数)。line 为 -1 的样本单独记，最后只报最常见的那个已知行号。 */
        final Map<String, Map<Integer, AtomicLong>> selfLines = new HashMap<>();
        final AtomicLong samples = new AtomicLong();
        final AtomicLong idleSamples = new AtomicLong();

        Session(String id, long durationMs, int intervalMs) {
            this.id = id;
            this.durationMs = durationMs;
            this.intervalMs = intervalMs;
        }
    }

    public record Status(String id, long elapsedMs, long remainingMs, long samples, int intervalMs) {
    }

    // ------------------------------------------------------------------ 控制

    public synchronized Status start(long seconds, int intervalMs) {
        if (session != null) {
            throw new IllegalStateException("a recording is already running: " + session.id);
        }
        if (serverThreadId <= 0) {
            throw new IllegalStateException("no server thread has ticked yet; start the server first");
        }
        int maxSeconds = ObservaConfig.PROFILER_MAX_SECONDS.get();
        long clampedSeconds = Math.max(1, Math.min(maxSeconds, seconds));
        int clampedInterval = Math.max(1, Math.min(100, intervalMs));

        Session s = new Session("sample-" + nextId++, clampedSeconds * 1000L, clampedInterval);
        session = s;
        s.sampler = scheduler.scheduleAtFixedRate(() -> sample(s), 0, clampedInterval, TimeUnit.MILLISECONDS);
        s.watchdog = scheduler.schedule(() -> finish(s), clampedSeconds, TimeUnit.SECONDS);
        Observa.LOGGER.info("[observa] profiler {} started: {}s @ {}ms on thread {}",
                s.id, clampedSeconds, clampedInterval, serverThreadName);
        return status();
    }

    public Status status() {
        Session s = session;
        if (s == null) {
            return null;
        }
        long elapsed = System.currentTimeMillis() - s.startedAtMs;
        return new Status(s.id, elapsed, Math.max(0, s.durationMs - elapsed), s.samples.get(), s.intervalMs);
    }

    public Report stop() {
        Session s = session;
        return s == null ? null : finish(s);
    }

    public Report report(String id) {
        synchronized (history) {
            return history.get(id);
        }
    }

    public List<String> recentIds() {
        synchronized (history) {
            return new ArrayList<>(history.keySet());
        }
    }

    private synchronized Report finish(Session s) {
        if (s.finished) {
            synchronized (history) {
                return history.get(s.id);
            }
        }
        s.finished = true;
        cancel(s.sampler);
        cancel(s.watchdog);
        Report report = buildReport(s);
        synchronized (history) {
            results.put(s.id, report);
            history.put(s.id, report);
            while (history.size() > MAX_HISTORY) {
                String oldest = history.keySet().iterator().next();
                history.remove(oldest);
                results.remove(oldest);
            }
        }
        session = null;
        Observa.LOGGER.info("[observa] profiler {} finished: {} samples ({} idle) over {}ms",
                s.id, report.samples(), report.idleSamples(), report.durationMs());
        return report;
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    // ------------------------------------------------------------------ 采样

    private void sample(Session s) {
        if (s.finished) {
            return;
        }
        try {
            ThreadInfo info = threads.getThreadInfo(serverThreadId, ObservaConfig.PROFILER_SAMPLE_DEPTH.get());
            if (info == null) {
                return;
            }
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length == 0) {
                return;
            }
            s.samples.incrementAndGet();

            // 空闲判定不能只看叶子帧：JIT 会把 park/onSpinWait 内联掉，叶子会变成
            // BlockableEventLoop.waitForTasks 本身，于是"等下一 tick"被算成热点（曾占 71%）。
            // 可靠判据是语义的：栈里根本没有 tickServer ⇒ 这一拍不在 tick 里干活。
            if (!insideTick(stack)) {
                s.idleSamples.incrementAndGet();
                return;
            }

            boolean includeJdk = ObservaConfig.PROFILER_INCLUDE_JDK_FRAMES.get();
            seenFrames.clear();
            String previous = null;
            boolean leaf = true;
            for (StackTraceElement element : stack) {
                // 必须用原始类名判断 JDK 帧：frameName 剥掉了包名，
                // "java.lang.invoke.DirectMethodHandle$Holder" 会变成
                // "DirectMethodHandle$Holder"，前缀匹配永远失败（踩过）。
                if (!includeJdk && isJdkClass(element.getClassName())) {
                    continue;
                }
                String frame = frameName(element);
                // lambda 帧要留着（服务器线程最外层就是 MinecraftServer.lambda$spin$2，
                // 去掉它整棵树就没有根了），但不能留内存地址：名字见 frameName。
                if (seenFrames.add(frame)) {
                    s.total.computeIfAbsent(frame, k -> new AtomicLong()).incrementAndGet();
                }
                if (leaf) {
                    s.self.computeIfAbsent(frame, k -> new AtomicLong()).incrementAndGet();
                    // 行号只在 self 归属的帧上有意义：调用者那一层的行号是"调用发生在哪行"，
                    // 对热点定位没有用，反而会让 agent 以为是那一行在烧 CPU。
                    int line = element.getLineNumber();
                    if (line > 0) {
                        s.selfLines.computeIfAbsent(frame, k -> new HashMap<>())
                                .computeIfAbsent(line, k -> new AtomicLong())
                                .incrementAndGet();
                    }
                    leaf = false;
                }
                if (previous != null) {
                    // 栈是从内到外遍历的：previous 更靠内（被调用者），frame 是调用者。
                    // 边必须记成 parent=frame → child=previous，反过来整棵树会上下颠倒
                    // （症状：根节点全是叶子方法，真正的入口 spin 反而挂在别人下面）。
                    s.parentOf.computeIfAbsent(frame, k -> new HashMap<>())
                            .computeIfAbsent(previous, k -> new AtomicLong())
                            .incrementAndGet();
                }
                previous = frame;
            }
        } catch (Throwable t) {
            // 采样失败绝不能杀掉调度线程。
            Observa.LOGGER.warn("[observa] sampler step failed", t);
        }
    }

    private static String frameName(StackTraceElement element) {
        String cls = element.getClassName();
        // lambda 类名带每次启动都不同的内存地址（...$$Lambda$12/0x0000020a8309c400），
        // 不剥掉的话两份报告同一方法会对不上，agent 也没法跨次比较。
        int slash = cls.indexOf("/0x");
        if (slash > 0) {
            cls = cls.substring(0, slash);
        }
        int dot = cls.lastIndexOf('.');
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + "." + element.getMethodName();
    }

    /** tickServer 在栈里 ⇒ 这一拍确实在跑世界逻辑；不在 ⇒ 在等下一 tick 或忙别的事。 */
    private static boolean insideTick(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            String cls = element.getClassName();
            if (cls.endsWith("MinecraftServer") && element.getMethodName().equals("tickServer")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkClass(String cls) {
        return cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.")
                || cls.startsWith("sun.") || cls.startsWith("com.sun.");
    }

    // ------------------------------------------------------------------ 报告

    public record Frame(String name, long selfSamples, long totalSamples) {
    }

    /** 行号级热点：某个类的某一行自己烧了多少样本。 */
    public record LineHit(String frame, int line, long selfSamples) {
    }

    public record TreeNode(String name, long selfSamples, long totalSamples, List<TreeNode> children) {
    }

    public record Report(
            String id,
            long startedAtMs,
            long durationMs,
            int intervalMs,
            long samples,
            long idleSamples,
            String thread,
            List<Frame> top,
            List<LineHit> lines,
            List<TreeNode> tree,
            boolean topTruncated,
            boolean treeTruncated) {
    }

    private Report buildReport(Session s) {
        long totalSamples = s.samples.get();
        long idleSamples = s.idleSamples.get();
        long busySamples = Math.max(1L, totalSamples - idleSamples);

        List<Frame> frames = new ArrayList<>(s.self.size());
        for (Map.Entry<String, AtomicLong> entry : s.self.entrySet()) {
            String name = entry.getKey();
            long self = entry.getValue().get();
            long total = s.total.getOrDefault(name, new AtomicLong()).get();
            // 只用忙碌样本当分母，否则每个帧的占比都会被 park 稀释。
            frames.add(new Frame(name, Math.min(self, busySamples), Math.min(total, busySamples)));
        }
        frames.sort(Comparator.comparingLong(Frame::selfSamples).reversed());
        boolean topTruncated = frames.size() > MAX_TOP;
        List<Frame> top = List.copyOf(frames.subList(0, Math.min(MAX_TOP, frames.size())));

        // 行号级热点：取 self 样本最多的 15 个 (帧, 行) 组合，按 self 降序。
        // 只保留 top 帧里的行，避免把「只出现一次的某行」也端上来。
        java.util.Set<String> topNames = new java.util.HashSet<>();
        for (Frame frame : top) {
            topNames.add(frame.name());
        }
        List<LineHit> lines = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, AtomicLong>> entry : s.selfLines.entrySet()) {
            if (!topNames.contains(entry.getKey())) {
                continue;
            }
            for (Map.Entry<Integer, AtomicLong> line : entry.getValue().entrySet()) {
                long count = Math.min(line.getValue().get(), busySamples);
                if (count > 0) {
                    lines.add(new LineHit(entry.getKey(), line.getKey(), count));
                }
            }
        }
        lines.sort(Comparator.comparingLong(LineHit::selfSamples).reversed());
        if (lines.size() > MAX_LINE_HITS) {
            lines = new ArrayList<>(lines.subList(0, MAX_LINE_HITS));
        }

        // 树里的祖先帧（如 runServer）往往没有 self 样本，但它的 total 必须是对的，
        // 否则根节点会显示 0.0%（之前就是这样）。
        Map<String, Long> selfByName = new HashMap<>();
        Map<String, Long> totalByName = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : s.self.entrySet()) {
            selfByName.put(entry.getKey(), Math.min(entry.getValue().get(), busySamples));
        }
        for (Map.Entry<String, AtomicLong> entry : s.total.entrySet()) {
            totalByName.put(entry.getKey(), Math.min(entry.getValue().get(), busySamples));
        }

        // 从"没有任何父帧"的帧开始展开真正的调用树（多数采样里就是 runServer）。
        // 比"每个热点各自回溯众数父链"可靠：同一方法不会因为父路径不同而碎成多个根。
        Set<String> children = new HashSet<>();
        for (Map<String, AtomicLong> out : s.parentOf.values()) {
            children.addAll(out.keySet());
        }
        List<String> roots = new ArrayList<>();
        for (String frame : totalByName.keySet()) {
            if (!children.contains(frame)) {
                roots.add(frame);
            }
        }
        roots.sort(Comparator.comparingLong((String r) -> totalByName.getOrDefault(r, 0L)).reversed());

        double minTree = Math.max(1.0, busySamples * TREE_MIN_PCT / 100.0);
        double minRoot = Math.max(1.0, busySamples * TREE_ROOT_MIN_PCT / 100.0);
        // 先按"大预算"把整棵树建出来，再剪成只保留通往热点帧的路径。
        // 直接建小树（旧做法）会把预算花在没人关心的分叉上，反而把真正的热链截断——
        // 实测 Top-1 出现过"在 top 里但树里找不到"的情况。
        int[] budget = {MAX_TREE_NODES * 40};
        boolean[] truncated = {false};
        List<TreeNode> full = new ArrayList<>();
        for (String root : roots) {
            if (budget[0] <= 0) {
                truncated[0] = true;
                break;
            }
            if (totalByName.getOrDefault(root, 0L) < minRoot) {
                continue;
            }
            full.add(buildNode(root, s.parentOf, selfByName, totalByName, minTree, budget, truncated, 0, new HashSet<>()));
        }
        if (full.isEmpty() && !roots.isEmpty()) {
            // 极端情况下（所有根都很小）至少给一个，别返回空树。
            full.add(buildNode(roots.get(0), s.parentOf, selfByName, totalByName, minTree, budget, truncated, 0, new HashSet<>()));
        }

        // top 已经告诉调用方"哪里热"，树的价值只剩"它是怎么被调到的"。
        // 所以只留下通往 top 帧的路径，其余分叉整枝剪掉：报告体积从 12 KB 降到 3 KB 量级。
        Set<String> hot = new HashSet<>();
        for (Frame frame : top) {
            hot.add(frame.name());
        }
        int[] kept = {MAX_TREE_NODES};
        List<TreeNode> tree = new ArrayList<>();
        for (TreeNode node : full) {
            TreeNode pruned = pruneToHot(node, hot, kept);
            if (pruned != null) {
                tree.add(pruned);
            }
        }
        if (kept[0] <= 0) {
            truncated[0] = true;
        }
        boolean treeTruncated = truncated[0];

        return new Report(s.id, s.startedAtMs, System.currentTimeMillis() - s.startedAtMs,
                s.intervalMs, totalSamples, idleSamples, serverThreadName, top, lines, tree,
                topTruncated, treeTruncated);
    }

    /**
     * 剪枝：不通往任何热点帧的分支整枝剪掉；热点帧自身的子树保留（说明它把时间花在哪儿）。
     * 这样树的节点数只和「热点有几条调用路径」有关，不再随无关分叉膨胀。
     */
    private static TreeNode pruneToHot(TreeNode node, Set<String> hot, int[] kept) {
        if (kept[0] <= 0) {
            return null;
        }
        if (hot.contains(node.name())) {
            kept[0]--;
            return keepSubtree(node, kept);
        }
        List<TreeNode> children = new ArrayList<>();
        for (TreeNode child : node.children()) {
            TreeNode pruned = pruneToHot(child, hot, kept);
            if (pruned != null) {
                children.add(pruned);
            }
        }
        if (children.isEmpty()) {
            return null;
        }
        kept[0]--;
        return new TreeNode(node.name(), node.selfSamples(), node.totalSamples(), children);
    }

    private static TreeNode keepSubtree(TreeNode node, int[] kept) {
        List<TreeNode> children = new ArrayList<>();
        for (TreeNode child : node.children()) {
            if (kept[0] <= 0) {
                return new TreeNode(node.name(), node.selfSamples(), node.totalSamples(), children);
            }
            kept[0]--;
            children.add(keepSubtree(child, kept));
        }
        return new TreeNode(node.name(), node.selfSamples(), node.totalSamples(), children);
    }

    private static TreeNode buildNode(String name, Map<String, Map<String, AtomicLong>> parentOf,
            Map<String, Long> self, Map<String, Long> total, double minSamples, int[] budget,
            boolean[] truncated, int depth, Set<String> ancestors) {
        budget[0]--;
        TreeNode node = new TreeNode(name, self.getOrDefault(name, 0L), total.getOrDefault(name, 0L), new ArrayList<>());
        // 深度上限 + 递归保护：真实代码有递归帧（如 PathFinder.explore），不加会无限展开。
        if (depth >= MAX_TREE_DEPTH || budget[0] <= 0 || !ancestors.add(name)) {
            // 只有"还有够大的子帧没展开"才算真的被截断，否则 prune 掉的噪声不该报 truncated。
            boolean cut = parentOf.getOrDefault(name, Map.of()).values().stream()
                    .anyMatch(count -> count.get() >= minSamples);
            if (cut && budget[0] > 0) {
                truncated[0] = true;
            }
            return node;
        }
        Map<String, AtomicLong> out = parentOf.getOrDefault(name, Map.of());
        List<Map.Entry<String, AtomicLong>> kids = new ArrayList<>(out.entrySet());
        kids.sort(Map.Entry.<String, AtomicLong>comparingByValue(
                Comparator.comparingLong(AtomicLong::get)).reversed());
        for (Map.Entry<String, AtomicLong> kid : kids) {
            if (budget[0] <= 0) {
                truncated[0] = true;
                break;
            }
            if (kid.getValue().get() < minSamples) {
                continue;
            }
            node.children().add(buildNode(kid.getKey(), parentOf, self, total, minSamples, budget,
                    truncated, depth + 1, ancestors));
        }
        ancestors.remove(name);
        return node;
    }
}
