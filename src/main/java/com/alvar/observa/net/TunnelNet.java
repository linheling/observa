package com.alvar.observa.net;

import com.alvar.observa.Observa;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 隧道注册与发送。这里刻意<b>不</b>引用任何客户端类：
 * 专用服务器上也会执行本类，引用 {@code net.minecraft.client.*} 会直接 NoClassDefFoundError。
 * 客户端行为通过 {@link TunnelClientBridge} 的接口注入（由客户端初始化时安装）。
 */
@EventBusSubscriber(modid = Observa.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TunnelNet {

    private TunnelNet() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // 请求：服务端线程处理（要读世界/实体表，只能在服务器线程）。
        registrar.playToServer(TunnelPayloads.Request.TYPE, TunnelPayloads.Request.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    context.enqueueWork(() -> TunnelServer.handle(payload, player));
                });

        // 应答：客户端拼块；服务端永远收不到这种包。
        registrar.playToClient(TunnelPayloads.Reply.TYPE, TunnelPayloads.Reply.STREAM_CODEC,
                (payload, context) -> TunnelClientBridge.onReply(payload));

        // 握手：登录时把「有没有权限」提前告诉客户端，免得每个请求都试一次。
        registrar.playToClient(TunnelPayloads.Hello.TYPE, TunnelPayloads.Hello.STREAM_CODEC,
                (payload, context) -> TunnelClientBridge.onHello(payload));
    }

    /** 给某个玩家发握手包（登录时调用；权限等级改配置后重进存档即生效）。 */
    public static void sendHello(ServerPlayer player) {
        boolean permitted = player.hasPermissions(
                Math.max(0, com.alvar.observa.config.ObservaConfig.TUNNEL_PERMISSION_LEVEL.get()));
        String note = permitted
                ? "tunnel allowed: read-only Observa data"
                : "tunnel denied: this player's permission level is below tunnel.permission_level";
        PacketDistributor.sendToPlayer(player,
                new TunnelPayloads.Hello(Observa.modVersion(), permitted, note));
    }

    /** 发一块应答（必须在服务器线程调用：PacketDistributor 会写 netty channel）。 */
    public static void send(ServerPlayer player, TunnelPayloads.Reply reply) {
        PacketDistributor.sendToPlayer(player, reply);
    }
}
