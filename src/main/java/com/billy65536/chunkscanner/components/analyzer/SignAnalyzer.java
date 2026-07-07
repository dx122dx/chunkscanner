package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.billy65536.chunkscanner.core.AnalyzeResult;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkDb;

/**
 * 默认告示牌分析器：扫描区块内所有告示牌并存入 ChunkDb。
 *
 * 支持 SignBlockEntity 和 HangingSignBlockEntity（1.20+），同时扫描正面和背面文字。
 * 重新扫描时会先删除该 chunk 中所有旧记录，保证已移除的告示牌被清理。
 *
 * 键格式（34 字节）：
 *   "sign:" (5B) | dimPoolId:u32 (4B) | cx:i32 (4B) | cz:i32 (4B) | side:u8 (1B) | keyHi:u64 (8B) | keyLo:u64 (8B)
 *   side: 0=正面, 1=背面
 *
 * 值格式（48 字节）：
 *   keyHi:u64 (8B) | keyLo:u64 (8B) | l1..l4:u32 each (16B) | timestamp:u64 (8B)
 */
public class SignAnalyzer implements ChunkAnalyzer {

    private static final int RECORD_SIZE = 48; // 8+8+4*4+8
    private static final byte[] KEY_PREFIX = "sign:".getBytes(StandardCharsets.UTF_8);
    // "sign:"(5) + dimPoolId(4) + cx(4) + cz(4) + side(1) + keyHi(8) + keyLo(8)
    private static final int KEY_SIZE = KEY_PREFIX.length + 4 + 4 + 4 + 1 + 8 + 8; // 34
    private static final int CHUNK_PREFIX_LEN = KEY_PREFIX.length + 4 + 4 + 4; // 17
    private static final byte SIDE_FRONT = 0;
    private static final byte SIDE_BACK = 1;

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, ChunkDb db, long now) {
        int dimPoolId = db.intern(dimId);

        // 先删除此区块中所有旧告示牌记录（保证已移除的告示牌从数据库中清除）
        byte[] chunkPrefix = makeChunkPrefix(dimPoolId, cx, cz);
        db.removeAllWithPrefix(chunkPrefix);

        List<byte[]> records = new ArrayList<>();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            // SignBlockEntity 覆盖普通告示牌和悬挂式告示牌（HangingSignBlockEntity extends SignBlockEntity）
            if (!(be instanceof SignBlockEntity sign)) continue;

            BlockPos pos = sign.getPos();
            // keyHi: [dimPoolId(32bit) | x(32bit)]，keyLo: [z(32bit) | y(32bit)]
            long keyHi = ((long) dimPoolId << 32) | (pos.getX() & 0xFFFFFFFFL);
            long keyLo = ((long) pos.getZ() << 32) | (pos.getY() & 0xFFFFFFFFL);

            // 同时扫描正面和背面文字
            collectRecord(records, sign.getFrontText(), keyHi, keyLo, cx, cz, SIDE_FRONT, now, db);
            collectRecord(records, sign.getBackText(), keyHi, keyLo, cx, cz, SIDE_BACK, now, db);
        }

        if (records.isEmpty()) {
            return AnalyzeResult.skipped();
        }

        // 批量写入（key/value 成对存储在 records 列表中）
        List<ChunkDb.Entry> entries = new ArrayList<>(records.size() / 2);
        for (int i = 0; i < records.size(); i += 2) {
            entries.add(ChunkDb.Entry.of(records.get(i), records.get(i + 1)));
        }
        db.putAll(entries);

        return AnalyzeResult.found("signs=" + entries.size());
    }

    /**
     * 收集单面告示牌文字记录。如果四行全空则跳过。
     */
    private static void collectRecord(List<byte[]> records, SignText text,
                                       long keyHi, long keyLo, int cx, int cz, byte side,
                                       long now, ChunkDb db) {
        String[] lines = new String[4];
        boolean hasContent = false;
        for (int i = 0; i < 4; i++) {
            lines[i] = text.getMessage(i, false).getString();
            if (lines[i] != null && !lines[i].trim().isEmpty()) hasContent = true;
        }
        if (!hasContent) return;

        int l1 = db.intern(lines[0]);
        int l2 = db.intern(lines[1]);
        int l3 = db.intern(lines[2]);
        int l4 = db.intern(lines[3]);

        // 值（48 字节）
        ByteBuffer bb = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(keyHi);
        bb.putLong(keyLo);
        bb.putInt(l1);
        bb.putInt(l2);
        bb.putInt(l3);
        bb.putInt(l4);
        bb.putLong(now);

        byte[] key = makeKey(keyHi, keyLo, cx, cz, side);
        records.add(key);
        records.add(bb.array());
    }

    /** 构造完整键（34 字节）。 */
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

    /**
     * 构造 chunk 级删除前缀（17 字节），用于批量删除某个 chunk 的所有告示牌记录。
     * 格式："sign:" + dimPoolId + cx + cz
     */
    private static byte[] makeChunkPrefix(int dimPoolId, int cx, int cz) {
        ByteBuffer bb = ByteBuffer.allocate(CHUNK_PREFIX_LEN).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(KEY_PREFIX);
        bb.putInt(dimPoolId);
        bb.putInt(cx);
        bb.putInt(cz);
        return bb.array();
    }

    @Override
    public String getId() {
        return "sign";
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner.analyzer.sign.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner.analyzer.sign.desc");
    }
}
