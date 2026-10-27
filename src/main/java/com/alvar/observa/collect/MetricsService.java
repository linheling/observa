package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.alvar.observa.api.Snapshot;
import com.alvar.observa.blackbox.Blackbox;
import com.alvar.observa.config.ObservaConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 常开采集器：每个 tick 记耗时，按 {@code metrics.interval_ms} 聚合成不可变 {@link Snapshot} 发布。
 *
 * <p>线程模型：所有采集都在服务器线程完成（tick 事件里），HTTP 线程只读 volatile 快照。
 * 这是硬约束——从 HTTP 线程遍历实体表会撞上 ConcurrentModification 或读到半个世界。
 */
public final class MetricsService {

    private static final int TOP_TYPE_CACHE = 200;
    private static final int TOP_CHUNK_CACHE = 100;

    private static volatile MetricsService instance;

    /**
     * 上一次 tick 完成的墙钟时间（毫秒）；还没跑完第一个 tick 时是 -1。
     *
     * <p>黑匣子的心跳用它判断 tick 是否还在动：tick 停了它就不再更新，于是能区分
     * 「卡死」和「采样间隔还没到」。
     */
    private static volatile long lastTickEndMs = -1L;

    private final TickMeter meter = new TickMeter();
    private volatile Snapshot snapshot;
    private volatile long lastRefreshMs;

    public MetricsService() {
        instance = this;
    }

    public static Snapshot snapshot() {
        MetricsService s = instance;
        return s == null ? null : s.snapshot;
    }

    /** 上一次 tick 完成的墙钟时间；还没跑完第一个 tick 时是 -1。 */
    public static long lastTickEndMs() {
        return lastTickEndMs;
    }

    // ------------------------------------------------------------------ tick 钩子

    @SubscribeEvent
    public void onTickPre(ServerTickEvent.Pre event) {
        meter.onTickStart();
        ProfilerService.captureServerThread();
    }

    @SubscribeEvent
    public void onTickPost(ServerTickEvent.Post event) {
        meter.onTickEnd();
        lastTickEndMs = System.currentTimeMillis();

        long now = System.currentTimeMillis();
        long interval = Math.max(50, ObservaConfig.METRICS_INTERVAL_MS.get());
        if (now - lastRefreshMs < interval) {
            return;
        }
        lastRefreshMs = now;
        try {
            Snapshot fresh = build(event.getServer(), now);
            snapshot = fresh;
            // 网络读数按同一节奏抓：ping 在服务器线程上才算得准，HTTP 线程只读不可变视图。
            NetService.capture(event.getServer());
            // 黑匣子：入环形缓冲，并在 MSPT 持续超阈值时自动落盘（写盘在独立线程）。
            Blackbox.instance().onSnapshot(fresh, now);
        } catch (Throwable t) {
            // 采集失败不能让服务器停摆：记一次日志，保留上一份快照。
            Observa.LOGGER.error("[observa] metrics refresh failed", t);
        }
    }

    // ------------------------------------------------------------------ 聚合

    private Snapshot build(MinecraftServer server, long now) {
        double[] tickStats = meter.percentilesAndAverage();
        Snapshot.TickStats tick = new Snapshot.TickStats(
                round(meter.currentMspt()),
                round(tickStats[0]),
                round(tickStats[1]),
                round(tickStats[2]),
                round(tickStats[3]),
                meter.tps(1_000),
                meter.tps(10_000),
                meter.tps(60_000),
                meter.samples());

        return new Snapshot(now, server.getTickCount(), tick,
                readMemory(), countEntities(server), countChunks(server, now), collectPlayers(server));
    }

    private static Snapshot.MemoryStats readMemory() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) {
                gcCount += c;
            }
            if (t > 0) {
                gcTime += t;
            }
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return new Snapshot.MemoryStats(
                heap.getUsed(), heap.getCommitted(), heap.getMax(),
                memory.getNonHeapMemoryUsage().getUsed(), gcCount, gcTime, threads.getThreadCount());
    }

    private record ChunkKey(String dimension, int x, int z) {
    }

    private static Snapshot.EntityStats countEntities(MinecraftServer server) {
        long total = 0;
        long items = 0;
        long orbs = 0;
        Map<String, Integer> byType = new HashMap<>();
        Map<String, Integer> byDimension = new HashMap<>();
        Map<ChunkKey, Long> byChunk = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (Entity entity : level.getEntities().getAll()) {
                total++;
                String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                byType.merge(type, 1, Integer::sum);
                byDimension.merge(dimension, 1, Integer::sum);
                if (entity instanceof ItemEntity) {
                    items++;
                } else if (entity instanceof ExperienceOrb) {
                    orbs++;
                }
                ChunkPos pos = entity.chunkPosition();
                byChunk.merge(new ChunkKey(dimension, pos.x, pos.z), 1L, Long::sum);
            }
        }

        List<Snapshot.ChunkCount> chunkTop = new ArrayList<>(byChunk.size());
        for (Map.Entry<ChunkKey, Long> entry : byChunk.entrySet()) {
            ChunkKey key = entry.getKey();
            chunkTop.add(new Snapshot.ChunkCount(key.dimension(), key.x(), key.z(), entry.getValue()));
        }
        chunkTop.sort(Comparator.comparingLong(Snapshot.ChunkCount::count).reversed());

        return new Snapshot.EntityStats(total, items, orbs, byType.size(),
                topEntries(byType, TOP_TYPE_CACHE), sortedCopy(byDimension),
                List.copyOf(chunkTop.subList(0, Math.min(TOP_CHUNK_CACHE, chunkTop.size()))));
    }

    private static Snapshot.ChunkStats countChunks(MinecraftServer server, long now) {
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            loaded += level.getChunkSource().getLoadedChunksCount();
        }
        // 生成计数来自一个 Mixin（ChunkStatusTasks#generateNoise）：只有「现造地形」才会走到那里，
        // 从磁盘读出的区块不会重复计数。速率是 60 秒窗口平均，窗口没攒够时如实给 null。
        ChunkGenCounter.Rate rate = ChunkGenCounter.sample(now);
        return new Snapshot.ChunkStats(loaded, rate.perSecond(), rate.total(), rate.windowSeconds());
    }

    private static Snapshot.PlayerStats collectPlayers(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getName().getString());
        }
        return new Snapshot.PlayerStats(server.getPlayerCount(), List.copyOf(names));
    }

    // ------------------------------------------------------------------ 工具

    private static Map<String, Integer> sortedCopy(Map<String, Integer> source) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** Top-N 在采样时就切好：HTTP 侧再排序既费 CPU 也费 token。 */
    private static Map<String, Integer> topEntries(Map<String, Integer> source, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
