package com.alvar.observa.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * JSON 响应工具：统一响应外壳与错误外壳。
 *
 * <p>成功响应：{@code {"schema_version":1,"generated_at":"...","data":{...}}}
 * <p>错误响应：{@code {"error":{"code":"...","detail":"...","next":["..."]}}}
 *
 * <p>错误外壳里带 {@code next}，是为了让 agent 不必靠猜：鉴权失败直接告诉去哪拿 token。
 */
public final class Json {

    public static final int SCHEMA_VERSION = 1;

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    /** 统一的成功响应外壳。 */
    public static JsonObject envelope() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("generated_at", Instant.now().toString());
        return root;
    }

    /** 序列化成字符串（隧道用：把同一份 JSON 原样塞进 payload）。 */
    public static String stringify(JsonElement element) {
        return GSON.toJson(element);
    }

    public static JsonObject ok(JsonObject data) {
        JsonObject root = envelope();
        root.add("data", data);
        return root;
    }

    /** 发送 JSON（调用方负责不再写 body）。 */
    public static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        // 不论前序处理如何，先清掉可能已设置的头，避免重复 Content-Length。
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 发送纯文本 / Markdown（agent 直接读，不需要 JSON 解析）。 */
    public static void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendOk(HttpExchange exchange, JsonObject data) throws IOException {
        send(exchange, 200, ok(data));
    }

    /**
     * 错误响应。{@code next} 是给 agent 的自救提示，不要写成空话。
     */
    public static void sendError(HttpExchange exchange, int status, String code, String detail, String... next) throws IOException {
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
        send(exchange, status, root);
    }
}
