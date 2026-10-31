package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 类名 → 模组的归属索引。回答「这块内存是哪个 mod 的」。
 *
 * <h2>为什么不用 ModList 的扫描数据</h2>
 * <p>NeoForge 有 {@code ModFileScanData} 这类现成接口，但那是加载器内部契约，跨版本会改名；
 * 本项目零第三方依赖、又不该为了归因去猜 API。所以这里只做两件确定的事：</p>
 * <ul>
 *   <li>扫 {@code <gameDir>/mods/*.jar}：jar 内 {@code neoforge.mods.toml}（或老式 {@code mods.toml}）
 *       的 {@code modId} 就是权威 id，jar 里每个 {@code .class} 条目给出**包名**；</li>
 *   <li>前缀规则兜底：{@code net.minecraft.} / {@code com.mojang.} → minecraft，
 *       {@code net.neoforged.} / {@code cpw.mods.} → neoforge，{@code java.} 等 → jdk，
 *       常见库包 → library（它们不属于任何 mod，归给 mod 只会误导）。</li>
 * </ul>
 *
 * <h2>只索引包名，不索引类名</h2>
 * <p>一个 200 mod 的整合包有几十万个类，把 FQCN 全记下来要吃几十 MB 堆——为了一个诊断工具
 * 不划算。包名数量只有类的百分之一量级，而 mod 的类不会散落到无关包名里，所以
 * 「最长包前缀匹配」既省内存又够准。代价：两个 mod 把类塞进同一个包时只能记先到的那个，
 * 冲突数会如实报在 {@code stats.package_conflicts} 里。</p>
 *
 * <h2>已知盲区（如实上报，不猜）</h2>
 * <ul>
 *   <li>开发环境（{@code runServer}）里 mod 走 Gradle classpath 而不是 {@code mods/}，
 *       所以索引是空的，非原版类会落到 {@code unknown}；{@code stats.mods_indexed} 会露馅。</li>
 *   <li>跨包塞进 {@code net.minecraft.*} 的类（mixin 辅助类之类）按前缀规则算 minecraft。</li>
 *   <li>不属于任何 mod 的库（fastutil、netty、gson）单独归为 {@code library}，
 *       因为把 「it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap 占了 80 MB」算到某个 mod 头上是错的。</li>
 * </ul>
 */
public final class ModIndex {

    /** 归因结果：(modId, 展示名)。 */
    public record ModRef(String modId, String displayName) {
    }

    /** 按 mod 汇总的一行。 */
    public record ModTotal(String modId, String displayName, long instances, long bytes, int classes) {
    }

    public static final ModRef MINECRAFT = new ModRef("minecraft", "Minecraft");
    public static final ModRef NEOFORGE = new ModRef("neoforge", "NeoForge");
    public static final ModRef JDK = new ModRef("jdk", "JDK");
    public static final ModRef LIBRARY = new ModRef("library", "third-party library");
    public static final ModRef UNKNOWN = new ModRef("unknown", "unattributed");

    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
    private static final int TOML_READ_LIMIT = 64 * 1024;

    /**
     * 这些包不属于任何 mod。顺序不重要，命中即用。
     *
     * <p>注意 {@code com.mojang.} 整段都归 minecraft（前缀规则在库规则之前），
     * 所以不要再列 {@code com.mojang.blaze3d.} 之类的子前缀——永远匹配不到。</p>
     */
    private static final String[] LIBRARY_PREFIXES = {
            "it.unimi.dsi.", "io.netty.", "com.google.", "org.apache.", "org.slf4j.",
            "org.jetbrains.", "org.objectweb.", "org.spongepowered.", "com.electronwill.",
            "org.lwjgl.", "oshi.", "org.joml."
    };

    private static volatile ModIndex instance;

    private final Map<String, ModRef> byPackage;
    private final List<ModRef> mods;
    private final String modsDir;
    private final int classesIndexed;
    private final int packageConflicts;

    private ModIndex(Map<String, ModRef> byPackage, List<ModRef> mods, String modsDir,
                     int classesIndexed, int packageConflicts) {
        this.byPackage = byPackage;
        this.mods = mods;
        this.modsDir = modsDir;
        this.classesIndexed = classesIndexed;
        this.packageConflicts = packageConflicts;
    }

    public static ModIndex get() {
        ModIndex local = instance;
        if (local == null) {
            synchronized (ModIndex.class) {
                local = instance;
                if (local == null) {
                    local = build();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 模组不热重载；万一有热重载场景，这里可以重建。 */
    public static synchronized void invalidate() {
        instance = null;
    }

    // ------------------------------------------------------------------ 建索引

    private static ModIndex build() {
        Map<String, ModRef> byPackage = new HashMap<>(1 << 12);
        List<ModRef> mods = new ArrayList<>();
        String dir = null;
        int classes = 0;
        int conflicts = 0;

        Path modsDir;
        try {
            modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
            dir = modsDir.toString();
        } catch (Throwable ignored) {
            // 没有 FML 环境（单测）：只留前缀规则。
            return new ModIndex(byPackage, mods, null, 0, 0);
        }

        if (!Files.isDirectory(modsDir)) {
            return new ModIndex(byPackage, mods, dir, 0, 0);
        }

        List<Path> jars;
        try (Stream<Path> stream = Files.list(modsDir)) {
            jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            return new ModIndex(byPackage, mods, dir, 0, 0);
        }

        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            String fallbackId = fileName.substring(0, Math.max(0, fileName.length() - 4));
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                // 每只 jar 只开一次：读 toml 定归属，同一次打开顺手遍历 class 条目
                ModRef ref = readModRef(zip, fallbackId);
                mods.add(ref);
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    classes++;
                    int slash = name.lastIndexOf('/');
                    if (slash <= 0) {
                        continue; // 默认包：无法归因，留给 unknown
                    }
                    String pkg = name.substring(0, slash).replace('/', '.');
                    ModRef existing = byPackage.get(pkg);
                    if (existing == null) {
                        byPackage.put(pkg, ref);
                    } else if (!existing.modId().equals(ref.modId())) {
                        // 同一个包被两个 mod 占用：保留先到的，冲突计数上报。
                        conflicts++;
                    }
                }
            } catch (IOException e) {
                // 单只 jar 读不动（损坏/权限）：按文件名记一条，不中断整轮索引。
                mods.add(new ModRef(fallbackId, fallbackId));
            }
        }
        return new ModIndex(byPackage, mods, dir, classes, conflicts);
    }

    /**
     * 已打开的 jar → {@link ModRef}：先看 toml（权威），再看文件名（兜底）。
     *
     * <p>一只 jar 里嵌多个 mod 时只取第一个 `modId`：包名归属在这种 jar 里本来就有歧义，
     * 与其猜不如稳定地取第一个（NeoForge 的 jarJar 布局下这是常见情况）。</p>
     */
    private static ModRef readModRef(ZipFile zip, String fallbackId) {
        for (String tomlName : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
            try {
                ZipEntry entry = zip.getEntry(tomlName);
                if (entry == null) {
                    continue;
                }
                String text;
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(TOML_READ_LIMIT);
                    text = new String(bytes, StandardCharsets.UTF_8);
                }
                Matcher id = MOD_ID.matcher(text);
                if (!id.find()) {
                    continue;
                }
                String modId = id.group(1);
                Matcher name = DISPLAY_NAME.matcher(text);
                String display = name.find() ? name.group(1) : modId;
                return new ModRef(modId, display);
            } catch (IOException ignored) {
                // 换个候选文件再试
            }
        }
        return new ModRef(fallbackId, fallbackId);
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 类名 → mod。接受数组写法（{@code byte[]}、{@code foo.Bar[]}）、内部类
     * （{@code a.b.C$D}，按包名归因所以内层名不影响结果）。
     */
    public ModRef modOfClass(String className) {
        if (className == null || className.isEmpty()) {
            return UNKNOWN;
        }
        String name = stripArraySuffix(className);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return UNKNOWN; // 基本类型 / 默认包
        }
        String pkg = name.substring(0, dot);

        // 最长包前缀匹配：a.b.C$D 命中 a.b 也行
        String candidate = pkg;
        while (true) {
            ModRef ref = byPackage.get(candidate);
            if (ref != null) {
                return ref;
            }
            int cut = candidate.lastIndexOf('.');
            if (cut < 0) {
                break;
            }
            candidate = candidate.substring(0, cut);
        }
        return byPrefixRule(pkg);
    }

    /** 环境自带的归属：原版 / NeoForge / JDK / 第三方库。 */
    private static ModRef byPrefixRule(String pkg) {
        if (pkg.startsWith("net.minecraft.") || pkg.equals("net.minecraft") || pkg.startsWith("com.mojang.")) {
            return MINECRAFT;
        }
        if (pkg.startsWith("net.neoforged.") || pkg.startsWith("cpw.mods.") || pkg.startsWith("net.minecraftforge.")) {
            return NEOFORGE;
        }
        if (pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jdk.")
                || pkg.startsWith("sun.") || pkg.startsWith("com.sun.")) {
            return JDK;
        }
        for (String prefix : LIBRARY_PREFIXES) {
            if (pkg.startsWith(prefix) || (prefix.endsWith(".") && pkg.equals(prefix.substring(0, prefix.length() - 1)))) {
                return LIBRARY;
            }
        }
        // 没有包名的类（极少）也会走到这里
        return UNKNOWN;
    }

    private static String stripArraySuffix(String name) {
        String out = name;
        while (out.endsWith("[]")) {
            out = out.substring(0, out.length() - 2);
        }
        return out;
    }

    /**
     * 按 mod 汇总一份类直方图，按 bytes 降序。{@code classes} 是被归入该 mod 的类数
     * （同一 mod 的不同类各算一个，与实例数无关）。
     */
    public List<ModTotal> rollup(List<HeapHistogram.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> acc = new HashMap<>(Math.max(16, mods.size() * 2));
        Map<String, ModRef> refs = new HashMap<>(Math.max(16, mods.size() * 2));
        for (HeapHistogram.Entry entry : entries) {
            ModRef ref = modOfClass(entry.className());
            long[] slot = acc.computeIfAbsent(ref.modId(), k -> new long[3]);
            slot[0] += entry.instances();
            slot[1] += entry.shallowBytes();
            slot[2]++;
            refs.putIfAbsent(ref.modId(), ref);
        }
        List<ModTotal> out = new ArrayList<>(acc.size());
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] v = e.getValue();
            ModRef ref = refs.get(e.getKey());
            out.add(new ModTotal(e.getKey(), ref == null ? e.getKey() : ref.displayName(), v[0], v[1], (int) v[2]));
        }
        out.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
        return out;
    }

    /** 归因层自己的元数据：agent 需要用这个判断「by_mod 到底靠不靠得住」。 */
    public JsonObject stats() {
        JsonObject data = new JsonObject();
        data.addProperty("mods_dir", modsDir);
        data.addProperty("mods_indexed", mods.size());
        data.addProperty("packages_indexed", byPackage.size());
        data.addProperty("classes_indexed", classesIndexed);
        if (packageConflicts > 0) {
            data.addProperty("package_conflicts", packageConflicts);
        }
        if (mods.isEmpty()) {
            JsonArray notes = new JsonArray();
            notes.add("no jar in mods/: in a development environment mods come from the Gradle classpath, "
                    + "so only minecraft/neoforge/jdk/library classes can be attributed");
            data.add("notes", notes);
        }
        return data;
    }
}
