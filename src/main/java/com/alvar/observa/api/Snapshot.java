package com.alvar.observa.api;

import java.util.List;
import java.util.Map;

/**
 * 采集结果的不可变快照。HTTP 线程只读这里，绝不直接读游戏状态。
 *
 * <p>所有 {@code null} 都表示「尚未采集」，不是 0——契约里也这么写，避免 agent 把缺失当健康。
 */
public record Snapshot(
        long collectedAtMs,
        long serverTick,
        TickStats tick,
        MemoryStats memory,
        EntityStats entities,
        ChunkStats chunks,
        PlayerStats players) {

    /** MSPT / TPS。mspt 为单 tick 实测耗时（不含 tick 之间的休眠）。 */
    public record TickStats(
            double msptNowMs,
            double msptAvg1m,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            Double tps1s,
            Double tps10s,
            Double tps1m,
            int sampleTicks) {
    }

    public record MemoryStats(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long gcCount,
            long gcPauseTotalMs,
            int threadCount) {
    }

    public record EntityStats(
            long total,
            long itemEntities,
            long xpOrbs,
            int loadedEntityTypes,
            Map<String, Integer> byType,
            Map<String, Integer> byDimension,
            List<ChunkCount> byChunk) {
    }

    public record ChunkCount(String dimension, int x, int z, long count) {
    }

    /**
     * 区块读数。
     *
     * @param loaded               当前已加载区块数
     * @param generatedPerSecond   最近 {@code generatedWindowSeconds} 秒的平均生成速率（区块/秒）；
     *                             窗口还没攒够时为 null
     * @param generatedTotal       进程启动以来新生成的区块总数（磁盘读出的不算）
     * @param generatedWindowSeconds 速率窗口秒数
     */
    public record ChunkStats(int loaded, Double generatedPerSecond, long generatedTotal, int generatedWindowSeconds) {
    }

    public record PlayerStats(int online, List<String> names) {
    }
}
