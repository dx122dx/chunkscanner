package com.billy65536.chunkscanner.components.db;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import com.billy65536.chunkscanner.core.CoreUtil;
import com.billy65536.chunkscanner.config.TaskConfig;

import net.minecraft.util.Identifier;

/**
 * 紧凑二进制 ChunkDb 实现。
 *
 * 文件命名：chunkscanner_{id_hash}.{analyzer_id}.{db_id}
 * 子数据库命名：chunkscanner_{id_hash}.{analyzer_id}.sub_{id}.{db_id}
 *
 * 文件结构：
 * ┌──────────────────────────────────────────────┐
 * │ Header (variable)                            │
 * │   magic: "CHNKSCAN" (8)                      │
 * │   version: u32 (4)                           │
 * │   scanIdLen: u16 (2)                         │
 * │   scanId: UTF-8 (scanIdLen)                  │
 * │   analyzerIdLen: u16 (2)                       │
 * │   analyzerId: UTF-8 (analyzerLen)           │
 * ├──────────────────────────────────────────────┤
 * │ TaskConfig (v4+)                             │
 * │   configLen: u16 (2)                         │
 * │   config: UTF-8 JSON (configLen)             │
 * ├──────────────────────────────────────────────┤
 * │ String Pool                                  │
 * │   count: u32                                 │
 * │   for each (v3+): id:u32 | len:u32 | data    │
 * │   for each (v1/v2): len:u32 | data (无 id)   │
 * ├──────────────────────────────────────────────┤
 * │ Chunk Meta                                   │
 * │   count: u32                                 │
 * │   for each: dimPoolId:u32 | cx:i32 | cz:i32 | ts:u64 │
 * ├──────────────────────────────────────────────┤
 * │ KV Records                                   │
 * │   count: u32                                 │
 * │   for each:                                  │
 * │     keyLen: u32  |  key: bytes               │
 * │     valLen: u32  |  val: bytes               │
 * ├──────────────────────────────────────────────┤
 * │ CRC32 (v4+)                                  │
 * │   crc: u32 (4)                               │
 * └──────────────────────────────────────────────┘
 */
public class BinaryChunkDb implements IChunkDb {
    /** 文件魔数 */
    private static final long MAGIC = DbFileUtil.MAGIC;
    /** 当前二进制文件格式版本。 */
    private static final int CURRENT_VERSION = 4;
    /** 任务配置元数据键（仅用于 v1-v3 兼容读取）。 */
    private static final byte[] TASK_CONFIG_KEY = "__taskConfig__".getBytes(StandardCharsets.UTF_8);

    /** 数据库目录路径（根据当前游戏上下文动态确定）。 */
    private final Path dbDir;
    /** 扫描任务 ID。 */
    private final String scanId;
    /** 安全文件名主干：chunkscanner_{hash}（不含扩展名）。 */
    private final String fileStem;
    /** 数据库扩展标识（由 Factory 指定）。 */
    private final String dbExt;
    /** 创建该数据库的分析器 ID。 */
    private Identifier analyzerId;
    /** 用于文件名的分析器 ID（sanitized）。 */
    private final String safeAnalyzerId;
    /** 子数据库 ID，0 表示主数据库。 */
    private final int subId;

    /** 字符串池：ID → 字符串（int → String）。空串固定为 id=0。 */
    private final Map<Integer, String> stringPool;
    /** 字符串池反向索引：字符串 → ID。 */
    private final Map<String, Integer> stringPoolReverse;
    /** 下一个可分配的字符串 ID。初始为 1（0 保留给空串）。使用 AtomicInteger 保证并发安全。 */
    private final AtomicInteger nextStringId;

    /** 通用 KV 存储：byte[] 键 → byte[] 值。使用 ByteArrayKey 包装器确保正确的 hashCode/equals。 */
    private final Map<ByteArrayKey, byte[]> kvStore;

    /** 任务配置（v4+ 独立存储于 Header 之后）。 */
    private TaskConfig taskConfig;

    /** Chunk 扫描时间戳：packedChunkKey → 毫秒时间戳。 */
    private final Map<Long, Long> chunkScanTime;

    /** 脏标记：存在未刷写到磁盘的修改时为 true。 */
    private volatile boolean dirty = false;
    /**
     * 加载失败标记：load() 因文件损坏或 IO 错误中断时为 true。
     * 此时内存数据不完整，flush() 必须拒绝写回，否则会用残缺数据覆盖原文件。
     */
    private volatile boolean loadFailed = false;
    /** loadFailed 拒绝写回的告警只打印一次，避免每 tick 刷屏。 */
    private volatile boolean loadFailWarned = false;
    /** 关闭标记：已调用 close() 时为 true，后续 flush() 将忽略。 */
    private volatile boolean closed = false;
    /** 打开标记：open() 被调用后为 true。 */
    private volatile boolean opened = false;
    /**
     * 元数据模式：仅用于文件列表展示。
     * 构造时不加载文件内容，调用 open() 后才加载。
     */
    private final boolean metadataOnly;

    public BinaryChunkDb(String scanId) {
        this(scanId, ChunkScannerMod.ID_UNKNOWN);
    }

    public BinaryChunkDb(String scanId, Identifier analyzerId) {
        this(scanId, analyzerId, false);
    }

    /**
     * 完整构造函数。
     *
     * @param metadataOnly 若为 true，只存储元数据不加载文件内容，用于文件列表浏览。
     */
    public BinaryChunkDb(String scanId, Identifier analyzerId, boolean metadataOnly) {
        this(scanId, analyzerId, metadataOnly, ChunkScannerMod.getDbDir(), "bin", 0);
    }

    /**
     * 完整构造函数，支持自定义数据库目录。
     * 当打开来自其他上下文的 DB 文件时（如 DB 浏览器），需要传入文件所在的实际目录。
     *
     * @param metadataOnly 若为 true，只存储元数据不加载文件内容，用于文件列表浏览。
     * @param dbDir 数据库目录，若为 null 则使用当前上下文默认路径。
     * @param dbExt  数据库扩展标识（如 "bin"），决定文件扩展名。
     * @param subId 子数据库 ID，0 表示主数据库。子数据库使用 .sub_{subId}. 文件名段。
     */
    public BinaryChunkDb(String scanId, Identifier analyzerId, boolean metadataOnly, Path dbDir, String dbExt, int subId) {
        this.scanId = scanId;
        this.analyzerId = analyzerId;
        this.dbDir = dbDir != null ? dbDir : ChunkScannerMod.getDbDir();
        this.dbExt = dbExt;
        this.subId = subId;
        this.fileStem = DbFileUtil.safeFilenameStem(scanId);
        this.safeAnalyzerId = sanitizeAnalyzerId(analyzerId);
        this.stringPool = new ConcurrentHashMap<>();
        this.stringPoolReverse = new ConcurrentHashMap<>();
        this.kvStore = new ConcurrentHashMap<>();
        this.chunkScanTime = new ConcurrentHashMap<>();
        this.nextStringId = new AtomicInteger(1);
        this.metadataOnly = metadataOnly;

        stringPool.put(0, "");
        stringPoolReverse.put("", 0);

        if (!metadataOnly) {
            load();
        }
    }

    @Override
    public String getScanId() { return scanId; }

    @Override
    public Identifier getFactoryId() { return ChunkScannerMod.id("binary"); }

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

    // ==================== 任务配置存取 ====================

    /**
     * 获取存储的任务配置。如果未设置或无法解析，返回 null。
     * v4+ 直接从独立字段读取，v1-v3 兼容从 KV store 读取并自动迁移。
     */
    public TaskConfig getTaskConfig() {
        if (taskConfig != null) return taskConfig;
        // fallback: v1-v3 兼容 — 从 KV Store 读取
        byte[] data = kvStore.get(new ByteArrayKey(TASK_CONFIG_KEY));
        if (data == null) return null;
        this.taskConfig = TaskConfig.fromJson(new String(data, StandardCharsets.UTF_8));
        return this.taskConfig;
    }

    /**
     * 存储任务配置。传入 null 表示清除配置。
     */
    public void setTaskConfig(TaskConfig config) {
        this.taskConfig = config;
        dirty = true;
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

    // ==================== 文件路径 ====================

    /** 数据库文件路径：主数据库 chunkscanner_{hash}.{analyzerId}.{dbExt}，子数据库加上 .sub_{subId} 段。 */
    private Path dataPath() {
        return dbDir.resolve(fileName());
    }

    /** 返回完整文件名（用于日志/显示/路径解析）。 */
    private String fileName() {
        if (subId > 0) {
            return fileStem + "." + safeAnalyzerId + ".sub_" + subId + "." + dbExt;
        }
        return fileStem + "." + safeAnalyzerId + "." + dbExt;
    }

    // ==================== 二进制加载 ====================

    /**
     * 从磁盘加载数据库文件。
     *
     * 读取流程（对应文件格式）：
     * 1. 验证 magic 魔数
     * 2. 读取 version，根据版本解析 header（v2+ 含 analyzerId，v4+ 含 taskConfig）
     * 3. v4+: 读取独立 taskConfig 段
     * 4. 加载字符串池（v3+ 每条记录带显式 ID，v1/v2 按顺序）
     * 5. 加载 Chunk Meta
     * 6. 加载 KV 记录（v1-v3 自动迁移 taskConfig 到独立字段）
     * 7. v4+: 验证 CRC32 校验和
     *
     * 使用 FileChannel + DirectByteBuffer 减少 GC 压力。
     * 单次最多分配 32KB 缓冲，大文件分多次 readFully。
     */
    private void load() {
        Path path = dataPath();
        if (!Files.exists(path)) {
            ChunkScannerMod.LOGGER.info("[scan:{}] No existing DB, starting fresh.", scanId);
            return;
        }

        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(32 * 1024);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            readFully(ch, buf, 8); buf.flip();
            if (buf.getLong() != MAGIC) {
                ChunkScannerMod.LOGGER.warn("[scan:{}] Invalid magic.", scanId);
                return;
            }
            buf.clear();

            readFully(ch, buf, 6); buf.flip();
            int version = buf.getInt();
            int scanIdLen = checkLen(buf.getShort() & 0xFFFF, ch, "scanId");
            buf.clear();

            byte[] scanIdBytes = new byte[scanIdLen];
            readFully(ch, ByteBuffer.wrap(scanIdBytes), scanIdLen);

            // analyzerId (version >= 2)
            if (version >= 2) {
                buf.clear(); readFully(ch, buf, 2); buf.flip();
                int analyzerLen = checkLen(buf.getShort() & 0xFFFF, ch, "analyzerId");
                buf.clear();
                if (analyzerLen > 0) {
                    byte[] analyzerBytes = new byte[analyzerLen];
                    readFully(ch, ByteBuffer.wrap(analyzerBytes), analyzerLen);
                    String raw = new String(analyzerBytes, StandardCharsets.UTF_8);
                    // 兼容旧文件：无命名空间时回退为 chunkscanner:<原值>；含冒号则按完整标识符解析
                    Identifier parsed = (raw.indexOf(':') >= 0) ? Identifier.tryParse(raw) : ChunkScannerMod.id(raw);
                    this.analyzerId = (parsed != null) ? parsed : ChunkScannerMod.id(raw);
                }
            }

            // taskConfig (version >= 4)
            if (version >= 4) {
                buf.clear(); readFully(ch, buf, 2); buf.flip();
                int configLen = checkLen(buf.getShort() & 0xFFFF, ch, "taskConfig");
                buf.clear();
                if (configLen > 0) {
                    byte[] configBytes = new byte[configLen];
                    readFully(ch, ByteBuffer.wrap(configBytes), configLen);
                    this.taskConfig = TaskConfig.fromJson(new String(configBytes, StandardCharsets.UTF_8));
                }
            }

            // String pool
            buf.clear(); readFully(ch, buf, 4); buf.flip();
            long poolCount = buf.getInt() & 0xFFFFFFFFL;
            buf.clear();
            poolCount = checkCount(poolCount, version >= 3 ? 8 : 4, ch, "stringPool");
            if (version >= 3) {
                // v3+: 每条记录带有显式 ID
                for (long i = 0; i < poolCount; i++) {
                    readFully(ch, buf, 8); buf.flip();
                    int id = buf.getInt();
                    int len = checkLen(buf.getInt(), ch, "stringPool entry");
                    buf.clear();
                    byte[] b = new byte[len];
                    readFully(ch, ByteBuffer.wrap(b), len);
                    String s = new String(b, StandardCharsets.UTF_8);
                    stringPool.put(id, s);
                    stringPoolReverse.put(s, id);
                    nextStringId.updateAndGet(cur -> Math.max(cur, id + 1));
                }
            } else {
                // v1/v2: 无 ID，按顺序加载。第一个条目是 id=0="" (已在构造时设置)，跳过。
                boolean first = true;
                for (long i = 0; i < poolCount; i++) {
                    readFully(ch, buf, 4); buf.flip();
                    int len = checkLen(buf.getInt(), ch, "stringPool entry");
                    buf.clear();
                    byte[] b = new byte[len];
                    readFully(ch, ByteBuffer.wrap(b), len);
                    if (first) {
                        first = false;
                        if (len == 0) continue; // id=0 的空串已存在
                        // 如果第一条不是空串，按原逻辑分配
                    }
                    int id = nextStringId.getAndIncrement();
                    String s = new String(b, StandardCharsets.UTF_8);
                    stringPool.put(id, s);
                    stringPoolReverse.put(s, id);
                }
            }

            // Chunk meta
            buf.clear(); readFully(ch, buf, 4); buf.flip();
            long metaCount = buf.getInt() & 0xFFFFFFFFL;
            buf.clear();
            metaCount = checkCount(metaCount, 20, ch, "chunkMeta");
            for (long i = 0; i < metaCount; i++) {
                readFully(ch, buf, 20); buf.flip();
                int dimPoolId = buf.getInt(), cx = buf.getInt(), cz = buf.getInt();
                long ts = buf.getLong();
                chunkScanTime.put(packChunkKey(dimPoolId, cx, cz), ts);
                buf.clear();
            }

            // KV records
            buf.clear(); readFully(ch, buf, 4); buf.flip();
            long kvCount = buf.getInt() & 0xFFFFFFFFL;
            kvCount = checkCount(kvCount, 8, ch, "kvRecord");
            for (long i = 0; i < kvCount; i++) {
                buf.clear(); readFully(ch, buf, 4); buf.flip();
                int keyLen = checkLen(buf.getInt(), ch, "kv key");
                byte[] key = new byte[keyLen];
                readFully(ch, ByteBuffer.wrap(key), keyLen);

                buf.clear(); readFully(ch, buf, 4); buf.flip();
                int valLen = checkLen(buf.getInt(), ch, "kv value");
                byte[] val = new byte[valLen];
                readFully(ch, ByteBuffer.wrap(val), valLen);

                kvStore.put(new ByteArrayKey(key), val);
            }

            // Migrate task config from KV (v1-v3 compat)
            if (version < 4) {
                byte[] tcData = kvStore.remove(new ByteArrayKey(TASK_CONFIG_KEY));
                if (tcData != null) {
                    this.taskConfig = TaskConfig.fromJson(new String(tcData, StandardCharsets.UTF_8));
                }
            }

            // CRC32 verification (version >= 4)
            if (version >= 4) {
                long dataLen = ch.position(); // position at start of CRC field
                ch.position(0);

                CRC32 crc = new CRC32();
                byte[] crcBuf = new byte[8192];
                long remaining = dataLen;
                while (remaining > 0) {
                    int toRead = (int) Math.min(crcBuf.length, remaining);
                    readFully(ch, ByteBuffer.wrap(crcBuf, 0, toRead), toRead);
                    crc.update(crcBuf, 0, toRead);
                    remaining -= toRead;
                }
                int computedCrc = (int) crc.getValue();

                buf.clear();
                readFully(ch, buf, 4);
                buf.flip();
                int storedCrc = buf.getInt();

                if (computedCrc != storedCrc) {
                    ChunkScannerMod.LOGGER.warn("[scan:{}] CRC32 mismatch: stored=0x{}, computed=0x{} — file may be corrupted.",
                            scanId, Integer.toHexString(storedCrc), Integer.toHexString(computedCrc));
                }
            }

            ChunkScannerMod.LOGGER.info("[scan:{}] Loaded {} kv, {} strings, {} metas.",
                    scanId, kvStore.size(), stringPool.size() - 1, chunkScanTime.size());

        } catch (IOException | RuntimeException e) {
            // 标记加载失败：内存中只有半截数据，禁止后续 flush 覆盖磁盘原文件。
            loadFailed = true;
            ChunkScannerMod.LOGGER.error("[scan:{}] Load failed ({}): {} — database is now read-only "
                    + "to protect the existing file.", scanId, fileName(), e.toString());
        }
    }

    private void readFully(FileChannel ch, ByteBuffer buf, int bytes) throws IOException {
        buf.clear(); buf.limit(bytes);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new EOFException();
        }
    }

    /**
     * 校验从文件读出的长度字段。
     * 损坏文件可能给出负数或超大长度，直接 {@code new byte[len]} 会抛
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
     * 条数超过「剩余字节 / minBytes」即可判定文件损坏，避免超长空转循环。
     */
    private static long checkCount(long count, int minBytes, FileChannel ch, String field) throws IOException {
        long remaining = ch.size() - ch.position();
        if (count < 0 || count > remaining / minBytes) {
            throw new IOException("Corrupted " + field + " count: " + count
                    + " (remaining bytes=" + remaining + ")");
        }
        return count;
    }

    // ==================== 二进制保存 ====================

    /**
     * 将内存数据刷写到磁盘。
     *
     * 使用原子写入策略：先写入临时 .tmp 文件，完成后 atomically move 到目标文件。
     * 这保证了写入过程中崩溃不会损坏已有数据。
     *
     * synchronized 防止多线程并发刷写。
     */
    @Override
    public synchronized void flush() {
        // 先刷写所有子数据库（仅主数据库拥有子数据库）
        if (subId == 0) {
            for (BinaryChunkDb sub : subDbs.values()) {
                sub.flush();
            }
        }
        if (!dirty || closed) return;
        if (loadFailed) {
            // 加载阶段就失败了：内存里只有半截数据，写回等于用残缺内容覆盖原文件（不可逆）。
            if (!loadFailWarned) {
                loadFailWarned = true;
                ChunkScannerMod.LOGGER.error("[scan:{}] Refusing to flush {}: previous load failed, "
                        + "writing would destroy the existing file.", scanId, fileName());
            }
            return;
        }
        // 在迭代 collection 之前重置脏标记：并发写入在迭代期间设置 dirty=true
        // 会被下一次 flush 捕获，避免数据遗漏。
        dirty = false;
        Path tmpPath = dataPath().resolveSibling(fileName() + ".tmp");
        try {
            Files.createDirectories(dbDir);
            byte[] scanIdBytes = scanId.getBytes(StandardCharsets.UTF_8);
            byte[] analyzerBytes = (analyzerId != null ? analyzerId : ChunkScannerMod.ID_UNKNOWN)
                    .toString().getBytes(StandardCharsets.UTF_8);
            checkU16(scanIdBytes.length, "scanId");
            checkU16(analyzerBytes.length, "analyzerId");

            // 先对并发容器取快照，再写入「条数 + 条目」。
            // ConcurrentHashMap 的 size() 与后续迭代是弱一致的：worker 线程在两者之间
            // put 一条记录，就会导致文件头声明的条数与实际写出的记录数不符，读回时错位解析。
            Map<Integer, String> poolSnapshot = new HashMap<>(stringPool);
            List<Map.Entry<Long, Long>> metaSnapshot = new ArrayList<>(chunkScanTime.entrySet());
            List<Map.Entry<ByteArrayKey, byte[]>> kvSnapshot = new ArrayList<>(kvStore.entrySet());

            try (FileChannel ch = FileChannel.open(tmpPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                ByteBuffer buf = ByteBuffer.allocateDirect(128 * 1024);
                buf.order(ByteOrder.LITTLE_ENDIAN);

                // Header (version 4: pool entries with explicit IDs, CRC32 appended)
                buf = ensureCapacity(buf, 16 + scanIdBytes.length + analyzerBytes.length);
                buf.putLong(MAGIC);
                buf.putInt(CURRENT_VERSION);
                buf.putShort((short) scanIdBytes.length);
                buf.put(scanIdBytes);
                buf.putShort((short) analyzerBytes.length);
                buf.put(analyzerBytes);
                writeFully(ch, buf);

                // Task config (v4+)
                byte[] tcBytes = taskConfig != null ? taskConfig.toJson().getBytes(StandardCharsets.UTF_8) : new byte[0];
                checkU16(tcBytes.length, "taskConfig");
                buf = ensureCapacity(buf, 2 + tcBytes.length);
                buf.putShort((short) tcBytes.length); buf.put(tcBytes); writeFully(ch, buf);

                // String pool — 保存全部（池很小，避免 scanIntsIn 误判）
                List<Integer> sorted = new ArrayList<>(poolSnapshot.keySet());
                Collections.sort(sorted);
                buf = ensureCapacity(buf, 4);
                buf.putInt(sorted.size()); writeFully(ch, buf);
                for (int id : sorted) {
                    byte[] b = poolSnapshot.getOrDefault(id, "").getBytes(StandardCharsets.UTF_8);
                    buf = ensureCapacity(buf, 8 + b.length);
                    buf.putInt(id); buf.putInt(b.length); buf.put(b); writeFully(ch, buf);
                }

                // Chunk meta
                buf = ensureCapacity(buf, 4);
                buf.putInt(metaSnapshot.size()); writeFully(ch, buf);
                for (Map.Entry<Long, Long> e : metaSnapshot) {
                    long key = e.getKey();
                    buf = ensureCapacity(buf, 20);
                    buf.putInt((int) (key >> 48) & 0xFFFF);
                    buf.putInt(signExtend24Bit((int) (key >> 24)));
                    buf.putInt(signExtend24Bit((int) key));
                    buf.putLong(e.getValue());
                    writeFully(ch, buf);
                }

                // KV records
                buf = ensureCapacity(buf, 4);
                buf.putInt(kvSnapshot.size()); writeFully(ch, buf);
                for (Map.Entry<ByteArrayKey, byte[]> e : kvSnapshot) {
                    byte[] k = e.getKey().data;
                    byte[] v = e.getValue();
                    // 单条记录可能超过默认缓冲容量，必须按需扩容，否则 BufferOverflowException
                    buf = ensureCapacity(buf, 8 + k.length + v.length);
                    buf.putInt(k.length); buf.put(k);
                    buf.putInt(v.length); buf.put(v);
                    writeFully(ch, buf);
                }
            }

            // v4+: compute CRC32 of all written data and append to file
            CRC32 crc = new CRC32();
            try (InputStream is = Files.newInputStream(tmpPath)) {
                byte[] crcBuf = new byte[8192];
                int n;
                while ((n = is.read(crcBuf)) > 0) {
                    crc.update(crcBuf, 0, n);
                }
            }
            try (FileChannel ch = FileChannel.open(tmpPath, StandardOpenOption.APPEND)) {
                ByteBuffer crcByteBuf = ByteBuffer.allocate(4);
                crcByteBuf.order(ByteOrder.LITTLE_ENDIAN);
                crcByteBuf.putInt((int) crc.getValue());
                crcByteBuf.flip();
                while (crcByteBuf.hasRemaining()) ch.write(crcByteBuf);
            }

            Files.move(tmpPath, dataPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException | RuntimeException e) {
            // RuntimeException（如 BufferOverflowException）同样必须拦截：
            // dirty 已被置为 false，若异常逃逸则这批数据再也不会被写盘。
            dirty = true;
            ChunkScannerMod.LOGGER.error("[scan:{}] Flush failed: {}", scanId, e.toString());
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
                // 残留 .tmp 不影响正确性，下次 flush 会 TRUNCATE_EXISTING 覆盖
            }
        }
    }

    /** 将缓冲区内容完整写入通道（FileChannel.write 不保证一次写完）。 */
    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        buf.flip();
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

    /** 校验将以 u16 长度前缀写出的字段，超长时快速失败而非静默截断。 */
    private static void checkU16(int len, String field) throws IOException {
        if (len > 0xFFFF) {
            throw new IOException(field + " too long for u16 length field: " + len + " bytes (max 65535)");
        }
    }

    @Override
    public void close() {
        // 关闭所有子数据库（仅主数据库拥有子数据库）
        if (subId == 0) {
            for (BinaryChunkDb sub : subDbs.values()) {
                sub.close();
            }
            subDbs.clear();
        }
        flush();
        closed = true;
        opened = false;
    }

    // ==================== 子数据库 ====================

    /** 子数据库缓存：id → 完整的 BinaryChunkDb 实例（独立文件、完整功能）。 */
    private final Map<Integer, BinaryChunkDb> subDbs = new ConcurrentHashMap<>();

    @Override
    public IChunkDb getSubDb(int id) {
        if (id == 0) return this;
        if (subId > 0) {
            throw new UnsupportedOperationException("Sub-database cannot create sub-databases");
        }
        return subDbs.computeIfAbsent(id, k ->
                new BinaryChunkDb(scanId, analyzerId, false, dbDir, dbExt, k));
    }

    // ==================== 公共访问方法（供 RawDbProvider 等使用） ====================

    /** 数据库文件路径。 */
    @Override
    public Path getFilePath() {
        return dataPath();
    }

    /** 创建此数据库的分析器名称。 */
    public Identifier getAnalyzerId() {
        return analyzerId != null ? analyzerId : ChunkScannerMod.ID_UNKNOWN;
    }

    /** 文件大小（字节）。 */
    public long getStorageSize() {
        Path p = dataPath();
        try { return Files.exists(p) ? Files.size(p) : 0; } catch (Exception e) { return 0; }
    }

    /** 最后修改时间戳。 */
    public long getLastModifiedTime() {
        Path p = dataPath();
        try { return Files.exists(p) ? p.toFile().lastModified() : 0; } catch (Exception e) { return 0; }
    }

    /** 打开数据库（延迟加载模式时触发 load）。 */
    public void open() {
        if (opened) return;
        if (metadataOnly) {
            load();
        }
        opened = true;
    }

    /** 是否已打开。 */
    public boolean isOpen() {
        return opened;
    }

    /** KV 记录总数。 */
    public int kvCount() {
        return kvStore.size();
    }

    /** Chunk 元数据记录总数。 */
    public int chunkMetaCount() {
        return chunkScanTime.size();
    }

    // ==================== IChunkDb.Factory ====================

    /** BinaryChunkDb 的工厂实现，注册为默认数据库引擎。 */
    public static class Factory implements IChunkDb.IFactory {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("binary"); }

        @Override
        public String getExt() { return "bin"; }

        @Override
        public IChunkDb create(String scanId, Identifier analyzerId, Path dbDir) {
            return new BinaryChunkDb(scanId, analyzerId, false, dbDir, getExt(), 0);
        }

        @Override
        public IChunkDb createMetadataOnly(String scanId, Identifier analyzerId, Path dbDir) {
            return new BinaryChunkDb(scanId, analyzerId, true, dbDir, getExt(), 0);
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

    // ==================== 文件名工具 ====================

    /** 将分析器标识符转换为符合文件名规范的片段（取 name 部分，命名空间不参与文件名）。 */
    private static String sanitizeAnalyzerId(Identifier id) {
        if (id == null) return "unknown";
        String name = id.getPath();
        if (name == null || name.isEmpty()) return "unknown";
        // 必须用 Locale.ROOT：土耳其语 locale 下 'I'.toLowerCase() 会变成 'ı'（无点 i），
        // 导致同一个 analyzerId 在不同系统语言下生成不同文件名。
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
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
