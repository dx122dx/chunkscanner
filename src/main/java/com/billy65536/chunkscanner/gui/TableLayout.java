package com.billy65536.chunkscanner.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * {@link ViewLayout} 的唯一实现，同时支持列表模式和表格模式。
 *
 * <p>通过 {@code headers == null} 区分两种模式：</p>
 * <ul>
 *   <li><b>列表模式</b>（headers == null）：逐行渲染单个文本，无表头，用于原始十六进制视图</li>
 *   <li><b>表格模式</b>（headers 非空）：多彩列表头渲染，支持位置列、自定义颜色、tooltip、物品图标</li>
 * </ul>
 *
 * <p>通过 {@link TableLayoutBuilder} 构建实例，不应直接调用构造函数。</p>
 */
public class TableLayout implements ViewLayout {

    // ==================== 渲染常量 ====================

    private static final int POSITION_COL_COLOR = 0xFF55FFFF;
    private static final int POSITION_HOVER_COLOR = 0xFFFFFF55;
    private static final int OTHER_COL_COLOR = 0xFFFFFFFF;
    private static final int ITEM_ICON_SIZE = 16;
    private static final int COL_PADDING = 8;
    private static final int HEADER_HEIGHT = 16;

    // ==================== 字段 ====================

    private final TextRenderer textRenderer;
    private final String[] headers;
    private final List<String[]> rows;
    private final int metaCount;

    // — 表格模式 —
    private final List<LocatedPosition> positions;
    private final Map<Integer, Map<Integer, List<Text>>> cellTooltips;
    private final Map<Integer, Map<Integer, Integer>> cellColors;
    private final Map<Integer, Map<Integer, ItemStack>> cellItems;
    private int[] colWidths;

    // — 列表模式 —
    private final List<String> listFullExport;

    // — 帧级悬停状态 —
    private int hoveredCol = -1;
    private ItemStack hoveredItemStack = null;

    // ==================== 构造（列表模式） ====================

    TableLayout(TextRenderer tr, List<String[]> rows, int metaCount, List<String> listFullExport) {
        this.textRenderer = tr;
        this.headers = null;
        this.rows = rows;
        this.metaCount = metaCount;
        this.positions = null;
        this.cellTooltips = null;
        this.cellColors = null;
        this.cellItems = null;
        this.colWidths = null;
        this.listFullExport = listFullExport != null ? listFullExport : Collections.emptyList();
    }

    // ==================== 构造（表格模式） ====================

    TableLayout(TextRenderer tr, String[] headers, List<String[]> rows, int metaCount,
                List<LocatedPosition> positions,
                Map<Integer, Map<Integer, List<Text>>> cellTooltips,
                Map<Integer, Map<Integer, Integer>> cellColors,
                Map<Integer, Map<Integer, ItemStack>> cellItems) {
        this.textRenderer = tr;
        this.headers = headers;
        this.rows = rows;
        this.metaCount = metaCount;
        this.positions = positions;
        this.cellTooltips = cellTooltips;
        this.cellColors = cellColors;
        this.cellItems = cellItems;
        this.listFullExport = null;
        this.colWidths = calcColWidths();
    }

    // ==================== 基础方法 ====================

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
        if (headers == null) {
            // 列表模式：估算（取前 200 行中最宽者）
            if (rows.isEmpty()) return 0;
            int maxW = 0;
            int limit = Math.min(rows.size(), 200);
            for (int i = 0; i < limit; i++) {
                String[] row = rows.get(i);
                int w = textRenderer.getWidth(row[0]);
                if (w > maxW) maxW = w;
            }
            return maxW + 10;
        }
        // 表格模式
        int total = 0;
        for (int w : colWidths) total += w + COL_PADDING;
        return total + COL_PADDING;
    }

    @Override
    public int getHeaderHeight() {
        return (headers != null && headers.length > 0) ? HEADER_HEIGHT : 0;
    }

    @Override
    public int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset) {
        if (headers == null || headers.length == 0) return 0;
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
        if (headers == null) {
            return renderListRow(ctx, idx, rowY, margin, hScrollOffset, mouseX, mouseY);
        }
        return renderTableRow(ctx, idx, rowY, margin, hScrollOffset, mouseX, mouseY);
    }

    /** 列表模式行渲染：单文本，全行可悬停。 */
    private int renderListRow(DrawContext ctx, int idx, int rowY,
                               int margin, int hScrollOffset, int mouseX, int mouseY) {
        String[] row = rows.get(idx);
        int x = margin - hScrollOffset;

        ctx.drawTextWithShadow(textRenderer, Text.literal(row[0]), x, rowY, 0xFFFFFF);

        int totalW = textRenderer.getWidth(row[0]);
        if (GuiUtil.isInRect(mouseX, mouseY, x, rowY, totalW, 20)) {
            return idx;
        }
        return -1;
    }

    /** 表格模式行渲染：多彩列，支持悬停检测。 */
    private int renderTableRow(DrawContext ctx, int idx, int rowY,
                                int margin, int hScrollOffset, int mouseX, int mouseY) {
        String[] row = rows.get(idx);
        int rx = margin - hScrollOffset;
        boolean rowHovered = false;
        for (int c = 0; c < row.length && c < headers.length; c++) {
            boolean isPositionCol = isPositionColumn(c);

            // 悬停检测
            boolean colHovered = mouseY >= rowY && mouseY < rowY + 20
                    && mouseX >= rx && mouseX < rx + colWidths[c];
            if (colHovered) {
                hoveredCol = c;
                rowHovered = true;
            }

            // 物品图标优先
            ItemStack icon = getCellItem(idx, c);
            if (icon != null && !icon.isEmpty()) {
                ctx.drawItem(icon, rx, rowY + 2);
                if (mouseX >= rx && mouseX < rx + ITEM_ICON_SIZE
                        && mouseY >= rowY + 2 && mouseY < rowY + 2 + ITEM_ICON_SIZE) {
                    hoveredItemStack = icon;
                }
            } else {
                String cell = row[c] != null ? row[c] : "";
                int color = OTHER_COL_COLOR;
                if (isPositionCol) {
                    color = colHovered ? POSITION_HOVER_COLOR : POSITION_COL_COLOR;
                } else {
                    Integer customColor = getCellColor(idx, c);
                    if (customColor != null) {
                        color = customColor;
                    }
                }
                ctx.drawTextWithShadow(textRenderer, Text.literal(cell), rx, rowY, color);
            }
            rx += colWidths[c] + COL_PADDING;
        }
        return rowHovered ? idx : -1;
    }

    @Override
    public List<Text> getTooltip(int idx) {
        return null;
    }

    @Override
    public void export(StringBuilder sb) {
        if (headers == null) {
            // 列表模式：使用全量导出文本
            for (String line : listFullExport) {
                sb.append(line).append("\n");
            }
        } else {
            // 表格模式：TSV
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

    // ==================== 表格模式方法 ====================

    @Override
    public String[] getHeaders() {
        return headers;
    }

    @Override
    public boolean isPositionColumn(int colIdx) {
        if (headers == null || colIdx < 0 || colIdx >= headers.length) return false;
        return "\u4f4d\u7f6e".equals(headers[colIdx]); // "位置"
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
        if (cellTooltips == null || colIdx < 0 || colIdx >= headers.length) return null;
        Map<Integer, List<Text>> rowTips = cellTooltips.get(rowIdx);
        if (rowTips == null) return null;
        return rowTips.get(colIdx);
    }

    @Override
    public void beginFrame() {
        hoveredCol = -1;
        hoveredItemStack = null;
    }

    @Override
    public LocatedPosition getPositionAt(int rowIdx) {
        if (positions == null || rowIdx < 0 || rowIdx >= positions.size()) return null;
        return positions.get(rowIdx);
    }

    @Override
    public String[] getRowAt(int rowIdx) {
        if (rows == null || rowIdx < 0 || rowIdx >= rows.size()) return null;
        return rows.get(rowIdx);
    }

    // ==================== 内部工具 ====================

    /** 计算各列像素宽度（表格模式）。 */
    private int[] calcColWidths() {
        if (rows == null || headers == null) return new int[0];
        int cols = headers.length;
        int[] widths = new int[cols];
        // 以表头宽度为基准
        for (int c = 0; c < cols; c++) {
            widths[c] = textRenderer.getWidth(headers[c]);
        }
        // 采样最多 200 行获取实际数据宽度
        int sampleSize = Math.min(rows.size(), 200);
        for (int i = 0; i < sampleSize; i++) {
            String[] row = rows.get(i);
            for (int c = 0; c < row.length && c < cols; c++) {
                int w = textRenderer.getWidth(row[c] != null ? row[c] : "");
                if (w > widths[c]) widths[c] = w;
            }
        }
        // 有物品图标的列最小宽度为 18px（16px 图标 + 2px margin）
        if (cellItems != null && !cellItems.isEmpty()) {
            for (int c = 0; c < cols; c++) {
                boolean hasItem = false;
                for (Map<Integer, ItemStack> rowItems : cellItems.values()) {
                    if (rowItems.containsKey(c)) {
                        hasItem = true;
                        break;
                    }
                }
                if (hasItem) {
                    widths[c] = Math.max(widths[c], ITEM_ICON_SIZE + 2);
                }
            }
        }
        return widths;
    }

    /** 获取指定行列的自定义颜色，无自定义返回 null。 */
    private Integer getCellColor(int rowIdx, int colIdx) {
        if (cellColors == null || colIdx < 0 || colIdx >= headers.length) return null;
        Map<Integer, Integer> rowColors = cellColors.get(rowIdx);
        if (rowColors == null) return null;
        return rowColors.get(colIdx);
    }

    /** 获取指定行列的物品图标，无图标返回 null。 */
    private ItemStack getCellItem(int rowIdx, int colIdx) {
        if (cellItems == null || colIdx < 0 || colIdx >= headers.length) return null;
        Map<Integer, ItemStack> rowItems = cellItems.get(rowIdx);
        if (rowItems == null) return null;
        return rowItems.get(colIdx);
    }
}
