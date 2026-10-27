package com.alvar.observa.collect;

import com.alvar.observa.Observa;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 堆直方图：活着的对象按类计数 + 浅尺寸。
 *
 * <p>这是 JFR 补不上的那一块：{@code jdk.ObjectAllocationSample} 回答「谁 new 了多少」，
 * 但回答不了「现在堆里到底堆着谁」——泄漏的定义是后者（分配多但回收得掉不是泄漏）。</p>
 *
 * <p>两条路：</p>
 * <ul>
 *   <li>JFR 的 {@code jdk.OldObjectSample} 只有采样、且需要长时间录制才准；</li>
 *   <li>HPROF dump 是精确的，代价是一次 STW（本机满堆 351 MB 实测 0.39 秒）加一次解析。</li>
 * </ul>
 * <p>这里选 HPROF：dump 之后在独立线程解析，解析期间服务器照常跑；解析完默认删掉 .hprof
 * （几百 MB，落在存档旁边很容易被误提交）。想留原件就传 {@code keep_file=true}。</p>
 */
public final class HeapService {

    /** 保留几份结果（只有 last 会被 GET 返回，留几份是为了让 .hprof 的清理有上限）。 */
    private static final int KEEP_FILES = 3;
    private static final String PATH = "/heap/histogram";

    private static final HeapService INSTANCE = new HeapService();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "observa-heap");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger seq = new AtomicInteger();
    private volatile Job job;

    /** 一次 dump+解析的任务状态；{@code done} 之前 top 为空。 */
    public record Job(String id, String state, long startedAtMs, long dumpMs, long parseMs,
                      long fileBytes, boolean fileKept, String file, String error,
                      long totalInstances, long totalShallowBytes, int classCount, int skippedRecords,
                      List<HeapHistogram.Entry> top, List<HeapHistogram.PackageEntry> packages) {
    }

    private HeapService() {
    }

    public static HeapService instance() {
        return INSTANCE;
    }

    public Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("observa").resolve("heap");
    }

    /** 启动一次 dump+解析；已有任务在跑时返回 false（调用方回 409）。 */
    public synchronized boolean start(boolean live, boolean keepFile, int topN) {
        Job current = job;
        if (current != null && "running".equals(current.state())) {
            return false;
        }
        String id = "heap-" + seq.incrementAndGet();
        Job running = new Job(id, "running", System.currentTimeMillis(), 0, 0, 0, keepFile, null, null,
                0, 0, 0, 0, List.of(), List.of());
        job = running;
        worker.submit(() -> run(running, live, keepFile, topN));
        return true;
    }

    private void run(Job running, boolean live, boolean keepFile, int topN) {
        Path dir = dir();
        Path hprof = dir.resolve(running.id() + ".hprof");
        long dumpMs = 0;
        long parseMs = 0;
        long bytes = 0;
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(hprof); // dumpHeap 拒绝覆盖已有文件
            long t0 = System.nanoTime();
            HeapHistogram.dumpTo(hprof, live);
            dumpMs = (System.nanoTime() - t0) / 1_000_000L;
            bytes = Files.exists(hprof) ? Files.size(hprof) : 0;
            long t1 = System.nanoTime();
            HeapHistogram.Result result = HeapHistogram.parse(hprof, topN);
            parseMs = (System.nanoTime() - t1) / 1_000_000L;
            job = new Job(running.id(), "done", running.startedAtMs(), dumpMs, parseMs, bytes, keepFile,
                    hprof.toString(), null,
                    result.totalInstances(), result.totalShallowBytes(), result.classCount(), result.skippedRecords(),
                    result.top(), result.topPackages());
            Observa.LOGGER.info("[observa] heap histogram {} done: {} classes, {} instances, dump {}ms, parse {}ms",
                    running.id(), result.classCount(), result.totalInstances(), dumpMs, parseMs);
        } catch (Throwable t) {
            job = new Job(running.id(), "failed", running.startedAtMs(), dumpMs, parseMs, bytes, keepFile,
                    hprof.toString(), String.valueOf(t), 0, 0, 0, 0, List.of(), List.of());
            Observa.LOGGER.warn("[observa] heap histogram {} failed", running.id(), t);
        } finally {
            // 原件默认不保留：这里就是它唯一的生命周期终点。
            if (!keepFile) {
                try {
                    Files.deleteIfExists(hprof);
                } catch (IOException e) {
                    Observa.LOGGER.warn("[observa] cannot delete {}: {}", hprof, e.toString());
                }
            }
            trim();
        }
    }

    /** 只留最近 KEEP_FILES 份 .hprof；本次文件不在其中时不动它（keep_file 的语义是「留着」）。 */
    private void trim() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> hprofs = files.filter(p -> p.getFileName().toString().endsWith(".hprof"))
                    .sorted((a, b) -> Long.compare(mtime(b), mtime(a)))
                    .toList();
            for (int i = KEEP_FILES; i < hprofs.size(); i++) {
                Files.deleteIfExists(hprofs.get(i));
            }
        } catch (IOException e) {
            Observa.LOGGER.warn("[observa] cannot trim heap dir {}: {}", dir, e.toString());
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public JsonObject json() {
        Job j = job;
        JsonObject data = new JsonObject();
        data.addProperty("dir", dir().toString());
        if (j == null) {
            data.addProperty("state", "idle");
            data.addProperty("tip", "POST " + PATH + " to dump the heap and parse it; first call takes a few seconds");
            return data;
        }
        data.addProperty("id", j.id());
        data.addProperty("state", j.state());
        data.addProperty("started_at_ms", j.startedAtMs());
        if (j.dumpMs() > 0) {
            data.addProperty("dump_ms", j.dumpMs());
        }
        if (j.parseMs() > 0) {
            data.addProperty("parse_ms", j.parseMs());
        }
        if (j.fileBytes() > 0) {
            data.addProperty("file_bytes", j.fileBytes());
        }
        data.addProperty("file_kept", j.fileKept());
        if (j.file() != null) {
            data.addProperty("file", j.file());
        }
        if (j.error() != null) {
            data.addProperty("error", j.error());
        }
        if ("done".equals(j.state())) {
            data.addProperty("total_instances", j.totalInstances());
            data.addProperty("total_shallow_bytes", j.totalShallowBytes());
            data.addProperty("class_count", j.classCount());
            if (j.skippedRecords() > 0) {
                data.addProperty("skipped_records", j.skippedRecords());
            }
            JsonArray top = new JsonArray();
            for (HeapHistogram.Entry e : j.top()) {
                JsonObject row = new JsonObject();
                row.addProperty("class", e.className());
                row.addProperty("instances", e.instances());
                row.addProperty("shallow_bytes", e.shallowBytes());
                top.add(row);
            }
            data.add("top", top);
            JsonArray pkgs = new JsonArray();
            for (HeapHistogram.PackageEntry e : j.packages()) {
                JsonObject row = new JsonObject();
                row.addProperty("package", e.packageName());
                row.addProperty("instances", e.instances());
                row.addProperty("shallow_bytes", e.shallowBytes());
                pkgs.add(row);
            }
            data.add("packages", pkgs);
            JsonArray notes = new JsonArray();
            notes.add("shallow_bytes is an estimate: object header counted as 16 bytes, referents not included");
            notes.add("counts are live objects only when the dump was taken with live=true");
            notes.add("dump pauses the JVM for the duration reported in dump_ms; parsing runs off-thread");
            data.add("notes", notes);
        } else if ("running".equals(j.state())) {
            data.add("tip", JsonNull.INSTANCE);
            data.addProperty("next", "poll GET " + PATH + " in a few seconds");
        }
        return data;
    }
}
