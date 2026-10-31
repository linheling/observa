package com.alvar.observa.collect;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象图 + 支配树：回答「谁持有这块内存」。
 *
 * <p>{@link HeapHistogram} 只数浅尺寸，回答不了「把 X 删掉能省多少」。要回答那个问题必须知道
 * 对象之间谁引用谁，然后在「从 GC root 出发」的有向图上求支配关系：
 * <b>retained size</b> = 该对象被移除后能一并回收的字节数 = 支配树里它子树的浅尺寸之和。</p>
 *
 * <h2>为什么分三趟读同一个文件</h2>
 * <p>HPROF 是流式的，引用指向的 object id 可能在文件很后面才出现，所以不能在单趟里同时建边。
 * 三趟各有明确目的，代价是三次顺序读（本机 350 MB 堆每趟 0.3–0.6 秒）：</p>
 * <ol>
 *   <li><b>布局</b>：{@code LOAD_CLASS} 给类名，{@code CLASS_DUMP} 给字段表（哪几个字段是引用、
 *       父类是谁）；静态字段里的对象引用算 GC root；</li>
 *   <li><b>计数</b>：给每个对象分配下标、算浅尺寸、数出它有几个引用（为第三趟的 CSR 定容量）；</li>
 *   <li><b>填边</b>：把引用 id 解析成下标写进 {@code int[]}。</li>
 * </ol>
 *
 * <h2>root 怎么定</h2>
 * <p>只用 HPROF 里**显式**的根：{@code ROOT_*} 记录（线程栈、JNI、monitor 等）与
 * {@code CLASS_DUMP} 的静态字段引用。刻意不用「入度为 0 就算 root」这种近似——那会把只被
 * 弱引用/软引用持有的对象也算成根，让 retained size 虚高。代价是存在环状不可达对象时它们
 * 到不了任何 root，这类对象的数量会如实报在 {@code unreachable} 里（正常 live dump 为 0）。
 * 合成 root 节点把所有 root 连起来后，用 Cooper–Harvey–Kennedy 迭代算法求 immediate dominator，
 * 再在支配树上累加浅尺寸得到 retained size。</p>
 *
 * <h2>内存与上限</h2>
 * <p>全部用 {@code int[]}/{@code long[]} 平铺，一个对象约 60–100 字节、一条边约 8 字节
 * （正向 + 反向 CSR）。超过 {@value #MAX_OBJECTS} 个对象或 {@value #MAX_EDGES} 条边时直接报错，
 * 而不是把服务器拖进 OOM——堆分析器自己成为内存压力源是最糟糕的失败方式。</p>
 */
public final class HeapGraph {

    // ---- 顶层记录 tag（HPROF spec；编号与 HeapHistogram 同源）----
    private static final int TAG_UTF8 = 0x01;
    private static final int TAG_LOAD_CLASS = 0x02;
    private static final int TAG_HEAP_DUMP = 0x0C;
    private static final int TAG_HEAP_DUMP_SEGMENT = 0x1C;

    // ---- 堆转储子记录 tag（HotSpot 真实编号，见 HeapHistogram 的注释）----
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
    private static final int SUB_ROOT_UNKNOWN = 0xFF;

    /** 对象数绝对上限；真正生效的上限还会受堆大小约束，见 {@link #effectiveObjectLimit()}。 */
    public static final int MAX_OBJECTS = 8_000_000;
    /** 引用上限：正向 + 反向 CSR 各约 48 MB。 */
    public static final int MAX_EDGES = 24_000_000;

    /** 每对象预算（下标/尺寸/计数/映射/支配树数组加起来的经验值）。 */
    private static final int BYTES_PER_OBJECT_BUDGET = 160;
    /** 分析最多肯占堆的比例：堆分析器自己变成内存压力源是最糟糕的失败方式。 */
    private static final double HEAP_SHARE = 0.4;

    /**
     * 这次运行真正允许的对象数 = min(静态上限, 堆的 40% ÷ 每对象预算)。
     *
     * <p>按默认 -Xmx2G 算约 500 万个对象；小堆服务器会得到更低的数字，而不是 OOM。</p>
     */
    public static int effectiveObjectLimit() {
        long byHeap = (long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE / BYTES_PER_OBJECT_BUDGET);
        return (int) Math.max(64_000L, Math.min(MAX_OBJECTS, byHeap));
    }

    private static final int MAX_LAYOUT_DEPTH = 64;
    private static final String UNKNOWN_CLASS = "<unknown>";
    /** 合成 root 的「持有者」名字：出现在它下面说明是被 GC root 直接持着。 */
    public static final String GC_ROOT_NAME = "(gc root)";

    /**
     * 一类对象的 retained size。
     *
     * @param retainedBytes    这一类「最上层」对象被移除后能回收的字节数之和；各类之间不重叠，
     *                         全部加起来约等于整堆大小
     * @param instances        被计入的对象个数（已经被更上层对象覆盖的实例不重复计入）
     * @param dominatedByClass 被计入的那些对象里最大那份的支配者类名
     *                         （{@code (gc root)} 表示直接被 GC root 持有）
     */
    public record ClassRow(String className, long retainedBytes, long instances, String dominatedByClass) {
    }

    /**
     * 一次分析的结果。
     *
     * @param rootCount        被当作 GC root 的对象数
     * @param unreachable      有记录但到不了任何 root 的对象数：live dump 里它们是垃圾，
     *                         非 live dump（或 JVM 跳过了 dump 前的 GC）里可能占很大比例
     * @param shallowBytes     可达对象的浅尺寸总和 = 真正的存活堆
     * @param dumpShallowBytes dump 里所有对象的浅尺寸总和（含不可达的垃圾），可与直方图的总量对齐
     * @param droppedEdges     指向「dump 里没有 INSTANCE_DUMP 的对象」（Class 镜像等）的引用条数；
     *                         它们被压掉，不计入边数。非 0 是正常的，极大时说明 dump 不完整
     */
    public record Result(long objects, long edges, int classes, int roots, long layoutMismatches,
                         long unreachable, long unreachableBytes, long shallowBytes, long dumpShallowBytes,
                         long droppedEdges, List<ClassRow> classRows) {
    }

    private static final Comparator<ClassRow> BY_RETAINED = Comparator
            .comparingLong(ClassRow::retainedBytes).reversed()
            .thenComparing(ClassRow::className);

    private HeapGraph() {
    }

    /**
     * 解析一份 HPROF 并求 retained size。
     *
     * <p>返回**全部**有保留字节的类（按 retained 降序）；截断留给调用方，这样按 mod 汇总
     * 不会因为 Top-N 截断而漏掉质量。</p>
     *
     * @param hprof dump 文件（调用方负责它的生命周期）
     */
    public static Result analyze(Path hprof) throws IOException {
        Layout layout = Layout.read(hprof);
        Graph graph = Graph.read(hprof, layout);
        return graph.analyze(layout);
    }

    // ---------------------------------------------------------------------
    // 打开文件 + 通用记录跳过
    // ---------------------------------------------------------------------

    private static HeapHistogram.HprofReader open(Path hprof) throws IOException {
        HeapHistogram.HprofReader reader = new HeapHistogram.HprofReader(Files.newInputStream(hprof));
        try {
            String header = reader.readHeader();
            if (!header.startsWith("JAVA PROFILE")) {
                throw new IOException("not an HPROF file, header=" + header);
            }
            int idSize = (int) reader.u4();
            if (idSize != 4 && idSize != 8) {
                throw new IOException("unsupported HPROF idSize: " + idSize);
            }
            reader.setIdSize(idSize);
            reader.u8(); // timestamp
            return reader;
        } catch (IOException e) {
            reader.close();
            throw e;
        }
    }

    private static boolean isRoot(int subTag) {
        return switch (subTag) {
            case SUB_ROOT_UNKNOWN, SUB_ROOT_STICKY_CLASS, SUB_ROOT_MONITOR_USED, SUB_ROOT_FINALIZING,
                    SUB_ROOT_DEBUGGER, SUB_ROOT_REFERENCE_CLEANUP, SUB_ROOT_VM_INTERNAL,
                    SUB_ROOT_JNI_MONITOR, SUB_ROOT_JNI_GLOBAL, SUB_ROOT_JNI_LOCAL, SUB_ROOT_JAVA_FRAME,
                    SUB_ROOT_THREAD_OBJ, SUB_ROOT_INTERNED_STRING, SUB_ROOT_UNREACHABLE,
                    SUB_ROOT_NATIVE_STACK, SUB_ROOT_THREAD_BLOCK -> true;
            default -> false;
        };
    }

    /**
     * 读掉一条 GC root 记录的载荷，把 object id 交给 {@code onRoot}（可能为 0）。
     *
     * <p>载荷宽度按 HPROF 规范逐条区分：只有 JNI 全局引用是「对象 id + JNI 引用 id」，
     * 栈帧类根带线程序号/帧号，其余（interned string、sticky class、monitor、finalizing…）
     * 只有对象 id。<b>这里多读或少读一个 u4 就会让后面整份 dump 错位</b>，
     * 所以宁可写清楚也不能合并成「大概读几个 u4」。</p>
     */
    private static void readRoot(HeapHistogram.HprofReader reader, int subTag, RootSink onRoot)
            throws IOException {
        switch (subTag) {
            case SUB_ROOT_JNI_GLOBAL -> {
                long id = reader.id();
                reader.id(); // JNI global ref id
                onRoot.accept(id);
            }
            case SUB_ROOT_JNI_LOCAL, SUB_ROOT_JAVA_FRAME, SUB_ROOT_THREAD_OBJ, SUB_ROOT_JNI_MONITOR -> {
                long id = reader.id();
                reader.u4(); // thread serial
                reader.u4(); // frame number / stack trace serial / stack depth
                onRoot.accept(id);
            }
            case SUB_ROOT_NATIVE_STACK, SUB_ROOT_THREAD_BLOCK -> {
                long id = reader.id();
                reader.u4(); // thread serial
                onRoot.accept(id);
            }
            default -> onRoot.accept(reader.id());
        }
    }

    private interface RootSink {
        void accept(long objectId);
    }

    private static void skipValue(HeapHistogram.HprofReader reader, int type) throws IOException {
        if (type == 2) {
            reader.id();
        } else {
            reader.skip(HeapHistogram.elementSizeOf(type, reader.idSize()));
        }
    }

    private static final class LongList {
        private long[] values = new long[16];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    // ---------------------------------------------------------------------
    // 第一趟：类布局（字段表 / 类名 / root）
    // ---------------------------------------------------------------------

    private static final class Layout {

        private final Map<Long, String> strings = new HashMap<>(1 << 14);
        private final HeapHistogram.LongIndexMap slots = new HeapHistogram.LongIndexMap(1 << 12);
        private final LongList roots = new LongList();

        private long[] classObj = new long[1 << 12];
        private long[] nameId = new long[1 << 12];
        private long[] superObj = new long[1 << 12];
        private byte[][] ownTypes = new byte[1 << 12][];
        private String[] names = new String[1 << 12];
        private byte[][] fullTypes = new byte[1 << 12][];
        private int[] shallow = new int[1 << 12];
        private final int[] primitiveSlots = new int[12];
        private int unknownSlot = -1;
        private int count;
        private int idSize = 4;
        /** dump 里的对象条数（第一趟顺手数出来，第二趟就能按精确大小分配，不必反复扩容）。 */
        private int objectCount;

        Layout() {
            Arrays.fill(primitiveSlots, -1);
        }

        static Layout read(Path hprof) throws IOException {
            Layout layout = new Layout();
            try (HeapHistogram.HprofReader reader = open(hprof)) {
                layout.idSize = reader.idSize();
                while (true) {
                    int tag;
                    try {
                        tag = reader.u1();
                    } catch (EOFException cleanEnd) {
                        break;
                    }
                    reader.u4();
                    long length = reader.u4();
                    long recordStart = reader.offset();
                    switch (tag) {
                        case TAG_UTF8 -> {
                            long id = reader.id();
                            layout.strings.put(id, reader.utf8((int) (length - reader.idSize())));
                        }
                        case TAG_LOAD_CLASS -> {
                            reader.u4();
                            long classObjId = reader.id();
                            reader.u4();
                            long nameStringId = reader.id();
                            int slot = layout.slotFor(classObjId);
                            layout.nameId[slot] = nameStringId;
                        }
                        case TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEGMENT -> layout.readHeap(reader, length);
                        default -> reader.skip(length);
                    }
                    long consumed = reader.offset() - recordStart;
                    if (consumed != length) {
                        throw new IOException("HPROF record tag=" + tag + " declared " + length
                                + " bytes but consumed " + consumed);
                    }
                }
            }
            layout.resolveNames();
            return layout;
        }

        private void readHeap(HeapHistogram.HprofReader reader, long segmentLength) throws IOException {
            long end = reader.offset() + segmentLength;
            while (reader.offset() < end) {
                int sub = reader.u1();
                if (isRoot(sub)) {
                    // SUB_ROOT_UNREACHABLE 只当「解析用的 root 记录」处理载荷，不真当根：
                    // 规范说它标记的是明确不可达的对象，当成根会把垃圾算进可达集。
                    boolean marker = sub == SUB_ROOT_UNREACHABLE;
                    readRoot(reader, sub, id -> {
                        if (id != 0L && !marker) {
                            roots.add(id);
                        }
                    });
                } else {
                    switch (sub) {
                        case SUB_CLASS_DUMP -> readClassDump(reader);
                        case SUB_INSTANCE_DUMP -> {
                            objectCount++;
                            reader.id();
                            reader.u4();
                            reader.id();
                            reader.skip(reader.u4());
                        }
                        case SUB_OBJECT_ARRAY_DUMP -> {
                            objectCount++;
                            reader.id();
                            reader.u4();
                            long elements = reader.u4();
                            reader.id();
                            reader.skip(elements * reader.idSize());
                        }
                        case SUB_PRIMITIVE_ARRAY_DUMP -> {
                            objectCount++;
                            reader.id();
                            reader.u4();
                            long elements = reader.u4();
                            int elementType = reader.u1();
                            reader.skip(elements * HeapHistogram.elementSizeOf(elementType, reader.idSize()));
                        }
                        case SUB_PRIMITIVE_ARRAY_NODATA_DUMP -> {
                            objectCount++;
                            reader.id();
                            reader.u4();
                            reader.u4();
                            reader.u1();
                        }
                        case SUB_HEAP_DUMP_INFO -> {
                            reader.u4();
                            reader.id();
                        }
                        default -> throw new IOException("unknown heap sub-record 0x"
                                + Integer.toHexString(sub) + " at offset " + reader.offset());
                    }
                }
            }
        }

        /** CLASS_DUMP：类名、父类、实例字段类型表；静态字段里的引用进 root 集合。 */
        private void readClassDump(HeapHistogram.HprofReader reader) throws IOException {
            long classObjId = reader.id();
            reader.u4();
            long superClassObj = reader.id();
            reader.id(); // loader
            reader.id(); // signers
            reader.id(); // protection domain
            reader.id(); // reserved
            reader.id(); // reserved
            reader.u4(); // instance size（文件布局口径，引用按 8 B）
            int constantPool = reader.u2();
            for (int i = 0; i < constantPool; i++) {
                reader.u2();
                skipValue(reader, reader.u1());
            }
            int staticFields = reader.u2();
            for (int i = 0; i < staticFields; i++) {
                reader.id();
                int type = reader.u1();
                if (type == 2) {
                    long ref = reader.id();
                    if (ref != 0L) {
                        roots.add(ref);
                    }
                } else {
                    reader.skip(HeapHistogram.elementSizeOf(type, reader.idSize()));
                }
            }
            int instanceFields = reader.u2();
            byte[] types = new byte[instanceFields];
            for (int i = 0; i < instanceFields; i++) {
                reader.id();
                types[i] = (byte) reader.u1();
            }
            int slot = slotFor(classObjId);
            superObj[slot] = superClassObj;
            ownTypes[slot] = types;
        }

        private int slotFor(long classObjId) {
            int slot = slots.getOrAdd(classObjId);
            if (slot >= count) {
                count = slot + 1;
                ensure(count);
                classObj[slot] = classObjId;
            }
            return slot;
        }

        /** 基本类型数组在 HPROF 里没有 classObjId：给它一个合成槽位，名字就是 {@code int[]} 这种。 */
        int primitiveSlot(int elementType) {
            if (elementType < 0 || elementType >= primitiveSlots.length) {
                return unknownSlot();
            }
            int slot = primitiveSlots[elementType];
            if (slot >= 0) {
                return slot;
            }
            int created = slotFor(-1_000_000L - elementType);
            ownTypes[created] = new byte[0];
            names[created] = HeapHistogram.primitiveArrayName(elementType);
            primitiveSlots[elementType] = created;
            return created;
        }

        /** 类元数据缺失时的兜底槽位。 */
        int unknownSlot() {
            if (unknownSlot >= 0) {
                return unknownSlot;
            }
            int created = slotFor(-2_000_000L);
            ownTypes[created] = new byte[0];
            names[created] = UNKNOWN_CLASS;
            unknownSlot = created;
            return created;
        }

        private void ensure(int needed) {
            int capacity = classObj.length;
            if (needed <= capacity) {
                return;
            }
            int next = Math.max(needed, capacity << 1);
            classObj = Arrays.copyOf(classObj, next);
            nameId = Arrays.copyOf(nameId, next);
            superObj = Arrays.copyOf(superObj, next);
            ownTypes = Arrays.copyOf(ownTypes, next);
            names = Arrays.copyOf(names, next);
            fullTypes = Arrays.copyOf(fullTypes, next);
            shallow = Arrays.copyOf(shallow, next);
        }

        private void resolveNames() {
            for (int slot = 0; slot < count; slot++) {
                if (names[slot] != null) {
                    continue;
                }
                String raw = strings.get(nameId[slot]);
                names[slot] = raw == null ? UNKNOWN_CLASS : HeapHistogram.normalizeClassName(raw);
            }
        }

        int size() {
            return count;
        }

        int objectCount() {
            return objectCount;
        }

        String nameOf(int slot) {
            if (slot < 0 || slot >= count) {
                return UNKNOWN_CLASS;
            }
            String name = names[slot];
            return name == null ? UNKNOWN_CLASS : name;
        }

        int slotOf(long classObjId) {
            int slot = slots.get(classObjId);
            return (slot < 0 || slot >= count) ? -1 : slot;
        }

        /**
         * 实例的**文件布局**字段类型表：父类字段在前、本类字段在后（HPROF 写 INSTANCE_DUMP 的顺序）。
         * 类数据缺失或父类未知时返回 {@code null}，调用方据此把该实例当叶子节点。
         */
        byte[] layoutOf(int slot) {
            if (slot < 0) {
                return null;
            }
            byte[] cached = fullTypes[slot];
            if (cached != null) {
                return cached;
            }
            byte[] own = ownTypes[slot];
            if (own == null) {
                return null;
            }
            int depth = 0;
            int[] chain = new int[MAX_LAYOUT_DEPTH];
            int cursor = slot;
            while (cursor >= 0 && depth < MAX_LAYOUT_DEPTH) {
                chain[depth++] = cursor;
                long superClass = superObj[cursor];
                if (superClass == 0L) {
                    cursor = -1;
                } else {
                    int superSlot = slots.get(superClass);
                    cursor = (superSlot < 0 || superSlot == cursor) ? -1 : superSlot;
                }
            }
            int total = 0;
            for (int i = 0; i < depth; i++) {
                total += ownTypes[chain[i]].length;
            }
            byte[] merged = new byte[total];
            int at = 0;
            for (int i = depth - 1; i >= 0; i--) {
                byte[] types = ownTypes[chain[i]];
                System.arraycopy(types, 0, merged, at, types.length);
                at += types.length;
            }
            fullTypes[slot] = merged;
            return merged;
        }

        /** 文件里该实例占多少字节（引用按 idSize 算），用于和 INSTANCE_DUMP 的长度交叉校验。 */
        int fileFieldBytes(byte[] types) {
            int total = 0;
            for (byte type : types) {
                total += type == 2 ? idSize : elementSizeOfUnchecked(type);
            }
            return total;
        }

        /** 运行时浅尺寸（引用按真实引用宽度算）。 */
        int shallowOf(int slot) {
            int cached = shallow[slot];
            if (cached > 0) {
                return cached;
            }
            byte[] types = layoutOf(slot);
            long bytes = 0;
            if (types != null) {
                for (byte type : types) {
                    bytes += type == 2 ? HeapHistogram.REF_BYTES : elementSizeOfUnchecked(type);
                }
            }
            int value = (int) HeapHistogram.align8(HeapHistogram.OBJECT_HEADER + bytes);
            shallow[slot] = value;
            return value;
        }

        private static int elementSizeOfUnchecked(int type) {
            return switch (type) {
                case 4, 8 -> 1;
                case 5, 9 -> 2;
                case 6, 10 -> 4;
                case 7, 11 -> 8;
                default -> 0;
            };
        }

        long[] rootIds() {
            return roots.toArray();
        }
    }

    // ---------------------------------------------------------------------
    // 第二、三趟：对象与引用
    // ---------------------------------------------------------------------

    private static final class Graph {

        private long[] ids;
        private int[] kind;
        private int[] shallow;
        private int[] refCount;
        private final HeapHistogram.LongIndexMap index;
        /** 这次分析允许的最大对象数：静态上限与「堆还装得下」取较小者。 */
        private final int objectLimit;

        private int objects;
        private long layoutMismatches;
        private long droppedEdges;

        /** 正向 CSR：节点 {@code i} 的边在 {@code [offset[i], offset[i+1])}；节点 {@code objects} 是合成 root。 */
        private int[] offset = new int[0];
        private int[] targets = new int[0];
        private int rootCount;

        private Graph(int expected) {
            int capacity = Math.max(1 << 12, expected + (expected >>> 3) + 1024);
            ids = new long[capacity];
            kind = new int[capacity];
            shallow = new int[capacity];
            refCount = new int[capacity];
            index = new HeapHistogram.LongIndexMap(capacity);
            objectLimit = effectiveObjectLimit();
        }

        static Graph read(Path hprof, Layout layout) throws IOException {
            Graph graph = new Graph(layout.objectCount());
            // 第一趟：给每个对象编号、记浅尺寸、数出每个节点几条引用（create=true, fill=false）
            graph.scan(hprof, layout, true, false);
            graph.buildCsr(layout);
            // 第二趟：把引用写进 CSR（编号已经固定，绝不能再新增节点）
            graph.scan(hprof, layout, false, true);
            // 第三趟在内存里做：压掉指向未导出对象的 -1 哨兵，否则它们会被当成「指向 0 号对象」
            graph.compact();
            return graph;
        }

        /**
         * 把 fill 趟写入的 {@code -1} 哨兵压掉，重建紧凑的 CSR。
         *
         * <p>不能只在遍历时跳过负值：前驱表、`edges` 计数、retained 累加都按 {@code offset} 走，
         * 留着空洞就等于给 0 号对象凭空加边。Class 镜像（只有 CLASS_DUMP、没有 INSTANCE_DUMP）
         * 在真实 dump 里很常见，所以这不是理论问题。</p>
         */
        private void compact() {
            int rootTail = offset[objects + 1] - offset[objects];
            int[] newOffset = new int[objects + 2];
            int total = 0;
            for (int i = 0; i < objects; i++) {
                newOffset[i] = total;
                for (int e = offset[i]; e < offset[i + 1]; e++) {
                    if (targets[e] >= 0) {
                        total++;
                    } else {
                        droppedEdges++;
                    }
                }
            }
            newOffset[objects] = total;
            newOffset[objects + 1] = total + rootTail;
            int[] newTargets = new int[total + rootTail];
            int w = 0;
            for (int i = 0; i < objects; i++) {
                for (int e = offset[i]; e < offset[i + 1]; e++) {
                    int t = targets[e];
                    if (t >= 0) {
                        newTargets[w++] = t;
                    }
                }
            }
            System.arraycopy(targets, offset[objects], newTargets, total, rootTail);
            offset = newOffset;
            targets = newTargets;
        }

        /** 前缀和 + 把合成 root 的边接到 CSR 尾部。 */
        private void buildCsr(Layout layout) {
            int[] cursor = new int[objects + 1];
            int total = 0;
            for (int i = 0; i < objects; i++) {
                cursor[i] = total;
                total += refCount[i];
                if (total > MAX_EDGES) {
                    throw new IllegalStateException("heap graph too large: more than " + MAX_EDGES
                            + " references; use /heap/histogram instead of retained-size analysis");
                }
            }
            cursor[objects] = total;

            // root 下标：HPROF 的显式 ROOT_* 记录 + CLASS_DUMP 静态字段里的对象引用
            long[] rootIds = layout.rootIds();
            int[] explicit = new int[rootIds.length];
            int found = 0;
            for (long id : rootIds) {
                int idx = index.get(id);
                if (idx >= 0) {
                    explicit[found++] = idx;
                }
            }
            int[] roots = Arrays.copyOf(explicit, found);
            offset = new int[objects + 2];
            System.arraycopy(cursor, 0, offset, 0, objects + 1);
            targets = new int[total + roots.length];
            System.arraycopy(roots, 0, targets, total, roots.length);
            offset[objects + 1] = total + roots.length;
            rootCount = roots.length;
        }

        private void scan(Path hprof, Layout layout, boolean create, boolean fill) throws IOException {
            int[] cursor = fill ? Arrays.copyOf(offset, offset.length) : null;
            try (HeapHistogram.HprofReader reader = open(hprof)) {
                while (true) {
                    int tag;
                    try {
                        tag = reader.u1();
                    } catch (EOFException cleanEnd) {
                        break;
                    }
                    reader.u4();
                    long length = reader.u4();
                    long end = reader.offset() + length;
                    if (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) {
                        while (reader.offset() < end) {
                            int sub = reader.u1();
                            if (isRoot(sub)) {
                                readRoot(reader, sub, id -> {
                                });
                            } else {
                                switch (sub) {
                                    case SUB_CLASS_DUMP -> skipClassDump(reader);
                                    case SUB_INSTANCE_DUMP -> readInstance(reader, layout, create, fill, cursor);
                                    case SUB_OBJECT_ARRAY_DUMP ->
                                            readObjectArray(reader, layout, create, fill, cursor);
                                    case SUB_PRIMITIVE_ARRAY_DUMP ->
                                            readPrimitiveArray(reader, layout, create, fill, true);
                                    case SUB_PRIMITIVE_ARRAY_NODATA_DUMP ->
                                            readPrimitiveArray(reader, layout, create, fill, false);
                                    case SUB_HEAP_DUMP_INFO -> {
                                        reader.u4();
                                        reader.id();
                                    }
                                    default -> throw new IOException("unknown heap sub-record 0x"
                                            + Integer.toHexString(sub) + " at offset " + reader.offset());
                                }
                            }
                        }
                    } else {
                        reader.skip(length);
                    }
                    if (reader.offset() != end) {
                        throw new IOException("HPROF record overran its declared length at offset "
                                + reader.offset() + " (declared end " + end + ")");
                    }
                }
            }
        }

        private static void skipClassDump(HeapHistogram.HprofReader reader) throws IOException {
            reader.id();
            reader.u4();
            reader.id();
            reader.id();
            reader.id();
            reader.id();
            reader.id();
            reader.id();
            reader.u4();
            int constantPool = reader.u2();
            for (int i = 0; i < constantPool; i++) {
                reader.u2();
                skipValue(reader, reader.u1());
            }
            int staticFields = reader.u2();
            for (int i = 0; i < staticFields; i++) {
                reader.id();
                skipValue(reader, reader.u1());
            }
            int instanceFields = reader.u2();
            for (int i = 0; i < instanceFields; i++) {
                reader.id();
                reader.u1();
            }
        }

        private void readInstance(HeapHistogram.HprofReader reader, Layout layout, boolean create,
                                  boolean fill, int[] cursor) throws IOException {
            long objectId = reader.id();
            reader.u4();
            long classObjId = reader.id();
            long fieldBytes = reader.u4();
            int idx = indexOf(objectId, create);
            int slot = layout.slotOf(classObjId);
            byte[] types = slot < 0 ? null : layout.layoutOf(slot);
            int needed = types == null ? -1 : layout.fileFieldBytes(types);
            if (idx < 0) {
                reader.skip(fieldBytes);
                return;
            }
            if (!fill) {
                kind[idx] = slot < 0 ? layout.unknownSlot() : slot;
                // 类数据缺失或字段长度对不上时按 dump 里声明的字节数估算浅尺寸
                shallow[idx] = (int) (needed >= 0 && needed == (int) fieldBytes
                        ? layout.shallowOf(slot)
                        : HeapHistogram.align8(HeapHistogram.OBJECT_HEADER + fieldBytes));
            }
            if (needed < 0 || needed != (int) fieldBytes) {
                // 宁可把这个对象当叶子，也不按错误的字段边界去切（否则后面的记录全错位）
                if (!fill) {
                    layoutMismatches++;
                }
                reader.skip(fieldBytes);
                return;
            }
            for (byte type : types) {
                if (type == 2) {
                    long ref = reader.id();
                    if (ref != 0L) {
                        record(idx, ref, fill, cursor);
                    }
                } else {
                    reader.skip(Layout.elementSizeOfUnchecked(type));
                }
            }
        }

        private void readObjectArray(HeapHistogram.HprofReader reader, Layout layout, boolean create,
                                     boolean fill, int[] cursor) throws IOException {
            long objectId = reader.id();
            reader.u4();
            long elements = reader.u4();
            long classObjId = reader.id();
            int slot = layout.slotOf(classObjId);
            int idx = indexOf(objectId, create);
            if (idx < 0) {
                reader.skip(elements * reader.idSize());
                return;
            }
            if (!fill) {
                kind[idx] = slot < 0 ? layout.unknownSlot() : slot;
                shallow[idx] = (int) HeapHistogram.align8(
                        HeapHistogram.OBJECT_HEADER + 4L + elements * HeapHistogram.REF_BYTES);
            }
            for (long i = 0; i < elements; i++) {
                long ref = reader.id();
                if (ref != 0L) {
                    record(idx, ref, fill, cursor);
                }
            }
        }

        private void readPrimitiveArray(HeapHistogram.HprofReader reader, Layout layout, boolean create,
                                        boolean fill, boolean hasData) throws IOException {
            long objectId = reader.id();
            reader.u4();
            long elements = reader.u4();
            int elementType = reader.u1();
            int elementSize = HeapHistogram.elementSizeOf(elementType, reader.idSize());
            int idx = indexOf(objectId, create);
            if (idx < 0) {
                if (hasData) {
                    reader.skip(elements * elementSize);
                }
                return;
            }
            if (!fill) {
                kind[idx] = layout.primitiveSlot(elementType);
                shallow[idx] = (int) HeapHistogram.align8(
                        HeapHistogram.OBJECT_HEADER + 4L + elements * elementSize);
            }
            if (hasData) {
                reader.skip(elements * elementSize);
            }
        }

        private void record(int from, long refId, boolean fill, int[] cursor) {
            if (!fill) {
                refCount[from]++;
                return;
            }
            // 指向「没有 INSTANCE_DUMP 的对象」（Class 镜像、未导出对象）时写 -1 哨兵，
            // 由 compact() 压掉。绝不能留 0：0 是合法的对象编号，会被当成真边接过去。
            targets[cursor[from]++] = index.get(refId);
        }

        private int indexOf(long objectId, boolean create) {
            if (!create) {
                return index.get(objectId);
            }
            int idx = index.getOrAdd(objectId);
            if (idx >= objects) {
                objects = idx + 1;
                if (objects > objectLimit) {
                    throw new IllegalStateException("heap graph too large: more than " + objectLimit
                            + " objects (max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                            + " MB); raise -Xmx or use /heap/histogram instead of retained-size analysis");
                }
                ensure(objects);
                ids[idx] = objectId;
            }
            return idx;
        }

        private void ensure(int needed) {
            int capacity = ids.length;
            if (needed <= capacity) {
                return;
            }
            int next = Math.max(needed, capacity << 1);
            ids = Arrays.copyOf(ids, next);
            kind = Arrays.copyOf(kind, next);
            shallow = Arrays.copyOf(shallow, next);
            refCount = Arrays.copyOf(refCount, next);
        }

        // ------------------------------------------------------------- 支配树

        private Result analyze(Layout layout) {
            int n = objects;
            int root = n;
            int nodes = n + 1;

            // 1) DFS 求后序 → 逆后序（RPO）
            int[] post = new int[nodes];
            int[] rpoIndex = new int[nodes];
            Arrays.fill(rpoIndex, -1);
            boolean[] seen = new boolean[nodes];
            int[] nodeStack = new int[nodes];
            int[] edgeStack = new int[nodes];
            int sp = 0;
            nodeStack[0] = root;
            edgeStack[0] = offset[root];
            seen[root] = true;
            int visited = 0;
            while (sp >= 0) {
                int node = nodeStack[sp];
                int e = edgeStack[sp];
                if (e < offset[node + 1]) {
                    edgeStack[sp] = e + 1;
                    int child = targets[e];
                    if (!seen[child]) {
                        seen[child] = true;
                        sp++;
                        nodeStack[sp] = child;
                        edgeStack[sp] = offset[child];
                    }
                } else {
                    post[visited++] = node;
                    sp--;
                }
            }
            int reachable = visited;
            int[] rpo = new int[reachable];
            for (int i = 0; i < reachable; i++) {
                rpo[i] = post[reachable - 1 - i];
            }
            for (int i = 0; i < reachable; i++) {
                rpoIndex[rpo[i]] = i;
            }

            // 2) 反向 CSR（CHK 需要每个节点的前驱）
            int[] predOffset = new int[nodes + 1];
            for (int i = 0; i < reachable; i++) {
                int node = rpo[i];
                for (int e = offset[node]; e < offset[node + 1]; e++) {
                    predOffset[targets[e] + 1]++;
                }
            }
            for (int i = 0; i < nodes; i++) {
                predOffset[i + 1] += predOffset[i];
            }
            int[] preds = new int[predOffset[nodes]];
            int[] predCursor = Arrays.copyOf(predOffset, predOffset.length);
            for (int i = 0; i < reachable; i++) {
                int node = rpo[i];
                for (int e = offset[node]; e < offset[node + 1]; e++) {
                    preds[predCursor[targets[e]]++] = node;
                }
            }

            // 3) 迭代求 immediate dominator
            int[] idom = new int[nodes];
            Arrays.fill(idom, -1);
            idom[root] = root;
            boolean changed = true;
            int rounds = 0;
            while (changed && rounds++ < 1000) {
                changed = false;
                for (int i = 1; i < reachable; i++) {
                    int node = rpo[i];
                    int newIdom = -1;
                    for (int p = predOffset[node]; p < predOffset[node + 1]; p++) {
                        int pred = preds[p];
                        if (idom[pred] < 0) {
                            continue;
                        }
                        newIdom = newIdom < 0 ? pred : intersect(pred, newIdom, idom, rpoIndex);
                    }
                    if (newIdom >= 0 && idom[node] != newIdom) {
                        idom[node] = newIdom;
                        changed = true;
                    }
                }
            }

            // 4) retained size：逆 RPO 累加（idom 一定排在它前面）
            long[] retained = new long[nodes];
            for (int i = 0; i < reachable; i++) {
                int node = rpo[i];
                retained[node] = node == root ? 0L : shallow[node];
            }
            for (int i = reachable - 1; i >= 1; i--) {
                int node = rpo[i];
                int parent = idom[node] < 0 ? root : idom[node];
                retained[parent] += retained[node];
            }

            // 5) 按类汇总：支配树上「最上层」的对象才算数——一旦某个对象被计入，它子树里的东西就不再重复
            //    累加。这样各类的 retained 互不重叠，加起来≈整堆；否则读起来会出现「总和比堆还大」。
            int[] domOffset = new int[nodes + 1];
            for (int i = 1; i < reachable; i++) {
                int node = rpo[i];
                int parent = idom[node] < 0 ? root : idom[node];
                domOffset[parent + 1]++;
            }
            for (int i = 0; i < nodes; i++) {
                domOffset[i + 1] += domOffset[i];
            }
            int[] domChildren = new int[domOffset[nodes]];
            int[] domCursor = Arrays.copyOf(domOffset, domOffset.length);
            for (int i = 1; i < reachable; i++) {
                int node = rpo[i];
                int parent = idom[node] < 0 ? root : idom[node];
                domChildren[domCursor[parent]++] = node;
            }

            int classCount = layout.size();
            long[] classRetained = new long[classCount];
            long[] classInstances = new long[classCount];
            long[] bestRetained = new long[classCount];
            String[] dominatedBy = new String[classCount];
            int[] dNode = new int[nodes];
            int[] dEdge = new int[nodes];
            boolean[] dCovered = new boolean[nodes];
            int dsp = 0;
            dNode[0] = root;
            dEdge[0] = domOffset[root];
            dCovered[0] = false;
            while (dsp >= 0) {
                int node = dNode[dsp];
                int e = dEdge[dsp];
                if (e < domOffset[node + 1]) {
                    dEdge[dsp] = e + 1;
                    int child = domChildren[e];
                    boolean covered = dCovered[dsp];
                    int slot = kind[child];
                    if (!covered && slot >= 0) {
                        classRetained[slot] += retained[child];
                        classInstances[slot]++;
                        if (retained[child] > bestRetained[slot]) {
                            bestRetained[slot] = retained[child];
                            int parent = idom[child] < 0 ? root : idom[child];
                            dominatedBy[slot] = parent == root ? GC_ROOT_NAME : layout.nameOf(kind[parent]);
                        }
                        covered = true;
                    }
                    dsp++;
                    dNode[dsp] = child;
                    dEdge[dsp] = domOffset[child];
                    dCovered[dsp] = covered;
                } else {
                    dsp--;
                }
            }

            List<ClassRow> rows = new ArrayList<>();
            for (int slot = 0; slot < classCount; slot++) {
                if (classInstances[slot] > 0) {
                    rows.add(new ClassRow(layout.nameOf(slot), classRetained[slot], classInstances[slot],
                            dominatedBy[slot] == null ? GC_ROOT_NAME : dominatedBy[slot]));
                }
            }
            rows.sort(BY_RETAINED);

            int edgeCount = offset[nodes];
            long totalShallow = retained[root];
            long dumpShallow = 0;
            for (int i = 0; i < n; i++) {
                dumpShallow += shallow[i];
            }
            long unreachable = Math.max(0, n - (reachable - 1));
            return new Result(n, edgeCount, classCount, rootCount, layoutMismatches, unreachable,
                    Math.max(0L, dumpShallow - totalShallow), totalShallow, dumpShallow, droppedEdges, rows);
        }

        /** CHK 的 intersect：沿 idom 链往上走到公共支配点（用 RPO 序号比较高低）。 */
        private static int intersect(int a, int b, int[] idom, int[] rpoIndex) {
            while (a != b) {
                while (rpoIndex[a] > rpoIndex[b]) {
                    a = idom[a];
                }
                while (rpoIndex[b] > rpoIndex[a]) {
                    b = idom[b];
                }
            }
            return a;
        }
    }
}
