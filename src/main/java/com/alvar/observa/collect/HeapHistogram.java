package com.alvar.observa.collect;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仅依赖 JDK 标准库的 HPROF（{@code .hprof}）堆转储解析器：按类统计「存活实例数 + 浅尺寸」，
 * 并给出按类与按包的排行。
 *
 * <h2>为什么自己写解析器</h2>
 * <p>线上诊断不能为了看一眼直方图就去 {@code jcmd} 一个外部进程，也不该把 350 MB 的 dump 整个读进内存
 * （{@code Files.readAllBytes} 会让堆雪上加霜）。本类只做一次顺序流式扫描：固定 1 MiB 读缓冲，
 * 每个实例只累加一个 {@code long} 计数器，内存占用只跟「类数量」成正比，与实例数量无关。
 *
 * <h2>浅尺寸口径（估算值，但按运行时的真实引用宽度折算）</h2>
 * <p>HPROF 的 {@code CLASS_DUMP.instanceSize} 是按 <b>8 字节引用</b>记录的字段总大小（不含对象头）。
 * 而运行中的 JVM 默认开启压缩指针（引用 4 B、对象头 12 B），直接拿 HPROF 的数字当尺寸会把
 * 引用密集的对象高估一倍左右——实测 {@code [Ljava.lang.Object;} 会从 17.9 MB 虚报成 31.3 MB。
 * 所以这里按本 JVM 的实际配置折算：</p>
 * <ul>
 *   <li>普通实例：{@code align8(header + instanceSize - (8 - refBytes) * refFieldCount)}，
 *       其中 {@code refFieldCount} 从 {@code CLASS_DUMP} 的实例字段描述里数出来。</li>
 *   <li>对象数组：{@code align8(header + 4 + count * refBytes)}。</li>
 *   <li>基本类型数组：{@code align8(header + 4 + count * elementSize)}。</li>
 * </ul>
 * <p>{@code header} 与 {@code refBytes} 由 {@code UseCompressedClassPointers}/{@code UseCompressedOops}
 * 决定（默认 12 + 4）。折算后与 {@code jcmd GC.class_histogram} 的同类字节数相差 1–2%（对齐与填充的残差），
 * 实例数完全一致；仍不含被引用对象（这不是 retained size）。</p>
 *
 * <h2>不计入的部分</h2>
 * <p>HPROF 用 {@code CLASS_DUMP} 描述「类」本身，而不是给每个 {@code java.lang.Class} 镜像写一条
 * {@code INSTANCE_DUMP}，所以本直方图<b>基本不含</b> {@code java.lang.Class} 实例（真实 dump 里只有
 * 个位数的 {@code java.lang.Class} 以 {@code INSTANCE_DUMP} 出现）；而 {@code jcmd GC.class_histogram}
 * 会把每个已加载类都算作一个 {@code Class} 实例（本机实测差 25,294 个）。
 * 交叉校验时这是已知的、可解释的差异。
 *
 * <h2>格式容错</h2>
 * <p>顶层记录按 {@code tag/time/length} 推进，未知 tag 用 {@code skip(length)} 跳过并计入
 * {@link Result#skippedRecords()}；堆转储子记录流中遇到不认识的子记录 tag 会直接抛
 * {@link IOException}（附带文件偏移），而不是“猜一个长度”把后续字节读错位。
 */
public final class HeapHistogram {

    /** 单个类的直方图条目；用于排序与展示。 */
    public record Entry(String className, long instances, long shallowBytes) {
    }

    /** 按包聚合的直方图条目（{@code packageName} 无包名时为 {@code "(default)"}）。 */
    public record PackageEntry(String packageName, long instances, long shallowBytes) {
    }

    /**
     * 一次解析的结果快照。
     *
     * @param totalInstances     所有类（含数组）的存活实例总数
     * @param totalShallowBytes  所有类（含数组）的浅尺寸合计（估算值，口径见类注释）
     * @param classCount         直方图中出现过的类数量（至少有一个实例的类）
     * @param top                按浅尺寸降序的前 N 个类
     * @param topPackages        按浅尺寸降序的前 20 个包
     * @param fileBytes          dump 文件字节数
     * @param parseMs            解析耗时（毫秒）
     * @param skippedRecords     被跳过、未解释的顶层记录数量
     */
    public record Result(
            long totalInstances, long totalShallowBytes, int classCount,
            List<Entry> top, List<PackageEntry> topPackages,
            long fileBytes, long parseMs, int skippedRecords) {
    }

    private static final int BUFFER_SIZE = 1 << 20;
    private static final int HEADER_LIMIT = 128;
    private static final int PACKAGE_TOP_N = 20;

    // ---- 顶层记录 tag（HPROF spec）----
    private static final int TAG_UTF8 = 0x01;
    private static final int TAG_LOAD_CLASS = 0x02;
    private static final int TAG_HEAP_DUMP = 0x0C;
    private static final int TAG_HEAP_DUMP_SEGMENT = 0x1C;

    // ---- 堆转储子记录 tag ----
    // 注意：HotSpot 实际写入的 GC root 编号与「便携版 HPROF 规范」的表格不同！
    // 这里用的是 JDK 21 的真实编号（可在 $JAVA_HOME/lib/src.zip 的
    // jdk.hotspot.agent/sun/jvm/hotspot/utilities/HeapHprofBinWriter.java 中核对：
    // ROOT_UNKNOWN=0xFF、JNI_GLOBAL=0x01、……、THREAD_OBJ=0x08）。
    // 便携版规范把 ROOT_UNKNOWN 记为 0x01、JNI_GLOBAL 记为 0x02……，按那张表解析真实 dump 一定会错位。
    private static final int SUB_ROOT_UNKNOWN = 0xFF;
    private static final int SUB_ROOT_JNI_GLOBAL = 0x01;
    private static final int SUB_ROOT_JNI_LOCAL = 0x02;
    private static final int SUB_ROOT_JAVA_FRAME = 0x03;
    private static final int SUB_ROOT_NATIVE_STACK = 0x04;
    private static final int SUB_ROOT_STICKY_CLASS = 0x05;
    private static final int SUB_ROOT_THREAD_BLOCK = 0x06;
    private static final int SUB_ROOT_MONITOR_USED = 0x07;
    private static final int SUB_ROOT_THREAD_OBJ = 0x08;
    private static final int SUB_CLASS_DUMP = 0x20;
    private static final int SUB_INSTANCE_DUMP = 0x21;
    private static final int SUB_OBJECT_ARRAY_DUMP = 0x22;
    private static final int SUB_PRIMITIVE_ARRAY_DUMP = 0x23;
    private static final int SUB_ROOT_INTERNED_STRING = 0x89;
    private static final int SUB_ROOT_FINALIZING = 0x8A;
    private static final int SUB_ROOT_DEBUGGER = 0x8B;
    private static final int SUB_ROOT_REFERENCE_CLEANUP = 0x8C;
    private static final int SUB_ROOT_VM_INTERNAL = 0x8D;
    private static final int SUB_ROOT_JNI_MONITOR = 0x8E;
    private static final int SUB_ROOT_UNREACHABLE = 0x8F;
    private static final int SUB_PRIMITIVE_ARRAY_NODATA_DUMP = 0xC3;
    private static final int SUB_HEAP_DUMP_INFO = 0xFE;

    /**
     * 运行时的引用宽度：压缩指针开启时 4 B，否则 8 B。HPROF 的尺寸按 8 B 记录，需要按这个折算。
     * 这两个值在类初始化时读一次 VM 选项，运行期不会变。
     */
    private static final int REF_BYTES = vmOptionEnabled("UseCompressedOops", true) ? 4 : 8;

    /** 对象头：Mark Word 8 B + Klass 指针（压缩时 4 B）。 */
    private static final int OBJECT_HEADER = vmOptionEnabled("UseCompressedClassPointers", true) ? 12 : 16;

    /** 读 HotSpot 的 VM 选项；拿不到（非 HotSpot / 受限环境）时用 JDK 21 的默认值。 */
    private static boolean vmOptionEnabled(String name, boolean fallback) {
        try {
            com.sun.management.HotSpotDiagnosticMXBean bean = java.lang.management.ManagementFactory
                    .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                return fallback;
            }
            String value = bean.getVMOption(name).getValue();
            return Boolean.parseBoolean(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparingLong(Entry::shallowBytes).reversed()
            .thenComparing(Entry::className);

    private static final Comparator<PackageEntry> PACKAGE_ORDER = Comparator
            .comparingLong(PackageEntry::shallowBytes).reversed()
            .thenComparing(PackageEntry::packageName);

    private HeapHistogram() {
    }

    /**
     * 抓一份堆转储（只在诊断时调用）。内部走 {@code HotSpotDiagnosticMXBean#dumpHeap}。
     *
     * <p>调用方必须保证 {@code hprof} 不存在：HPROF 写入器不会覆盖已有文件，这里先显式检查，
     * 以免把“文件已存在”误报成别的原因。
     *
     * @param hprof 目标路径，必须不存在
     * @param live  {@code true} 表示先做一次 full GC、只导出存活对象
     */
    public static void dumpTo(Path hprof, boolean live) throws IOException {
        Objects.requireNonNull(hprof, "hprof");
        if (Files.exists(hprof)) {
            throw new IOException("hprof target already exists: " + hprof);
        }
        HotSpotDiagnosticMXBean bean =
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (bean == null) {
            throw new IOException("HotSpotDiagnosticMXBean unavailable (not a HotSpot VM?)");
        }
        bean.dumpHeap(hprof.toAbsolutePath().toString(), live);
    }

    /**
     * 流式解析 HPROF，返回按浅尺寸降序的前 {@code topN} 个类。
     *
     * @param hprof dump 文件
     * @param topN  取前 N 个类；{@code 0} 表示不取（仅要总计），负数非法
     */
    public static Result parse(Path hprof, int topN) throws IOException {
        Objects.requireNonNull(hprof, "hprof");
        if (topN < 0) {
            throw new IllegalArgumentException("topN must be >= 0: " + topN);
        }
        long fileBytes = Files.size(hprof);
        long startedNanos = System.nanoTime();

        Histogram histogram = new Histogram();
        try (HprofReader reader = new HprofReader(Files.newInputStream(hprof))) {
            histogram.read(reader);
        }

        Map<String, long[]> totals = histogram.totals();
        long parseMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        long totalInstances = 0L;
        long totalShallowBytes = 0L;
        List<Entry> all = new ArrayList<>(totals.size());
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            long[] v = e.getValue();
            totalInstances += v[0];
            totalShallowBytes += v[1];
            all.add(new Entry(e.getKey(), v[0], v[1]));
        }
        all.sort(ENTRY_ORDER);

        List<Entry> top = List.copyOf(all.subList(0, Math.min(topN, all.size())));

        Map<String, long[]> packages = new HashMap<>(Math.max(16, all.size() / 2));
        for (Entry e : all) {
            addTo(packages, packageOf(e.className()), e.instances(), e.shallowBytes());
        }
        List<PackageEntry> packageList = new ArrayList<>(packages.size());
        for (Map.Entry<String, long[]> e : packages.entrySet()) {
            long[] v = e.getValue();
            packageList.add(new PackageEntry(e.getKey(), v[0], v[1]));
        }
        packageList.sort(PACKAGE_ORDER);
        List<PackageEntry> topPackages =
                List.copyOf(packageList.subList(0, Math.min(PACKAGE_TOP_N, packageList.size())));

        return new Result(totalInstances, totalShallowBytes, all.size(), top, topPackages,
                fileBytes, parseMs, histogram.skippedRecords);
    }

    // ---------------------------------------------------------------------
    // 类名归一化 / 包名
    // ---------------------------------------------------------------------

    /**
     * 把 HPROF 的类名归一成 Java 源码写法：内部名 {@code java/util/HashMap$Node} →
     * {@code java.util.HashMap$Node}；数组 {@code [B -> byte[]}、
     * {@code [Lfoo/Bar; -> foo.Bar[]}、{@code [[I -> int[][]}。
     * 非数组类名只做分隔符替换。
     */
    static String normalizeClassName(String raw) {
        String name = raw.replace('/', '.');
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return name;
        }
        String element;
        String body = name.substring(dims);
        if (body.length() == 1) {
            element = primitiveName(body.charAt(0));
        } else if (body.charAt(0) == 'L' && body.endsWith(";")) {
            element = body.substring(1, body.length() - 1);
        } else {
            element = body;
        }
        StringBuilder sb = new StringBuilder(element.length() + 2 * dims);
        sb.append(element);
        for (int i = 0; i < dims; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private static String primitiveName(char descriptor) {
        return switch (descriptor) {
            case 'Z' -> "boolean";
            case 'C' -> "char";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'B' -> "byte";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            default -> String.valueOf(descriptor);
        };
    }

    private static String primitiveArrayName(int elementType) {
        return switch (elementType) {
            case 4 -> "boolean[]";
            case 5 -> "char[]";
            case 6 -> "float[]";
            case 7 -> "double[]";
            case 8 -> "byte[]";
            case 9 -> "short[]";
            case 10 -> "int[]";
            case 11 -> "long[]";
            default -> throw new IllegalArgumentException("not a primitive type code: " + elementType);
        };
    }

    /** 包名 = 类名最后一个 {@code '.'} 之前的部分；无包名（含所有基本类型数组）记 {@code "(default)"}。 */
    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "(default)" : className.substring(0, dot);
    }

    /** JVM 的对象尺寸都按 8 B 对齐；不做这一步会系统性少算几个字节的填充。 */
    private static long align8(long size) {
        return (size + 7L) & ~7L;
    }

    private static void addTo(Map<String, long[]> map, String key, long instances, long bytes) {
        long[] v = map.get(key);
        if (v == null) {
            map.put(key, new long[] {instances, bytes});
        } else {
            v[0] += instances;
            v[1] += bytes;
        }
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    // ---------------------------------------------------------------------
    // 直方图累加器：每个类只有几个 long 计数器，与实例数量无关
    // ---------------------------------------------------------------------

    private static final class Histogram {

        /** UTF8 id -> 字符串（类名/字段名等），数量只与类数量同一量级。 */
        private final Map<Long, String> strings = new HashMap<>(1 << 14);

        /** classObjId -> 类槽位下标。 */
        private final LongIndexMap classSlots = new LongIndexMap(1 << 12);

        /** 槽位 -> 该类对象的 classObjId（用于报错定位）。 */
        private long[] classObjId = new long[1 << 12];
        /** 槽位 -> LOAD_CLASS 里的类名字符串 id。 */
        private long[] classNameId = new long[1 << 12];
        /** 槽位 -> CLASS_DUMP 里的 instanceSize（字段总大小，不含对象头）；-1 表示尚未看到。 */
        private long[] instanceSize = new long[1 << 12];

        /** 每个类的实例字段里有多少个是引用类型（HPROF 的 instanceSize 把它们按 8 B 算）。 */
        private int[] instanceRefs = new int[1 << 12];
        /** 槽位 -> INSTANCE_DUMP 个数。 */
        private long[] instanceCount = new long[1 << 12];
        /** 槽位 -> 对象数组个数。 */
        private long[] objectArrayCount = new long[1 << 12];
        /** 槽位 -> 对象数组浅尺寸合计。 */
        private long[] objectArrayBytes = new long[1 << 12];
        /** 已登记的类槽位数量。 */
        private int slots;

        /** 基本类型数组按元素类型码（4..11）直接累计。 */
        private final long[] primitiveArrayCount = new long[12];
        private final long[] primitiveArrayBytes = new long[12];

        /** 未解释、按长度跳过的顶层记录数。 */
        private int skippedRecords;

        Histogram() {
            Arrays.fill(instanceSize, -1L);
        }

        void read(HprofReader reader) throws IOException {
            String header = reader.readHeader();
            if (!header.startsWith("JAVA PROFILE")) {
                throw new IOException("not an HPROF file, header=" + header);
            }
            int idSize = (int) reader.u4();
            if (idSize != 4 && idSize != 8) {
                throw new IOException("unsupported HPROF idSize: " + idSize);
            }
            reader.setIdSize(idSize);
            reader.u8(); // dump timestamp (unused)

            while (true) {
                int tag;
                try {
                    tag = reader.u1();
                } catch (EOFException cleanEnd) {
                    break;
                }
                reader.u4(); // time offset in ms
                long length = reader.u4();
                if (length < 0) {
                    throw new IOException("record length out of range at " + hex(reader.offset()));
                }
                long recordStart = reader.offset();
                switch (tag) {
                    case TAG_UTF8 -> {
                        long id = reader.id();
                        int byteCount = (int) (length - idSize);
                        if (byteCount < 0) {
                            throw new IOException("UTF8 record shorter than id at " + hex(recordStart));
                        }
                        strings.put(id, reader.utf8(byteCount));
                    }
                    case TAG_LOAD_CLASS -> {
                        reader.u4();          // class serial
                        long classObj = reader.id();
                        reader.u4();          // stack trace serial
                        long nameStringId = reader.id();
                        int slot = slotFor(classObj);
                        classNameId[slot] = nameStringId;
                    }
                    case TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEGMENT -> readHeap(reader, length);
                    default -> {
                        reader.skip(length);
                        skippedRecords++;
                    }
                }
                long consumed = reader.offset() - recordStart;
                if (consumed != length) {
                    throw new IOException("top-level record tag=" + hex(tag)
                            + " declared length=" + length + " but consumed=" + consumed
                            + " (payload at " + hex(recordStart) + ")");
                }
            }
        }

        private void readHeap(HprofReader reader, long segmentLength) throws IOException {
            long segmentEnd = reader.offset() + segmentLength;
            while (reader.offset() < segmentEnd) {
                long subStart = reader.offset();
                int tag = reader.u1();
                switch (tag) {
                    // ---- GC roots ----
                    case SUB_ROOT_UNKNOWN, SUB_ROOT_STICKY_CLASS, SUB_ROOT_MONITOR_USED,
                            SUB_ROOT_FINALIZING, SUB_ROOT_DEBUGGER, SUB_ROOT_REFERENCE_CLEANUP,
                            SUB_ROOT_VM_INTERNAL, SUB_ROOT_JNI_MONITOR -> reader.id();
                    case SUB_ROOT_JNI_GLOBAL -> {
                        reader.id();
                        reader.id();
                    }
                    case SUB_ROOT_JNI_LOCAL, SUB_ROOT_JAVA_FRAME, SUB_ROOT_THREAD_OBJ,
                            SUB_ROOT_INTERNED_STRING, SUB_ROOT_UNREACHABLE -> {
                        reader.id();
                        reader.u4();
                        reader.u4();
                    }
                    case SUB_ROOT_NATIVE_STACK, SUB_ROOT_THREAD_BLOCK -> {
                        reader.id();
                        reader.u4();
                    }
                    // ---- 类 / 实例 / 数组 ----
                    case SUB_CLASS_DUMP -> readClassDump(reader);
                    case SUB_INSTANCE_DUMP -> {
                        reader.id();          // object id
                        reader.u4();          // stack trace serial
                        long classObj = reader.id();
                        long nbytes = reader.u4();
                        reader.skip(nbytes);
                        instanceCount[slotFor(classObj)]++;
                    }
                    case SUB_OBJECT_ARRAY_DUMP -> {
                        reader.id();          // array object id
                        reader.u4();          // stack trace serial
                        long count = reader.u4();
                        long arrayClassObj = reader.id();
                        reader.skip(count * reader.idSize());
                        int slot = slotFor(arrayClassObj);
                        objectArrayCount[slot]++;
                        objectArrayBytes[slot] += align8(OBJECT_HEADER + 4L + count * REF_BYTES);
                    }
                    case SUB_PRIMITIVE_ARRAY_DUMP -> {
                        reader.id();          // array object id
                        reader.u4();          // stack trace serial
                        long count = reader.u4();
                        int elementType = reader.u1();
                        int elementSize = primitiveElementSize(elementType, reader.idSize());
                        reader.skip(count * elementSize);
                        primitiveArrayCount[elementType]++;
                        primitiveArrayBytes[elementType] +=
                                align8(OBJECT_HEADER + 4L + count * elementSize);
                    }
                    case SUB_PRIMITIVE_ARRAY_NODATA_DUMP -> {
                        reader.id();          // array object id
                        reader.u4();          // stack trace serial
                        long count = reader.u4();
                        int elementType = reader.u1();
                        int elementSize = primitiveElementSize(elementType, reader.idSize());
                        primitiveArrayCount[elementType]++;
                        primitiveArrayBytes[elementType] +=
                                align8(OBJECT_HEADER + 4L + count * elementSize);
                    }
                    case SUB_HEAP_DUMP_INFO -> {
                        reader.u4();
                        reader.id();
                    }
                    default -> throw new IOException("unknown heap sub-record tag=" + hex(tag)
                            + " at file offset " + hex(subStart)
                            + " (segment ends at " + hex(segmentEnd) + ")");
                }
                if (reader.offset() > segmentEnd) {
                    throw new IOException("heap sub-record tag=" + hex(tag) + " at " + hex(subStart)
                            + " overran segment end " + hex(segmentEnd)
                            + " (offset now " + hex(reader.offset()) + ")");
                }
            }
        }

        /**
         * CLASS_DUMP（以本机 JDK 21 真实 dump 逐字节核对过的布局）：
         * <pre>
         * id classObjId, u4 stackSerial,
         * id super, id loader, id signers, id protectionDomain, id reserved, id reserved,
         * u4 instanceSize,
         * u2 constantPoolCount, 每项 u2 index + u1 type + 变长值,
         * u2 staticFieldCount,  每项 id nameStringId + u1 type + 变长值,
         * u2 instanceFieldCount, 每项 id nameStringId + u1 type
         * </pre>
         * 注意：静态字段段与实例字段描述段在任务给的格式说明里没有提到，但真实 dump 里存在
         * （例如 0x13c8435 处 u2=2 后跟两个 static object 字段），漏读会让整个子记录流错位。
         */
        private void readClassDump(HprofReader reader) throws IOException {
            long classObj = reader.id();
            reader.u4();          // stack trace serial
            reader.id();          // super class object id
            reader.id();          // class loader object id
            reader.id();          // signers object id
            reader.id();          // protection domain object id
            reader.id();          // reserved
            reader.id();          // reserved
            long size = reader.u4();
            int constantPoolCount = reader.u2();
            for (int i = 0; i < constantPoolCount; i++) {
                reader.u2();      // constant pool index
                int type = reader.u1();
                skipValue(reader, type);
            }
            int staticFieldCount = reader.u2();
            for (int i = 0; i < staticFieldCount; i++) {
                reader.id();      // field name string id
                int type = reader.u1();
                skipValue(reader, type);
            }
            int instanceFieldCount = reader.u2();
            int refs = 0;
            for (int i = 0; i < instanceFieldCount; i++) {
                reader.id();      // field name string id
                if (reader.u1() == 2) {
                    refs++;       // type 2 = object reference
                }
            }
            int slot = slotFor(classObj);
            instanceSize[slot] = size;
            instanceRefs[slot] = refs;
        }

        private static void skipValue(HprofReader reader, int type) throws IOException {
            if (type == 2) {
                reader.id();
            } else {
                reader.skip(valueSize(type, reader.idSize()));
            }
        }

        private int slotFor(long classObjId) {
            int slot = classSlots.getOrAdd(classObjId);
            if (slot >= slots) {
                slots = slot + 1;
                ensureCapacity(slots);
                this.classObjId[slot] = classObjId;
                instanceSize[slot] = -1L;
            }
            return slot;
        }

        private void ensureCapacity(int needed) {
            int capacity = classObjId.length;
            if (needed <= capacity) {
                return;
            }
            int newCapacity = Math.max(needed, capacity << 1);
            classObjId = Arrays.copyOf(classObjId, newCapacity);
            classNameId = Arrays.copyOf(classNameId, newCapacity);
            instanceCount = Arrays.copyOf(instanceCount, newCapacity);
            objectArrayCount = Arrays.copyOf(objectArrayCount, newCapacity);
            objectArrayBytes = Arrays.copyOf(objectArrayBytes, newCapacity);
            int oldCapacity = instanceSize.length;
            instanceSize = Arrays.copyOf(instanceSize, newCapacity);
            Arrays.fill(instanceSize, oldCapacity, newCapacity, -1L);
            instanceRefs = Arrays.copyOf(instanceRefs, newCapacity);
        }

        /** 把累加器结算成「类名 -> {实例数, 浅尺寸}」；缺失映射会直接报错而不是猜。 */
        Map<String, long[]> totals() throws IOException {
            Map<String, long[]> totals = new HashMap<>(Math.max(16, slots * 2));
            for (int slot = 0; slot < slots; slot++) {
                long instances = instanceCount[slot];
                long arrays = objectArrayCount[slot];
                if (instances == 0 && arrays == 0) {
                    continue;
                }
                long bytes = objectArrayBytes[slot];
                if (instances > 0) {
                    long size = instanceSize[slot];
                    if (size < 0) {
                        throw new IOException("class object " + hex(classObjId[slot])
                                + " has INSTANCE_DUMP but no CLASS_DUMP (instance size unknown)");
                    }
                    // HPROF 的 size 以 8 B 引用计；换成实际引用宽度后加对象头再对齐到 8 B。
                    long fieldBytes = size - (long) (8 - REF_BYTES) * instanceRefs[slot];
                    bytes += instances * align8(OBJECT_HEADER + fieldBytes);
                }
                String name = strings.get(classNameId[slot]);
                if (name == null) {
                    throw new IOException("class object " + hex(classObjId[slot])
                            + " has no LOAD_CLASS name (string id " + hex(classNameId[slot]) + ")");
                }
                addTo(totals, normalizeClassName(name), instances + arrays, bytes);
            }
            for (int elementType = 4; elementType <= 11; elementType++) {
                if (primitiveArrayCount[elementType] == 0) {
                    continue;
                }
                addTo(totals, primitiveArrayName(elementType),
                        primitiveArrayCount[elementType], primitiveArrayBytes[elementType]);
            }
            return totals;
        }

        private static int valueSize(int type, int idSize) throws IOException {
            return switch (type) {
                case 2 -> idSize;   // object
                case 4, 8 -> 1;     // boolean, byte
                case 5, 9 -> 2;     // char, short
                case 6, 10 -> 4;    // float, int
                case 7, 11 -> 8;    // double, long
                default -> throw new IOException("unknown constant-pool value type: " + type);
            };
        }

        private static int primitiveElementSize(int elementType, int idSize) throws IOException {
            return switch (elementType) {
                case 4, 8 -> 1;     // boolean, byte
                case 5, 9 -> 2;     // char, short
                case 6, 10 -> 4;    // float, int
                case 7, 11 -> 8;    // double, long
                case 2 -> idSize;   // 理论上 PRIMITIVE_ARRAY_DUMP 不应出现 object
                default -> throw new IOException("unknown primitive array element type: " + elementType);
            };
        }
    }

    // ---------------------------------------------------------------------
    // 流式读取器：大端 u1/u2/u4/u8/id/skip，跳过区域也保持字节对齐
    // ---------------------------------------------------------------------

    private static final class HprofReader implements Closeable {

        private final InputStream in;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int position;
        private int limit;
        private int idSize;
        /** 已消费的文件偏移，用于报错定位与段边界计算。 */
        private long offset;

        HprofReader(InputStream in) {
            this.in = new BufferedInputStream(in, 1 << 16);
        }

        long offset() {
            return offset;
        }

        int idSize() {
            return idSize;
        }

        void setIdSize(int idSize) {
            this.idSize = idSize;
        }

        /** 读取 NUL 结尾的头部字符串（ASCII）。 */
        String readHeader() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32);
            for (int i = 0; i < HEADER_LIMIT; i++) {
                int b = u1();
                if (b == 0) {
                    return new String(out.toByteArray(), StandardCharsets.US_ASCII);
                }
                out.write(b);
            }
            throw new IOException("invalid HPROF header: no NUL terminator within " + HEADER_LIMIT + " bytes");
        }

        int u1() throws IOException {
            if (position >= limit) {
                fill();
            }
            offset++;
            return buffer[position++] & 0xFF;
        }

        int u2() throws IOException {
            return (u1() << 8) | u1();
        }

        long u4() throws IOException {
            return ((long) u1() << 24) | ((long) u1() << 16) | ((long) u1() << 8) | u1();
        }

        long u8() throws IOException {
            long high = u4();
            long low = u4();
            return (high << 32) | low;
        }

        long id() throws IOException {
            return switch (idSize) {
                case 4 -> u4();
                case 8 -> u8();
                default -> throw new IOException("idSize not initialised");
            };
        }

        String utf8(int byteCount) throws IOException {
            if (byteCount < 0) {
                throw new IOException("negative UTF8 length: " + byteCount);
            }
            byte[] bytes = new byte[byteCount];
            readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void skip(long byteCount) throws IOException {
            if (byteCount < 0) {
                throw new IOException("negative skip: " + byteCount);
            }
            while (byteCount > 0) {
                if (position >= limit) {
                    // 大段跳过交给底层流，避免把要跳过的字节全读一遍
                    if (byteCount > buffer.length) {
                        long skipped = in.skip(byteCount);
                        if (skipped > 0) {
                            offset += skipped;
                            byteCount -= skipped;
                            continue;
                        }
                        fill();
                        continue;
                    }
                    fill();
                }
                int take = (int) Math.min(byteCount, limit - position);
                position += take;
                offset += take;
                byteCount -= take;
            }
        }

        private void readFully(byte[] target) throws IOException {
            int written = 0;
            while (written < target.length) {
                if (position >= limit) {
                    fill();
                }
                int take = Math.min(target.length - written, limit - position);
                System.arraycopy(buffer, position, target, written, take);
                position += take;
                written += take;
                offset += take;
            }
        }

        private void fill() throws IOException {
            int read = in.read(buffer, 0, buffer.length);
            if (read < 0) {
                throw new EOFException("unexpected end of HPROF at file offset " + offset);
            }
            position = 0;
            limit = read;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ---------------------------------------------------------------------
    // long -> int 的开放寻址映射（避免每个实例都装箱 Long）
    // ---------------------------------------------------------------------

    private static final class LongIndexMap {

        private long[] keys;
        private int[] values;
        private int mask;
        private int size;

        LongIndexMap(int expected) {
            int capacity = Integer.highestOneBit(Math.max(16, expected) - 1) << 1;
            keys = new long[capacity];
            values = new int[capacity];
            mask = capacity - 1;
        }

        int size() {
            return size;
        }

        /** 返回已有键的下标；新键按插入顺序分配下标。键 0 非法（HPROF 里 0 表示 null）。 */
        int getOrAdd(long key) {
            if (key == 0L) {
                throw new IllegalArgumentException("null object id");
            }
            int slot = spread(key) & mask;
            while (true) {
                long existing = keys[slot];
                if (existing == key) {
                    return values[slot];
                }
                if (existing == 0L) {
                    int value = size++;
                    keys[slot] = key;
                    values[slot] = value;
                    if (size * 2 > keys.length) {
                        rehash();
                    }
                    return value;
                }
                slot = (slot + 1) & mask;
            }
        }

        private static int spread(long key) {
            long h = key;
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            return (int) h;
        }

        private void rehash() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            int capacity = oldKeys.length << 1;
            long[] newKeys = new long[capacity];
            int[] newValues = new int[capacity];
            int newMask = capacity - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                long key = oldKeys[i];
                if (key == 0L) {
                    continue;
                }
                int slot = spread(key) & newMask;
                while (newKeys[slot] != 0L) {
                    slot = (slot + 1) & newMask;
                }
                newKeys[slot] = key;
                newValues[slot] = oldValues[i];
            }
            keys = newKeys;
            values = newValues;
            mask = newMask;
        }
    }
}
