package com.alvar.observa.client;

import com.alvar.observa.Observa;
import com.alvar.observa.http.HttpService;
import com.alvar.observa.http.Json;
import com.alvar.observa.net.TunnelClientBridge;
import com.alvar.observa.net.TunnelNet;
import com.alvar.observa.net.TunnelPayloads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 客户端：在玩家本机开一个只绑 {@code 127.0.0.1} 的 HTTP 端点，把每次请求打包成 payload 发给
 * 「当前连着的服务器」，再把服务端回来的分块拼回完整响应。
 *
 * <p>这样 agent 在玩家机器上用的还是同一套 API、同一个 token 文件、同一份 {@code brief.md}，
 * 而服务器一个端口都不用开。前提是这个玩家在服务器上达到 {@code tunnel.permission_level}。
 *
 * <p>类只会在客户端加载（{@code @EventBusSubscriber(value = Dist.CLIENT)}），专用服务器上连
 * {@code net.minecraft.client} 都不会碰到。
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Observa.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TunnelClient implements TunnelClientBridge.Sink {

    private static final String PREFIX = HttpService.PREFIX;
    private static final int MAX_PORT_ATTEMPTS = 10;
    private static final long REQUEST_TIMEOUT_MS = 8_000L;
    private static final int MAX_BODY_BYTES = 4096;

    private static TunnelClient instance;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final String token = HttpService.resolveToken();
    private HttpServer server;
    private int port;

    /** 服务端最近一次握手；没连上 Obs 服务端时必须清空，否则会拿上一局的权限继续放行。 */
    private volatile TunnelPayloads.Hello hello;

    private TunnelClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            TunnelClient client = new TunnelClient();
            client.start();
            instance = client;
            TunnelClientBridge.install(client);
        });
    }

    private void start() {
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int candidate = Math.max(1024, com.alvar.observa.config.ObservaConfig.PORT.get()) + attempt;
            try {
                HttpServer httpServer = HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 4);
                httpServer.createContext(PREFIX, this::dispatch);
                httpServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "observa-tunnel-http");
                    t.setDaemon(true);
                    return t;
                }));
                httpServer.start();
                this.server = httpServer;
                this.port = candidate;
                Observa.LOGGER.info("[observa] tunnel endpoint on http://127.0.0.1:{}{}/brief.md (client side)",
                        candidate, PREFIX);
                return;
            } catch (IOException e) {
                if (attempt == MAX_PORT_ATTEMPTS - 1) {
                    Observa.LOGGER.warn("[observa] tunnel endpoint could not bind any port near {}",
                            com.alvar.observa.config.ObservaConfig.PORT.get(), e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 服务端事件

    @Override
    public void onReply(TunnelPayloads.Reply reply) {
        Pending p = pending.get(reply.id());
        if (p == null) {
            return;
        }
        p.parts.put(reply.seq(), reply);
        if (p.parts.size() >= reply.total()) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < reply.total(); i++) {
                TunnelPayloads.Reply part = p.parts.get(i);
                if (part == null) {
                    return;
                }
                b.append(part.body());
            }
            pending.remove(reply.id());
            p.done.complete(new TunnelPayloads.Reply(reply.id(), reply.status(), reply.ctype(), 0, 1, b.toString()));
        }
    }

    @Override
    public void onHello(TunnelPayloads.Hello hello) {
        this.hello = hello;
        Observa.LOGGER.info("[observa] tunnel handshake: server version {} permitted={} ({})",
                hello.version(), hello.permitted(), hello.note());
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(hello.permitted()
                    ? "[observa] tunnel ready: 127.0.0.1:" + port + PREFIX + "/brief.md"
                    : "[observa] tunnel denied (permission level too low)"), false);
        }
    }

    @Override
    public void onDisconnect() {
        hello = null;
        pending.values().forEach(p -> p.done.completeExceptionally(new IOException("disconnected")));
        pending.clear();
    }

    // ------------------------------------------------------------------ HTTP

    private void dispatch(HttpExchange exchange) {
        try {
            if (!authorized(exchange)) {
                write(exchange, 401, "application/json",
                        "{\"error\":{\"code\":\"unauthorized\",\"detail\":\"missing or invalid bearer token\","
                                + "\"next\":[\"token lives in config/observa/token.txt\"]}}");
                return;
            }
            TunnelPayloads.Hello h = hello;
            if (h == null) {
                write(exchange, 503, "application/json",
                        "{\"error\":{\"code\":\"no_tunnel\",\"detail\":\"not connected to a server running Observa\","
                                + "\"next\":[\"join the server in-game first\",\"the server must have Observa installed\"]}}");
                return;
            }
            // 刻意不在客户端硬拦：OP 可能在玩家进服之后才授予（本机测试就踩过这个坑）。
            // 权限由服务端按实时 hasPermissions 判定，客户端只透传它的 403。

            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(PREFIX)) {
                path = PREFIX + path;
            }
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && !query.isEmpty()) {
                path = path + "?" + query;
            }
            // brief 里写的是「本机端口」：经隧道读时那个端口是客户端这个，得让服务端按它生成，
            // 否则 agent 会照着服务端 8724 去连，连不到。
            if (path.startsWith(PREFIX + "/brief")) {
                path = path + (path.indexOf('?') >= 0 ? "&" : "?") + "client_port=" + port;
            }
            String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
            String body = "";
            if ("POST".equals(method)) {
                body = readBody(exchange);
                if (body == null) {
                    write(exchange, 400, "application/json",
                            "{\"error\":{\"code\":\"bad_request\",\"detail\":\"body must be a JSON object\"}}");
                    return;
                }
            }

            String id = UUID.randomUUID().toString();
            Pending p = new Pending();
            pending.put(id, p);
            try {
                PacketDistributor.sendToServer(new TunnelPayloads.Request(id, method, path, body));
                TunnelPayloads.Reply reply = p.done.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                write(exchange, reply.status(), reply.ctype(), reply.body());
            } catch (TimeoutException e) {
                pending.remove(id);
                write(exchange, 504, "application/json",
                        "{\"error\":{\"code\":\"tunnel_timeout\",\"detail\":\"server did not answer within "
                                + REQUEST_TIMEOUT_MS + " ms\","
                                + "\"next\":[\"the server thread may be stalled; try again\",\"check the server log\"]}}");
            } catch (IOException | ExecutionException e) {
                pending.remove(id);
                write(exchange, 502, "application/json",
                        "{\"error\":{\"code\":\"tunnel_failed\",\"detail\":\"" + e.getClass().getSimpleName()
                                + "\",\"next\":[\"reconnect to the server and retry\"]}}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(id);
                write(exchange, 500, "application/json",
                        "{\"error\":{\"code\":\"interrupted\",\"detail\":\"request thread interrupted\"}}");
            }
        } catch (Throwable t) {
            Observa.LOGGER.warn("[observa] tunnel HTTP handler failed", t);
            try {
                write(exchange, 500, "application/json",
                        "{\"error\":{\"code\":\"internal_error\",\"detail\":\""
                                + t.getClass().getSimpleName() + "\"}}");
            } catch (IOException ignored) {
                // 客户端已经断开
            }
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = header.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return "{}";
            }
            return text;
        }
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class Pending {
        final Map<Integer, TunnelPayloads.Reply> parts = new ConcurrentHashMap<>();
        final CompletableFuture<TunnelPayloads.Reply> done = new CompletableFuture<>();
    }
}
