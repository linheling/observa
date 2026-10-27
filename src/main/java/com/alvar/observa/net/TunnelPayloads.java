package com.alvar.observa.net;

import com.alvar.observa.Observa;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 隧道的三种 payload：请求（客户端→服务端）、应答分块（服务端→客户端）、握手（服务端→客户端）。
 *
 * <p>为什么自己写 codec 而不是 {@code StreamCodec.composite}：字段全是字符串，手写更短，也不用为
 * 4/6 元签名找对应的 {@code Function4}。
 *
 * <p>{@code body} 走 {@code STRING_UTF8}，它的上限是 32767 字符；所以大响应在
 * {@link TunnelServer} 里被切成 24000 字符一块——留出 JSON 转义带来的膨胀余量。
 */
public final class TunnelPayloads {

    private TunnelPayloads() {
    }

    /** 客户端本地 HTTP 收到的一次请求：method/path 原样透传，body 只用于 POST /dump 的 label。 */
    public record Request(String id, String method, String path, String body) implements CustomPacketPayload {

        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Observa.MODID, "tunnel_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC =
                StreamCodec.<RegistryFriendlyByteBuf, Request>of(
                        (buf, v) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.id());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.method());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.path());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.body() == null ? "" : v.body());
                        },
                        buf -> new Request(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 分块应答：同一个 id 的 seq 从 0 到 total-1，client 拼完才是完整 body。 */
    public record Reply(String id, int status, String ctype, int seq, int total, String body)
            implements CustomPacketPayload {

        public static final Type<Reply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Observa.MODID, "tunnel_reply"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> STREAM_CODEC =
                StreamCodec.<RegistryFriendlyByteBuf, Reply>of(
                        (buf, v) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.id());
                            ByteBufCodecs.VAR_INT.encode(buf, v.status());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.ctype());
                            ByteBufCodecs.VAR_INT.encode(buf, v.seq());
                            ByteBufCodecs.VAR_INT.encode(buf, v.total());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.body());
                        },
                        buf -> new Reply(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.VAR_INT.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 登录时服务端告诉我们两件事：对面有没有装 Observa、这个玩家有没有权限读。 */
    public record Hello(String version, boolean permitted, String note) implements CustomPacketPayload {

        public static final Type<Hello> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Observa.MODID, "tunnel_hello"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> STREAM_CODEC =
                StreamCodec.<RegistryFriendlyByteBuf, Hello>of(
                        (buf, v) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.version());
                            buf.writeBoolean(v.permitted());
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.note() == null ? "" : v.note());
                        },
                        buf -> new Hello(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                buf.readBoolean(),
                                ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
