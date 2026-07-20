package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.gui.TableLayoutBuilder;
import com.billy65536.chunkscanner.gui.ViewLayout;

/**
 * 原始（Raw）数据库视图提供者。
 *
 * <p>封装 ChunkDb，将其作为原始的 DbViewProvider 暴露给 DatabaseScreen。
 * 直接显示原始字节的键值对，不进行结构化解析。</p>
 *
 * <p>适用于所有分析器（applicableAnalyzers 为空集）。</p>
 */
public class RawDbProvider implements DbViewProvider {

    private static final int MAX_KEY_BYTES = 24;
    private static final int MAX_VAL_BYTES = 64;
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
    public ViewLayout getLayout(TextRenderer textRenderer) {
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
        List<String> exportLines = new ArrayList<>(entries.size());

        for (int i = 0; i < entries.size(); i++) {
            ChunkDb.Entry e = entries.get(i);
            String hexKey = GuiUtil.bytesToHex(e.key(), MAX_KEY_BYTES);
            String hexVal = GuiUtil.bytesToHex(e.value(), MAX_VAL_BYTES);

            lb.addRow()
                .text(hexKey).withColor(KEY_COLOR).withTooltip(List.of(
                        Text.literal(GuiUtil.bytesToFullHex(e.key())).formatted(Formatting.YELLOW),
                        Text.literal(GuiUtil.bytesToFullHex(e.value())).formatted(Formatting.WHITE)
                ))
                .text(hexVal).done();

            exportLines.add(
                    GuiUtil.bytesToFullHex(e.key())
                    + "\t"
                    + GuiUtil.bytesToFullHex(e.value()));
        }
        lb.setListFullExport(exportLines);
        return lb.build();
    }

    // ==================== 类型描述符 ====================

    /** Raw 视图类型描述符：直接显示原始字节。适用于所有分析器。 */
    public static class Type implements DbViewProviderRegistry.Type {
        @Override
        public String getId() { return "raw"; }

        @Override
        public String getName() {
            return Text.translatable("chunkscanner.dbview.raw.name").getString();
        }

        @Override
        public String getDescription() {
            return Text.translatable("chunkscanner.dbview.raw.desc").getString();
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
