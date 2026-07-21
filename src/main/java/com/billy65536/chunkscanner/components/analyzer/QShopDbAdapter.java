package com.billy65536.chunkscanner.components.analyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.chunkscanner.core.IChunkDb;

/**
 * QShop 数据库适配器 —— 所有 QShop 二进制格式定义和数据库读写的唯一权威。
 *
 * <h3>数据模型</h3>
 * 两层存储：主数据库（id=0）存基础记录，子数据库（id=1）存聊天增强数据。
 * 两者共用同一套 Key 格式，通过值长度区分记录类型。
 *
 * <h3>二进制布局</h3>
 * <pre>
 *   Key (34 bytes):  "qshop:" (6) + dimPoolId (4) + cx (4) + cz (4)
 *                  + keyHi (8, dimPoolId:32|x:32) + keyLo (8, z:32|y:32)
 *
 *   Base Value (48 bytes):  keyHi+keyLo (16) + ownerId (4) + modeQty (4)
 *                         + itemNameId (4) + price (4) + timestamp (8)
 *                         + itemIdPoolId (4) + flags (4)
 *
 *   Enhanced Value (20 bytes):  itemIdPoolId (4) + detailNbtPoolId (4)
 *                             + flags (4) + updateTime (8)
 * </pre>
 *
 * <h3>公开方法统一的 3 步模式</h3>
 * <ol>
 *   <li>转换 key — {@link #makeKey(String, int, int, int, int, int)}</li>
 *   <li>转换 value — {@link #makeRecordValue} / {@link #parseRecordValue} /
 *       {@link #makeEnhancementValue} / {@link #parseEnhancementValue}</li>
 *   <li>DB 操作 — {@link IChunkDb#put} / {@link IChunkDb#get} / …</li>
 * </ol>
 */
public final class QShopDbAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.adapter");

    private final IChunkDb db;
    private final IChunkDb subDb;

    public QShopDbAdapter(IChunkDb db) {
        this.db = db;
        this.subDb = db.getSubDb(1);
    }

    // ==================== 公开记录类型 ====================

    /**
     * QShop 商店记录，由基础扫描字段 + 聊天增强字段合并而成。
     *
     * @param dimId          维度标识
     * @param cx             chunk X 坐标
     * @param cz             chunk Z 坐标
     * @param x              方块 X 坐标
     * @param y              方块 Y 坐标
     * @param z              方块 Z 坐标
     * @param owner          商店所有者
     * @param mode           交易模式（购买/出售）
     * @param quantity       交易数量
     * @param itemName       物品名称（游戏内翻译名）
     * @param price          价格（最小货币单位）
     * @param timestamp      记录时间戳（毫秒）
     * @param itemId         物品注册 ID（增强后覆盖）
     * @param flags          标志位（来自 QShopAnalyzer FLAG_*）
     * @param detailNbtString 完整 NBT 字符串（增强数据，可为 null）
     * @param enhancementTimestamp 增强数据时间戳（毫秒，不存在为 -1）
     */
    public record Record(String dimId, int cx, int cz, int x, int y, int z,
                         String owner, byte mode, int quantity,
                         String itemName, int price, long timestamp,
                         String itemId, int flags,
                         String detailNbtString,
                         long enhancementTimestamp) {}

    /**
     * 聊天增强数据，存储物品的注册 ID、完整 NBT 等信息。
     *
     * @param dimId          维度标识
     * @param x              方块 X 坐标
     * @param y              方块 Y 坐标
     * @param z              方块 Z 坐标
     * @param itemId         物品注册 ID
     * @param detailNbtString 完整 NBT 字符串（可为 null）
     * @param flags          标志位（FLAG_ENHANCED_DATA | FLAG_SHULKER_EXPANDED | FLAG_BOOK）
     */
    public record Enhancement(String dimId, int x, int y, int z,
                              String itemId, String detailNbtString, int flags) {}

    // ==================== 公开方法 ====================

    /**
     * 删除一个 chunk 内的所有 QShop 主记录。
     *
     * <p>仅清除主数据库（id=0）中的记录，<b>不删除子数据库（id=1）中的增强数据</b>。
     * 增强数据在重新扫描后仍可通过同一 Key 被 {@link #parseRecordValue} 合并，
     * 确保聊天捕获的物品 NBT、注册 ID 等信息不会因区块重访而丢失。</p>
     *
     * @param dimId 维度标识
     * @param cx    chunk X 坐标
     * @param cz    chunk Z 坐标
     * @return 主数据库中是否有记录被删除
     */
    public boolean deleteChunk(String dimId, int cx, int cz) {
        byte[] prefix = makeChunkPrefix(dimId, cx, cz);
        int count = db.removeAllWithPrefix(prefix);
        return count > 0;
    }

    /**
     * 检查指定位置是否存在 QShop 主记录。
     *
     * @return 记录存在且值长度合法时为 true
     */
    public boolean hasRecord(String dimId, int cx, int cz, int x, int y, int z) {
        byte[] value = db.get(makeKey(dimId, cx, cz, x, y, z));
        return value != null && value.length >= BASE_RECORD_SIZE;
    }

    /**
     * 写入一条扫描记录到主数据库。
     * <p>所有字符串自动 intern，price 以最小货币单位整数存储。
     *
     * @param registryId 物品注册 ID，可为 null（计入 flags）
     */
    public void addRecord(String dimId, int cx, int cz, int x, int y, int z,
                          String owner, byte mode, int quantity,
                          String itemName, int price,
                          String registryId, long timestamp) {
        byte[] key = makeKey(dimId, cx, cz, x, y, z);
        byte[] value = makeRecordValue(dimId, x, z, y, owner, mode, quantity,
                itemName, price, registryId, timestamp);
        db.put(key, value);
    }

    /**
     * 获取指定位置的 QShop 记录。
     * <p>自动从子数据库合并增强数据（注册 ID、NBT 详情、flags）。
     *
     * @return 合并后的完整记录，不存在则返回 null
     */
    public Record getRecord(String dimId, int cx, int cz, int x, int y, int z) {
        byte[] key = makeKey(dimId, cx, cz, x, y, z);
        byte[] value = db.get(key);
        if (value == null || value.length < BASE_RECORD_SIZE) return null;
        return parseRecordValue(key, value);
    }

    /**
     * 获取数据库中所有 QShop 记录（合并增强数据）。
     * <p>遍历主数据库全部条目，过滤非 QShop key，逐一解析。
     *
     * @return 全部记录列表，异常时返回空列表
     */
    public List<Record> getAllRecords() {
        List<IChunkDb.Entry> entries;
        try {
            entries = db.getAllEntries();
        } catch (Exception e) {
            LOGGER.warn("QShopDbAdapter: failed to get entries: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (entries.isEmpty()) return Collections.emptyList();

        List<Record> records = new ArrayList<>(entries.size());
        for (IChunkDb.Entry entry : entries) {
            byte[] key = entry.key();
            byte[] val = entry.value();
            if (!isQShopKey(key)) continue;
            if (val.length < BASE_RECORD_SIZE) continue;
            try {
                Record rec = parseRecordValue(key, val);
                if (rec != null) {
                    records.add(rec);
                }
            } catch (Exception e) {
                LOGGER.warn("QShopDbAdapter: failed to parse entry: {}", e.getMessage());
            }
        }
        return records;
    }

    /**
     * 检查指定位置的记录是否已有增强数据。
     *
     * @return 子数据库中存在对应增强记录时为 true
     */
    public boolean hasEnhanced(String dimId, int cx, int cz, int x, int y, int z) {
        return subDb.get(makeKey(dimId, cx, cz, x, y, z)) != null;
    }

    /**
     * 为已有记录添加聊天增强数据。
     * <p>仅写入子数据库（id=1），不修改主数据库。
     * 若主记录不存在则静默跳过，已有增强数据会直接覆盖。
     *
     * @param registryId       物品注册 ID
     * @param isBook           是否为书类物品
     * @param isShulkerExpanded 是否已展开潜影盒
     * @param fullNbtString    完整 NBT 字符串（可序列化，支持潜影盒等复杂物品）
     */
    public void enhanceRecord(String dimId, int cx, int cz, int x, int y, int z,
                              String registryId,
                              boolean isBook, boolean isShulkerExpanded,
                              String fullNbtString) {
        byte[] key = makeKey(dimId, cx, cz, x, y, z);

        if (db.get(key) == null) {
            LOGGER.debug("QShopDbAdapter: no record at ({}, {}, {}), cannot enhance", x, y, z);
            return;
        }

        byte[] value = makeEnhancementValue(registryId, fullNbtString, isBook, isShulkerExpanded);
        subDb.put(key, value);

        LOGGER.info("QShopDbAdapter: enhanced record {} at ({}, {}, {})",
                registryId, x, y, z);
    }

    /**
     * 获取指定位置的增强数据。
     *
     * @return 增强数据，不存在或长度非法时返回 null
     */
    public Enhancement getEnhancement(String dimId, int cx, int cz, int x, int y, int z) {
        byte[] key = makeKey(dimId, cx, cz, x, y, z);
        byte[] val = subDb.get(key);
        if (val == null || val.length < ENHANCED_RECORD_SIZE) return null;
        return parseEnhancementValue(key, val);
    }

    /**
     * 获取子数据库中所有增强数据。
     * <p>遍历子数据库全部条目，逐一解析。不验证主记录是否存在。
     *
     * @return 全部增强数据列表，异常时返回空列表
     */
    public List<Enhancement> getAllEnhancements() {
        List<IChunkDb.Entry> entries;
        try {
            entries = subDb.getAllEntries();
        } catch (Exception e) {
            LOGGER.warn("QShopDbAdapter: failed to get subDb entries: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (entries.isEmpty()) return Collections.emptyList();

        List<Enhancement> results = new ArrayList<>(entries.size());
        for (IChunkDb.Entry entry : entries) {
            byte[] val = entry.value();
            if (val.length < ENHANCED_RECORD_SIZE) continue;
            try {
                Enhancement enh = parseEnhancementValue(entry.key(), val);
                if (enh != null) {
                    results.add(enh);
                }
            } catch (Exception e) {
                LOGGER.warn("QShopDbAdapter: failed to parse enhancement: {}", e.getMessage());
            }
        }
        return results;
    }

    /** @return 底层主数据库实例（id=0） */
    public IChunkDb getMainDb() {
        return db;
    }

    /** @return 底层子数据库实例（id=1，存储增强数据） */
    public IChunkDb getSubDb() {
        return subDb;
    }

    // ==================== 格式常量 ====================

    /** Key 前缀，用于在 DB 中区分 QShop 记录与其他数据。 */
    private static final byte[] KEY_PREFIX = "qshop:".getBytes(StandardCharsets.UTF_8);
    /** 完整 Key 长度：prefix(6) + dimPoolId(4) + cx(4) + cz(4) + keyHi(8) + keyLo(8) */
    private static final int KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 8 + 8; // 34
    /** Chunk 级前缀长度：prefix(6) + dimPoolId(4) + cx(4) + cz(4)，用于批量删除 */
    private static final int CHUNK_PREFIX_LEN = KEY_PREFIX.length + 4 + 4 + 4; // 18
    /** 基础记录值最小长度：keyHi+keyLo(16) + 8 个 int/long 字段 */
    private static final int BASE_RECORD_SIZE = 48;
    /** 增强记录值最小长度：3 个 int + 1 个 long */
    private static final int ENHANCED_RECORD_SIZE = 20;

    // ==================== 私有 — keyHi/keyLo 编解码 ====================

    /**
     * keyHi = dimPoolId(高 32 位) | x(低 32 位)。
     * 将 dimPoolId 和 X 坐标打包为 64 位整数，用于 DB 排序（先按维度、再按 X）。
     */
    private static long encodeKeyHi(int dimPoolId, int x) {
        return ((long) dimPoolId << 32) | (x & 0xFFFFFFFFL);
    }

    /**
     * keyLo = z(高 32 位) | y(低 32 位)。
     * 将 Z 和 Y 坐标打包为 64 位整数，用于 DB 排序（先按 Z、再按 Y）。
     */
    private static long encodeKeyLo(int z, int y) {
        return ((long) z << 32) | (y & 0xFFFFFFFFL);
    }

    /** 从 keyHi 中解码 X 坐标（低 32 位）。 */
    private static int decodeX(long keyHi) {
        return (int) (keyHi & 0xFFFFFFFFL);
    }

    /** 从 keyLo 中解码 Z 坐标（高 32 位）。 */
    private static int decodeZ(long keyLo) {
        return (int) (keyLo >> 32);
    }

    /** 从 keyLo 中解码 Y 坐标（低 32 位）。 */
    private static int decodeY(long keyLo) {
        return (int) (keyLo & 0xFFFFFFFFL);
    }

    // ==================== 私有 — Key 构造与解析 ====================

    /** keyHi/keyLo 的内部表示，通过 {@link #parseKeyHeader} 从二进制 Key 解析得到。 */
    private record KeyHeader(int dimPoolId, int cx, int cz, long keyHi, long keyLo) {}

    /**
     * 构造完整 DB 键（34 字节）。
     * <p>将 dimId 池化后编码，组装 keyHi/keyLo，委托给 {@link #makeKeyBytes}。
     */
    private byte[] makeKey(String dimId, int cx, int cz, int x, int y, int z) {
        int dimPoolId = db.intern(dimId);
        return makeKeyBytes(dimPoolId, cx, cz, encodeKeyHi(dimPoolId, x), encodeKeyLo(z, y));
    }

    /** 将已池化的参数拼装为 34 字节 Key 字节数组（小端序）。 */
    private static byte[] makeKeyBytes(int dimPoolId, int cx, int cz, long keyHi, long keyLo) {
        ByteBuffer bb = ByteBuffer.allocate(KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        return bb.array();
    }

    /**
     * 构造 chunk 级删除前缀（18 字节）。
     * <p>仅含 prefix + dimPoolId + cx + cz，不含坐标，用于 {@link IChunkDb#removeAllWithPrefix} 批量删除。
     */
    private byte[] makeChunkPrefix(String dimId, int cx, int cz) {
        int dimPoolId = db.intern(dimId);
        ByteBuffer bb = ByteBuffer.allocate(CHUNK_PREFIX_LEN).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        return bb.array();
    }

    /**
     * 从 Key 字节数组解析出 dimPoolId / cx / cz / keyHi / keyLo。
     *
     * @return 解析后的 KeyHeader，Key 长度不足时返回 null
     */
    private static KeyHeader parseKeyHeader(byte[] key) {
        if (key.length < CHUNK_PREFIX_LEN + 8 + 8) return null;
        ByteBuffer kb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
        kb.position(KEY_PREFIX.length);
        return new KeyHeader(kb.getInt(), kb.getInt(), kb.getInt(), kb.getLong(), kb.getLong());
    }

    /**
     * 判断 Key 是否属于 QShop 前缀。
     * <p>仅做前缀逐字节匹配，不验证后续字段合法性。
     */
    private static boolean isQShopKey(byte[] key) {
        if (key.length < KEY_PREFIX.length) return false;
        for (int i = 0; i < KEY_PREFIX.length; i++) {
            if (key[i] != KEY_PREFIX[i]) return false;
        }
        return true;
    }

    // ==================== 私有 — Record Value 转换 ====================

    /**
     * 序列化基础记录为 48 字节（小端序）。
     * <p>所有字符串通过 {@link IChunkDb#intern} 池化，仅写回整数引用。
     * registryId 为 null 时不设 FLAG_ID_RECOVERED。
     */
    private byte[] makeRecordValue(String dimId, int x, int z, int y,
                                   String owner, byte mode, int quantity,
                                   String itemName, int price,
                                   String registryId, long timestamp) {
        int dimPoolId = db.intern(dimId);
        int ownerId = db.intern(owner);
        int itemNameId = db.intern(itemName);
        int itemIdPoolId = registryId != null ? db.intern(registryId) : 0;
        int flags = registryId != null ? QShopAnalyzer.FLAG_ID_RECOVERED : 0;

        ByteBuffer vb = ByteBuffer.allocate(BASE_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        vb.putLong(encodeKeyHi(dimPoolId, x));
        vb.putLong(encodeKeyLo(z, y));
        vb.putInt(ownerId);
        vb.putInt(packModeQuantity(mode, quantity));
        vb.putInt(itemNameId);
        vb.putInt(price);
        vb.putLong(timestamp);
        vb.putInt(itemIdPoolId);
        vb.putInt(flags);
        return vb.array();
    }

    /**
     * 反序列化 Record，自动合并增强数据。
     * <p>坐标从 key（而非 value）中解析，确保数据一致性。
     * 若子数据库存在增强记录则合并：覆盖 itemId、合并 flags、读取 detailNbtString。
     */
    private Record parseRecordValue(byte[] key, byte[] value) {
        KeyHeader kh = parseKeyHeader(key);
        if (kh == null) return null;
        String dimId = db.lookup(kh.dimPoolId);

        ByteBuffer vb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        /* long keyHi = */ vb.getLong();
        /* long keyLo = */ vb.getLong();
        int x = decodeX(kh.keyHi);
        int z = decodeZ(kh.keyLo);
        int y = decodeY(kh.keyLo);

        int ownerId = vb.getInt();
        int modePacked = vb.getInt();
        int itemNameId = vb.getInt();
        int price = vb.getInt();
        long timestamp = vb.getLong();
        int itemIdPoolId = vb.getInt();
        int flags = vb.getInt();

        byte mode = unpackMode(modePacked);
        int quantity = unpackQuantity(modePacked);

        String owner = db.lookup(ownerId);
        String itemName = db.lookup(itemNameId);
        String itemId = itemIdPoolId != 0 ? db.lookup(itemIdPoolId) : "";
        String detailNbtString = null;
        long enhancementTimestamp = -1L;

        // 合并增强数据（覆盖 itemId、合并 flags、读取 NBT 详情）
        byte[] enhancedVal = subDb.get(key);
        if (enhancedVal != null && enhancedVal.length >= ENHANCED_RECORD_SIZE) {
            ByteBuffer evb = ByteBuffer.wrap(enhancedVal).order(ByteOrder.LITTLE_ENDIAN);
            int enhancedItemId = evb.getInt();
            int detailNbtPoolId = evb.getInt();
            int enhancedFlags = evb.getInt();
            enhancementTimestamp =  evb.getLong();

            if (enhancedItemId != 0) {
                itemId = subDb.lookup(enhancedItemId);
                flags &= ~QShopAnalyzer.FLAG_ID_RECOVERED; // 覆盖后，不再使用从映射表恢复的 id，移除该标记
            }
            flags |= enhancedFlags;
            detailNbtString = detailNbtPoolId != 0 ? subDb.lookup(detailNbtPoolId) : null;
        }

        return new Record(dimId, kh.cx, kh.cz, x, y, z, owner, mode, quantity,
                itemName, price, timestamp, itemId, flags, detailNbtString,
                enhancementTimestamp);
    }
    // ==================== 私有 — Enhancement Value 转换 ====================

    /**
     * 序列化增强数据为 20 字节（小端序）。
     * <p>字符串通过子数据库的 {@link IChunkDb#intern} 池化（与主数据库隔离）。
     * isBook / isShulkerExpanded 编码到 flags 字段。
     */
    private byte[] makeEnhancementValue(String registryId, String fullNbtString,
                                        boolean isBook, boolean isShulkerExpanded) {
        int itemIdPoolId = registryId != null && !registryId.isEmpty()
                ? subDb.intern(registryId) : 0;
        int detailNbtPoolId = fullNbtString != null
                ? subDb.intern(fullNbtString) : 0;

        int flags = QShopAnalyzer.FLAG_ENHANCED_DATA;
        if (isShulkerExpanded) flags |= QShopAnalyzer.FLAG_SHULKER_EXPANDED;
        if (isBook) flags |= QShopAnalyzer.FLAG_BOOK;

        ByteBuffer buf = ByteBuffer.allocate(ENHANCED_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(itemIdPoolId);
        buf.putInt(detailNbtPoolId);
        buf.putInt(flags);
        buf.putLong(System.currentTimeMillis());
        return buf.array();
    }

    /**
     * 反序列化 Enhancement。
     * <p>坐标从 key 中解析，池化 ID 通过子数据库的 {@link IChunkDb#lookup} 恢复为字符串。
     */
    private Enhancement parseEnhancementValue(byte[] key, byte[] value) {
        KeyHeader kh = parseKeyHeader(key);
        if (kh == null) return null;
        String dimId = db.lookup(kh.dimPoolId);

        ByteBuffer vb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int itemIdPoolId = vb.getInt();
        int detailNbtPoolId = vb.getInt();
        int flags = vb.getInt();
        /* long updateTime = */ vb.getLong();

        String itemId = itemIdPoolId != 0 ? subDb.lookup(itemIdPoolId) : "";
        String detailNbt = detailNbtPoolId != 0 ? subDb.lookup(detailNbtPoolId) : null;

        return new Enhancement(dimId, decodeX(kh.keyHi), decodeY(kh.keyLo),
                decodeZ(kh.keyLo), itemId, detailNbt, flags);
    }

    // ==================== 私有 — mode+quantity 位打包 ====================

    /**
     * 将 mode(1 字节) 和 quantity(3 字节) 打包为一个 int（小端序）。
     * <p>布局: [23:0] quantity | [7:0] mode
     */
    private static int packModeQuantity(byte mode, int quantity) {
        return (mode & 0xFF) | ((quantity & 0xFFFFFF) << 8);
    }

    /** 从打包值中提取 mode（低 8 位）。 */
    private static byte unpackMode(int packed) {
        return (byte) (packed & 0xFF);
    }

    /** 从打包值中提取 quantity（高 24 位）。 */
    private static int unpackQuantity(int packed) {
        return (packed >> 8) & 0xFFFFFF;
    }
}
