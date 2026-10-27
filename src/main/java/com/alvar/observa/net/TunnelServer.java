package com.alvar.observa.net;

import com.alvar.observa.Observa;
import com.alvar.observa.api.Snapshot;
import com.alvar.observa.blackbox.Blackbox;
import com.alvar.observa.blackbox.LogWatch;
import com.alvar.observa.collect.HeapService;
import com.alvar.observa.collect.JfrService;
import com.alvar.observa.collect.NetService;
import com.alvar.observa.collect.SystemStats;
import com.alvar.observa.collect.MetricsService;
import com.alvar.observa.collect.ProfilerService;
import com.alvar.observa.config.ObservaConfig;
import com.alvar.observa.http.Brief;
import com.alvar.observa.http.HttpService;
import com.alvar.observa.http.Json;
import com.alvar.observa.http.ProfilerJson;
import com.alvar.observa.http.SnapshotJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 服务端：把一次隧道请求变成 HTTP 语义的响应（同一份 JSON 信封、同一套 next 提示），再分块发回去。
 *
 * <p>只在<b>服务器线程</b>执行（{@link TunnelNet} 用 {@code enqueueWork} 保证），因为快照/实体表/落盘
 * 都必须在那条线程上做——这正是 HTTP 端点「先拍快照、HTTP 线程只读快照」那条约束的另一面。
 *
 * <p>支持的端点是一个<b>明确的子集</b>；不支持的（剖析报告、JFR 报告、完整 manifest）回 501 并给出
 * 「到服务器本机走 HTTP」的提示，而不是悄悄返回半份数据。
 */
public final class TunnelServer {

    /** 单块字符数：{@code STRING_UTF8} 上限 32767，留出 JSON 转义与长度前缀的余量。 */
    private static final int CHUNK_CHARS = 24_000;

    private static final List<String> SUPPORTED = List.of(
            "/health", "/metrics", "/entities", "/memory", "/chunks", "/snapshot",
            "/logs", "/blackbox", "/dump", "/profiler/status", "/brief", "/brief.md");

    private TunnelServer() {
    }

    public static void handle(TunnelPayloads.Request request, ServerPlayer player) {
        try {
            if (!ObservaConfig.TUNNEL_ENABLED.get()) {
                send(player, request.id(), 503, "application/json",
                        error("tunnel_disabled", "server config has tunnel.enabled = false",
                                "ask the server owner to enable tunnel.enabled",
                                "or use the server-side HTTP endpoint if you have access"));
                return;
            }
            int need = ObservaConfig.TUNNEL_PERMISSION_LEVEL.get();
            if (!player.hasPermissions(Math.max(0, need))) {
                send(player, request.id(), 403, "application/json",
                        error("forbidden", "this player is below tunnel.permission_level " + need,
                                "only players with permission level >= " + need + " may read Observa data",
                                "the server owner can lower tunnel.permission_level in config/observa-common.toml"));
                return;
            }

            String path = request.path() == null ? "" : request.path();
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            path = stripPrefix(path);
            String method = request.method() == null ? "GET" : request.method().toUpperCase(Locale.ROOT);

            switch (path) {
                case "/health" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("status", "ok");
                    data.addProperty("via", "tunnel");
                    data.addProperty("player", player.getGameProfile().getName());
                    data.addProperty("server_tick", player.server.getTickCount());
                    data.addProperty("schema_version", Json.SCHEMA_VERSION);
                    data.addProperty("server_http_port", HttpService.boundPort());
                    ok(player, request.id(), data);
                }
                case "/metrics" -> {
                    Snapshot s = MetricsService.snapshot();
                    if (s == null) {
                        notReady(player, request.id());
                        return;
                    }
                    int window = intParam(query, "window", 10);
                    if (window != 1 && window != 10 && window != 60) {
                        badRequest(player, request.id(), "window must be 1, 10 or 60");
                        return;
                    }
                    ok(player, request.id(), SnapshotJson.metrics(s, window));
                }
                case "/entities" -> {
                    Snapshot s = MetricsService.snapshot();
                    if (s == null) {
                        notReady(player, request.id());
                        return;
                    }
                    String by = param(query, "by", "type");
                    if (!"type".equals(by) && !"dimension".equals(by) && !"chunk".equals(by)) {
                        badRequest(player, request.id(), "by must be type, dimension or chunk");
                        return;
                    }
                    int top = clamp(intParam(query, "top", ObservaConfig.TOP_N_DEFAULT.get()),
                            1, ObservaConfig.TOP_N_MAX.get());
                    ok(player, request.id(), SnapshotJson.entities(s, by, top));
                }
                case "/memory" -> withSnapshot(player, request.id(), s -> SnapshotJson.memory(s));
                case "/system" -> ok(player, request.id(), SystemStats.system());
                case "/network" -> ok(player, request.id(), NetService.json());
                case "/heap/histogram" -> ok(player, request.id(), HeapService.instance().json());
                case "/heap/histogram/start" -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(player, request.id(), "POST");
                        return;
                    }
                    JsonObject body = new JsonObject();
                    try {
                        if (request.body() != null && !request.body().isBlank()) {
                            body = JsonParser.parseString(request.body()).getAsJsonObject();
                        }
                    } catch (RuntimeException e) {
                        badRequest(player, request.id(), "body must be a JSON object");
                        return;
                    }
                    boolean live = body.has("live") && body.get("live").getAsBoolean();
                    boolean keep = body.has("keep_file") && body.get("keep_file").getAsBoolean();
                    int top = clamp(body.has("top") ? body.get("top").getAsInt()
                                    : ObservaConfig.TOP_N_DEFAULT.get(),
                            1, ObservaConfig.TOP_N_MAX.get());
                    if (!HeapService.instance().start(live, keep, top)) {
                        send(player, request.id(), 409, "application/json",
                                error("already_running", "a heap histogram is already being built",
                                        "GET /observa/v1/heap/histogram to check state"));
                        return;
                    }
                    JsonObject accepted = new JsonObject();
                    accepted.addProperty("state", "running");
                    accepted.addProperty("live", live);
                    accepted.addProperty("keep_file", keep);
                    send(player, request.id(), 202, "application/json", Json.stringify(Json.ok(accepted)));
                }
                case "/chunks" -> withSnapshot(player, request.id(), s -> SnapshotJson.chunks(s));
                case "/snapshot" -> withSnapshot(player, request.id(), s -> SnapshotJson.full(s));
                case "/logs" -> {
                    if (!"GET".equals(method)) {
                        methodNotAllowed(player, request.id(), "GET");
                        return;
                    }
                    String level = param(query, "level", "ERROR").toUpperCase(Locale.ROOT);
                    if (!List.of("WARN", "ERROR", "FATAL").contains(level)) {
                        badRequest(player, request.id(), level.equals("INFO")
                                ? "level=INFO is not available: only WARN/ERROR/FATAL are retained"
                                : "level must be WARN, ERROR or FATAL");
                        return;
                    }
                    int since = clamp(intParam(query, "since", 60), 0, 86_400);
                    int limit = clamp(intParam(query, "limit", 20), 1, 100);
                    ok(player, request.id(), LogWatch.instance().query(since, level, limit));
                }
                case "/blackbox" -> {
                    int limit = clamp(intParam(query, "limit", 10), 1, 50);
                    ok(player, request.id(), Blackbox.instance().history(limit));
                }
                case "/dump" -> {
                    if (!"POST".equals(method)) {
                        methodNotAllowed(player, request.id(), "POST");
                        return;
                    }
                    String label = null;
                    if (request.body() != null && !request.body().isBlank()) {
                        try {
                            JsonElement parsed = JsonParser.parseString(request.body());
                            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("label")
                                    && !parsed.getAsJsonObject().get("label").isJsonNull()) {
                                label = parsed.getAsJsonObject().get("label").getAsString();
                            }
                        } catch (RuntimeException e) {
                            badRequest(player, request.id(), "body must be a JSON object");
                            return;
                        }
                    }
                    if (label != null && label.length() > 80) {
                        badRequest(player, request.id(), "label must be at most 80 characters");
                        return;
                    }
                    Path file = Blackbox.instance().write("manual", label);
                    JsonObject data = new JsonObject();
                    data.addProperty("reason", "manual");
                    data.addProperty("label", label);
                    data.addProperty("file", file.getFileName().toString());
                    data.addProperty("path", file.toAbsolutePath().toString());
                    try {
                        data.addProperty("bytes", Files.size(file));
                    } catch (IOException e) {
                        data.addProperty("bytes", (Number) null);
                    }
                    data.addProperty("tip", "written on the server; GET /blackbox lists every dump");
                    ok(player, request.id(), data);
                }
                case "/profiler/status" -> ok(player, request.id(), profilerStatus());
                case "/brief" -> {
                    int cp = intParam(query, "client_port", HttpService.boundPort());
                    ok(player, request.id(), Brief.json(cp));
                }
                case "/brief.md" -> {
                    // 经隧道读 brief 时，里面该写客户端那个端口（服务端的 8724 对 agent 可能不可达）。
                    int cp = intParam(query, "client_port", HttpService.boundPort());
                    sendHtml(player, request.id(), "text/markdown", Brief.markdown(cp));
                }
                case "/manifest" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("via", "tunnel");
                    data.addProperty("note", "tunnel exposes a read-only subset; the server-side HTTP endpoint has more");
                    data.addProperty("server_http_port", HttpService.boundPort());
                    JsonArray endpoints = new JsonArray();
                    for (String p : SUPPORTED) {
                        endpoints.add(HttpService.PREFIX + p);
                    }
                    data.add("endpoints", endpoints);
                    data.add("not_over_tunnel", stringArray("/profiler/report/{id}", "/profiler/start", "/profiler/stop",
                            "/profiler/stop is intentionally not exposed: recordings are triggered on the server host"));
                    ok(player, request.id(), data);
                }
                default -> {
                    if (path.startsWith("/profiler/report/")) {
                        send(player, request.id(), 501, "application/json",
                                error("not_supported_over_tunnel",
                                        "profiler reports can be larger than one payload",
                                        "run /profiler/start|stop from the server host's HTTP endpoint",
                                        "GET /" + "observa/v1/manifest over HTTP lists every endpoint"));
                    } else {
                        send(player, request.id(), 404, "application/json",
                                error("not_found", "unknown path: " + path,
                                        "GET /observa/v1/manifest (over tunnel) lists the supported subset"));
                    }
                }
            }
        } catch (Throwable t) {
            Observa.LOGGER.warn("[observa] tunnel request failed for {}", player.getGameProfile().getName(), t);
            send(player, request.id(), 500, "application/json",
                    error("internal_error", t.getClass().getSimpleName() + ": " + t.getMessage(),
                            "retry once; if it repeats, report the server log line with [observa] tunnel request failed"));
        }
    }

    // ------------------------------------------------------------------ 视图

    private static JsonObject profilerStatus() {
        ProfilerService profiler = ProfilerService.get();
        ProfilerService.Status status = profiler == null ? null : profiler.status();
        JsonObject data = status == null ? new JsonObject() : ProfilerJson.status(status);
        if (status == null) {
            data.addProperty("running", false);
        }
        data.add("jfr", JfrService.instance().status());
        JsonArray recent = new JsonArray();
        if (profiler != null) {
            for (String id : profiler.recentIds()) {
                recent.add(id);
            }
        }
        JsonObject jfr = JfrService.instance().status();
        if (jfr.has("recent_reports")) {
            for (JsonElement element : jfr.getAsJsonArray("recent_reports")) {
                recent.add(element);
            }
        }
        data.add("recent_reports", recent);
        return data;
    }

    private interface View {
        JsonObject build(Snapshot snapshot);
    }

    private static void withSnapshot(ServerPlayer player, String id, View view) {
        Snapshot s = MetricsService.snapshot();
        if (s == null) {
            notReady(player, id);
            return;
        }
        ok(player, id, view.build(s));
    }

    // ------------------------------------------------------------------ 发送

    private static void ok(ServerPlayer player, String id, JsonObject data) {
        send(player, id, 200, "application/json", Json.stringify(Json.ok(data)));
    }

    private static void sendHtml(ServerPlayer player, String id, String ctype, String body) {
        send(player, id, 200, ctype, body);
    }

    private static void notReady(ServerPlayer player, String id) {
        send(player, id, 503, "application/json",
                error("not_ready", "the first snapshot has not been collected yet",
                        "wait about one second and retry",
                        "GET /observa/v1/health to confirm the endpoint is alive"));
    }

    private static void badRequest(ServerPlayer player, String id, String detail) {
        send(player, id, 400, "application/json",
                error("bad_request", detail, "GET /observa/v1/manifest lists the supported subset"));
    }

    private static void methodNotAllowed(ServerPlayer player, String id, String expected) {
        send(player, id, 405, "application/json",
                error("method_not_allowed", expected + " expected",
                        "use " + expected + " instead"));
    }

    private static void send(ServerPlayer player, String id, int status, String ctype, String body) {
        String text = body == null ? "" : body;
        int total = Math.max(1, (text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int seq = 0; seq < total; seq++) {
            int from = seq * CHUNK_CHARS;
            int to = Math.min(text.length(), from + CHUNK_CHARS);
            TunnelNet.send(player, new TunnelPayloads.Reply(id, status, ctype, seq, total, text.substring(from, to)));
        }
    }

    private static String error(String code, String detail, String... next) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("detail", detail);
        if (next != null && next.length > 0) {
            JsonArray arr = new JsonArray();
            for (String n : next) {
                arr.add(n);
            }
            err.add("next", arr);
        }
        JsonObject root = new JsonObject();
        root.add("error", err);
        return Json.stringify(root);
    }

    // ------------------------------------------------------------------ 小工具

    private static String stripPrefix(String path) {
        String prefix = HttpService.PREFIX;
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        return path.isEmpty() ? "/manifest" : path;
    }

    private static String param(String query, String key, String def) {
        if (query == null || query.isEmpty()) {
            return def;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (k.equals(key)) {
                String v = eq < 0 ? "" : java.net.URLDecoder.decode(part.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
                return v.isEmpty() ? def : v;
            }
        }
        return def;
    }

    private static int intParam(String query, String key, int def) {
        try {
            return Integer.parseInt(param(query, key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static JsonArray stringArray(String... values) {
        JsonArray arr = new JsonArray();
        for (String v : values) {
            arr.add(v);
        }
        return arr;
    }
}
