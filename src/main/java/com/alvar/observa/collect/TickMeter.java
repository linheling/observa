package com.alvar.observa.collect;

import java.util.Arrays;

/**
 * 单 tick 耗时的环形缓冲。
 *
 * <p>关键区分：MSPT 量的是「一次 tick 的工作耗时」（Pre→Post 之间的墙钟时间），
 * 不是两次 tick 结束之间的间隔——专用服务器会 sleep 到 50ms 节奏，后者永远是 ~50ms，
 * 用它算 MSPT 会把健康的服务器也显示成 50ms。
 *
 * <p>所有方法只在服务器线程调用，因此不需要加锁。
 */
public final class TickMeter {

    private static final int CAPACITY = 2400;      // 60s × 20tps 的两倍余量
    private static final int MSPT_WINDOW = 1200;   // 60s @ 20tps

    private final long[] durationNs = new long[CAPACITY];
    private final long[] endMs = new long[CAPACITY];
    private int head;
    private int size;
    private long tickStartNs;

    public void onTickStart() {
        tickStartNs = System.nanoTime();
    }

    public void onTickEnd() {
        long start = tickStartNs;
        if (start == 0L) {
            return; // 没见到 Pre（例如中途注册），这一拍不记
        }
        long now = System.nanoTime();
        durationNs[head] = Math.max(0L, now - start);
        endMs[head] = System.currentTimeMillis();
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    public int samples() {
        return size;
    }

    /** 最近一次 tick 的耗时（毫秒）。 */
    public double currentMspt() {
        if (size == 0) {
            return 0.0;
        }
        int last = (head - 1 + CAPACITY) % CAPACITY;
        return durationNs[last] / 1_000_000.0;
    }

    /** 最近 60s 的均值与 p50/p95/p99（毫秒）。 */
    public double[] percentilesAndAverage() {
        int n = Math.min(size, MSPT_WINDOW);
        if (n == 0) {
            return new double[]{0, 0, 0, 0};
        }
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) {
            int idx = (head - n + i + CAPACITY) % CAPACITY;
            copy[i] = durationNs[idx];
        }
        double avg = 0;
        for (long v : copy) {
            avg += v;
        }
        avg = avg / n / 1_000_000.0;

        Arrays.sort(copy);
        double p50 = copy[(int) Math.min(n - 1, Math.round(0.50 * (n - 1)))] / 1_000_000.0;
        double p95 = copy[(int) Math.min(n - 1, Math.round(0.95 * (n - 1)))] / 1_000_000.0;
        double p99 = copy[(int) Math.min(n - 1, Math.round(0.99 * (n - 1)))] / 1_000_000.0;
        return new double[]{avg, p50, p95, p99};
    }

    /**
     * 窗口内真实 tick 速率。用「已完成 tick 的时间戳」计数，而不是 1000/mspt 推算——
     * 后者会把休眠时间算进去，且服务器追赶进度（catch-up）时反而显示正常。
     */
    public Double tps(int windowMs) {
        if (size < 2) {
            return null;
        }
        long now = System.currentTimeMillis();
        int counted = 0;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int idx = (head - 1 - i + CAPACITY) % CAPACITY;
            long t = endMs[idx];
            if (t <= 0L) {
                continue;
            }
            oldest = Math.min(oldest, t);
            if (now - t <= windowMs) {
                counted++;
            } else {
                break;
            }
        }
        if (oldest == Long.MAX_VALUE) {
            return null;
        }
        // 服务器刚起步时窗口未填满，用实际覆盖时长做分母，避免 TPS 被低估。
        long covered = Math.min(windowMs, now - oldest);
        if (covered < 500L) {
            return null;
        }
        double tps = counted * 1000.0 / covered;
        return Math.min(20.0, Math.round(tps * 100.0) / 100.0);
    }
}
