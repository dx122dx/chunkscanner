package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.analyzer.SignDbAdaptor;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.gui.layout.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.layout.ILayout;

/**
 * Sign 分析器特化的 DbViewProvider。
 *
 * <p>通过 {@link SignDbAdaptor} 解析 sign 分析器生成的二进制 KV 数据，将原始字节转换为可读的告示牌信息。
 * 所有字节级解析集中在适配器中，本类仅负责展示布局。</p>
 */
public class SignDbViewProvider implements IDbViewProvider {

    private static final String[] HEADERS = {"位置", "Side", "Line 1", "Line 2", "Line 3", "Line 4"};

    private final SignDbAdaptor ad;

    /** 缓存解析后的告示牌记录，避免每帧重复解析。 */
    private List<SignDbAdaptor.SignRecord> cachedRecords;
    private volatile boolean cacheValid = false;

    public SignDbViewProvider(DbPackage pkg) {
        this.ad = pkg.getAdaptor(SignDbAdaptor.class);
    }

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<SignDbAdaptor.SignRecord> records = getSignRecords();
        int metaCount;
        try {
            metaCount = ad.getAllChunkMetas().size();
        } catch (Exception e) {
            metaCount = 0;
        }

        TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount, HEADERS);
        for (SignDbAdaptor.SignRecord sr : records) {
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
     * 获取解析后的告示牌记录（委托适配器）。
     * 结果会被缓存，数据不变时不会重复解析。
     */
    public List<SignDbAdaptor.SignRecord> getSignRecords() {
        if (cacheValid && cachedRecords != null) {
            return cachedRecords;
        }
        cachedRecords = ad.getAllRecords();
        cacheValid = true;
        return cachedRecords;
    }

    // ==================== 类型描述符 ====================

    /** Sign 视图类型描述符：解析告示牌数据为可读文本。对应 adaptorId {@code chunkscanner:sign}。 */
    public static class Type implements DbViewProviderRegistry.ITypeDescriptor {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("sign_view"); }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.sign.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.sign.desc");
        }

        @Override
        public Set<Identifier> applicableAdaptors() {
            return Set.of(ChunkScannerMod.id("sign"));
        }

        @Override
        public IDbViewProvider create(DbPackage pkg) {
            return new SignDbViewProvider(pkg);
        }
    }
}
