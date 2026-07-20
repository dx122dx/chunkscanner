package com.billy65536.chunkscanner.components.view_provider;

import com.billy65536.chunkscanner.core.CoreUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.gui.layout.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.layout.ILayout;

/**
 * Sign 分析器特化的 DbViewProvider。
 *
 * 解析 sign 分析器生成的二进制 KV 数据，将原始字节转换为可读的告示牌信息。
 *
 * 新格式键（34 字节，version 2）：
 *   "sign:" (5B) | dimPoolId:u32 (4B) | cx:i32 (4B) | cz:i32 (4B) | side:u8 (1B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *   side: 0=正面(Front), 1=背面(Back)
 *
 * 旧格式键（21 字节，version 1，兼容）：
 *   "sign:" (5B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *
 * 值格式（48 字节）：
 *   keyHi:u64 (8B) | keyLo:u64 (8B) | l1..l4:u32 each (16B) | timestamp:u64 (8B)
 */
public class SignDbViewProvider implements DbViewProvider {

    private static final byte[] KEY_PREFIX = "sign:".getBytes(StandardCharsets.UTF_8);
    /** 新格式键长度：prefix(5) + dimPoolId(4) + cx(4) + cz(4) + side(1) + keyHi(8) + keyLo(8) = 34 */
    private static final int NEW_KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 1 + 8 + 8;
    private static final String[] HEADERS = {"位置", "Side", "Line 1", "Line 2", "Line 3", "Line 4"};

    private final ChunkDb db;

    /** 缓存解析后的告示牌记录，避免每帧重复解析。 */
    private List<SignRecord> cachedRecords;
    private volatile boolean cacheValid = false;

    public SignDbViewProvider(ChunkDb db) {
        this.db = db;
    }

    @Override
    public ChunkDb getDb() {
        return db;
    }

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<SignRecord> records = getSignRecords();
        int metaCount;
        try {
            metaCount = db.getAllChunkMetas().size();
        } catch (Exception e) {
            metaCount = 0;
        }

        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount, HEADERS);
        for (SignRecord sr : records) {
            LocatedPosition pos = new LocatedPosition(sr.dimId(), sr.x(), sr.y(), sr.z());
            b.addRow()
                    .position(pos)
                    .text(sr.side())
                    .text(sr.line1())
                    .text(sr.line2())
                    .text(sr.line3())
                    .text(sr.line4())
                    .done();
        }
        return b.build();
    }

    // ==================== Sign 特化展示 ====================

    /**
     * 解析 sign KV 条目为可读格式。
     * 结果会被缓存，数据不变时不会重复解析。
     */
    public List<SignRecord> getSignRecords() {
        if (cacheValid && cachedRecords != null) {
            return cachedRecords;
        }

        List<SignRecord> records = new ArrayList<>();
        List<ChunkDb.Entry> entries;
        try {
            entries = db.getAllEntries();
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("SignDbViewProvider: failed to get entries: {}", e.getMessage());
            cachedRecords = records;
            cacheValid = true;
            return records;
        }

        for (ChunkDb.Entry entry : entries) {
            try {
                byte[] key = entry.key();
                // 检查前缀
                if (key.length < KEY_PREFIX.length) continue;
                if (!CoreUtil.startsWith(key, KEY_PREFIX)) continue;

                byte[] val = entry.value();
                if (val.length < 48) continue;

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

                // 根据键长度判断格式版本
                String side;
                if (key.length >= NEW_KEY_SIZE) {
                    // 新格式：side 在 offset 17 处
                    byte sideByte = key[KEY_PREFIX.length + 4 + 4 + 4]; // offset 17
                    side = (sideByte == 1) ? "Back" : "Front";
                } else {
                    // 旧格式兼容：默认为正面
                    side = "Front";
                }

                String line1 = db.lookup(l1);
                String line2 = db.lookup(l2);
                String line3 = db.lookup(l3);
                String line4 = db.lookup(l4);

                records.add(new SignRecord(dimId, x, y, z, side, line1, line2, line3, line4, ts));
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("SignDbViewProvider: failed to parse entry: {}", e.getMessage());
            }
        }

        cachedRecords = records;
        cacheValid = true;
        return records;
    }

    public record SignRecord(String dimId, int x, int y, int z, String side,
                              String line1, String line2, String line3, String line4,
                              long timestamp) {}

    // ==================== 类型描述符 ====================

    /** Sign 视图类型描述符：解析告示牌数据为可读文本。仅适用于 sign 分析器。 */
    public static class Type implements DbViewProviderRegistry.Type {
        @Override
        public String getId() { return "sign_view"; }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.sign.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.sign.desc");
        }

        @Override
        public Set<String> applicableAnalyzers() {
            return Set.of("sign");
        }

        @Override
        public DbViewProvider create(ChunkDb db) {
            // 仅适用于 sign 分析器生成的数据库
            if (!"sign".equals(db.getAnalyzerName())) return null;
            return new SignDbViewProvider(db);
        }
    }
}
