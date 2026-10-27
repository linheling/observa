package com.alvar.observa.client;

import com.alvar.observa.Observa;
import com.alvar.observa.net.TunnelClientBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** 客户端游戏总线：断线时清空隧道状态（权限不能跨局残留）。 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = Observa.MODID, value = Dist.CLIENT)
public final class TunnelClientEvents {

    private TunnelClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TunnelClientBridge.onDisconnect();
    }
}
