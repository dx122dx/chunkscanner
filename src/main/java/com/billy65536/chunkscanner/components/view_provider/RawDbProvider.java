package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.RawDbAdaptor;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.infrastructure.core.gui.layout.ILayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.infrastructure.core.gui.layout.TextCell;

/**
 * 原始（Raw）数据库视图提供者。
 *
 * <p>封装 raw 适配器，直接显示原始字节的键值对，不进行结构化解析。
 * 对应 adaptorId {@code chunkscanner:raw}，是所有数据库包的兜底视图。</p>
 */
public class RawDbProvider implements IDbViewProvider {

    private static final int KEY_COLOR = 0xFFFFFF00;
    private static final String[] HEADERS = {"Key", "Value"};

    private final RawDbAdaptor ad;

    public RawDbProvider(DbPackage pkg) {
        this.ad = pkg.getAdaptor(RawDbAdaptor.class);
    }

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<IChunkDb.Entry> entries;
        try {
            entries = ad.getAllEntries();
        } catch (Exception e) {
            entries = List.of();
        }

        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(80),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(120),
        };
        TableLayoutBuilder lb = new TableLayoutBuilder(textRenderer, HEADERS, specs);

        for (IChunkDb.Entry e : entries) {
            String hexKey = GuiUtil.bytesToFullHex(e.key());
            String hexVal = GuiUtil.bytesToFullHex(e.value());

            lb.addRow()
                .cell(TextCell.of(hexKey).withColor(KEY_COLOR))
                .text(hexVal)
                .done();
        }
        return lb.build();
    }

    // ==================== 类型描述符 ====================

    /** Raw 视图类型描述符：直接显示原始字节。对应 adaptorId {@code chunkscanner:raw}。 */
    public static class Type implements DbViewProviderRegistry.ITypeDescriptor {
        @Override
        public Identifier getId() { return ChunkScannerMod.id("raw"); }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.raw.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.raw.desc");
        }

        @Override
        public Set<Identifier> applicableAdaptors() {
            return Set.of(ChunkScannerMod.id("raw"));
        }

        @Override
        public IDbViewProvider create(DbPackage pkg) {
            return new RawDbProvider(pkg);
        }
    }
}
