package com.billy65536.chunkscanner.components.analyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.CoreUtil;

import net.minecraft.util.Identifier;

/**
 * Sign 分析器的数据库适配器。
 *
 * <p>把告示牌 KV 二进制封装为强类型访问：写入时由 {@link SignAnalyzer} 调用
 * {@link #deleteChunk} / {@link #addRecord}，读取时由 {@link SignDbViewProvider} 调用
 * {@link #getAllRecords()}。所有字节级编解码与解析逻辑集中于此，分析器与视图提供者
 * 均不直接接触 {@link IChunkDb}。</p>
 */
public final class SignDbAdaptor implements IDbAdaptor {

    /** sign 适配器的唯一标识符。 */
    public static final Identifier ID = ChunkScannerMod.id("sign");

    private static final int RECORD_SIZE = 48; // 8+8+4*4+8
    private static final byte[] KEY_PREFIX = "sign:".getBytes(StandardCharsets.UTF_8);
    // "sign:"(5) + dimPoolId(4) + cx(4) + cz(4) + side(1) + keyHi(8) + keyLo(8)
    private static final int KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 1 + 8 + 8; // 34
    private static final int CHUNK_PREFIX_LEN = KEY_PREFIX.length + 4 + 4 + 4; // 17
    private static final byte SIDE_FRONT = 0;
    private static final byte SIDE_BACK = 1;

    private final DbPackage pkg;
    private final IChunkDb db;

    public SignDbAdaptor(DbPackage pkg) {
        this.pkg = pkg;
        this.db = pkg.main();
    }

    @Override
    public DbPackage pkg() {
        return pkg;
    }

    /** 删除一个 chunk 内的所有 sign 记录（仅清主库，不影响其他数据）。 */
    public boolean deleteChunk(String dimId, int cx, int cz) {
        byte[] prefix = makeChunkPrefix(db.intern(dimId), cx, cz);
        return db.removeAllWithPrefix(prefix) > 0;
    }

    /**
     * 写入一条告示牌记录（单面）。
     *
     * @param dimId 维度标识
     * @param cx    chunk X
     * @param cz    chunk Z
     * @param x     方块 X
     * @param y     方块 Y
     * @param z     方块 Z
     * @param side  0=正面, 1=背面
     * @param now   时间戳（毫秒）
     * @param lines 四行文字（可能为空/空白）
     */
    public void addRecord(String dimId, int cx, int cz, int x, int y, int z,
                          byte side, long now, String l0, String l1, String l2, String l3) {
        int dimPoolId = db.intern(dimId);
        long keyHi = ((long) dimPoolId << 32) | (x & 0xFFFFFFFFL);
        long keyLo = ((long) z << 32) | (y & 0xFFFFFFFFL);

        int li1 = db.intern(l0);
        int li2 = db.intern(l1);
        int li3 = db.intern(l2);
        int li4 = db.intern(l3);

        ByteBuffer bb = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        bb.putInt(li1);
        bb.putInt(li2);
        bb.putInt(li3);
        bb.putInt(li4);
        bb.putLong(now);

        db.put(makeKey(keyHi, keyLo, cx, cz, side), bb.array());
    }

    /** 读取并解析数据库中所有告示牌记录（含坐标/正背面/四行文本）。 */
    public List<SignRecord> getAllRecords() {
        List<SignRecord> records = new ArrayList<>();
        List<IChunkDb.Entry> entries;
        try {
            entries = db.getAllEntries();
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("SignDbAdaptor: failed to get entries: {}", e.getMessage());
            return records;
        }

        for (IChunkDb.Entry entry : entries) {
            try {
                byte[] key = entry.key();
                if (key.length < KEY_PREFIX.length) continue;
                if (!CoreUtil.startsWith(key, KEY_PREFIX)) continue;

                byte[] val = entry.value();
                if (val.length < RECORD_SIZE) continue;

                ByteBuffer vb = ByteBuffer.wrap(val).order(ByteOrder.LITTLE_ENDIAN);
                long keyHi = vb.getLong();
                long keyLo = vb.getLong();

                int dimPoolId = (int) (keyHi >> 32);
                String dimId = db.lookup(dimPoolId);
                int x = (int) (keyHi & 0xFFFFFFFFL);
                int z = (int) (keyLo >> 32);
                int y = (int) (keyLo & 0xFFFFFFFFL);

                int l1 = vb.getInt();
                int l2 = vb.getInt();
                int l3 = vb.getInt();
                int l4 = vb.getInt();
                long ts = vb.getLong();

                String side;
                if (key.length >= KEY_SIZE) {
                    byte sideByte = key[KEY_PREFIX.length + 4 + 4 + 4]; // offset 17
                    side = (sideByte == SIDE_BACK) ? "Back" : "Front";
                } else {
                    side = "Front";
                }

                String line1 = db.lookup(l1);
                String line2 = db.lookup(l2);
                String line3 = db.lookup(l3);
                String line4 = db.lookup(l4);

                records.add(new SignRecord(dimId, x, y, z, side, line1, line2, line3, line4, ts));
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("SignDbAdaptor: failed to parse entry: {}", e.getMessage());
            }
        }
        return records;
    }

    /** 返回底层数据库的全部 chunk 扫描元数据（供视图统计）。 */
    public List<IChunkDb.ChunkMeta> getAllChunkMetas() {
        return db.getAllChunkMetas();
    }

    /** 解析后的告示牌记录（坐标 + 正背面 + 四行文本 + 时间戳）。 */
    public record SignRecord(String dimId, int x, int y, int z, String side,
                             String line1, String line2, String line3, String line4,
                             long timestamp) {}

    // ==================== 私有 — 键构造 ====================

    private static byte[] makeKey(long keyHi, long keyLo, int cx, int cz, byte side) {
        int dimPoolId = (int) (keyHi >> 32);
        ByteBuffer bb = ByteBuffer.allocate(KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        bb.put(side);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        return bb.array();
    }

    private static byte[] makeChunkPrefix(int dimPoolId, int cx, int cz) {
        ByteBuffer bb = ByteBuffer.allocate(CHUNK_PREFIX_LEN).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        return bb.array();
    }

    /** sign 适配器工厂。 */
    public static final class Factory implements IDbAdaptor.IFactory {
        @Override
        public Identifier getId() {
            return ID;
        }

        @Override
        public IDbAdaptor create(DbPackage pkg) {
            return new SignDbAdaptor(pkg);
        }
    }
}
