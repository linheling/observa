package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.sun.management.OperatingSystemMXBean;

/**
 * 进程之外的机器状态：CPU、物理内存、swap、磁盘。
 *
 * <p>为什么单独一个端点：{@code /metrics} 里的内存是 JVM 堆，回答不了「这台机器是不是被别的进程占了」。
 * 服务器卡的原因有一半在 JVM 外面（磁盘慢、swap 抖动、别的进程抢 CPU），而 spark 的 {@code health}
 * 恰好覆盖这一块，这里用 {@link com.sun.management.OperatingSystemMXBean} 直接读。</p>
 *
 * <p>取值全部在请求线程上现算，不做缓存：这些 MXBean 调用是微秒级，且缓存会让「刚才那一下」消失。
 * 几个平台相关的坑写在各字段的注释里：Windows 的 load average 恒为 -1，CPU 负载偶尔返回负值表示
 * 「这次没采到」，磁盘只报游戏目录所在的那个卷。</p>
 */
public final class SystemStats {

    private SystemStats() {
    }

    /** 现算一份机器状态。读不到的量给 null（不是 0），调用方据此区分「没采到」和「真的是 0」。 */
    public static JsonObject system() {
        JsonObject data = new JsonObject();

        OperatingSystemMXBean os = osBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        JsonObject cpu = new JsonObject();

        // 进程自己用了多少 CPU（0..1）。不可用时 JDK 返回负数，这里必须给 null。
        double processLoad = os == null ? -1.0 : os.getProcessCpuLoad();
        cpu.add("process_load_pct", processLoad < 0 ? JsonNull.INSTANCE : pct(processLoad));

        // 整机 CPU 负载。并不是所有平台都实现（拿不到时返回 -1，转成 null）。
        double systemLoad = os == null ? -1.0 : os.getCpuLoad();
        cpu.add("system_load_pct", systemLoad < 0 ? JsonNull.INSTANCE : pct(systemLoad));

        // 进程累计 CPU 时间；两次请求相减就是这段时间里真实吃掉的 CPU。
        cpu.add("process_cpu_time_ms", os == null ? JsonNull.INSTANCE : new JsonPrimitive(os.getProcessCpuTime() / 1_000_000L));

        cpu.addProperty("processors", Runtime.getRuntime().availableProcessors());

        // 1 分钟平均负载：Windows 永远不支持，null 比 0 诚实。
        double loadAvg = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        cpu.add("system_load_avg_1m", loadAvg < 0 ? JsonNull.INSTANCE : new JsonPrimitive(round2(loadAvg)));

        cpu.addProperty("os", System.getProperty("os.name"));
        cpu.addProperty("arch", System.getProperty("os.arch"));
        data.add("cpu", cpu);

        JsonObject machine = new JsonObject();
        machine.add("physical_free_bytes", os == null ? JsonNull.INSTANCE : new JsonPrimitive(os.getFreeMemorySize()));
        machine.add("physical_total_bytes", os == null ? JsonNull.INSTANCE : new JsonPrimitive(os.getTotalMemorySize()));
        machine.add("swap_free_bytes", os == null ? JsonNull.INSTANCE : new JsonPrimitive(os.getFreeSwapSpaceSize()));
        machine.add("swap_total_bytes", os == null ? JsonNull.INSTANCE : new JsonPrimitive(os.getTotalSwapSpaceSize()));

        long free = os == null ? -1 : os.getFreeMemorySize();
        long total = os == null ? -1 : os.getTotalMemorySize();
        machine.add("physical_used_pct", free <= 0 || total <= 0 ? JsonNull.INSTANCE : pct(1.0 - (double) free / total));

        long swapFree = os == null ? -1 : os.getFreeSwapSpaceSize();
        long swapTotal = os == null ? -1 : os.getTotalSwapSpaceSize();
        machine.add("swap_used_pct", swapFree < 0 || swapTotal <= 0 ? JsonNull.INSTANCE : pct(1.0 - (double) swapFree / swapTotal));
        data.add("memory", machine);

        JsonObject process = new JsonObject();
        process.addProperty("pid", ProcessHandle.current().pid());
        process.addProperty("uptime_ms", runtime.getUptime());
        process.addProperty("threads", threads.getThreadCount());
        process.addProperty("peak_threads", threads.getPeakThreadCount());
        data.add("process", process);

        data.add("disk", disk());

        JsonArray notes = new JsonArray();
        notes.add("system_load_pct is whole-machine CPU; some platforms report null for it (then only process_load_pct is usable)");
        notes.add("disk covers the volume that holds the game directory, not every volume");
        notes.add("fields are computed per request; two requests let you derive a rate for cpu/disk");
        data.add("notes", notes);
        return data;
    }

    private static JsonObject disk() {
        JsonObject disk = new JsonObject();
        Path root = gameRoot();
        disk.addProperty("path", root.toString());
        try {
            FileStore store = Files.getFileStore(root);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            disk.addProperty("total_bytes", total);
            disk.addProperty("free_bytes", store.getUnallocatedSpace());
            disk.addProperty("usable_bytes", free);
            disk.add("used_pct", total <= 0 ? JsonNull.INSTANCE : pct(1.0 - (double) free / total));
            disk.addProperty("fs", store.name());
        } catch (IOException | RuntimeException e) {
            // 磁盘信息读不到不影响其它字段；如实说明，不伪造 0。
            disk.add("total_bytes", JsonNull.INSTANCE);
            disk.add("free_bytes", JsonNull.INSTANCE);
            disk.add("usable_bytes", JsonNull.INSTANCE);
            disk.add("used_pct", JsonNull.INSTANCE);
            disk.addProperty("error", String.valueOf(e));
        }
        return disk;
    }

    /** 游戏目录：磁盘要报的是「存档写在这块盘上」的那块盘，而不是 JVM 的当前工作目录。 */
    private static Path gameRoot() {
        try {
            Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            if (dir != null) {
                return dir;
            }
        } catch (Throwable ignored) {
            // 单测/静态初始化场景下没有 FML 环境，退回当前目录。
        }
        return Paths.get("").toAbsolutePath();
    }

    private static OperatingSystemMXBean osBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof OperatingSystemMXBean sun ? sun : null;
    }

    private static JsonPrimitive pct(double fraction) {
        return new JsonPrimitive(round2(fraction * 100.0));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
