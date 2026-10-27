package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * 网络概况：每个在线玩家的延迟（ping）与连接上的收发量。
 *
 * <p>两个数据源刻意分开：</p>
 * <ul>
 *   <li><b>ping</b> 直接读 {@code ServerPlayer.connection.latency()}——这是原版自己维护的往返延迟，
 *       spark 的 {@code /spark ping} 用的也是它，抄一份不产生额外流量。</li>
 *   <li><b>收发量</b>是本模组自己在 Netty 管道里插的计数器（{@link Tap}）。原版只给「平均包数」这种
 *       粗糙数字，而排查「谁在刷包」需要按玩家、按方向的包数与字节数。</li>
 * </ul>
 *
 * <p>计数器挂的位置刻意贴着<b>帧边界</b>，而不是管道末尾：末尾拿到的是 {@code Packet} 对象，量不出字节。
 * 入方向插在 {@code splitter} 之后（此时是压缩帧），出方向插在 {@code prepender} 之前（同样是压缩帧、
 * 但还没加长度前缀）。所以统计的是<b>压缩后的载荷字节</b>，同一个包在不同玩家身上量会有差别（压缩率不同），
 * 这是对的。帧数也不总是等于包数：一个包被拆成多个帧时会多算，小包被合并时相反——报告以字节为主，帧数为辅。</p>
 *
 * <p>所有读取都在服务器线程（{@link MetricsService} 刷新快照时）完成，HTTP 线程只读
 * {@link #json()} 用的那份不可变视图——Netty 的管道和玩家对象都不是线程安全的。</p>
 */
public final class NetService {

    /** 挂在帧边界上的两个处理器名（出方向在 prepender 之前，入方向在 splitter 之后）。 */
    static final String TAP_OUT = "observa_traffic_out";
    static final String TAP_IN = "observa_traffic_in";

    /** 原版管道里两个可依赖的锚点；找不到时退回管道两端（退化为只数帧、字节可能为 0）。 */
    private static final String ANCHOR_IN = "splitter";
    private static final String ANCHOR_OUT = "prepender";

    /** 一个玩家某一刻的网络读数。ping 拿不到时是 null（本地连接的原版实现会返回 0）。 */
    public record PlayerNet(String name, String uuid, Integer pingMs,
                            long sentPackets, long sentBytes, long receivedPackets, long receivedBytes,
                            double sentPacketsPerSecond, double receivedPacketsPerSecond,
                            double sentBytesPerSecond, double receivedBytesPerSecond) {
    }

    private static volatile List<PlayerNet> players = List.of();
    private static volatile long capturedAtMs;
    private static volatile long totalSentPackets;
    private static volatile long totalSentBytes;
    private static volatile long totalReceivedPackets;
    private static volatile long totalReceivedBytes;
    /** 上一次滚动窗口的累计值，用来算每秒速率；只在服务器线程上动。 */
    private static final Map<UUID, long[]> PREVIOUS = new HashMap<>();

    private NetService() {
    }

    /** 服务器线程调用：抓一次所有玩家的网络读数。 */
    public static void capture(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<PlayerNet> fresh = new ArrayList<>();
        long sp = 0;
        long sb = 0;
        long rp = 0;
        long rb = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Integer ping = null;
            try {
                ping = player.connection.latency();
            } catch (RuntimeException ignored) {
                // 有的实现/某些状态下拿不到；null 比 0 诚实。
            }

            Counter out = tap(player, true);
            Counter in = tap(player, false);
            long sentPackets = out == null ? 0 : out.packets();
            long sentBytes = out == null ? 0 : out.bytes();
            long receivedPackets = in == null ? 0 : in.packets();
            long receivedBytes = in == null ? 0 : in.bytes();
            sp += sentPackets;
            sb += sentBytes;
            rp += receivedPackets;
            rb += receivedBytes;

            UUID id = player.getUUID();
            long[] prev = PREVIOUS.get(id);
            double seconds = prev == null || now <= prev[4] ? 0.0 : (now - prev[4]) / 1000.0;
            double sentPerS = 0.0;
            double sentBytesPerS = 0.0;
            double recvPerS = 0.0;
            double recvBytesPerS = 0.0;
            if (prev != null && seconds > 0.05) {
                sentPerS = round1((sentPackets - prev[0]) / seconds);
                sentBytesPerS = round1((sentBytes - prev[1]) / seconds);
                recvPerS = round1((receivedPackets - prev[2]) / seconds);
                recvBytesPerS = round1((receivedBytes - prev[3]) / seconds);
            }
            PREVIOUS.put(id, new long[]{sentPackets, sentBytes, receivedPackets, receivedBytes, now});

            fresh.add(new PlayerNet(player.getGameProfile().getName(), id.toString(), ping,
                    sentPackets, sentBytes, receivedPackets, receivedBytes,
                    sentPerS, recvPerS, sentBytesPerS, recvBytesPerS));
        }
        // 玩家离开后把他那一行清掉，否则速率会一直用旧基线下发 0。
        PREVIOUS.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());

        fresh.sort((a, b) -> {
            Integer pa = a.pingMs() == null ? -1 : a.pingMs();
            Integer pb = b.pingMs() == null ? -1 : b.pingMs();
            return Integer.compare(pb, pa);
        });
        players = List.copyOf(fresh);
        capturedAtMs = now;
        totalSentPackets = sp;
        totalSentBytes = sb;
        totalReceivedPackets = rp;
        totalReceivedBytes = rb;
    }

    /**
     * 返回该玩家某个方向上的计数器，必要时把处理器插进管道。
     *
     * <p>插在帧编解码器旁边而不是管道末尾：末尾拿到的已经是 {@code Packet} 对象，量不出字节；
     * 帧边界上的才是 {@link ByteBuf}。原版管道名字（{@code splitter}/{@code prepender}）在
     * 版本之间稳定，但这里仍然用「找不到就退回 addLast」的写法，退化成只数包不数字节。</p>
     */
    private static Counter tap(ServerPlayer player, boolean outbound) {
        try {
            Channel channel = player.connection.getConnection().channel();
            if (channel == null) {
                return null;
            }
            ChannelPipeline pipeline = channel.pipeline();
            String name = outbound ? TAP_OUT : TAP_IN;
            if (pipeline.get(name) instanceof Counter existing) {
                return existing;
            }
            io.netty.channel.ChannelHandler tap = outbound ? new OutboundTap() : new InboundTap();
            if (outbound) {
                if (pipeline.get(ANCHOR_OUT) != null) {
                    pipeline.addBefore(ANCHOR_OUT, name, tap);
                } else {
                    pipeline.addFirst(name, tap);
                }
            } else {
                if (pipeline.get(ANCHOR_IN) != null) {
                    pipeline.addAfter(ANCHOR_IN, name, tap);
                } else {
                    pipeline.addLast(name, tap);
                }
            }
            return (Counter) tap;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 一个方向上的两个计数。出/入各一个实例，避免同一个处理器把两个方向都算进来。 */
    interface Counter {
        long packets();

        long bytes();
    }

    /** 两个方向共用的计数逻辑：在帧边界上，消息是 {@link ByteBuf}。 */
    abstract static class FrameTap {
        private final LongAdder packets = new LongAdder();
        private final LongAdder bytes = new LongAdder();

        final void count(Object msg) {
            packets.increment();
            if (msg instanceof ByteBuf buf) {
                bytes.add(buf.readableBytes());
            }
        }

        public final long frames() {
            return packets.sum();
        }

        public final long byteCount() {
            return bytes.sum();
        }
    }

    static final class OutboundTap extends ChannelOutboundHandlerAdapter implements Counter {
        private final FrameTap frames = new FrameTap() {
        };

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
            frames.count(msg);
            super.write(ctx, msg, promise);
        }

        @Override
        public long packets() {
            return frames.frames();
        }

        @Override
        public long bytes() {
            return frames.byteCount();
        }
    }

    static final class InboundTap extends ChannelInboundHandlerAdapter implements Counter {
        private final FrameTap frames = new FrameTap() {
        };

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            frames.count(msg);
            super.channelRead(ctx, msg);
        }

        @Override
        public long packets() {
            return frames.frames();
        }

        @Override
        public long bytes() {
            return frames.byteCount();
        }
    }

    public static JsonObject json() {
        JsonObject data = new JsonObject();
        if (capturedAtMs == 0) {
            data.add("captured_at_ms", JsonNull.INSTANCE);
        } else {
            data.addProperty("captured_at_ms", capturedAtMs);
        }
        JsonObject totals = new JsonObject();
        totals.addProperty("sent_packets", totalSentPackets);
        totals.addProperty("sent_bytes", totalSentBytes);
        totals.addProperty("received_packets", totalReceivedPackets);
        totals.addProperty("received_bytes", totalReceivedBytes);
        data.add("totals", totals);

        JsonArray rows = new JsonArray();
        for (PlayerNet p : players) {
            JsonObject row = new JsonObject();
            row.addProperty("name", p.name());
            row.addProperty("uuid", p.uuid());
            if (p.pingMs() == null) {
                row.add("ping_ms", JsonNull.INSTANCE);
            } else {
                row.addProperty("ping_ms", p.pingMs());
            }
            JsonObject sent = new JsonObject();
            sent.addProperty("packets", p.sentPackets());
            sent.addProperty("bytes", p.sentBytes());
            sent.addProperty("packets_per_s", p.sentPacketsPerSecond());
            sent.addProperty("bytes_per_s", p.sentBytesPerSecond());
            row.add("sent", sent);
            JsonObject recv = new JsonObject();
            recv.addProperty("packets", p.receivedPackets());
            recv.addProperty("bytes", p.receivedBytes());
            recv.addProperty("packets_per_s", p.receivedPacketsPerSecond());
            recv.addProperty("bytes_per_s", p.receivedBytesPerSecond());
            row.add("received", recv);
            rows.add(row);
        }
        data.add("players", rows);
        data.addProperty("online", rows.size());

        JsonArray notes = new JsonArray();
        notes.add("ping_ms comes from the server's own keep-alive RTT, the same number /spark ping shows");
        notes.add("sent/received.packets counts frames at the compression boundary, not logical packets: "
                + "a large packet split into several frames counts more than once, and every tick adds two "
                + "bundle-delimiter frames (~2/s * 20 ticks). bytes are compressed payload bytes, no frame length prefix");
        notes.add("packets_per_s / bytes_per_s need two captures: the first poll after a player joins reports 0");
        notes.add("null ping means the connection has not completed a round trip yet");
        data.add("notes", notes);
        return data;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
