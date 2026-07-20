package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.gui.layout.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.layout.ILayout;

/**
 * 原始（Raw）数据库视图提供者。
 *
 * <p>封装 ChunkDb，直接显示原始字节的键值对，不进行结构化解析。
 * 适用于所有分析器（applicableAnalyzers 为空集）。</p>
 */
public class RawDbProvider implements DbViewProvider {

    private static final int KEY_COLOR = 0xFFFFFF00;
    private static final String[] HEADERS = {"Key", "Value"};


    private final ChunkDb db;

    public RawDbProvider(ChunkDb db) {
        this.db = db;
    }

    @Override
    public ChunkDb getDb() {
        return db;
    }

    @Override
    public ILayout getLayout(TextRenderer textRenderer) {
        List<ChunkDb.Entry> entries;
        int metaCount;
        try {
            entries = db.getAllEntries();
            metaCount = db.getAllChunkMetas().size();
        } catch (Exception e) {
            entries = List.of();
            metaCount = 0;
        }

        TableLayoutBuilder lb = new TableLayoutBuilder(textRenderer, metaCount, HEADERS);

        for (ChunkDb.Entry e : entries) {
            String hexKey = GuiUtil.bytesToFullHex(e.key());
            String hexVal = GuiUtil.bytesToFullHex(e.value());

            lb.addRow()
                .text(hexKey).withColor(KEY_COLOR).text(hexVal).done();
        }
        return lb.build();
    }

    // ==================== 类型描述符 ====================

    /** Raw 视图类型描述符：直接显示原始字节。适用于所有分析器。 */
    public static class Type implements DbViewProviderRegistry.Type {
        @Override
        public String getId() { return "raw"; }

        @Override
        public Text getName() {
            return Text.translatable("chunkscanner.dbview.raw.name");
        }

        @Override
        public Text getDescription() {
            return Text.translatable("chunkscanner.dbview.raw.desc");
        }

        @Override
        public Set<String> applicableAnalyzers() {
            return Collections.emptySet(); // 适用于所有
        }

        @Override
        public DbViewProvider create(ChunkDb db) {
            return new RawDbProvider(db);
        }
    }
}
