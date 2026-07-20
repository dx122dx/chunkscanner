package com.billy65536.chunkscanner.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * {@link ILayout} 的唯一实现，通过 {@link CellContent} 密封接口统一存储单元格数据。
 *
 * <p>每个单元格是 {@link RichText}、{@link PositionCell} 或 {@link ItemCell} 之一，
 * 渲染时通过 {@code instanceof} 分派到对应逻辑。不再维护分离的颜色/tooltip/物品 Map。</p>
 */
public class TableLayout implements ILayout {

    // ==================== 渲染常量 ====================

    private static final int POSITION_COL_COLOR = 0xFF55FFFF;
    private static final int POSITION_HOVER_COLOR = 0xFFFFFF55;
    private static final int ITEM_ICON_SIZE = 16;
    private static final int COL_PADDING = 8;
    private static final int HEADER_HEIGHT = 16;

    // ==================== 字段 ====================

    private final TextRenderer textRenderer;
    private final String[] headers;
    private final List<List<CellContent>> rows;
    private final int metaCount;
    private final int[] colWidths;

    // — 帧级悬停状态 —
    private int hoveredCol = -1;
    private ItemStack hoveredItemStack = null;

    // ==================== 构造 ====================

    TableLayout(TextRenderer tr, String[] headers, List<List<CellContent>> rows,
                int metaCount) {
        this.textRenderer = tr;
        this.headers = headers;
        this.rows = rows;
        this.metaCount = metaCount;
        this.colWidths = calcColWidths();
    }

    // ==================== ViewLayout 实现 ====================

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getMetaCount() {
        return metaCount;
    }

    @Override
    public int computeContentWidth() {
        int total = 0;
        for (int w : colWidths) total += w + COL_PADDING;
        return total + COL_PADDING;
    }

    @Override
    public int getHeaderHeight() {
        return HEADER_HEIGHT;
    }

    @Override
    public int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset) {
        int hx = margin - hScrollOffset;
        for (int c = 0; c < headers.length; c++) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(headers[c]).formatted(Formatting.GOLD),
                    hx, listTop, 0xFFFFFF);
            hx += colWidths[c] + COL_PADDING;
        }
        return HEADER_HEIGHT;
    }

    @Override
    public int renderRow(DrawContext ctx, int idx, int rowY,
                          int margin, int hScrollOffset, int mouseX, int mouseY) {
        List<CellContent> row = rows.get(idx);
        int rx = margin - hScrollOffset;
        boolean rowHovered = false;
        for (int c = 0; c < row.size(); c++) {
            boolean colHovered = mouseY >= rowY && mouseY < rowY + 20
                    && mouseX >= rx && mouseX < rx + colWidths[c];
            if (colHovered) {
                hoveredCol = c;
                rowHovered = true;
            }

            CellContent cell = row.get(c);

            if (cell instanceof PositionCell pc) {
                int color = colHovered ? POSITION_HOVER_COLOR : POSITION_COL_COLOR;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(pc.pos().toString()), rx, rowY, color);

            } else if (cell instanceof ItemCell ic) {
                ctx.drawItem(ic.stack(), rx, rowY + 2);
                if (mouseX >= rx && mouseX < rx + ITEM_ICON_SIZE
                        && mouseY >= rowY + 2 && mouseY < rowY + 2 + ITEM_ICON_SIZE) {
                    hoveredItemStack = ic.stack();
                }

            } else if (cell instanceof RichText rt) {
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal(rt.text()), rx, rowY, rt.color());
            }

            rx += colWidths[c] + COL_PADDING;
        }
        return rowHovered ? idx : -1;
    }

    @Override
    public void export(StringBuilder sb) {
        sb.append(String.join("\t", headers)).append("\n");
        for (List<CellContent> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append("\t");
                sb.append(cellToText(row.get(i)));
            }
            sb.append("\n");
        }
    }

    @Override
    public String[] getHeaders() {
        return headers;
    }

    @Override
    public boolean isPositionColumn(int colIdx) {
        if (colIdx < 0 || colIdx >= headers.length || rows.isEmpty()) return false;
        return rows.get(0).get(colIdx) instanceof PositionCell;
    }

    @Override
    public int getHoveredCol() {
        return hoveredCol;
    }

    @Override
    public ItemStack getHoveredItemStack() {
        return hoveredItemStack;
    }

    @Override
    public List<Text> getCellTooltip(int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= rows.size()) return null;
        if (colIdx < 0 || colIdx >= headers.length) return null;
        CellContent cell = rows.get(rowIdx).get(colIdx);
        if (cell instanceof RichText rt && rt.tooltip() != null) {
            return java.util.Arrays.stream(rt.tooltip())
                    .map(Text::literal)
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }

    @Override
    public void beginFrame() {
        hoveredCol = -1;
        hoveredItemStack = null;
    }

    @Override
    public LocatedPosition getPositionAt(int rowIdx) {
        if (rowIdx < 0 || rowIdx >= rows.size()) return null;
        for (CellContent cell : rows.get(rowIdx)) {
            if (cell instanceof PositionCell pc) return pc.pos();
        }
        return null;
    }

    @Override
    public String[] getRowAt(int rowIdx) {
        if (rowIdx < 0 || rowIdx >= rows.size()) return null;
        List<CellContent> row = rows.get(rowIdx);
        String[] result = new String[row.size()];
        for (int i = 0; i < row.size(); i++) {
            result[i] = cellToText(row.get(i));
        }
        return result;
    }

    // ==================== 内部工具 ====================

    /** 将任意 CellContent 转为导出用文本。 */
    private static String cellToText(CellContent cell) {
        if (cell instanceof RichText rt) return rt.text();
        if (cell instanceof PositionCell pc) return pc.pos().toString();
        if (cell instanceof ItemCell ic) return ic.stack().getName().getString();
        return "";
    }

    /** 计算任意 CellContent 的文本像素宽度。 */
    private int cellTextWidth(CellContent cell) {
        return textRenderer.getWidth(cellToText(cell));
    }

    /** 计算各列像素宽度。 */
    private int[] calcColWidths() {
        int cols = headers.length;
        int[] widths = new int[cols];
        // 以表头宽度为基准
        for (int c = 0; c < cols; c++) {
            widths[c] = textRenderer.getWidth(headers[c]);
        }
        // 采样最多 200 行获取实际数据宽度
        int sampleSize = Math.min(rows.size(), 200);
        for (int i = 0; i < sampleSize; i++) {
            List<CellContent> row = rows.get(i);
            for (int c = 0; c < row.size() && c < cols; c++) {
                int w = cellTextWidth(row.get(c));
                if (w > widths[c]) widths[c] = w;
            }
        }
        // 有 ItemCell 的列最小宽度为 ITEM_ICON_SIZE + 2
        for (int c = 0; c < cols; c++) {
            for (int i = 0; i < sampleSize; i++) {
                if (i < rows.size() && c < rows.get(i).size()
                        && rows.get(i).get(c) instanceof ItemCell) {
                    widths[c] = Math.max(widths[c], ITEM_ICON_SIZE + 2);
                    break;
                }
            }
        }
        return widths;
    }
}
