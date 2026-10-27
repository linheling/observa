package com.alvar.observa.client;

import com.alvar.observa.collect.PauseState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端暂停探针：把 {@code Minecraft.isPaused()}（ESC 菜单 / 失焦暂停）接到公共侧的
 * {@link PauseState}。
 *
 * <p>放在 client 包里、且只订阅客户端事件：专用服加载不到 {@code Minecraft}，所以这个类在
 * 专用服上不会被触碰。单机时它和集成服务器同在客户端进程，探针读的是同一个布尔字段。</p>
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class PauseProbe {

    private PauseProbe() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        PauseState.installClientProbe(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.isPaused();
        });
    }
}
