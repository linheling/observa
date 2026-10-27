package com.alvar.observa.collect;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 区块生成计数（Mixin 在 {@code ChunkStatusTasks#generateNoise} 头部打点）。
 *
 * <p>为什么钩 NOISE 步：区块加载有三种来源——从磁盘读（存档里已有）、生成（第一次到这片区域）、
 * 以及只读元数据。只有「生成」会走 NOISE 步；磁盘读出来的区块已经存到 FULL，不会再跑一次噪声。
 * 所以这个计数回答的是「这十分钟新造了多少地形」，而不是「加载了多少区块」。</p>
 *
 * <p>为什么还要环形采样：生成是突发的（飞过去、冲下界、跑图），1 秒速率会剧烈跳。
 * 这里保留最近 10 分钟的累计值采样，{@code /chunks} 返回最近 60 秒的平均速率，并同时给总量，
 * agent 想算「十分钟生成了多少」用两次 {@code generated_total} 相减即可。</p>
 */
public final class ChunkGenCounter {

    /** {@code generated_per_s} 默认平滑窗口。 */
    public static final int WINDOW_SECONDS = 60;
    private static final int CAPACITY = 600;

    private static final AtomicLong total = new AtomicLong();
    private static final Object LOCK = new Object();
    private static final long[] stamps = new long[CAPACITY];
    private static final long[] values = new long[CAPACITY];
    private static int head;
    private static int size;

    private ChunkGenCounter() {
    }

    /** Mixin 调用：每有一个区块进入 NOISE 生成步就 +1。 */
    public static void onChunkGenerated() {
        total.incrementAndGet();
    }

    public static long total() {
        return total.get();
    }

    /**
     * 记录一个采样点并返回当前速率。
     *
     * @param nowMs 采样时刻（与指标刷新同一节奏，通常 1 秒一次）
     */
    public static Rate sample(long nowMs) {
        long current = total.get();
        synchronized (LOCK) {
            stamps[head] = nowMs;
            values[head] = current;
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) {
                size++;
            }
        }
        return rate(nowMs, current);
    }

    /** 不推进采样、只读当前速率（给 {@code /chunks} 之外需要的地方用）。 */
    public static Double currentPerSecond() {
        return rate(System.currentTimeMillis(), total.get()).perSecond();
    }

    private static Rate rate(long nowMs, long current) {
        long windowMs = WINDOW_SECONDS * 1000L;
        long baseStamp = -1L;
        long baseValue = current;
        synchronized (LOCK) {
            int oldest = (head - size + CAPACITY) % CAPACITY;
            for (int i = 0; i < size; i++) {
                int idx = (oldest + i) % CAPACITY;
                long age = nowMs - stamps[idx];
                if (age <= windowMs) {
                    baseStamp = stamps[idx];
                    baseValue = values[idx];
                    break;
                }
            }
        }
        if (baseStamp < 0 || nowMs - baseStamp < 1000L) {
            // 窗口还不够长：给 null 而不是 0，别把「还没测够」说成「没生成」。
            return new Rate(null, current, WINDOW_SECONDS);
        }
        double perSecond = (current - baseValue) / ((nowMs - baseStamp) / 1000.0);
        return new Rate(Math.max(0.0, perSecond), current, WINDOW_SECONDS);
    }

    /** 一次采样结果：速率（可能为 null）、自启动以来的总量、速率窗口。 */
    public record Rate(Double perSecond, long total, int windowSeconds) {
    }
}
