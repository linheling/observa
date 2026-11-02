package com.alvar.observa.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 外部 mapping.json 的精确类名覆盖：包名规则解释不了的类靠它补。 */
    private final Map<String, ModRef> byClass;
    private final List<ModRef> mods;
    private final String modsDir;
    private final int classesIndexed;
    private final int packageConflicts;
    private final String mappingFile;
    private final int mappingEntries;

    private ModIndex(Map<String, ModRef> byPackage, Map<String, ModRef> byClass, List<ModRef> mods,
                     String modsDir, int classesIndexed, int packageConflicts,
                     String mappingFile, int mappingEntries) {
        this.byPackage = byPackage;
        this.byClass = byClass;
        this.mods = mods;
        this.modsDir = modsDir;
        this.classesIndexed = classesIndexed;
        this.packageConflicts = packageConflicts;
        this.mappingFile = mappingFile;
        this.mappingEntries = mappingEntries;
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
        Map<String, ModRef> byClass = new HashMap<>(1 << 8);
        List<ModRef> mods = new ArrayList<>();
        String dir = null;
        int classes = 0;
        int conflicts = 0;
        Mapping mapping = loadMapping();

        Path modsDir;
        try {
            modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
            dir = modsDir.toString();
        } catch (Throwable ignored) {
            // 没有 FML 环境（单测）：只留前缀规则（外部 mapping 仍然生效）。
            return finish(byPackage, byClass, mods, null, 0, 0, mapping);
        }

        if (!Files.isDirectory(modsDir)) {
            return finish(byPackage, byClass, mods, dir, 0, 0, mapping);
        }

        List<Path> jars;
        try (Stream<Path> stream = Files.list(modsDir)) {
            jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            return finish(byPackage, byClass, mods, dir, 0, 0, mapping);
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
        return finish(byPackage, byClass, mods, dir, classes, conflicts, mapping);
    }

    // ------------------------------------------------------------------ 外部映射

    /**
     * {@code config/observa/mapping.json} 的内容。
     *
     * <p>为什么需要它：包名规则只能回答「这个类属于哪只 jar」，回答不了
     * 「这只 jar 其实是某个库的 shade 版本」「这几百个类是我手写的、不在 mods/ 里」。
     * 报告里 unknown 占到 54% 时，唯一靠谱的出路是让用户把答案写下来。</p>
     *
     * <pre>{@code
     * {
     *   "names":    {"someid": "Some Mod"},
     *   "packages": {"com.example.foo": "someid"},
     *   "classes":  {"com.example.Bar": "someid"}
     * }
     * }</pre>
     *
     * <p>{@code packages} 吃最长前缀匹配，{@code classes} 精确匹配并优先于包规则。
     * 文件缺失/写坏都只是「没有覆盖」，不报错——诊断工具不能因为一个可选文件罢工。</p>
     */
    private record Mapping(Path file, Map<String, ModRef> byPackage, Map<String, ModRef> byClass) {
        static final Mapping EMPTY = new Mapping(null, Map.of(), Map.of());
    }

    private static Mapping loadMapping() {
        Path path;
        try {
            path = FMLPaths.CONFIGDIR.get().resolve("observa").resolve("mapping.json");
        } catch (Throwable t) {
            return Mapping.EMPTY;
        }
        if (!Files.isRegularFile(path)) {
            return Mapping.EMPTY;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, String> names = new HashMap<>();
            if (root.has("names") && root.get("names").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
                    names.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Map<String, ModRef> byPackage = new HashMap<>();
            if (root.has("packages") && root.get("packages").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("packages").entrySet()) {
                    String id = e.getValue().getAsString();
                    byPackage.put(e.getKey(), new ModRef(id, names.getOrDefault(id, id)));
                }
            }
            Map<String, ModRef> byClass = new HashMap<>();
            if (root.has("classes") && root.get("classes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("classes").entrySet()) {
                    String id = e.getValue().getAsString();
                    byClass.put(e.getKey(), new ModRef(id, names.getOrDefault(id, id)));
                }
            }
            return new Mapping(path, byPackage, byClass);
        } catch (Exception e) {
            // 写坏的映射不阻断诊断：按没有覆盖处理。
            return new Mapping(path, Map.of(), Map.of());
        }
    }

    /** 把外部映射叠到扫描结果上，并把它提到的新 mod 也计入 {@code mods}。 */
    private static ModIndex finish(Map<String, ModRef> byPackage, Map<String, ModRef> byClass, List<ModRef> mods,
                                   String dir, int classes, int conflicts, Mapping mapping) {
        int entries = 0;
        for (Map.Entry<String, ModRef> e : mapping.byPackage().entrySet()) {
            byPackage.put(e.getKey(), e.getValue());
            entries++;
        }
        for (Map.Entry<String, ModRef> e : mapping.byClass().entrySet()) {
            byClass.put(e.getKey(), e.getValue());
            entries++;
        }
        Set<String> known = new HashSet<>();
        for (ModRef ref : mods) {
            known.add(ref.modId());
        }
        for (ModRef ref : mapping.byPackage().values()) {
            if (known.add(ref.modId())) {
                mods.add(ref);
            }
        }
        for (ModRef ref : mapping.byClass().values()) {
            if (known.add(ref.modId())) {
                mods.add(ref);
            }
        }
        String file = mapping.file() == null ? null : mapping.file().toString();
        return new ModIndex(byPackage, byClass, mods, dir, classes, conflicts, file, entries);
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
        // 精确类名覆盖优先：mapping.json 里写死的答案比包名推断可信。
        ModRef exact = byClass.get(name);
        if (exact != null) {
            return exact;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return UNKNOWN; // 基本类型 / 默认包
        }
        return modOfPackage(name.substring(0, dot));
    }

    /**
     * 只按包名归因。{@code /heap/histogram} 的 {@code packages} 段是包聚合的，
     * 拿 {@code modOfClass("a.b.c")} 去问会把包名误当类名（会砍掉最后一段），所以单开一个入口。
     */
    public ModRef modOfPackage(String packageName) {
        if (packageName == null || packageName.isEmpty() || "(default)".equals(packageName)) {
            return UNKNOWN;
        }
        // 最长包前缀匹配：a.b.C$D 命中 a.b 也行
        String candidate = packageName;
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
        return byPrefixRule(packageName);
    }

    /**
     * unknown 字节的去向：把包聚合表里归不到任何 mod 的包按字节降序挑出来。
     *
     * <p>这就是补 {@code mapping.json} 的输入——报告里 unknown 占 54% 时，
     * 用户需要的不是一句「归因失败」，而是「这 54% 集中在哪 20 个包」。</p>
     */
    public List<HeapHistogram.PackageEntry> unknownPackages(List<HeapHistogram.PackageEntry> packages, int top) {
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        List<HeapHistogram.PackageEntry> out = new ArrayList<>();
        for (HeapHistogram.PackageEntry p : packages) {
            if (modOfPackage(p.packageName()) == UNKNOWN) {
                out.add(p);
            }
        }
        out.sort((a, b) -> Long.compare(b.shallowBytes(), a.shallowBytes()));
        if (top > 0 && out.size() > top) {
            return List.copyOf(out.subList(0, top));
        }
        return List.copyOf(out);
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
        data.addProperty("mapping_file", mappingFile);
        data.addProperty("mapping_entries", mappingEntries);
        data.addProperty("class_overrides", byClass.size());
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
