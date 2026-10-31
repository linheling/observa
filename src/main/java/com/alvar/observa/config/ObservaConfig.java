package com.alvar.observa.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON 配置（单机集成服务器与专用服务器都加载）。
 *
 * <p>设计约束：bind 地址不开放为配置项（硬编码 127.0.0.1），避免被改成 0.0.0.0
 * 把观测接口暴露到公网。
 */
public final class ObservaConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.ConfigValue<String> TOKEN;
    public static final ModConfigSpec.IntValue RATE_LIMIT_PER_SECOND;

    public static final ModConfigSpec.IntValue METRICS_INTERVAL_MS;
    public static final ModConfigSpec.IntValue RING_SECONDS;
    public static final ModConfigSpec.IntValue TOP_N_DEFAULT;
    public static final ModConfigSpec.IntValue TOP_N_MAX;

    public static final ModConfigSpec.BooleanValue BLACKBOX_ENABLED;
    public static final ModConfigSpec.IntValue BLACKBOX_MSPT_THRESHOLD_MS;
    public static final ModConfigSpec.IntValue BLACKBOX_SUSTAIN_SECONDS;
    public static final ModConfigSpec.BooleanValue BLACKBOX_ON_SHUTDOWN;
    public static final ModConfigSpec.IntValue BLACKBOX_MIN_SERVER_TICKS;
    public static final ModConfigSpec.IntValue BLACKBOX_GRACE_SECONDS;
    public static final ModConfigSpec.IntValue BLACKBOX_FREEZE_AFTER_MS;

    public static final ModConfigSpec.IntValue PROFILER_MAX_SECONDS;
    public static final ModConfigSpec.IntValue PROFILER_SAMPLE_DEPTH;
    public static final ModConfigSpec.BooleanValue TUNNEL_ENABLED;
    public static final ModConfigSpec.IntValue TUNNEL_PERMISSION_LEVEL;
    public static final ModConfigSpec.BooleanValue PROFILER_INCLUDE_JDK_FRAMES;
    public static final ModConfigSpec.BooleanValue DEBUG_ALLOW_SELFTEST;
    public static final ModConfigSpec.BooleanValue DEBUG_RETAINED_ON_START;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Observa —— 供 agent 读取的 Minecraft 运行时观测接口。只监听回环地址。").push("http");
        ENABLED = b.comment("服务器启动时是否开启本地 HTTP 接口。")
                .define("enabled", true);
        PORT = b.comment("监听端口。被占用时自动尝试 port+1 … port+9，实际端口写入日志。")
                .defineInRange("port", 8724, 1024, 65535);
        TOKEN = b.comment("鉴权 token（Authorization: Bearer）。留空则首次启动生成到 config/observa/token.txt。")
                .define("token", "");
        RATE_LIMIT_PER_SECOND = b.comment("全局请求速率上限（次/秒）。")
                .defineInRange("rate_limit_per_second", 20, 1, 1000);
        b.pop();

        b.comment("常开采集（开销目标 < 1% MSPT）。").push("metrics");
        METRICS_INTERVAL_MS = b.comment("轻量指标采样间隔（毫秒）。")
                .defineInRange("interval_ms", 1000, 50, 60000);
        RING_SECONDS = b.comment("环形缓冲保留时长（秒），黑匣子回溯窗口。")
                .defineInRange("ring_seconds", 300, 60, 3600);
        TOP_N_DEFAULT = b.comment("Top-N 排行榜默认条数。")
                .defineInRange("top_n_default", 10, 1, 100);
        TOP_N_MAX = b.comment("Top-N 排行榜允许的最大条数（请求参数 top 的上限）。")
                .defineInRange("top_n_max", 100, 1, 1000);
        b.pop();

        b.comment("黑匣子：环形缓冲 + 异常自动落盘。").push("blackbox");
        BLACKBOX_ENABLED = b.comment("是否启用黑匣子。")
                .define("enabled", true);
        BLACKBOX_MSPT_THRESHOLD_MS = b.comment("触发落盘的 MSPT 阈值（毫秒）。")
                .defineInRange("mspt_threshold_ms", 50, 5, 5000);
        BLACKBOX_SUSTAIN_SECONDS = b.comment("MSPT 超过阈值持续多少秒才触发。")
                .defineInRange("sustain_seconds", 5, 1, 60);
        BLACKBOX_ON_SHUTDOWN = b.comment("正常停服时也写一份黑匣子。")
                .define("on_shutdown", false);
        BLACKBOX_MIN_SERVER_TICKS = b.comment("自动落盘的启动宽限之一：服务器至少跑完这么多 tick 才判定卡顿。",
                        "世界加载/区块生成阶段 MSPT 天然超阈值，没有宽限就会在开局误报一堆 dump。")
                .defineInRange("min_server_ticks", 400, 0, 1000000);
        BLACKBOX_GRACE_SECONDS = b.comment("自动落盘的启动宽限之二：服务器启动后的这么多秒内不自动落盘。",
                        "两个宽限是 AND 关系：tick 数与墙钟都过了才会触发。")
                .defineInRange("grace_seconds", 60, 0, 86400);
        BLACKBOX_FREEZE_AFTER_MS = b.comment("多久没看到 tick 完成就算「冻住」，此时心跳线程仍推进环形缓冲。",
                        "卡死时 tick 不再触发，如果只靠 tick 回调，环形缓冲会正好在最需要它的时候停止增长。")
                .defineInRange("freeze_after_ms", 2000, 200, 60000);
        b.pop();

        b.comment("按需剖析。").push("profiler");
        PROFILER_MAX_SECONDS = b.comment("单次录制最长秒数。")
                .defineInRange("max_seconds", 300, 10, 1800);
        PROFILER_SAMPLE_DEPTH = b.comment("采样器每帧抓取的栈深度。",
                        "MC 实体 tick 的调用栈常常超过 64 层，设小了会在栈顶被截断，",
                        "于是每次采样的\"最外层帧\"都不一样，调用树会碎成几十个根。",
                        "256 覆盖绝大多数情况；调大到 512 只会让单次采样拷贝的帧更多。")
                .defineInRange("sample_depth", 256, 8, 512);
        PROFILER_INCLUDE_JDK_FRAMES = b.comment("剖析报告是否保留 JDK 内部栈帧。")
                .define("include_jdk_frames", false);
        b.pop();

        b.comment("客户端隧道：把游戏连接当隧道，让玩家机器上的 agent 读到服务端观测数据。",
                        "服务端不需要开放任何端口；数据只发给满足权限等级的在线玩家。")
                .push("tunnel");
        TUNNEL_ENABLED = b.comment("服务端是否允许隧道。关掉后客户端请求一律 503。")
                .define("enabled", true);
        TUNNEL_PERMISSION_LEVEL = b.comment("需要的最低权限等级（0=任何人都能读，2=OP，4=最高）。",
                        "默认 2：观测数据包含日志与实体/区块细节，等同服务器内部信息。")
                .defineInRange("permission_level", 2, 0, 4);
        b.pop();

        b.comment("自测开关。默认全关。").push("debug");
        DEBUG_ALLOW_SELFTEST = b.comment("允许 POST /observa/v1/selftest/stall：阻塞服务器线程 N 毫秒。",
                        "唯一用途是验证「卡死时环形缓冲/自动落盘是否仍然工作」。打开它会让服务器真的卡一下，",
                        "所以默认关闭，只在自己验证黑匣子时临时开启。")
                .define("allow_selftest", false);
        DEBUG_RETAINED_ON_START = b.comment("服务端启动后立刻跑一次 retained size（支配树）分析，并把摘要写进日志。",
                        "这是 /observa/v1/heap/retained 的端到端自检：它会 dump 一次堆（STW 一次）并建整张对象图，",
                        "在开发机上约几百毫秒到几秒、额外几十到上百 MB 临时内存。只用于验证，默认关闭。")
                .define("retained_on_start", false);
        b.pop();

        SPEC = b.build();
    }

    private ObservaConfig() {
    }
}
