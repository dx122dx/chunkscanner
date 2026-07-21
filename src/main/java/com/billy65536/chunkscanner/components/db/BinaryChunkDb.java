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

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.CoreUtil;
import com.billy65536.chunkscanner.config.TaskConfig;

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
 * └──────────────────────────────────────────────┘
 */
public class BinaryChunkDb implements ChunkDb {
    /** 文件魔数："CHNKSCAN"（little-endian uint64）。package-private 供 DbFileUtil 引用。 */
    static final long MAGIC = 0x4E4143534B4E4843L; // "CHNKSCAN" (little-endian)
    /** 任务配置元数据键（存储在 KV store 中，JSON 序列化）。 */
    private static final byte[] TASK_CONFIG_KEY = "__taskConfig__".getBytes(StandardCharsets.UTF_8);

    /** 数据库目录路径（根据当前游戏上下文动态确定）。 */
    private final Path dbDir;
    /** 扫描任务 ID。 */
    private final String scanId;
    /** 安全文件名主干：chunkscanner_{hash}（不含扩展名）。 */
    private final String fileStem;
    /** 数据库扩展标识（由 Factory 指定，如 "bin4"）。 */
    private final String dbExt;
    /** 创建该数据库的分析器 ID。 */
    private String analyzerId;
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

    /** Chunk 扫描时间戳：packedChunkKey → 毫秒时间戳。 */
    private final Map<Long, Long> chunkScanTime;

    /** 脏标记：存在未刷写到磁盘的修改时为 true。 */
    private volatile boolean dirty = false;
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
        this(scanId, "");
    }

    public BinaryChunkDb(String scanId, String analyzerId) {
        this(scanId, analyzerId, false);
    }

    /**
     * 完整构造函数。
     *
     * @param metadataOnly 若为 true，只存储元数据不加载文件内容，用于文件列表浏览。
     */
    public BinaryChunkDb(String scanId, String analyzerId, boolean metadataOnly) {
        this(scanId, analyzerId, metadataOnly, ChunkScannerMod.getDbDir(), "bin4", 0);
    }

    /**
     * 完整构造函数，支持自定义数据库目录。
     * 当打开来自其他上下文的 DB 文件时（如 DB 浏览器），需要传入文件所在的实际目录。
     *
     * @param metadataOnly 若为 true，只存储元数据不加载文件内容，用于文件列表浏览。
     * @param dbDir 数据库目录，若为 null 则使用当前上下文默认路径。
     * @param dbExt  数据库扩展标识（如 "bin4"），决定文件扩展名。
     * @param subId 子数据库 ID，0 表示主数据库。子数据库使用 .sub_{subId}. 文件名段。
     */
    public BinaryChunkDb(String scanId, String analyzerId, boolean metadataOnly, Path dbDir, String dbExt, int subId) {
        this.scanId = scanId;
        this.analyzerId = analyzerId;
        this.dbDir = dbDir != null ? dbDir : ChunkScannerMod.getDbDir();
        this.dbExt = dbExt;
        this.subId = subId;
        this.fileStem = safeFileStem(scanId);
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
     */
    public TaskConfig getTaskConfig() {
        byte[] data = kvStore.get(new ByteArrayKey(TASK_CONFIG_KEY));
        if (data == null) return null;
        return TaskConfig.fromJson(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * 存储任务配置。传入 null 表示清除配置。
     */
    public void setTaskConfig(TaskConfig config) {
        if (config == null) {
            kvStore.remove(new ByteArrayKey(TASK_CONFIG_KEY));
        } else {
            kvStore.put(new ByteArrayKey(TASK_CONFIG_KEY), config.toJson().getBytes(StandardCharsets.UTF_8));
        }
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
     * 2. 读取 version，根据版本解析 header（v2+ 含 analyzerId）
     * 3. 加载字符串池（v3+ 每条记录带显式 ID，v1/v2 按顺序）
     * 4. 加载 Chunk Meta（含 dimPoolId，需通过字符串池还原维度名）
     * 5. 加载 KV 记录
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
            int scanIdLen = buf.getShort() & 0xFFFF;
            buf.clear();

            byte[] scanIdBytes = new byte[scanIdLen];
            readFully(ch, ByteBuffer.wrap(scanIdBytes), scanIdLen);

            // analyzerId (version >= 2)
            if (version >= 2) {
                buf.clear(); readFully(ch, buf, 2); buf.flip();
                int analyzerLen = buf.getShort() & 0xFFFF;
                buf.clear();
                if (analyzerLen > 0) {
                    byte[] analyzerBytes = new byte[analyzerLen];
                    readFully(ch, ByteBuffer.wrap(analyzerBytes), analyzerLen);
                    this.analyzerId = new String(analyzerBytes, StandardCharsets.UTF_8);
                }
            }

            // String pool
            buf.clear(); readFully(ch, buf, 4); buf.flip();
            long poolCount = buf.getInt() & 0xFFFFFFFFL;
            buf.clear();
            if (version >= 3) {
                // v3+: 每条记录带有显式 ID
                for (long i = 0; i < poolCount; i++) {
                    readFully(ch, buf, 8); buf.flip();
                    int id = buf.getInt();
                    int len = buf.getInt();
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
                    int len = buf.getInt();
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
            for (long i = 0; i < kvCount; i++) {
                buf.clear(); readFully(ch, buf, 4); buf.flip();
                int keyLen = buf.getInt();
                byte[] key = new byte[keyLen];
                readFully(ch, ByteBuffer.wrap(key), keyLen);

                buf.clear(); readFully(ch, buf, 4); buf.flip();
                int valLen = buf.getInt();
                byte[] val = new byte[valLen];
                readFully(ch, ByteBuffer.wrap(val), valLen);

                kvStore.put(new ByteArrayKey(key), val);
            }

            ChunkScannerMod.LOGGER.info("[scan:{}] Loaded {} kv, {} strings, {} metas.",
                    scanId, kvStore.size(), stringPool.size() - 1, chunkScanTime.size());

        } catch (IOException e) {
            ChunkScannerMod.LOGGER.error("[scan:{}] Load failed: {}", scanId, e.getMessage());
        }
    }

    private void readFully(FileChannel ch, ByteBuffer buf, int bytes) throws IOException {
        buf.clear(); buf.limit(bytes);
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new EOFException();
        }
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
        // 在迭代 collection 之前重置脏标记：并发写入在迭代期间设置 dirty=true
        // 会被下一次 flush 捕获，避免数据遗漏。
        dirty = false;
        try {
            Files.createDirectories(dbDir);
            Path tmpPath = dataPath().resolveSibling(fileName() + ".tmp");
            byte[] scanIdBytes = scanId.getBytes(StandardCharsets.UTF_8);
            byte[] analyzerBytes = (analyzerId != null ? analyzerId : "").getBytes(StandardCharsets.UTF_8);

            try (FileChannel ch = FileChannel.open(tmpPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                ByteBuffer buf = ByteBuffer.allocateDirect(128 * 1024);
                buf.order(ByteOrder.LITTLE_ENDIAN);

                // Header (version 3: pool entries with explicit IDs)
                buf.putLong(MAGIC);
                buf.putInt(3);
                buf.putShort((short) scanIdBytes.length);
                buf.put(scanIdBytes);
                buf.putShort((short) analyzerBytes.length);
                buf.put(analyzerBytes);
                buf.flip(); ch.write(buf);

                // String pool — 保存全部（池很小，避免 scanIntsIn 误判）
                // 使用快照避免并发 intern() 导致迭代期间遗漏新条目
                List<Integer> sorted = new ArrayList<>(new HashMap<>(stringPool).keySet());
                Collections.sort(sorted);
                buf.clear(); buf.putInt(sorted.size()); buf.flip(); ch.write(buf);
                for (int id : sorted) {
                    byte[] b = stringPool.getOrDefault(id, "").getBytes(StandardCharsets.UTF_8);
                    buf.clear(); buf.putInt(id); buf.putInt(b.length); buf.put(b); buf.flip(); ch.write(buf);
                }

                // Chunk meta
                buf.clear(); buf.putInt(chunkScanTime.size()); buf.flip(); ch.write(buf);
                for (Map.Entry<Long, Long> e : chunkScanTime.entrySet()) {
                    long key = e.getKey();
                    buf.clear();
                    buf.putInt((int) (key >> 48) & 0xFFFF);
                    buf.putInt(signExtend24Bit((int) (key >> 24)));
                    buf.putInt(signExtend24Bit((int) key));
                    buf.putLong(e.getValue());
                    buf.flip(); ch.write(buf);
                }

                // KV records
                buf.clear(); buf.putInt(kvStore.size()); buf.flip(); ch.write(buf);
                for (Map.Entry<ByteArrayKey, byte[]> e : kvStore.entrySet()) {
                    byte[] k = e.getKey().data;
                    byte[] v = e.getValue();
                    buf.clear(); buf.putInt(k.length); buf.put(k);
                    buf.putInt(v.length); buf.put(v);
                    buf.flip(); ch.write(buf);
                }
            }

            Files.move(tmpPath, dataPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            // 刷写失败时恢复脏标记，确保数据不会丢失
            dirty = true;
            ChunkScannerMod.LOGGER.error("[scan:{}] Flush failed: {}", scanId, e.getMessage());
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
    public ChunkDb getSubDb(int id) {
        if (id == 0) return this;
        if (subId > 0) {
            throw new UnsupportedOperationException("Sub-database cannot create sub-databases");
        }
        return subDbs.computeIfAbsent(id, k ->
                new BinaryChunkDb(scanId, analyzerId, false, dbDir, dbExt, k));
    }

    // ==================== 公共访问方法（供 RawDbProvider 等使用） ====================

    /** 数据库文件路径。 */
    public Path filePath() {
        return dataPath();
    }

    /** 创建此数据库的分析器名称。 */
    public String getAnalyzerId() {
        return analyzerId != null ? analyzerId : "";
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

    // ==================== ChunkDb.Factory ====================

    /** BinaryChunkDb 的工厂实现，注册为默认数据库引擎。 */
    public static class Factory implements ChunkDb.Factory {
        @Override
        public String getId() { return "binary"; }

        @Override
        public String getExt() { return "bin4"; }

        @Override
        public ChunkDb create(String scanId, String analyzerId, Path dbDir) {
            return new BinaryChunkDb(scanId, analyzerId, false, dbDir, getExt(), 0);
        }

        @Override
        public ChunkDb createMetadataOnly(String scanId, String analyzerId, Path dbDir) {
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

    /**
     * 根据 scanId 生成安全的文件名主干（不含扩展名）。
     * 使用 SHA-256 前 8 字节（base-36 编码），保证跨 JVM 确定性。
     */
    static String safeFileStem(String scanId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(scanId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long hash = ((long)(digest[0] & 0xFF) << 56)
                      | ((long)(digest[1] & 0xFF) << 48)
                      | ((long)(digest[2] & 0xFF) << 40)
                      | ((long)(digest[3] & 0xFF) << 32)
                      | ((long)(digest[4] & 0xFF) << 24)
                      | ((long)(digest[5] & 0xFF) << 16)
                      | ((long)(digest[6] & 0xFF) << 8)
                      | (digest[7] & 0xFF);
            return "chunkscanner_" + Long.toUnsignedString(hash & 0x7FFFFFFFFFFFFFFFL, 36);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 旧版 .dat 文件名生成（保留用于兼容旧文件，如 DbFileUtil 的 fallback 路径）。
     * @deprecated 新代码应使用安全命名语法 chunkscanner_{hash}.{analyzer}.{dbExt}
     */
    @Deprecated
    static String safeFileName(String scanId) {
        return safeFileStem(scanId) + ".dat";
    }

    /** 将分析器名称转换为符合文件名规范的标识符。 */
    private static String sanitizeAnalyzerId(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
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
