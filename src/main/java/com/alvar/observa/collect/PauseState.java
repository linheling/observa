package com.alvar.observa.collect;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/**
 * 「服务器是不是被玩家主动暂停了」的唯一判定口。
 *
 * <p>为什么要单独一层：单机（集成服务器）打开暂停菜单时 tick 会停，但这不是卡死——
 * 心跳线程看到 tick 不走会误判成 {@code stall}。判定需要两个来源：</p>
 * <ul>
 *   <li><b>客户端探针</b>：{@code Minecraft.isPaused()} 反映 ESC 菜单/失焦暂停，客户端侧安装；</li>
 *   <li><b>服务端字段</b>：{@code MinecraftServer.isPaused()}，集成服务器会同步它（专用服恒 false）。</li>
 * </ul>
 *
 * <p>两边都在同一个 JVM 里（单机时集成服务器跑在客户端进程内），所以这里不需要任何网络通信。
 * 专用服上客户端探针为空，只剩服务端字段（恒 false），行为与之前一致。</p>
 */
public final class PauseState {

    private static volatile Supplier<Boolean> clientProbe;
    private static volatile boolean forced;

    private PauseState() {
    }

    /** 客户端侧安装探针（{@code client.PauseProbe} 在客户端初始化时调用一次）。 */
    public static void installClientProbe(Supplier<Boolean> probe) {
        clientProbe = probe;
    }

    /** 自测用：强制「暂停」而不真的弹出菜单（仅 {@code debug.allow_selftest} 时可达）。 */
    public static void force(boolean value) {
        forced = value;
    }

    public static boolean forced() {
        return forced;
    }

    /** 当前这一进程里的服务器是否处于「玩家主动暂停」状态。 */
    public static boolean isPaused() {
        if (forced) {
            return true;
        }
        Supplier<Boolean> probe = clientProbe;
        if (probe != null) {
            try {
                if (Boolean.TRUE.equals(probe.get())) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 客户端还没初始化完就查询：当作没暂停，走服务端字段那条路。
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isPaused();
    }
}
