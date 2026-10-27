package com.alvar.observa.blackbox;

import com.alvar.observa.api.Snapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 环形缓冲：保留最近 N 份轻量快照，供黑匣子回溯「触发前发生了什么」。
 *
 * <p>存的是压缩后的 {@link Frame} 而不是整份 {@link Snapshot}：快照里有按类型/按区块的完整
 * 计数表（各上百条），300 帧全留着就是几十 MB 常驻堆——黑匣子自己要吃掉它想监控的内存。
 *
 * <p>写入在服务器线程（tick 结尾），读取在 HTTP 线程；用 {@code synchronized} 保护，代价是
 * 每次写入几微秒，远低于一次分配的噪声。
 */
public final class RingBuffer {

    /** 一帧：只留判读需要的量。字段名与 HTTP 输出一一对应，不在这里做任何推导。 */
    public record Frame(
            long atMs,
            double msptNowMs,
            double msptAvg1m,
            double p95Ms,
            double p99Ms,
            Double tps1m,
            long heapUsedBytes,
            long heapMaxBytes,
            long gcCount,
            long entities,
            int chunks,
            int players,
            boolean frozen,
            long sinceTickMs) {
    }

    private final Frame[] frames;
    private final long[] stamps;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        int safe = Math.max(1, capacity);
        this.frames = new Frame[safe];
        this.stamps = new long[safe];
    }

    public synchronized void push(Frame frame) {
        frames[head] = frame;
        stamps[head] = frame.atMs();
        head = (head + 1) % frames.length;
        if (size < frames.length) {
            size++;
        }
    }

    /**
     * 正常帧：由 tick 回调推进，数据新鲜。
     *
     * @param sinceTickMs 距上一次 tick 完成过了多久；正常情况下是 0–几十毫秒
     */
    public static Frame frameOf(Snapshot s, long atMs, long sinceTickMs) {
        return new Frame(
                atMs,
                s.tick().msptNowMs(),
                s.tick().msptAvg1m(),
                s.tick().p95Ms(),
                s.tick().p99Ms(),
                s.tick().tps1m(),
                s.memory().heapUsedBytes(),
                s.memory().heapMaxBytes(),
                s.memory().gcCount(),
                s.entities().total(),
                s.chunks().loaded(),
                s.players().online(),
                false,
                Math.max(0L, sinceTickMs));
    }

    /**
     * 冻结帧：tick 已经停了，但墙上时间还在走。
     *
     * <p>用最后一次成功的快照填数值（没有就填 0），并把 {@code frozen} 标成 true——
     * 判读时看到连续的 frozen 帧就是「服务器卡死」，而不是「数据采集坏了」。</p>
     */
    public static Frame frozen(Snapshot last, long atMs, long sinceTickMs) {
        if (last == null) {
            return new Frame(atMs, 0.0, 0.0, 0.0, 0.0, null, 0L, 0L, 0L, 0L, 0, 0, true, Math.max(0L, sinceTickMs));
        }
        Frame base = frameOf(last, atMs, sinceTickMs);
        return new Frame(atMs, base.msptNowMs(), base.msptAvg1m(), base.p95Ms(), base.p99Ms(), base.tps1m(),
                base.heapUsedBytes(), base.heapMaxBytes(), base.gcCount(), base.entities(), base.chunks(),
                base.players(), true, Math.max(0L, sinceTickMs));
    }

    public synchronized int size() {
        return size;
    }

    public synchronized long oldestAtMs() {
        if (size == 0) {
            return -1L;
        }
        int oldest = (head - size + frames.length) % frames.length;
        return stamps[oldest];
    }

    /** 按时间正序（旧 → 新）导出，最多 {@code maxFrames} 帧。 */
    public synchronized JsonArray toJson(int maxFrames) {
        int limit = Math.min(size, Math.max(1, maxFrames));
        JsonArray arr = new JsonArray();
        int start = (head - size + frames.length) % frames.length;
        int skip = size - limit;
        for (int i = 0; i < limit; i++) {
            Frame f = frames[(start + skip + i) % frames.length];
            if (f == null) {
                continue;
            }
            arr.add(toJson(f));
        }
        return arr;
    }

    public static JsonObject toJson(Frame f) {
        JsonObject o = new JsonObject();
        o.addProperty("at_ms", f.atMs());
        o.addProperty("mspt_now_ms", f.msptNowMs());
        o.addProperty("mspt_avg_1m", f.msptAvg1m());
        o.addProperty("mspt_p95_ms", f.p95Ms());
        o.addProperty("mspt_p99_ms", f.p99Ms());
        o.addProperty("tps_1m", f.tps1m());
        o.addProperty("heap_used_bytes", f.heapUsedBytes());
        o.addProperty("heap_max_bytes", f.heapMaxBytes());
        o.addProperty("gc_count", f.gcCount());
        o.addProperty("entities", f.entities());
        o.addProperty("chunks", f.chunks());
        o.addProperty("players", f.players());
        o.addProperty("frozen", f.frozen());
        o.addProperty("since_tick_ms", f.sinceTickMs());
        return o;
    }
}
