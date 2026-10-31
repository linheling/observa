package com.alvar.observa;

import com.alvar.observa.blackbox.Blackbox;
import com.alvar.observa.collect.JfrService;
import com.alvar.observa.collect.MetricsService;
import com.alvar.observa.collect.ModIndex;
import com.alvar.observa.collect.ProfilerService;
import com.alvar.observa.collect.RetainedService;
import com.alvar.observa.config.ObservaConfig;
import com.alvar.observa.http.HttpService;
import com.alvar.observa.net.TunnelNet;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Observa —— 把 Minecraft 运行时数据以机器可读的方式暴露给 agent（DSH）。
 *
 * <p>纯服务端逻辑：单机时由集成服务器承载，客户端无需额外安装（但单机必须把 jar 放进客户端 mods/，
 * 因为集成服务器跑在客户端进程里）。
 */
@Mod(Observa.MODID)
public class Observa {

    public static final String MODID = "observa";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Observa(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ObservaConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
        // 采集器常驻：整个进程只注册一次，避免每次进出存档叠加监听器。
        NeoForge.EVENT_BUS.register(new MetricsService());
        new ProfilerService();
        // 崩溃兜底：server 线程未捕获异常时落一份黑匣子（同步写，进程随时可能消失）。
        Blackbox.instance().installCrashHandler();
        LOGGER.info("[observa] {} loaded", MODID);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // 集成服务器每次进存档都会走到这里；HttpService.start() 幂等。
        HttpService.start();
        // 黑匣子心跳：tick 回调停了它还在跑，否则「卡死」正好让环形缓冲停止记录。
        Blackbox.instance().onServerStarted(System.currentTimeMillis());
        Blackbox.instance().startWatch();
        prewarmModIndex();
        // 自检：把 retained 分析真的跑一遍（它同时是这条链路唯一的端到端验证手段）。
        if (ObservaConfig.DEBUG_RETAINED_ON_START.get()) {
            LOGGER.info("[observa] retained selfcheck: starting a dump right after server start");
            if (RetainedService.instance().start(true, true)) {
                LOGGER.info("[observa] retained selfcheck: running (GET /heap/retained for the result)");
            } else {
                LOGGER.warn("[observa] retained selfcheck: an analysis was already running, skipped");
            }
        }
    }

    /**
     * 预热类→mod 索引：第一次用到时会扫 {@code mods/*.jar}（整合包里几百个 jar），
     * 放在后台线程做，免得某个 HTTP 请求（尤其是经隧道、跑在服务器线程上的那次）把它扛在 tick 里。
     */
    private static void prewarmModIndex() {
        Thread t = new Thread(() -> {
            try {
                ModIndex.get();
            } catch (RuntimeException e) {
                LOGGER.warn("[observa] mod index prewarm failed: {}", e.toString());
            }
        }, "observa-modindex");
        t.setDaemon(true);
        t.start();
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {        // 隧道握手：告诉客户端「对面装了 Observa、你这个玩家有没有权限」。
        if (event.getEntity() instanceof ServerPlayer player) {
            TunnelNet.sendHello(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 先停心跳：停服期间不该再往 ring 里塞冻结帧，也不该再自动落盘。
        Blackbox.instance().stopWatch();
        // 停服前把还没结束的 JFR 录制收尾，否则文件停在半截、摘要也丢了。
        JfrService.instance().stopOnShutdown();
        Blackbox.instance().onShutdownCheck();
        HttpService.stop();
        Blackbox.instance().stop();
    }

    /** 供 /manifest 报告的模组版本；开发环境（runClient）下 ModList 里也有条目。 */
    public static String modVersion() {
        return ModList.get().getModContainerById(MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("dev");
    }
}
