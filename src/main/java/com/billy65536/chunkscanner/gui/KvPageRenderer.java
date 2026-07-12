package com.billy65536.chunkscanner.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.chunkscanner.core.ChunkDb;

/**
 * 数据库内页渲染器抽象 —— 将原始 KV 视图与特化视图解耦。
 *
 * <p>DatabaseScreen 持有当前渲染器，在渲染循环中逐行调用 {@link #renderRow}，
 * 由具体实现决定每行的绘制方式和悬停检测。表头通过 {@link #renderHeader}/{@link #getHeaderHeight}
 * 处理。</p>
 */
public abstract class KvPageRenderer {

    protected final TextRenderer textRenderer;

    protected KvPageRenderer(TextRenderer tr) {
        this.textRenderer = tr;
    }

    /** 数据行总数。 */
    public abstract int getItemCount();

    /** 关联的区块元数据数量（用于信息行显示）。 */
    public abstract int getMetaCount();

    /** 内容总像素宽度（用于水平滚动条）。 */
    public abstract int computeContentWidth();

    /**
     * 返回表头占用的像素高度（无需绘制上下文）。
     * 特化视图返回 16，原始视图返回 0。
     */
    public int getHeaderHeight() {
        return 0;
    }

    /**
     * 渲染表头（特化视图有列头 + 分隔线，原始视图无操作）。
     *
     * @return 表头占用的像素高度
     */
    public int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset) {
        return 0;
    }

    /**
     * 渲染单个数据行。
     *
     * @param ctx          绘制上下文
     * @param actualIdx    实际行索引
     * @param rowY         行 Y 坐标（屏幕坐标）
     * @param margin       左边距
     * @param hScrollOffset 水平滚动偏移
     * @param mouseX       鼠标 X
     * @param mouseY       鼠标 Y
     * @return 悬停索引（如果鼠标悬停在此行），否则 -1
     */
    public abstract int renderRow(DrawContext ctx, int actualIdx, int rowY,
                                   int margin, int hScrollOffset, int mouseX, int mouseY);

    /**
     * 返回指定行的 tooltip 列表，默认返回 null（无 tooltip）。
     */
    public List<Text> getTooltip(int idx) {
        return null;
    }

    /** 导出为文本 (TSV)，追加到 StringBuilder。 */
    public abstract void export(StringBuilder sb);

    // ==================== 内置实现 ====================

    /** 原始键值对视图渲染器。 */
    public static class Entries extends KvPageRenderer {

        private static final int MAX_KEY_BYTES = 24;
        private static final int MAX_VAL_BYTES = 64;

        private final List<ChunkDb.Entry> entries;
        private final int metaCount;
        private final int contentWidth;

        public Entries(TextRenderer tr, List<ChunkDb.Entry> entries, int metaCount) {
            super(tr);
            this.entries = entries;
            this.metaCount = metaCount;
            this.contentWidth = calcContentWidth();
        }

        @Override public int getItemCount() { return entries.size(); }
        @Override public int getMetaCount() { return metaCount; }
        @Override public int computeContentWidth() { return contentWidth; }

        private int calcContentWidth() {
            if (entries.isEmpty()) return 0;
            int maxW = 0;
            int limit = Math.min(entries.size(), 300);
            for (int i = 0; i < limit; i++) {
                ChunkDb.Entry e = entries.get(i);
                int idxW = textRenderer.getWidth("[" + i + "] ");
                int keyW = textRenderer.getWidth(GuiUtil.bytesToFullHex(e.key()));
                int arrowW = textRenderer.getWidth(" → ");
                int valW = textRenderer.getWidth(GuiUtil.bytesToFullHex(e.value()));
                int totalW = idxW + keyW + arrowW + valW + 10;
                if (totalW > maxW) maxW = totalW;
            }
            return maxW;
        }

        @Override
        public int renderRow(DrawContext ctx, int actualIdx, int rowY,
                              int margin, int hScrollOffset, int mouseX, int mouseY) {
            ChunkDb.Entry entry = entries.get(actualIdx);
            int baseX = margin - hScrollOffset;

            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("[" + actualIdx + "]").formatted(Formatting.GRAY),
                    baseX, rowY, 0xFFFFFF);

            String keyStr = GuiUtil.bytesToHex(entry.key(), MAX_KEY_BYTES);
            int keyX = baseX + textRenderer.getWidth("[" + actualIdx + "] ");
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(keyStr).formatted(Formatting.YELLOW),
                    keyX, rowY, 0xFFFFFF);

            String arrow = " → ";
            int arrowW = textRenderer.getWidth(arrow);
            String valStr = GuiUtil.bytesToHex(entry.value(), MAX_VAL_BYTES);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(arrow + valStr).formatted(Formatting.WHITE),
                    keyX + textRenderer.getWidth(keyStr), rowY, 0xFFFFFF);

            // 悬停检测：仅当键或值被截断时
            boolean keyTruncated = entry.key() != null && entry.key().length > MAX_KEY_BYTES;
            boolean valTruncated = entry.value() != null && entry.value().length > MAX_VAL_BYTES;
            if ((keyTruncated || valTruncated) && GuiUtil.isInRect(mouseX, mouseY,
                    keyX, rowY,
                    textRenderer.getWidth(keyStr) + arrowW + textRenderer.getWidth(valStr),
                    20)) {
                return actualIdx;
            }
            return -1;
        }

        @Override
        public List<Text> getTooltip(int idx) {
            if (idx < 0 || idx >= entries.size()) return null;
            ChunkDb.Entry entry = entries.get(idx);
            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.literal(GuiUtil.bytesToFullHex(entry.key())).formatted(Formatting.YELLOW));
            tooltip.add(Text.literal(GuiUtil.bytesToFullHex(entry.value())).formatted(Formatting.WHITE));
            return tooltip;
        }

        @Override
        public void export(StringBuilder sb) {
            for (int i = 0; i < entries.size(); i++) {
                ChunkDb.Entry e = entries.get(i);
                sb.append("[").append(i).append("] ")
                        .append(GuiUtil.bytesToFullHex(e.key()))
                        .append(" -> ")
                        .append(GuiUtil.bytesToFullHex(e.value()))
                        .append("\n");
            }
        }
    }

    /** 特化视图渲染器（如 Sign/QShop）。 */
    public static class Specialized extends KvPageRenderer {

        private static final int POSITION_COL_COLOR = 0xFF55FFFF;   // 位置列（青色，可点击）
        private static final int POSITION_HOVER_COLOR = 0xFFFFFF55; // 位置列悬停（亮黄）
        private static final int OTHER_COL_COLOR = 0xFFFFFFFF;

        private final List<String[]> rows;
        private final String[] headers;
        private final int metaCount;
        private final int[] colWidths;

        /** 上次渲染时检测到的悬停列索引，-1 表示无悬停列。 */
        private int hoveredCol = -1;

        /** 单元格级 tooltip：行索引 → 列标题 → tooltip 文本列表。 */
        private Map<Integer, Map<String, List<Text>>> cellTooltips = Collections.emptyMap();

        public Specialized(TextRenderer tr, List<String[]> rows, String[] headers, int metaCount) {
            super(tr);
            this.rows = rows;
            this.headers = headers;
            this.metaCount = metaCount;
            this.colWidths = calcColWidths();
        }

        @Override public int getItemCount() { return rows.size(); }
        @Override public int getMetaCount() { return metaCount; }

        @Override
        public int computeContentWidth() {
            int total = 0;
            for (int w : colWidths) total += w + 8;
            return total + 8;
        }

        public String[] getHeaders() { return headers; }

        /** 获取上次渲染时检测到的悬停列索引。 */
        public int getHoveredCol() { return hoveredCol; }

        /** 设置单元格级 tooltip 数据。 */
        public void setCellTooltips(Map<Integer, Map<String, List<Text>>> tooltips) {
            this.cellTooltips = tooltips != null ? tooltips : Collections.emptyMap();
        }

        /**
         * 获取指定行、指定列的单元格 tooltip。
         * @return tooltip 文本列表，无 tooltip 时返回 null
         */
        public List<Text> getCellTooltip(int rowIdx, int colIdx) {
            if (cellTooltips.isEmpty() || colIdx < 0 || colIdx >= headers.length) return null;
            Map<String, List<Text>> rowTips = cellTooltips.get(rowIdx);
            if (rowTips == null) return null;
            return rowTips.get(headers[colIdx]);
        }

        /** 判断指定列是否为位置列（可点击创建路径点）。 */
        public boolean isPositionColumn(int colIdx) {
            if (colIdx < 0 || colIdx >= headers.length) return false;
            return "位置".equals(headers[colIdx]);
        }

        private int[] calcColWidths() {
            if (rows == null || headers == null) return new int[0];
            int cols = headers.length;
            int[] widths = new int[cols];
            for (int c = 0; c < cols; c++) {
                widths[c] = textRenderer.getWidth(headers[c]);
            }
            int sampleSize = Math.min(rows.size(), 200);
            for (int i = 0; i < sampleSize; i++) {
                String[] row = rows.get(i);
                for (int c = 0; c < row.length && c < cols; c++) {
                    int w = textRenderer.getWidth(row[c] != null ? row[c] : "");
                    if (w > widths[c]) widths[c] = w;
                }
            }
            return widths;
        }

        @Override
        public int getHeaderHeight() {
            return (headers != null && headers.length > 0) ? 16 : 0;
        }

        @Override
        public int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset) {
            if (headers == null || headers.length == 0) return 0;
            int hx = margin - hScrollOffset;
            for (int c = 0; c < headers.length; c++) {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(headers[c]).formatted(Formatting.GOLD),
                        hx, listTop, 0xFFFFFF);
                hx += colWidths[c] + 8;
            }

            return 16;
        }

        @Override
        public int renderRow(DrawContext ctx, int actualIdx, int rowY,
                              int margin, int hScrollOffset, int mouseX, int mouseY) {
            if (actualIdx == 0) {
                hoveredCol = -1; // 每帧首行重置悬停列，避免无行悬停时返回陈旧值
            }
            String[] row = rows.get(actualIdx);
            int rx = margin - hScrollOffset;
            boolean rowHovered = false;
            for (int c = 0; c < row.length && c < headers.length; c++) {
                String cell = row[c] != null ? row[c] : "";
                boolean isPositionCol = isPositionColumn(c);

                // 检测该列的鼠标悬停（只在完整行范围内检测）
                boolean colHovered = mouseY >= rowY && mouseY < rowY + 20
                        && mouseX >= rx && mouseX < rx + colWidths[c];
                if (colHovered) {
                    hoveredCol = c;
                    rowHovered = true;
                }

                int color;
                if (isPositionCol) {
                    color = colHovered ? POSITION_HOVER_COLOR : POSITION_COL_COLOR;
                } else {
                    color = OTHER_COL_COLOR;
                }
                ctx.drawTextWithShadow(textRenderer, Text.literal(cell), rx, rowY, color);
                rx += colWidths[c] + 8;
            }
            return rowHovered ? actualIdx : -1;
        }

        @Override
        public void export(StringBuilder sb) {
            sb.append(String.join("\t", headers)).append("\n");
            for (String[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append("\t");
                    sb.append(row[i] != null ? row[i] : "");
                }
                sb.append("\n");
            }
        }
    }
}
