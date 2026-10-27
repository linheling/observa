package com.alvar.observa.http;

import com.alvar.observa.collect.ProfilerService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 剖析报告 → JSON。百分比保留两位，帧名去掉包名（可读 + 省 token），
 * 需要全限定名时看 {@code frame_format} 的说明。
 *
 * <p>百分比的分母是 <b>busy_samples</b>（总样本减去空闲样本），这样 Top-1
 * 反映的是真正在干活时的热点，而不是"服务器大部分时间在 park"这个废话。
 */
public final class ProfilerJson {

    private ProfilerJson() {
    }

    public static JsonObject report(ProfilerService.Report report) {
        JsonObject data = new JsonObject();
        data.addProperty("id", report.id());
        data.addProperty("mode", "sample");
        data.addProperty("thread", report.thread());
        data.addProperty("frame_format", "SimpleClassName.methodName (see lines[] for source line numbers)");
        data.addProperty("started_at_ms", report.startedAtMs());
        data.addProperty("duration_ms", report.durationMs());
        data.addProperty("interval_ms", report.intervalMs());
        data.addProperty("samples", report.samples());
        data.addProperty("idle_samples", report.idleSamples());
        data.addProperty("busy_samples", Math.max(0, report.samples() - report.idleSamples()));
        data.addProperty("idle_pct", pct(report.idleSamples(), Math.max(1, report.samples())));

        long busy = Math.max(1, report.samples() - report.idleSamples());

        JsonArray top = new JsonArray();
        for (ProfilerService.Frame frame : report.top()) {
            JsonObject o = new JsonObject();
            o.addProperty("frame", frame.name());
            o.addProperty("self_pct", pct(frame.selfSamples(), busy));
            o.addProperty("total_pct", pct(frame.totalSamples(), busy));
            o.addProperty("self_samples", frame.selfSamples());
            o.addProperty("total_samples", frame.totalSamples());
            top.add(o);
        }
        data.add("top", top);

        // 行号级热点：同一个方法里不同代码行的成本可以差一个数量级，
        // 只给方法名会让 agent 去优化"整个方法"，而真正该改的往往是一行。
        JsonArray lines = new JsonArray();
        for (ProfilerService.LineHit hit : report.lines()) {
            JsonObject o = new JsonObject();
            o.addProperty("frame", hit.frame());
            o.addProperty("line", hit.line());
            o.addProperty("self_pct", pct(hit.selfSamples(), busy));
            o.addProperty("self_samples", hit.selfSamples());
            lines.add(o);
        }
        data.add("lines", lines);

        JsonArray tree = new JsonArray();
        for (ProfilerService.TreeNode node : report.tree()) {
            tree.add(node(node, busy));
        }
        data.add("tree", tree);

        data.addProperty("top_truncated", report.topTruncated());
        data.addProperty("tree_truncated", report.treeTruncated());

        JsonArray notes = new JsonArray();
        notes.add("busy_samples = samples taken inside MinecraftServer.tickServer; idle_samples = waiting between ticks");
        notes.add("percentages use busy_samples as denominator (idle samples excluded)");
        notes.add("self_pct = time inside this frame itself; total_pct includes callees");
        notes.add("tree starts at the outermost frame, children ordered by sample count");
        notes.add("jdk frames are excluded unless profiler.include_jdk_frames = true");
        notes.add("short recordings (<200 busy samples) are noisy; prefer 15-30s at 5ms");
        notes.add("lines[] gives the source line of the innermost game frame: the exact line burning samples");
        if (report.samples() - report.idleSamples() <= 0L) {
            // 空报告最容易骗人：要明说「是服务器闲着」而不是「剖析器坏了」。
            notes.add("busy_samples = 0: every sample was taken while the server waited between ticks, "
                    + "so top/tree are empty by construction. Record longer, lower interval_ms, "
                    + "or induce load before recording.");
        }
        data.add("notes", notes);
        return data;
    }

    public static JsonObject status(ProfilerService.Status status) {
        JsonObject data = new JsonObject();
        data.addProperty("running", true);
        data.addProperty("id", status.id());
        data.addProperty("elapsed_ms", status.elapsedMs());
        data.addProperty("remaining_ms", status.remainingMs());
        data.addProperty("samples", status.samples());
        data.addProperty("interval_ms", status.intervalMs());
        return data;
    }

    private static JsonObject node(ProfilerService.TreeNode node, long total) {
        JsonObject o = new JsonObject();
        o.addProperty("frame", node.name());
        o.addProperty("self_pct", pct(node.selfSamples(), total));
        o.addProperty("total_pct", pct(node.totalSamples(), total));
        if (!node.children().isEmpty()) {
            JsonArray children = new JsonArray();
            for (ProfilerService.TreeNode child : node.children()) {
                children.add(node(child, total));
            }
            o.add("children", children);
        }
        return o;
    }

    private static double pct(long part, long total) {
        return Math.round(part * 10000.0 / total) / 100.0;
    }
}
