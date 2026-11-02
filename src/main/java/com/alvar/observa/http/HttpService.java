package com.alvar.observa.http;

import com.alvar.observa.Observa;
import com.alvar.observa.api.Snapshot;
import com.alvar.observa.blackbox.Blackbox;
import com.alvar.observa.blackbox.LogWatch;
import com.alvar.observa.collect.AllocLive;
import com.alvar.observa.collect.JvmFlags;
import com.alvar.observa.collect.MetricsService;
import com.alvar.observa.collect.HeapService;
import com.alvar.observa.collect.JfrService;
import com.alvar.observa.collect.NetService;
import com.alvar.observa.collect.PauseState;
import com.alvar.observa.collect.ProfilerService;
import com.alvar.observa.collect.RetainedService;
import com.alvar.observa.collect.SystemStats;
import com.alvar.observa.config.ObservaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地观测接口。生命周期挂在服务器启停上：{@code ServerStartedEvent} 起、{@code ServerStoppingEvent} 停。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>只绑 {@code 127.0.0.1}（不读配置，避免被改成 0.0.0.0 暴露到公网）；</li>
 *   <li>所有端点都要 Bearer token；</li>
 *   <li>HTTP 处理线程<b>不得</b>直接读游戏状态——只能读已发布的不可变快照。</li>
 * </ol>
 */
public final class HttpService implements AutoCloseable {

    public static final String PREFIX = "/observa/v1";
    private static final String BIND_ADDRESS = "127.0.0.1";
    private static final int MAX_PORT_ATTEMPTS = 10;

    private static HttpService instance;

    private final HttpServer server;
    private final ExecutorService pool;
    private final String token;
    private final int port;
    private final long startedAtMs = System.currentTimeMillis();
    private final AtomicLong rateSecond = new AtomicLong(-1L);
    private final AtomicLong rateCount = new AtomicLong();
    private final Map<String, Route> routes = new LinkedHashMap<>();

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Route(String method, Handler handler) {
    }

    private HttpService(HttpServer server, ExecutorService pool, String token, int port) {
        this.server = server;
        this.pool = pool;
        this.token = token;
        this.port = port;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 幂等启动；端口被占时向上重试。失败只记日志，不影响服务器启动。 */
    public static synchronized HttpService start() {
        if (instance != null) {
            return instance;
        }
        if (!ObservaConfig.ENABLED.get()) {
            Observa.LOGGER.info("[observa] http endpoint disabled by config");
            return null;
        }

        final String token = resolveToken();
        final int basePort = ObservaConfig.PORT.get();

        IOException lastBindError = null;
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int candidate = basePort + attempt;
            try {
                HttpServer httpServer = HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName(BIND_ADDRESS), candidate), 8);
                ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "observa-http");
                    t.setDaemon(true);
                    return t;
                });
                HttpService service = new HttpService(httpServer, pool, token, candidate);
                service.wire();
                httpServer.setExecutor(pool);
                httpServer.start();
                instance = service;
                if (attempt > 0) {
                    Observa.LOGGER.warn("[observa] port {} was busy, bound {} instead", basePort, candidate);
                }
                Observa.LOGGER.info("[observa] listening on http://{}:{}{}/manifest", BIND_ADDRESS, candidate, PREFIX);
                // 跟随 logs/latest.log：/logs 与黑匣子的日志尾巴都从这里来。
                LogWatch.instance().start(FMLPaths.GAMEDIR.get().resolve("logs"));
                // 把「agent 入职说明」落到磁盘并在日志里打印可点链接。
                Brief.publish(candidate);
                return service;
            } catch (IOException e) {
                lastBindError = e;
            }
        }
        Observa.LOGGER.error("[observa] could not bind any port in {}..{} — endpoint stays off",
                basePort, basePort + MAX_PORT_ATTEMPTS - 1, lastBindError);
        return null;
    }

    /** 当前绑定的端口（没启动时 0）。隧道响应里会带上它，方便区分「本机 HTTP」与「经隧道」。 */
    public static int boundPort() {
        HttpService service = instance;
        return service == null ? 0 : service.port;
    }

    public static synchronized void stop() {
        if (instance == null) {
            return;
        }
        try {
            instance.close();
        } finally {
            instance = null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
        Observa.LOGGER.info("[observa] http endpoint on port {} stopped", port);
    }

    // ------------------------------------------------------------------ 路由

    private void wire() {
        // 单一 context + 手工路由：未匹配路径也能返回结构化 404，而不是默认的 HTML。
        server.createContext(PREFIX, this::dispatch);

        route("/manifest", new Route("GET", this::handleManifest));
        route("/health", new Route("GET", this::handleHealth));
        route("/metrics", new Route("GET", this::handleMetrics));
        route("/entities", new Route("GET", this::handleEntities));
        route("/memory", new Route("GET", this::handleMemory));
        route("/system", new Route("GET", this::handleSystem));
        route("/jvm/flags", new Route("GET", this::handleJvmFlags));
        route("/network", new Route("GET", this::handleNetwork));
        route("/heap/histogram", new Route("GET", this::handleHeapHistogram));
        route("/heap/histogram/start", new Route("POST", this::handleHeapStart));
        route("/heap/histogram/compare", new Route("GET", this::handleHeapCompare));
        route("/heap/retained", new Route("GET", this::handleRetained));
        route("/heap/retained/start", new Route("POST", this::handleRetainedStart));
        route("/heap/dumps", new Route("GET", this::handleHeapDumps));
        route("/heap/alloc-live", new Route("GET", this::handleAllocLive));
        route("/heap/alloc-live/start", new Route("POST", this::handleAllocLiveStart));
        route("/chunks", new Route("GET", this::handleChunks));
        route("/snapshot", new Route("GET", this::handleSnapshot));
        route("/session/export", new Route("GET", this::handleSessionExport));
        route("/profiler/start", new Route("POST", this::handleProfilerStart));
        route("/profiler/stop", new Route("POST", this::handleProfilerStop));
        route("/profiler/status", new Route("GET", this::handleProfilerStatus));
        route("/logs", new Route("GET", this::handleLogs));
        route("/blackbox", new Route("GET", this::handleBlackbox));
        route("/dump", new Route("POST", this::handleDump));
        // 自测：只有 debug.allow_selftest=true 时才可用，用来验证「服务器卡死」时黑匣子仍然工作。
        route("/selftest/stall", new Route("POST", this::handleSelfTestStall));
        route("/selftest/pause", new Route("POST", this::handleSelfTestPause));
        // 入职说明：Markdown 给 agent 读，JSON 给程序读；内容一致（见 Brief）。
        route("/brief.md", new Route("GET", this::handleBriefMarkdown));
        route("/brief", new Route("GET", this::handleBrief));
    }

    private void route(String path, Route route) {
        routes.put(path, route);
    }

    private void dispatch(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        try {
            if (!authorized(exchange)) {
                Json.sendError(exchange, 401, "unauthorized", "missing or invalid bearer token",
                        "token lives in config/observa/token.txt (or key http.token in config/observa-common.toml)",
                        "onboarding? read <gameDir>/observa/agent.md — it lists the token path and every endpoint",
                        "send header: Authorization: Bearer <token>");
                return;
            }
            if (!allowRequest()) {
                Json.sendError(exchange, 429, "rate_limited",
                        "global request limit is " + ObservaConfig.RATE_LIMIT_PER_SECOND.get() + " per second",
                        "wait about 1s and retry; batch reads via /snapshot");
                return;
            }

            String sub = path.substring(PREFIX.length());
            if (sub.isEmpty() || "/".equals(sub)) {
                sub = "/manifest";
            }
            if (sub.startsWith("/profiler/report/")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    Json.sendError(exchange, 405, "method_not_allowed",
                            "GET expected, got " + exchange.getRequestMethod(),
                            "GET " + PREFIX + "/profiler/report/{id}");
                    return;
                }
                handleProfilerReport(exchange, sub.substring("/profiler/report/".length()));
                return;
            }
            Route target = routes.get(sub);
            if (target == null) {
                Json.sendError(exchange, 404, "not_found", "unknown path: " + path,
                        "GET " + PREFIX + "/manifest lists every endpoint");
                return;
            }
            if (!target.method().equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", target.method());
                Json.sendError(exchange, 405, "method_not_allowed",
                        target.method() + " expected, got " + exchange.getRequestMethod(),
                        target.method() + " " + PREFIX + sub);
                return;
            }
            target.handler().handle(exchange);
        } catch (Throwable t) {
            Observa.LOGGER.error("[observa] handler failed for {}", path, t);
            try {
                Json.sendError(exchange, 500, "internal_error", String.valueOf(t),
                        "this is a bug in observa; check the server log for the stack trace");
            } catch (Throwable ignored) {
                // 响应头可能已发出，此时只能放弃——日志里已有栈。
            }
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ 端点

    private void handleBriefMarkdown(HttpExchange exchange) throws IOException {
        Json.sendText(exchange, 200, "text/markdown", Brief.markdown(port));
    }

    private void handleBrief(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, Brief.json(port));
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, manifestJson());
    }

    /** /manifest 的内容本体；/session/export 也用它，免得两处的字段慢慢跑偏。 */
    private JsonObject manifestJson() {
        JsonObject data = new JsonObject();

        JsonObject mod = new JsonObject();
        mod.addProperty("id", Observa.MODID);
        mod.addProperty("version", Observa.modVersion());
        data.add("mod", mod);

        JsonObject runtime = new JsonObject();
        runtime.addProperty("platform", "neoforge");
        runtime.addProperty("java_version", System.getProperty("java.version"));
        runtime.add("java_vendor", new com.google.gson.JsonPrimitive(System.getProperty("java.vendor")));
        data.add("runtime", runtime);

        data.add("endpoints", endpointList());
        data.add("limits", limitsObject());
        data.add("started_at", new com.google.gson.JsonPrimitive(Instant.ofEpochMilli(startedAtMs).toString()));
        data.addProperty("port", port);

        JsonObject auth = new JsonObject();
        auth.addProperty("scheme", "Bearer");
        auth.addProperty("header", "Authorization");
        auth.addProperty("token_source", "config/observa/token.txt");
        data.add("auth", auth);

        JsonArray rules = new JsonArray();
        rules.add("loopback only: the socket is bound to 127.0.0.1 and never to 0.0.0.0");
        rules.add("read-only: no endpoint mutates world or player state");
        rules.add("units are part of the field name (_ms, _bytes, _per_s)");
        rules.add("null means not collected yet; never assume 0");
        rules.add("top-N is computed server-side; pass top=N to change size");
        data.add("conventions", rules);
        return data;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("status", "ok");
        data.addProperty("via", "direct");
        data.addProperty("uptime_ms", System.currentTimeMillis() - startedAtMs);
        data.addProperty("port", port);
        data.addProperty("schema_version", Json.SCHEMA_VERSION);
        data.addProperty("bind", BIND_ADDRESS);
        // 单机按 ESC 时集成服务器停 tick，数据会停在那一刻；这里明说，免得 agent 把
        // 「age_ms 一直涨」当成采集坏了。
        data.addProperty("paused", PauseState.isPaused());
        Json.sendOk(exchange, data);
    }

    // ---- 采集类端点 ----

    private void handleMetrics(HttpExchange exchange) throws IOException {
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        Integer window = queryInt(exchange, "window", 10, 1, 60);
        if (window == null || (window != 1 && window != 10 && window != 60)) {
            Json.sendError(exchange, 400, "bad_request", "window must be 1, 10 or 60",
                    "GET " + PREFIX + "/metrics?window=10");
            return;
        }
        JsonObject data = SnapshotJson.metrics(snapshot, window);
        if (PauseState.isPaused()) {
            // 快照是暂停前最后一 tick 的，age_ms 会持续变大——加上显式标记与说明。
            data.addProperty("paused", true);
            JsonArray notes = data.has("notes") ? data.getAsJsonArray("notes") : new JsonArray();
            notes.add("the integrated server is paused (ESC menu): metrics are frozen at the moment of the pause, "
                    + "age_ms grows until it resumes. This is not a collection failure.");
            data.add("notes", notes);
        }
        Json.sendOk(exchange, data);
    }

    private void handleEntities(HttpExchange exchange) throws IOException {
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        String by = queryParam(exchange, "by");
        if (by == null || by.isBlank()) {
            by = "type";
        }
        if (!by.equals("type") && !by.equals("dimension") && !by.equals("chunk")) {
            Json.sendError(exchange, 400, "bad_request", "by must be type, dimension or chunk",
                    "GET " + PREFIX + "/entities?by=type&top=10");
            return;
        }
        Integer top = queryInt(exchange, "top", ObservaConfig.TOP_N_DEFAULT.get(), 1, ObservaConfig.TOP_N_MAX.get());
        if (top == null) {
            Json.sendError(exchange, 400, "bad_request",
                    "top must be an integer between 1 and " + ObservaConfig.TOP_N_MAX.get(),
                    "GET " + PREFIX + "/entities?by=type&top=10");
            return;
        }
        Json.sendOk(exchange, SnapshotJson.entities(snapshot, by, top));
    }

    private void handleMemory(HttpExchange exchange) throws IOException {
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        Json.sendOk(exchange, SnapshotJson.memory(snapshot));
    }

    /** 机器层面的状态：CPU/物理内存/swap/磁盘。和堆无关，回答「是不是机器本身的问题」。 */
    private void handleSystem(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, SystemStats.system());
    }

    /** 每玩家 ping 与收发量：回答「是网络慢还是服务端慢」。 */
    private void handleNetwork(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, NetService.json());
    }

    /** 最近一次堆直方图的状态/结果。 */
    private void handleHeapHistogram(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, HeapService.instance().json());
    }

    /**
     * 触发一次 dump+解析。会 STW 一小段（本机满堆约 0.4 秒），所以是 POST 且异步返回；
     * 解析在独立线程，轮的节奏是几分钟一次，不是每秒一次。
     */
    private void handleHeapStart(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            Json.sendError(exchange, 400, "bad_request", "body must be a JSON object",
                    "POST " + PREFIX + "/heap/histogram/start with {\"live\":true}");
            return;
        }
        boolean live = !body.has("live") || body.get("live").getAsBoolean();
        boolean keepFile = body.has("keep_file") && body.get("keep_file").getAsBoolean();
        int topN = Math.max(1, Math.min(ObservaConfig.TOP_N_MAX.get(), 50));
        try {
            if (body.has("top")) {
                topN = Math.max(1, Math.min(200, body.get("top").getAsInt()));
            }
        } catch (RuntimeException e) {
            Json.sendError(exchange, 400, "bad_request", "top must be an integer",
                    "POST " + PREFIX + "/heap/histogram/start with {\"top\":20}");
            return;
        }
        if (HeapService.instance().running()) {
            Json.sendError(exchange, 409, "conflict", "a heap histogram is already running",
                    "GET " + PREFIX + "/heap/histogram for progress",
                    "wait for it to finish before starting another one (each dump stops the world briefly)");
            return;
        }
        if (RetainedService.instance().running()) {
            // 两个 dump 同时跑 = 两次 STW + 两份对象图同时驻留，峰值内存翻倍。
            Json.sendError(exchange, 409, "conflict", "a retained-size analysis is already running",
                    "GET " + PREFIX + "/heap/retained for progress",
                    "only one heap dump runs at a time: both of them stop the world and keep a graph in memory");
            return;
        }
        if (!HeapService.instance().start(live, keepFile, topN)) {
            Json.sendError(exchange, 409, "conflict", "a heap histogram is already running",
                    "GET " + PREFIX + "/heap/histogram for progress",
                    "wait for it to finish before starting another one (each dump stops the world briefly)");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("state", "running");
        data.addProperty("live", live);
        data.addProperty("keep_file", keepFile);
        data.addProperty("next", "poll GET " + PREFIX + "/heap/histogram in a few seconds");
        Json.send(exchange, 202, Json.ok(data));
    }

    /**
     * 两份直方图的差分：回答「什么在涨」。
     * 不传 from/to 时用最近两份（第二新作为基线），传同一个 id 回 400 —— 和自己比没有意义。
     */
    private void handleHeapCompare(HttpExchange exchange) throws IOException {
        String from = queryParam(exchange, "from");
        String to = queryParam(exchange, "to");
        Integer top = queryInt(exchange, "top", 20, 1, 200);
        if (top == null) {
            Json.sendError(exchange, 400, "bad_request", "top must be an integer between 1 and 200",
                    "GET " + PREFIX + "/heap/histogram/compare?top=20");
            return;
        }
        JsonObject data = HeapService.instance().compare(from, to, top);
        if (data == null) {
            Json.sendError(exchange, 400, "not_enough_history",
                    "compare needs two finished histograms that are not the same run",
                    "POST " + PREFIX + "/heap/histogram/start twice (a few minutes apart) then retry",
                    "GET " + PREFIX + "/heap/histogram lists recent_histograms with their ids",
                    "each dump stops the world briefly, so do not trigger them back to back");
            return;
        }
        Json.sendOk(exchange, data);
    }

    /**
     * 最近一次 retained size（支配树）分析的状态/结果。
     *
     * <p>和 /heap/histogram 的区别是这一份带引用关系：它回答「删掉谁能掉多少内存、
     * 是被谁持有的」，直方图只能回答「哪个类占多少浅字节」。</p>
     */
    private void handleRetained(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, RetainedService.instance().json());
    }

    /**
     * 触发一次 dump + 建图 + 支配树。比 /heap/histogram/start 更重（多一张对象图），
     * 所以同样异步返回，并且会和直方图 dump 互相避让。
     */
    private void handleRetainedStart(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            Json.sendError(exchange, 400, "bad_request", "body must be a JSON object",
                    "POST " + PREFIX + "/heap/retained/start with {\"live\":true}");
            return;
        }
        boolean live = !body.has("live") || body.get("live").getAsBoolean();
        int maxObjects = 0;
        if (body.has("max_objects")) {
            try {
                maxObjects = body.get("max_objects").getAsInt();
            } catch (RuntimeException e) {
                Json.sendError(exchange, 400, "bad_request", "max_objects must be a positive integer",
                        "POST " + PREFIX + "/heap/retained/start with {\"max_objects\":16000000}",
                        "it overrides the default object limit; the JVM heap still caps it at 40% of -Xmx");
                return;
            }
            if (maxObjects <= 0) {
                Json.sendError(exchange, 400, "bad_request", "max_objects must be greater than 0",
                        "omit it to use the default limit (see object_limit in GET " + PREFIX + "/heap/retained)");
                return;
            }
        }
        if (HeapService.instance().running()) {
            Json.sendError(exchange, 409, "conflict", "a heap histogram dump is already running",
                    "GET " + PREFIX + "/heap/histogram for progress, retry when it is done",
                    "two dumps at once would each stop the world for nothing");
            return;
        }
        if (!RetainedService.instance().start(live, false, maxObjects)) {
            Json.sendError(exchange, 409, "conflict", "a retained size analysis is already running",
                    "GET " + PREFIX + "/heap/retained for progress",
                    "wait for it to finish (it builds the whole object graph)");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("state", "running");
        data.addProperty("live", live);
        if (maxObjects > 0) {
            data.addProperty("max_objects", maxObjects);
        }
        data.addProperty("object_limit",
                com.alvar.observa.collect.HeapGraph.effectiveObjectLimit(maxObjects));
        data.addProperty("next", "poll GET " + PREFIX + "/heap/retained in a few seconds; if the dump is too big "
                + "the response carries too_large plus a next[] with the exact numbers to raise");
        Json.send(exchange, 202, Json.ok(data));
    }

    /**
     * 历次 dump 记录：每条带 dump_ms/parse_ms 和 impact（采集自身造成的 GC 次数与停顿）。
     *
     * <p>历史只在内存里，进程重启即清空；磁盘上的 .hprof 是另一回事，看 dir。</p>
     */
    private void handleHeapDumps(HttpExchange exchange) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("dir", HeapService.instance().dir().toString());
        List<HeapService.Job> jobs = HeapService.instance().history();
        data.addProperty("count", jobs.size());
        JsonArray arr = new JsonArray();
        for (HeapService.Job j : jobs) {
            JsonObject o = new JsonObject();
            o.addProperty("id", j.id());
            o.addProperty("state", j.state());
            o.addProperty("started_at_ms", j.startedAtMs());
            o.addProperty("dump_ms", j.dumpMs());
            o.addProperty("parse_ms", j.parseMs());
            o.addProperty("file_bytes", j.fileBytes());
            o.addProperty("file_kept", j.fileKept());
            o.addProperty("total_instances", j.totalInstances());
            o.addProperty("total_shallow_bytes", j.totalShallowBytes());
            if (j.impact() != null) {
                o.add("impact", j.impact().brief());
            }
            arr.add(o);
        }
        data.add("dumps", arr);
        data.addProperty("note", "history is in memory only and resets on restart; "
                + "compare two runs with GET " + PREFIX + "/heap/histogram/compare");
        Json.sendOk(exchange, data);
    }

    /** allocation（JFR 采样）与 liveness（直方图）按类对齐：回答「分配得多但活得少还是真泄漏」。 */
    private void handleAllocLive(HttpExchange exchange) throws IOException {
        // heap_id / jfr_id 是文档里的名字，heap / jfr 是短别名；都认，省得调用方记两种。
        String heapId = firstNonNull(queryParam(exchange, "heap_id"), queryParam(exchange, "heap"));
        String jfrId = firstNonNull(queryParam(exchange, "jfr_id"), queryParam(exchange, "jfr"));
        Integer top = queryInt(exchange, "top", ObservaConfig.TOP_N_DEFAULT.get(), 1,
                Math.min(200, ObservaConfig.TOP_N_MAX.get() * 2));
        if (top == null) {
            Json.sendError(exchange, 400, "bad_request", "top must be an integer between 1 and 200",
                    "GET " + PREFIX + "/heap/alloc-live?top=20");
            return;
        }
        Json.sendOk(exchange, AllocLive.join(heapId, jfrId, top));
    }

    /** 先 dump 堆再立刻开始一段 JFR，让两侧覆盖同一时间窗（对齐的前提）。 */
    private void handleAllocLiveStart(HttpExchange exchange) throws IOException {
        Integer seconds = queryInt(exchange, "seconds", ObservatoryAllocSeconds(), 1,
                ObservaConfig.PROFILER_MAX_SECONDS.get());
        if (seconds == null) {
            Json.sendError(exchange, 400, "bad_request",
                    "seconds must be an integer between 1 and " + ObservaConfig.PROFILER_MAX_SECONDS.get(),
                    "POST " + PREFIX + "/heap/alloc-live/start?seconds=30");
            return;
        }
        Json.sendOk(exchange, AllocLive.start(seconds));
    }

    /** 采集窗的默认秒数：和 JFR 端点的默认值保持一致。 */
    private static int ObservatoryAllocSeconds() {
        return 30;
    }

    /** 启动参数体检：静态规则，不看运行时数字（见 JvmFlags）。 */
    private void handleJvmFlags(HttpExchange exchange) throws IOException {
        Json.sendOk(exchange, JvmFlags.json());
    }

    /**
     * 一次性打包一次分析需要的东西，省掉手动 curl 十几次。
     *
     * <p>{@code include} 逗号分隔：manifest,health,metrics,memory,system,network,chunks,entities,
     * heap,retained,jfr。省略即全部。<b>只读</b>，不会触发新的 dump。</p>
     */
    private void handleSessionExport(HttpExchange exchange) throws IOException {
        String raw = queryParam(exchange, "include");
        Set<String> include = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            include.addAll(List.of("manifest", "health", "metrics", "memory", "system", "network",
                    "chunks", "entities", "heap", "retained", "jfr"));
        } else {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    include.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("include", String.join(",", include));
        data.addProperty("note", "read-only bundle: nothing here triggers a dump or a profiler "
                + "unless one was already running; heap/retained/jfr carry whatever is in memory");

        if (include.contains("manifest")) {
            data.add("manifest", manifestJson());
        }
        if (include.contains("health")) {
            JsonObject o = new JsonObject();
            o.addProperty("status", "ok");
            o.addProperty("uptime_ms", System.currentTimeMillis() - startedAtMs);
            o.addProperty("schema_version", Json.SCHEMA_VERSION);
            data.add("health", o);
        }
        if (include.contains("metrics")) {
            data.add("metrics", SnapshotJson.metrics(snapshot, 10));
        }
        if (include.contains("memory")) {
            data.add("memory", SnapshotJson.memory(snapshot));
        }
        if (include.contains("system")) {
            data.add("system", SystemStats.system());
        }
        if (include.contains("network")) {
            data.add("network", NetService.json());
        }
        if (include.contains("chunks")) {
            data.add("chunks", SnapshotJson.chunks(snapshot));
        }
        if (include.contains("entities")) {
            data.add("entities", SnapshotJson.entities(snapshot, "type", ObservaConfig.TOP_N_DEFAULT.get()));
        }
        if (include.contains("heap")) {
            data.add("heap", HeapService.instance().json());
        }
        if (include.contains("retained")) {
            data.add("retained", RetainedService.instance().json());
        }
        if (include.contains("jfr")) {
            data.add("jfr", JfrService.instance().status());
        }
        Json.sendOk(exchange, data);
    }

    private void handleChunks(HttpExchange exchange) throws IOException {
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        Json.sendOk(exchange, SnapshotJson.chunks(snapshot));
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        Snapshot snapshot = requireSnapshot(exchange);
        if (snapshot == null) {
            return;
        }
        Json.sendOk(exchange, SnapshotJson.full(snapshot));
    }

    // ---- 剖析端点 ----

    private void handleProfilerStart(HttpExchange exchange) throws IOException {
        ProfilerService profiler = ProfilerService.get();
        if (profiler == null) {
            Json.sendError(exchange, 503, "not_ready", "profiler is not wired up in this build",
                    "GET " + PREFIX + "/manifest lists what this build supports");
            return;
        }
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            Json.sendError(exchange, 400, "bad_request", "body must be a JSON object",
                    "POST " + PREFIX + "/profiler/start with {\"seconds\":30,\"interval_ms\":10}");
            return;
        }
        String mode = body.has("mode") ? body.get("mode").getAsString() : "sample";
        if (!"sample".equals(mode) && !"jfr".equals(mode)) {
            Json.sendError(exchange, 400, "bad_request", "mode must be sample or jfr",
                    "POST " + PREFIX + "/profiler/start with {\"mode\":\"sample\"} or {\"mode\":\"jfr\"}",
                    "GET " + PREFIX + "/manifest lists what each mode returns");
            return;
        }
        long seconds;
        int intervalMs;
        try {
            seconds = body.has("seconds") ? body.get("seconds").getAsLong() : 30L;
            intervalMs = body.has("interval_ms") ? body.get("interval_ms").getAsInt() : 10;
        } catch (RuntimeException e) {
            Json.sendError(exchange, 400, "bad_request", "seconds and interval_ms must be numbers",
                    "{\"seconds\":30,\"interval_ms\":10}");
            return;
        }
        if ("jfr".equals(mode)) {
            int clamped = (int) Math.max(1, Math.min(seconds, ObservaConfig.PROFILER_MAX_SECONDS.get()));
            try {
                JsonObject data = JfrService.instance().start(clamped);
                data.addProperty("tip", "GET " + PREFIX + "/profiler/report/" + data.get("id").getAsString()
                        + " after the recording finishes; the raw .jfr stays on disk");
                Json.sendOk(exchange, data);
            } catch (IllegalStateException e) {
                Json.sendError(exchange, 409, "conflict", e.getMessage(),
                        "POST " + PREFIX + "/profiler/stop to finish the running JFR recording",
                        "GET " + PREFIX + "/profiler/status for progress");
            } catch (Exception e) {
                // JFR 打不开（模块缺失/磁盘只读）时不要把整个端点搞挂，如实报错。
                Json.sendError(exchange, 503, "jfr_unavailable", "could not start JFR recording: " + e,
                        "POST " + PREFIX + "/profiler/start with {\"mode\":\"sample\"} instead");
            }
            return;
        }
        ProfilerService.Status status;
        try {
            status = profiler.start(seconds, intervalMs);
        } catch (IllegalStateException e) {
            Json.sendError(exchange, 409, "conflict", e.getMessage(),
                    "POST " + PREFIX + "/profiler/stop to finish the running recording",
                    "GET " + PREFIX + "/profiler/status for progress");
            return;
        }
        JsonObject data = ProfilerJson.status(status);
        data.addProperty("tip", "stop early with POST " + PREFIX + "/profiler/stop, or let it finish");
        Json.sendOk(exchange, data);
    }

    private void handleProfilerStatus(HttpExchange exchange) throws IOException {
        ProfilerService profiler = ProfilerService.get();
        ProfilerService.Status status = profiler == null ? null : profiler.status();
        JsonObject data = status == null ? new JsonObject() : ProfilerJson.status(status);
        if (status == null) {
            data.addProperty("running", false);
        }
        JsonObject jfr = JfrService.instance().status();
        data.add("jfr", jfr);
        JsonArray recent = new JsonArray();
        if (profiler != null) {
            for (String id : profiler.recentIds()) {
                recent.add(id);
            }
        }
        if (jfr.has("recent_reports")) {
            for (var element : jfr.getAsJsonArray("recent_reports")) {
                recent.add(element);
            }
        }
        data.add("recent_reports", recent);
        Json.sendOk(exchange, data);
    }

    private void handleProfilerStop(HttpExchange exchange) throws IOException {
        // 两种录制共用一对 start/stop：先看 JFR 是否在录，避免调用方还要记住自己开了哪种。
        if (JfrService.instance().status().get("running").getAsBoolean()) {
            try {
                Json.sendOk(exchange, JfrService.instance().stop());
            } catch (IllegalStateException e) {
                Json.sendError(exchange, 409, "conflict", e.getMessage(),
                        "GET " + PREFIX + "/profiler/status lists recent report ids");
            }
            return;
        }
        ProfilerService profiler = ProfilerService.get();
        ProfilerService.Report report = profiler == null ? null : profiler.stop();
        if (report == null) {
            Json.sendError(exchange, 409, "conflict", "no recording is running",
                    "POST " + PREFIX + "/profiler/start first",
                    "GET " + PREFIX + "/profiler/status lists recent report ids");
            return;
        }
        Json.sendOk(exchange, ProfilerJson.report(report));
    }

    private void handleProfilerReport(HttpExchange exchange, String id) throws IOException {
        if (id.startsWith("jfr-")) {
            Integer minSamples = queryInt(exchange, "min_samples", 0, 0, 1_000_000);
            if (minSamples == null) {
                Json.sendError(exchange, 400, "bad_request", "min_samples must be an integer >= 0",
                        "GET " + PREFIX + "/profiler/report/" + id + "?min_samples=5");
                return;
            }
            JsonObject jfrReport = JfrService.instance().report(id, minSamples);
            if (jfrReport == null) {
                Json.sendError(exchange, 404, "not_found", "no report with id " + id,
                        "GET " + PREFIX + "/profiler/status lists recent report ids");
                return;
            }
            Json.sendOk(exchange, jfrReport);
            return;
        }
        ProfilerService profiler = ProfilerService.get();
        ProfilerService.Report report = profiler == null ? null : profiler.report(id);
        if (report == null) {
            Json.sendError(exchange, 404, "not_found", "no report with id " + id,
                    "GET " + PREFIX + "/profiler/status lists recent report ids");
            return;
        }
        Json.sendOk(exchange, ProfilerJson.report(report));
    }

    // ---- 黑匣子 / 日志端点 ----

    private void handleLogs(HttpExchange exchange) throws IOException {
        Integer since = queryInt(exchange, "since", 60, 0, 86_400);
        if (since == null) {
            Json.sendError(exchange, 400, "bad_request", "since must be seconds between 0 and 86400",
                    "GET " + PREFIX + "/logs?since=60&level=ERROR");
            return;
        }
        String level = queryParam(exchange, "level");
        if (level == null || level.isBlank()) {
            level = "ERROR";
        }
        String normalized = level.trim().toUpperCase(java.util.Locale.ROOT);
        // INFO 不再接受：跟随者只存 WARN+，返回 400 比静默降级到 WARN 更诚实
        // （调用方原本以为自己拿到了 INFO 级别的事件）。
        if (!normalized.equals("WARN") && !normalized.equals("ERROR") && !normalized.equals("FATAL")) {
            Json.sendError(exchange, 400, "bad_request",
                    normalized.equals("INFO")
                            ? "level=INFO is not available: the follower only keeps WARN/ERROR/FATAL (read logs/latest.log for INFO)"
                            : "level must be WARN, ERROR or FATAL",
                    "GET " + PREFIX + "/logs?since=60&level=ERROR");
            return;
        }
        Integer limit = queryInt(exchange, "limit", 20, 1, 100);
        if (limit == null) {
            Json.sendError(exchange, 400, "bad_request", "limit must be between 1 and 100",
                    "GET " + PREFIX + "/logs?since=60&level=ERROR&limit=20");
            return;
        }
        Json.sendOk(exchange, LogWatch.instance().query(since, normalized, limit));
    }

    private void handleBlackbox(HttpExchange exchange) throws IOException {
        Integer limit = queryInt(exchange, "limit", 10, 1, 50);
        if (limit == null) {
            Json.sendError(exchange, 400, "bad_request", "limit must be between 1 and 50",
                    "GET " + PREFIX + "/blackbox?limit=10");
            return;
        }
        Json.sendOk(exchange, Blackbox.instance().history(limit));
    }

    private void handleDump(HttpExchange exchange) throws IOException {
        JsonObject body = readJsonBody(exchange);
        if (body == null) {
            Json.sendError(exchange, 400, "bad_request", "body must be a JSON object",
                    "POST " + PREFIX + "/dump with {\"label\":\"before-restart\"}");
            return;
        }
        String label = body.has("label") && !body.get("label").isJsonNull()
                ? body.get("label").getAsString() : null;
        if (label != null && label.length() > 80) {
            Json.sendError(exchange, 400, "bad_request", "label must be at most 80 characters",
                    "POST " + PREFIX + "/dump with {\"label\":\"short-name\"}");
            return;
        }
        try {
            java.nio.file.Path file = Blackbox.instance().write("manual", label);
            JsonObject data = new JsonObject();
            data.addProperty("reason", "manual");
            data.addProperty("label", label);
            data.addProperty("file", file.getFileName().toString());
            data.addProperty("path", file.toString());
            data.addProperty("bytes", java.nio.file.Files.size(file));
            data.addProperty("tip", "GET " + PREFIX + "/blackbox lists every dump; unzip and read trigger/ring/snapshot");
            Json.sendOk(exchange, data);
        } catch (IOException e) {
            Json.sendError(exchange, 500, "dump_failed", "could not write blackbox file: " + e,
                    "check that " + Blackbox.dir() + " is writable");
        }
    }

    /**
     * 自测：让服务器线程真的阻塞 N 毫秒，用来回归验证「卡死时黑匣子还在工作」。
     *
     * <p>它故意会卡住服务器，因此默认关闭（{@code debug.allow_selftest=false}）。
     * 只在直连端点暴露，隧道侧不转发——不要把远程玩家变成 DoS 工具。</p>
     */
    private void handleSelfTestStall(HttpExchange exchange) throws IOException {
        if (!ObservaConfig.DEBUG_ALLOW_SELFTEST.get()) {
            Json.sendError(exchange, 403, "selftest_disabled",
                    "debug.allow_selftest is false; this endpoint can freeze the server on purpose",
                    "set [debug] allow_selftest=true in config/observa-common.toml, restart, then retry",
                    "POST " + PREFIX + "/selftest/stall with {\"ms\":3000}");
            return;
        }
        JsonObject body = readJsonBody(exchange);
        int ms = 3000;
        if (body != null && body.has("ms") && !body.get("ms").isJsonNull()) {
            try {
                ms = body.get("ms").getAsInt();
            } catch (RuntimeException e) {
                ms = -1;
            }
        }
        if (ms < 1 || ms > 10_000) {
            Json.sendError(exchange, 400, "bad_request", "ms must be between 1 and 10000",
                    "POST " + PREFIX + "/selftest/stall with {\"ms\":3000}");
            return;
        }
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            Json.sendError(exchange, 503, "not_ready", "server is not running",
                    "GET " + PREFIX + "/health");
            return;
        }
        int blockMs = ms;
        // 下一个 tick 开始时在服务器线程上睡：这才叫「真卡住」，能复现心跳线程看到的停摆。
        server.execute(() -> {
            try {
                Thread.sleep(blockMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        JsonObject data = new JsonObject();
        data.addProperty("scheduled", true);
        data.addProperty("block_ms", blockMs);
        data.addProperty("tip", "the server thread blocks on its next tick; "
                + "a blackbox dump with reason=stall should appear once blackbox.sustain_seconds passes");
        Json.send(exchange, 202, Json.ok(data));
    }

    /**
     * 自测：把「玩家暂停」这个状态强行打开/关闭，不弹菜单。
     *
     * <p>用来验证「ESC 暂停不会被误报成 stall」这条逻辑，而不需要真的去按 ESC
     * （无头环境/远程机器上也能回归）。同样只在 {@code debug.allow_selftest=true} 时可用。</p>
     */
    private void handleSelfTestPause(HttpExchange exchange) throws IOException {
        if (!ObservaConfig.DEBUG_ALLOW_SELFTEST.get()) {
            Json.sendError(exchange, 403, "selftest_disabled",
                    "debug.allow_selftest is false; this endpoint changes how freezes are classified",
                    "set [debug] allow_selftest=true in config/observa-common.toml, restart, then retry",
                    "POST " + PREFIX + "/selftest/pause with {\"paused\":true}");
            return;
        }
        JsonObject body = readJsonBody(exchange);
        boolean paused = body == null || !body.has("paused") || body.get("paused").getAsBoolean();
        PauseState.force(paused);
        JsonObject data = new JsonObject();
        data.addProperty("paused", paused);
        data.addProperty("tip", "while paused, blackbox treats a stopped tick as a pause (no stall dump) "
                + "and /health reports paused:true; send {\"paused\":false} to clear");
        Json.send(exchange, 202, Json.ok(data));
    }

    private static JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(4096);
        if (raw.length == 0) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Snapshot requireSnapshot(HttpExchange exchange) throws IOException {
        Snapshot snapshot = MetricsService.snapshot();
        if (snapshot == null) {
            Json.sendError(exchange, 503, "not_ready", "no metrics collected yet",
                    "the first refresh happens " + ObservaConfig.METRICS_INTERVAL_MS.get() + "ms after server start",
                    "GET " + PREFIX + "/health to check liveness");
        }
        return snapshot;
    }

    // ---- 查询参数 ----

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (part.substring(0, eq).equals(name)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 返回 null 表示参数非法（调用方负责回 400），缺省返回 {@code fallback}。 */
    private static Integer queryInt(HttpExchange exchange, String name, int fallback, int min, int max) {
        String raw = queryParam(exchange, name);
        if ((raw == null || raw.isBlank()) && "top".equals(name)) {
            // 统一口径：凡是「要几行」的地方都同时接受 top= 和 limit=（历史文档两种都出现过）。
            raw = queryParam(exchange, "limit");
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value < min || value > max) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 端点清单：available = 已实现，planned = 本计划内但当前里程碑尚未实现。 */
    private JsonArray endpointList() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"GET", "/manifest", "available", "this document: endpoints, limits, conventions"});
        rows.add(new String[]{"GET", "/health", "available", "liveness, uptime, bound port"});
        rows.add(new String[]{"GET", "/metrics?window=60", "available", "TPS, MSPT percentiles, memory, entity/chunk totals"});
        rows.add(new String[]{"GET", "/entities?by=type|dimension|chunk&top=10", "available", "entity counts, item entities broken out"});
        rows.add(new String[]{"GET", "/network", "available", "per-player latency plus packets/bytes sent and received, with per-second rates"});
        rows.add(new String[]{"GET", "/system", "available", "process/system CPU, physical memory, swap, disk of the game directory"});
        rows.add(new String[]{"GET", "/memory", "available", "heap, per-pool usage, per-collector counts and the last GC in detail"});
        rows.add(new String[]{"GET", "/chunks", "available", "loaded chunks plus generated_total/generated_per_s (chunk generation is counted by a Mixin on the NOISE step)"});
        rows.add(new String[]{"GET", "/heap/histogram", "available", "last heap histogram: shallow bytes per class, rolled up per mod (by_mod) plus the class->mod mapping stats"});
        rows.add(new String[]{"POST", "/heap/histogram/start", "available", "body {live, keep_file, top}; dumps the heap (brief STW) and parses it off-thread; the last 5 results are kept for diffing"});
        rows.add(new String[]{"GET", "/heap/histogram/compare?from=&to=&top=20", "available", "diff of two histograms: which classes and which mods grew (defaults to the two most recent runs)"});
        rows.add(new String[]{"GET", "/heap/retained", "available", "last retained size analysis: per class and per mod bytes that go away if it is dropped, plus who dominates it"});
        rows.add(new String[]{"POST", "/heap/retained/start", "available", "body {live, max_objects}; dump the heap, build the object graph and run the dominator tree off-thread; a too-large dump is rejected by preflight with an exact next[]"});
        rows.add(new String[]{"GET", "/heap/dumps", "available", "recent dump runs with dump_ms/parse_ms and impact (GCs and pause caused by the dump itself)"});
        rows.add(new String[]{"GET", "/heap/alloc-live?heap_id=&jfr_id=&top=20", "available", "join JFR allocation samples with histogram liveness per class: allocated bytes vs bytes still alive (short aliases heap= and jfr= work too)"});
        rows.add(new String[]{"POST", "/heap/alloc-live/start?seconds=30", "available", "dump the heap and immediately start JFR so both sides cover the same window; poll /heap/alloc-live afterwards"});
        rows.add(new String[]{"GET", "/jvm/flags", "available", "effective JVM input args plus static checks (missing -Xms/-XX:MaxMetaspaceSize, dump/log paths, -Xmx vs physical RAM)"});
        rows.add(new String[]{"GET", "/session/export?include=manifest,metrics,memory,system,heap,jfr", "available", "one read-only bundle of every section; nothing here triggers a dump"});
        rows.add(new String[]{"GET", "/logs?since=60&level=ERROR&limit=20", "available", "deduplicated WARN/ERROR/FATAL events with counts, first/last seen and sample stack (INFO is not retained)"});
        rows.add(new String[]{"GET", "/snapshot", "available", "all of the above in one response"});
        rows.add(new String[]{"GET", "/profiler/status", "available", "current recording state and recent report ids"});
        rows.add(new String[]{"POST", "/profiler/start", "available", "body {mode: sample|jfr, seconds, interval_ms}; jfr returns a JSON summary plus a raw .jfr file"});
        rows.add(new String[]{"POST", "/profiler/stop", "available", "stop whichever recording runs (sample or jfr) and return its report"});
        rows.add(new String[]{"GET", "/profiler/report/{id}", "available", "stored profiler report as JSON"});
        rows.add(new String[]{"POST", "/dump", "available", "body {label} — write ring buffer + snapshot + errors + log tail to a .json.gz now"});
        rows.add(new String[]{"GET", "/blackbox?limit=10", "available", "history of automatic (mspt/stall/uncaught) and manual dumps plus watcher stats"});
        rows.add(new String[]{"POST", "/selftest/stall", "debug", "body {ms}: block the server thread on purpose to verify freeze detection (needs [debug] allow_selftest=true)"});
        rows.add(new String[]{"GET", "/brief.md", "available", "onboarding brief in Markdown: what this is, your task, endpoints, recipes, reading rules"});
        rows.add(new String[]{"GET", "/brief", "available", "same onboarding brief as JSON (state, first_calls, recipes, rules)"});

        JsonArray arr = new JsonArray();
        for (String[] row : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("method", row[0]);
            o.addProperty("path", PREFIX + row[1]);
            o.addProperty("status", row[2]);
            o.addProperty("summary", row[3]);
            arr.add(o);
        }
        return arr;
    }

    private JsonObject limitsObject() {
        JsonObject limits = new JsonObject();
        limits.addProperty("top_n_default", ObservaConfig.TOP_N_DEFAULT.get());
        limits.addProperty("top_n_max", ObservaConfig.TOP_N_MAX.get());
        limits.addProperty("ring_seconds", ObservaConfig.RING_SECONDS.get());
        limits.addProperty("metrics_interval_ms", ObservaConfig.METRICS_INTERVAL_MS.get());
        limits.addProperty("rate_limit_per_second", ObservaConfig.RATE_LIMIT_PER_SECOND.get());
        limits.addProperty("profiler_max_seconds", ObservaConfig.PROFILER_MAX_SECONDS.get());
        limits.addProperty("retained_default_object_limit", com.alvar.observa.collect.HeapGraph.effectiveObjectLimit());
        limits.addProperty("retained_hard_object_limit", com.alvar.observa.collect.HeapGraph.HARD_MAX_OBJECTS);
        limits.addProperty("retained_bytes_per_object", com.alvar.observa.collect.HeapGraph.bytesPerObject());
        limits.addProperty("max_response_bytes", 65536);
        limits.addProperty("blackbox_enabled", ObservaConfig.BLACKBOX_ENABLED.get());
        limits.addProperty("blackbox_mspt_threshold_ms", ObservaConfig.BLACKBOX_MSPT_THRESHOLD_MS.get());
        limits.addProperty("blackbox_sustain_seconds", ObservaConfig.BLACKBOX_SUSTAIN_SECONDS.get());
        limits.addProperty("blackbox_keep_files", 10);
        limits.addProperty("blackbox_min_server_ticks", ObservaConfig.BLACKBOX_MIN_SERVER_TICKS.get());
        limits.addProperty("blackbox_grace_seconds", ObservaConfig.BLACKBOX_GRACE_SECONDS.get());
        limits.addProperty("blackbox_freeze_after_ms", ObservaConfig.BLACKBOX_FREEZE_AFTER_MS.get());
        limits.addProperty("logs_entries_cap", 5000);
        limits.addProperty("debug_allow_selftest", ObservaConfig.DEBUG_ALLOW_SELFTEST.get());
        limits.addProperty("chunk_gen_window_seconds", com.alvar.observa.collect.ChunkGenCounter.WINDOW_SECONDS);
        limits.addProperty("heap_retained_max_objects", com.alvar.observa.collect.HeapGraph.MAX_OBJECTS);
        limits.addProperty("heap_retained_effective_object_limit",
                com.alvar.observa.collect.HeapGraph.effectiveObjectLimit());
        limits.addProperty("heap_retained_max_edges", com.alvar.observa.collect.HeapGraph.MAX_EDGES);
        return limits;
    }

    // ------------------------------------------------------------------ 鉴权 / 限速

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || token.isEmpty()) {
            return false;
        }
        byte[] given = header.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);
    }

    private boolean allowRequest() {
        int limit = ObservaConfig.RATE_LIMIT_PER_SECOND.get();
        long nowSecond = System.currentTimeMillis() / 1000L;
        long observed = rateSecond.get();
        if (observed != nowSecond) {
            if (rateSecond.compareAndSet(observed, nowSecond)) {
                rateCount.set(0L);
            }
        }
        return rateCount.incrementAndGet() <= limit;
    }

    public static String resolveToken() {
        String configured = ObservaConfig.TOKEN.get();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        Path dir = FMLPaths.CONFIGDIR.get().resolve("observa");
        Path tokenFile = dir.resolve("token.txt");
        try {
            Files.createDirectories(dir);
            if (Files.exists(tokenFile)) {
                String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String generated = generateToken();
            Files.writeString(tokenFile, generated + System.lineSeparator(), StandardCharsets.UTF_8);
            Observa.LOGGER.info("[observa] generated auth token at {} (not logged on purpose)", tokenFile);
            return generated;
        } catch (IOException e) {
            Observa.LOGGER.error("[observa] cannot read or create token file {}, endpoint will refuse every request",
                    tokenFile, e);
            return "";
        }
    }

    private static String generateToken() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
