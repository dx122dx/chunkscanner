package com.billy65536.chunkscanner.components.db;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.db.DbStorage;
import com.billy65536.chunkscanner.core.CoreUtil;

import net.minecraft.util.Identifier;

/**
 * 紧凑二进制 ChunkDb 实现。
 *
 * <p>本类<b>不做任何文件操作</b>：负载的存放位置、文件名、临时文件与原子改名
 * 全部由 {@link DbPackage} 经 {@link DbStorage} 提供，这里只负责在
 * {@link FileChannel} 上完成一次完整的读或写。</p>
 *
 * <p>负载结构（v5）：</p>
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ Header                                       │
 * │   magic: "CHNKSCAN" (8)                      │
 * │   version: u32 (4)                           │
 * ├──────────────────────────────────────────────┤
 * │ String Pool                                  │
 * │   count: u32                                 │
 * │   for each: id:u32 | len:u32 | data          │
 * ├──────────────────────────────────────────────┤
 * │ Chunk Meta                                   │
 * │   count: u32                                 │
 * │   for each: dimPoolId:u32 | cx:i32 | cz:i32 | ts:u64 │
 * ├──────────────────────────────────────────────┤
 * │ KV Records                                   │
 * │   count: u32                                 │
 * │   for each: keyLen:u32 | key | valLen:u32 | val │
 * ├──────────────────────────────────────────────┤
 * │ CRC32: u32 (4)                               │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>v1–v4 的负载在 Header 之后还内嵌了 scanId / analyzerId / taskConfig，
 * v5 起这些身份信息统一由 {@code metadata.json} 承载，读取旧负载时跳过即可。</p>
 */
public class BinaryChunkDb implements IChunkDb {

    /** 负载魔数。 */
    private static final long MAGIC = DbFileUtil.MAGIC;
    /** 当前负载格式版本。 */
    private static final int CURRENT_VERSION = 5;
    /** 任务配置元数据键（仅用于 v1–v3 兼容读取时剔除）。 */
    private static final byte[] TASK_CONFIG_KEY = "__taskConfig__".getBytes(StandardCharsets.UTF_8);

    /** 日志标签（与所属包无强关联，仅用于诊断）。 */
    private final String label;
    /** 持久化通道。 */
    private final DbStorage storage;

    /** 字符串池：ID → 字符串。空串固定为 id=0。 */
    private final Map<Integer, String> stringPool = new ConcurrentHashMap<>();
    /** 字符串池反向索引：字符串 → ID。 */
    private final Map<String, Integer> stringPoolReverse = new ConcurrentHashMap<>();
    /** 下一个可分配的字符串 ID。初始为 1（0 保留给空串）。 */
    private final AtomicInteger nextStringId = new AtomicInteger(1);

    /** 通用 KV 存储：byte[] 键 → byte[] 值。 */
    private final Map<ByteArrayKey, byte[]> kvStore = new ConcurrentHashMap<>();

    /** Chunk 扫描时间戳：packedChunkKey → 毫秒时间戳。 */
    private final Map<Long, Long> chunkScanTime = new ConcurrentHashMap<>();

    /** 最近一次读入或写出的负载格式版本。 */
    private volatile int formatVersion = CURRENT_VERSION;
    /** 脏标记：存在未刷写的修改时为 true。 */
    private volatile boolean dirty = false;
    /**
     * 加载失败标记：读取因负载损坏或 IO 错误中断时为 true。
     * 此时内存数据不完整，flush() 必须拒绝写回，否则会用残缺数据覆盖原内容。
     */
    private volatile boolean loadFailed = false;
    /** loadFailed 拒绝写回的告警只打印一次，避免每 tick 刷屏。 */
    private volatile boolean loadFailWarned = false;
    /** 关闭标记：已调用 close() 时为 true，后续 flush() 将忽略。 */
    private volatile boolean closed = false;
    /** 打开标记：负载已加载（或确认无负载）后为 true。 */
    private volatile boolean opened = false;

    /** 创建纯内存实例（无持久化）。 */
    public BinaryChunkDb() {
        this(DbStorage.NONE, "binary");
    }

    /**
     * 由 {@link IChunkDb.IFactory} 创建的实例。
     *
     * <p>构造时<b>不</b>加载负载，由 {@link DbPackage} 按需 {@link #open()}。</p>
     *
     * @param storage 持久化通道，纯内存实例传 {@link DbStorage#NONE}
     * @param label   日志标签
     */
    public BinaryChunkDb(DbStorage storage, String label) {
        this.storage = storage != null ? storage : DbStorage.NONE;
        this.label = label != null ? label : "binary";

        stringPool.put(0, "");
        stringPoolReverse.put("", 0);
    }

    // ==================== 字符串池 ====================

    @Override
    public int intern(String s) {
        if (s == null || s.isEmpty()) return 0;
        return stringPoolReverse.computeIfAbsent(s, k -> {
            int id = nextStringId.getAndIncrement();
            stringPool.put(id, s);
            return id;
        });
    }

    @Override
    public String lookup(int id) {
        return stringPool.getOrDefault(id, "");
    }

    // ==================== KV 操作 ====================

    @Override
    public void put(byte[] key, byte[] value) {
        kvStore.put(new ByteArrayKey(key), value);
        dirty = true;
    }

    @Override
    public void putAll(Iterable<Entry> entries) {
        for (Entry e : entries) {
            kvStore.put(new ByteArrayKey(e.key()), e.value());
        }
        dirty = true;
    }

    @Override
    public byte[] get(byte[] key) {
        return kvStore.get(new ByteArrayKey(key));
    }

    @Override
    public void remove(byte[] key) {
        kvStore.remove(new ByteArrayKey(key));
        dirty = true;
    }

    @Override
    public int removeAllWithPrefix(byte[] prefix) {
        int removed = 0;
        var it = kvStore.entrySet().iterator();
        while (it.hasNext()) {
            byte[] key = it.next().getKey().data;
            if (key.length >= prefix.length && CoreUtil.startsWith(key, prefix)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) dirty = true;
        return removed;
    }

    @Override
    public boolean containsKey(byte[] key) {
        return kvStore.containsKey(new ByteArrayKey(key));
    }

    @Override
    public int size() {
        return kvStore.size();
    }

    @Override
    public List<Entry> getAllEntries() {
        List<Entry> entries = new ArrayList<>(kvStore.size());
        for (Map.Entry<ByteArrayKey, byte[]> e : kvStore.entrySet()) {
            entries.add(Entry.of(e.getKey().data, e.getValue()));
        }
        return entries;
    }

    @Override
    public List<ChunkMeta> getAllChunkMetas() {
        List<ChunkMeta> metas = new ArrayList<>(chunkScanTime.size());
        for (Map.Entry<Long, Long> e : chunkScanTime.entrySet()) {
            long key = e.getKey();
            int dimPoolId = (int) (key >> 48) & 0xFFFF;
            String dimensionId = lookup(dimPoolId);
            int cx = signExtend24Bit((int) (key >> 24));
            int cz = signExtend24Bit((int) key);
            metas.add(new ChunkMeta(dimensionId, cx, cz, e.getValue()));
        }
        return metas;
    }

    // ==================== Chunk 元数据 ====================

    @Override
    public long getChunkScanTime(String dimensionId, int cx, int cz) {
        int dimPoolId = intern(dimensionId);
        return chunkScanTime.getOrDefault(packChunkKey(dimPoolId, cx, cz), 0L);
    }

    @Override
    public void updateChunkScanTime(String dimensionId, int cx, int cz, long timestamp) {
        int dimPoolId = intern(dimensionId);
        chunkScanTime.put(packChunkKey(dimPoolId, cx, cz), timestamp);
        dirty = true;
    }

    /** KV 记录总数。 */
    public int kvCount() {
        return kvStore.size();
    }

    /** Chunk 元数据记录总数。 */
    public int chunkMetaCount() {
        return chunkScanTime.size();
    }

    // ==================== 生命周期 ====================

    @Override
    public void open() {
        if (opened) return;
        load();
        opened = true;
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    /**
     * 将内存数据写回持久化通道。
     *
     * <p>原子性由 {@link DbStorage#write} 保证；本方法只负责判定是否需要写、
     * 以及在失败时恢复脏标记。{@code synchronized} 防止多线程并发刷写。</p>
     */
    @Override
    public synchronized void flush() {
        if (!dirty || closed) return;
        if (loadFailed) {
            // 加载阶段就失败了：内存里只有半截数据，写回等于用残缺内容覆盖原负载（不可逆）。
            if (!loadFailWarned) {
                loadFailWarned = true;
                ChunkScannerMod.LOGGER.error("[{}] Refusing to flush: previous load failed, "
                        + "writing would destroy the existing payload.", label);
            }
            return;
        }
        // 在取快照之前重置脏标记：并发写入在此期间设置 dirty=true 会被下一次 flush 捕获。
        dirty = false;
        try {
            storage.write(this::writeTo);
        } catch (IOException | RuntimeException e) {
            // RuntimeException（如 BufferOverflowException）同样必须拦截：
            // dirty 已被置为 false，若异常逃逸则这批数据再也不会被写盘。
            dirty = true;
            ChunkScannerMod.LOGGER.error("[{}] Flush failed: {}", label, e.toString());
        }
    }

    @Override
    public void close() {
        flush();
        closed = true;
        opened = false;
    }

    private void load() {
        try {
            if (!storage.read(this::readFrom)) {
                ChunkScannerMod.LOGGER.info("[{}] No existing payload, starting fresh.", label);
            }
        } catch (IOException | RuntimeException e) {
            // 标记加载失败：内存中只有半截数据，禁止后续 flush 覆盖磁盘原内容。
            loadFailed = true;
            ChunkScannerMod.LOGGER.error("[{}] Load failed: {} — database is now read-only "
                    + "to protect the existing payload.", label, e.toString());
        }
    }

    // ==================== 负载读取 ====================

    /**
     * 从通道读取整个负载。
     *
     * <p>使用 DirectByteBuffer 减少 GC 压力，单次最多分配 32KB，大段内容分多次读取。</p>
     */
    @Override
    public void readFrom(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(32 * 1024);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        readFully(channel, buf, 8);
        buf.flip();
        if (buf.getLong() != MAGIC) {
            throw new IOException("Invalid magic number");
        }

        readFully(channel, buf, 4);
        buf.flip();
        int version = buf.getInt();
        this.formatVersion = version;

        if (version <= 4) {
            skipLegacyHeader(channel, buf, version);
        }

        readStringPool(channel, buf, version);
        readChunkMeta(channel, buf);
        readKvRecords(channel, buf);

        if (version < 4) {
            // v1–v3 把 taskConfig 塞在 KV 里；身份信息现由 metadata.json 承载，此处仅剔除
            kvStore.remove(new ByteArrayKey(TASK_CONFIG_KEY));
        }
        if (version >= 4) {
            verifyCrc(channel, buf);
        }

        ChunkScannerMod.LOGGER.info("[{}] Loaded {} kv, {} strings, {} metas (v{}).",
                label, kvStore.size(), stringPool.size() - 1, chunkScanTime.size(), version);
    }

    /** 跳过 v1–v4 内嵌的 scanId / analyzerId / taskConfig：这些字段已迁往 metadata.json。 */
    private void skipLegacyHeader(FileChannel channel, ByteBuffer buf, int version) throws IOException {
        skipBlock(channel, buf, "scanId");
        if (version >= 2) skipBlock(channel, buf, "analyzerId");
        if (version >= 4) skipBlock(channel, buf, "taskConfig");
    }

    /** 跳过一段「u16 长度 + 内容」。 */
    private static void skipBlock(FileChannel channel, ByteBuffer buf, String field) throws IOException {
        readFully(channel, buf, 2);
        buf.flip();
        int len = checkLen(buf.getShort() & 0xFFFF, channel, field);
        if (len > 0) channel.position(channel.position() + len);
    }

    private void readStringPool(FileChannel channel, ByteBuffer buf, int version) throws IOException {
        readFully(channel, buf, 4);
        buf.flip();
        long poolCount = checkCount(buf.getInt() & 0xFFFFFFFFL, version >= 3 ? 8 : 4, channel, "stringPool");

        if (version >= 3) {
            for (long i = 0; i < poolCount; i++) {
                readFully(channel, buf, 8);
                buf.flip();
                int id = buf.getInt();
                int len = checkLen(buf.getInt(), channel, "stringPool entry");
                byte[] b = new byte[len];
                readFully(channel, ByteBuffer.wrap(b), len);
                String s = new String(b, StandardCharsets.UTF_8);
                stringPool.put(id, s);
                stringPoolReverse.put(s, id);
                nextStringId.updateAndGet(cur -> Math.max(cur, id + 1));
            }
            return;
        }

        // v1/v2：无显式 ID，按顺序加载。第一个条目是 id=0 的空串（构造时已存在），跳过。
        boolean first = true;
        for (long i = 0; i < poolCount; i++) {
            readFully(channel, buf, 4);
            buf.flip();
            int len = checkLen(buf.getInt(), channel, "stringPool entry");
            byte[] b = new byte[len];
            readFully(channel, ByteBuffer.wrap(b), len);
            if (first) {
                first = false;
                if (len == 0) continue;
            }
            int id = nextStringId.getAndIncrement();
            String s = new String(b, StandardCharsets.UTF_8);
            stringPool.put(id, s);
            stringPoolReverse.put(s, id);
        }
    }

    private void readChunkMeta(FileChannel channel, ByteBuffer buf) throws IOException {
        readFully(channel, buf, 4);
        buf.flip();
        long metaCount = checkCount(buf.getInt() & 0xFFFFFFFFL, 20, channel, "chunkMeta");
        for (long i = 0; i < metaCount; i++) {
            readFully(channel, buf, 20);
            buf.flip();
            int dimPoolId = buf.getInt(), cx = buf.getInt(), cz = buf.getInt();
            long ts = buf.getLong();
            chunkScanTime.put(packChunkKey(dimPoolId, cx, cz), ts);
        }
    }

    private void readKvRecords(FileChannel channel, ByteBuffer buf) throws IOException {
        readFully(channel, buf, 4);
        buf.flip();
        long kvRecords = checkCount(buf.getInt() & 0xFFFFFFFFL, 8, channel, "kvRecord");
        for (long i = 0; i < kvRecords; i++) {
            readFully(channel, buf, 4);
            buf.flip();
            int keyLen = checkLen(buf.getInt(), channel, "kv key");
            byte[] key = new byte[keyLen];
            readFully(channel, ByteBuffer.wrap(key), keyLen);

            readFully(channel, buf, 4);
            buf.flip();
            int valLen = checkLen(buf.getInt(), channel, "kv value");
            byte[] val = new byte[valLen];
            readFully(channel, ByteBuffer.wrap(val), valLen);

            kvStore.put(new ByteArrayKey(key), val);
        }
    }

    /** 校验尾部 CRC32；不匹配只告警，读到的内容仍然保留。 */
    private void verifyCrc(FileChannel channel, ByteBuffer buf) throws IOException {
        long dataLen = channel.position();
        channel.position(0);

        CRC32 crc = new CRC32();
        byte[] crcBuf = new byte[8192];
        long remaining = dataLen;
        while (remaining > 0) {
            int toRead = (int) Math.min(crcBuf.length, remaining);
            readFully(channel, ByteBuffer.wrap(crcBuf, 0, toRead), toRead);
            crc.update(crcBuf, 0, toRead);
            remaining -= toRead;
        }

        readFully(channel, buf, 4);
        buf.flip();
        int storedCrc = buf.getInt();
        int computedCrc = (int) crc.getValue();
        if (computedCrc != storedCrc) {
            ChunkScannerMod.LOGGER.warn("[{}] CRC32 mismatch: stored=0x{}, computed=0x{} — payload may be corrupted.",
                    label, Integer.toHexString(storedCrc), Integer.toHexString(computedCrc));
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, int bytes) throws IOException {
        buf.clear();
        buf.limit(bytes);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new EOFException();
        }
    }

    /**
     * 校验从负载读出的长度字段。
     * 损坏内容可能给出负数或超大长度，直接 {@code new byte[len]} 会抛
     * NegativeArraySizeException 或 OutOfMemoryError。
     */
    private static int checkLen(int len, FileChannel ch, String field) throws IOException {
        long remaining = ch.size() - ch.position();
        if (len < 0 || len > remaining) {
            throw new IOException("Corrupted " + field + " length: " + len
                    + " (remaining bytes=" + remaining + ")");
        }
        return len;
    }

    /**
     * 校验记录条数：每条记录至少占 {@code minBytes} 字节，
     * 条数超过「剩余字节 / minBytes」即可判定负载损坏，避免超长空转循环。
     */
    private static long checkCount(long count, int minBytes, FileChannel ch, String field) throws IOException {
        long remaining = ch.size() - ch.position();
        if (count < 0 || count > remaining / minBytes) {
            throw new IOException("Corrupted " + field + " count: " + count
                    + " (remaining bytes=" + remaining + ")");
        }
        return count;
    }

    // ==================== 负载写出 ====================

    /**
     * 将全部内存数据写入通道，末尾附加 CRC32。
     *
     * <p>通道由 {@link DbStorage#write} 提供且指向临时文件，本方法不做任何
     * 文件级操作；{@code force} 与原子改名由 {@link DbPackage} 负责。</p>
     */
    @Override
    public void writeTo(FileChannel channel) throws IOException {
        // 先对并发容器取快照，再写入「条数 + 条目」。
        // ConcurrentHashMap 的 size() 与后续迭代是弱一致的：worker 线程在两者之间
        // put 一条记录，就会导致声明的条数与实际写出的记录数不符，读回时错位解析。
        Map<Integer, String> poolSnapshot = new HashMap<>(stringPool);
        List<Map.Entry<Long, Long>> metaSnapshot = new ArrayList<>(chunkScanTime.entrySet());
        List<Map.Entry<ByteArrayKey, byte[]>> kvSnapshot = new ArrayList<>(kvStore.entrySet());

        CRC32 crc = new CRC32();
        ByteBuffer buf = ByteBuffer.allocateDirect(128 * 1024);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf = ensureCapacity(buf, 12);
        buf.putLong(MAGIC);
        buf.putInt(CURRENT_VERSION);
        writeFully(channel, buf, crc);

        // String pool —— 全量保存（池很小）
        List<Integer> sorted = new ArrayList<>(poolSnapshot.keySet());
        Collections.sort(sorted);
        buf = ensureCapacity(buf, 4);
        buf.putInt(sorted.size());
        writeFully(channel, buf, crc);
        for (int id : sorted) {
            byte[] b = poolSnapshot.getOrDefault(id, "").getBytes(StandardCharsets.UTF_8);
            buf = ensureCapacity(buf, 8 + b.length);
            buf.putInt(id);
            buf.putInt(b.length);
            buf.put(b);
            writeFully(channel, buf, crc);
        }

        // Chunk meta
        buf = ensureCapacity(buf, 4);
        buf.putInt(metaSnapshot.size());
        writeFully(channel, buf, crc);
        for (Map.Entry<Long, Long> e : metaSnapshot) {
            long key = e.getKey();
            buf = ensureCapacity(buf, 20);
            buf.putInt((int) (key >> 48) & 0xFFFF);
            buf.putInt(signExtend24Bit((int) (key >> 24)));
            buf.putInt(signExtend24Bit((int) key));
            buf.putLong(e.getValue());
            writeFully(channel, buf, crc);
        }

        // KV records
        buf = ensureCapacity(buf, 4);
        buf.putInt(kvSnapshot.size());
        writeFully(channel, buf, crc);
        for (Map.Entry<ByteArrayKey, byte[]> e : kvSnapshot) {
            byte[] k = e.getKey().data;
            byte[] v = e.getValue();
            // 单条记录可能超过默认缓冲容量，必须按需扩容，否则 BufferOverflowException
            buf = ensureCapacity(buf, 8 + k.length + v.length);
            buf.putInt(k.length);
            buf.put(k);
            buf.putInt(v.length);
            buf.put(v);
            writeFully(channel, buf, crc);
        }

        // CRC32 尾部（不计入自身）
        buf = ensureCapacity(buf, 4);
        buf.putInt((int) crc.getValue());
        writeFully(channel, buf, null);

        this.formatVersion = CURRENT_VERSION;
    }

    /**
     * 将缓冲区内容完整写入通道并累加校验和。
     * {@code FileChannel.write} 不保证一次写完，必须循环。
     */
    private static void writeFully(FileChannel ch, ByteBuffer buf, CRC32 crc) throws IOException {
        buf.flip();
        if (crc != null) {
            crc.update(buf.duplicate());
        }
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /**
     * 确保缓冲区至少能容纳 {@code need} 字节并复位；容量不足时返回一个更大的新缓冲。
     * 调用方必须用返回值覆盖原引用。
     */
    private static ByteBuffer ensureCapacity(ByteBuffer buf, int need) {
        if (need <= buf.capacity()) {
            buf.clear();
            return buf;
        }
        ByteBuffer bigger = ByteBuffer.allocateDirect(need);
        bigger.order(ByteOrder.LITTLE_ENDIAN);
        return bigger;
    }

    // ==================== IChunkDb.IFactory ====================

    /** BinaryChunkDb 的工厂实现，注册为默认数据库引擎。 */
    public static class Factory implements IChunkDb.IFactory {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("binary"); }

        @Override
        public String getExt() { return "bin"; }

        @Override
        public int getFormatVersion() { return CURRENT_VERSION; }

        @Override
        public IChunkDb create(DbStorage storage) {
            return new BinaryChunkDb(storage, "binary");
        }
    }

    // ==================== 工具 ====================

    private static long packChunkKey(int dimPoolId, int cx, int cz) {
        return ((long) (dimPoolId & 0xFFFF) << 48)
                | ((long) (cx & 0xFFFFFF) << 24)
                | (cz & 0xFFFFFFL);
    }

    /**
     * 将 24-bit 无符号值符号扩展为 32-bit 有符号 int。
     * 打包时低 24 位丢失了符号信息，解包时通过这一转换恢复原始 int 值。
     * 前提：实际区块坐标不会超出 24-bit 有符号范围（±8,388,607），这对 Minecraft 始终成立。
     */
    private static int signExtend24Bit(int value) {
        int v = value & 0xFFFFFF;
        if ((v & 0x800000) != 0) v |= 0xFF000000;
        return v;
    }

    /** byte[] 包装器，提供正确的 hashCode/equals 用于 HashMap。 */
    private record ByteArrayKey(byte[] data) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ByteArrayKey other && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
