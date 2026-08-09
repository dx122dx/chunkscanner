package com.billy65536.chunkscanner.components.analyzer;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.world.chunk.WorldChunk;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzeResult;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.db.DbPackage;

/**
 * 默认告示牌分析器：扫描区块内所有告示牌并存入数据库。
 *
 * <p>支持 SignBlockEntity 和 HangingSignBlockEntity（1.20+），同时扫描正面和背面文字。
 * 重新扫描时会先删除该 chunk 中所有旧记录，保证已移除的告示牌被清理。</p>
 *
 * <p>所有的字节级编码与写入经由 {@link SignDbAdaptor} 完成，本类不直接接触
 * 底层 {@link com.billy65536.chunkscanner.core.IChunkDb}。</p>
 */
public class SignAnalyzer implements IChunkAnalyzer {

    private static final byte SIDE_FRONT = 0;
    private static final byte SIDE_BACK = 1;

    @Override
    public AnalyzeResult analyze(WorldChunk chunk, int cx, int cz, String dimId, DbPackage pkg, long now) {
        SignDbAdaptor ad = pkg.getAdaptor(SignDbAdaptor.class);
        ad.deleteChunk(dimId, cx, cz);

        int count = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            // SignBlockEntity 覆盖普通告示牌和悬挂式告示牌（HangingSignBlockEntity extends SignBlockEntity）
            if (!(be instanceof SignBlockEntity sign)) continue;

            BlockPos pos = sign.getPos();
            count += collect(ad, sign.getFrontText(), dimId, cx, cz, pos, SIDE_FRONT, now);
            count += collect(ad, sign.getBackText(), dimId, cx, cz, pos, SIDE_BACK, now);
        }

        return count > 0 ? AnalyzeResult.found("signs=" + count) : AnalyzeResult.skipped();
    }

    /**
     * 收集单面告示牌文字记录，四行全空则跳过。
     *
     * @return 本面是否有效写入（写入返回 1，跳过返回 0）
     */
    private static int collect(SignDbAdaptor ad, SignText text,
                               String dimId, int cx, int cz, BlockPos pos, byte side, long now) {
        String[] lines = new String[4];
        boolean hasContent = false;
        for (int i = 0; i < 4; i++) {
            lines[i] = text.getMessage(i, false).getString();
            if (lines[i] != null && !lines[i].trim().isEmpty()) hasContent = true;
        }
        if (!hasContent) return 0;

        ad.addRecord(dimId, cx, cz, pos.getX(), pos.getY(), pos.getZ(), side, now,
                lines[0], lines[1], lines[2], lines[3]);
        return 1;
    }

    @Override
    public Identifier getId() {
        return ChunkScannerMod.id("sign");
    }

    @Override
    public Identifier getAdaptorId() {
        return SignDbAdaptor.ID;
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
