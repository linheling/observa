package com.alvar.observa.net;

/**
 * 服务端/公共代码与客户端实现之间的唯一接触点。
 *
 * <p>公共代码永远不 import 客户端类（专用服务器没有 {@code net.minecraft.client.*}），
 * 客户端在自己初始化时把实现装进来；没装就等于「这台机器没有客户端隧道」。
 */
public final class TunnelClientBridge {

    /** 客户端实现要接的三个事件；参数都是纯数据，不含任何客户端类型。 */
    public interface Sink {
        void onReply(TunnelPayloads.Reply reply);

        void onHello(TunnelPayloads.Hello hello);

        void onDisconnect();
    }

    private static volatile Sink sink;

    private TunnelClientBridge() {
    }

    public static void install(Sink impl) {
        sink = impl;
    }

    public static void onReply(TunnelPayloads.Reply reply) {
        Sink s = sink;
        if (s != null) {
            s.onReply(reply);
        }
    }

    public static void onHello(TunnelPayloads.Hello hello) {
        Sink s = sink;
        if (s != null) {
            s.onHello(hello);
        }
    }

    public static void onDisconnect() {
        Sink s = sink;
        if (s != null) {
            s.onDisconnect();
        }
    }
}
