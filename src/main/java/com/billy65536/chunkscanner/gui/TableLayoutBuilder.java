package com.billy65536.chunkscanner.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * {@link TableLayout} 的流式构建器。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *   <li><b>列表模式</b>（headers == null）：{@code addRow().text("...").done()}，适用于原始十六进制视图</li>
 *   <li><b>表格模式</b>（headers 非空）：{@code addRow().position(loc).text("...").withTooltip(...).done()}，
 *       适用于 Sign/QShop 等特化视图。{@code done()} 会校验列数与 headers 一致，不一致则抛出
 *       {@link IllegalStateException}。</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 列表模式
 * TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount);
 * b.addRow().text("[0] AABB → 1122").done();
 * return b.build();
 *
 * // 表格模式
 * TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount,
 *     new String[]{"位置", "Side", "Line 1"});
 * b.addRow().position(pos).text("Front").text("Hello").done();
 * return b.build();
 * }</pre>
 */
public class TableLayoutBuilder {

    private final TextRenderer textRenderer;
    private final int metaCount;
    private final String[] headers;

    private final List<String[]> rows = new ArrayList<>();
    private final List<LocatedPosition> positions = new ArrayList<>();
    private final Map<Integer, Map<Integer, List<Text>>> cellTooltips = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> cellColors = new HashMap<>();
    private final Map<Integer, Map<Integer, ItemStack>> cellItems = new HashMap<>();
    private final List<String> listFullExport = new ArrayList<>();

    /**
     * 表格模式构建器。
     *
     * @param tr        TextRenderer 实例
     * @param metaCount 区块元数据数量
     * @param headers   列标题数组，长度必须 &gt; 0
     */
    public TableLayoutBuilder(TextRenderer tr, int metaCount, String[] headers) {
        this.textRenderer = tr;
        this.metaCount = metaCount;
        this.headers = headers;
    }

    /** 开始构建一行。返回的 {@link RowBuilder} 用于逐列填充数据。 */
    public RowBuilder addRow() {
        return new RowBuilder(rows.size());
    }

    /**
     * 设置全量导出文本行（每行一条完整记录），用于 {@link TableLayout#export}。
     * 不必须，默认导出由 headers + rows 自动生成 TSV。
     */
    public void setListFullExport(List<String> lines) {
        this.listFullExport.clear();
        if (lines != null) {
            this.listFullExport.addAll(lines);
        }
    }

    /** 构建并返回 {@link TableLayout} 实例。 */
    public TableLayout build() {
        if (headers == null) {
            // 列表模式：传递全量导出文本行
            return new TableLayout(textRenderer, rows, metaCount,
                    listFullExport.isEmpty() ? null : new ArrayList<>(listFullExport));
        }
        return new TableLayout(textRenderer, headers, rows, metaCount,
                    positions, cellTooltips, cellColors, cellItems);
    }

    // ==================== RowBuilder ====================

    /**
     * 行构建器。按从左到右的顺序调用 {@link #text} 填充各列，最后以 {@link #done()} 结束。
     *
     * <p>{@link #position} 必须作为第一个调用（如果该行有位置信息）。
     * {@link #withTooltip}/{@link #withColor}/{@link #item} 作用在最近一次 {@link #text} 所填充的列上。</p>
     */
    public class RowBuilder {

        private final int rowIdx;
        private final List<String> cells = new ArrayList<>();
        private LocatedPosition pos;

        RowBuilder(int rowIdx) {
            this.rowIdx = rowIdx;
        }

        /**
         * 标记此行首列为位置列，并存储对应的世界位置。
         * 表格模式下会自动以 {@link LocatedPosition#toString()} 填充该列文本。
         * 只在表格模式使用。
         */
        public RowBuilder position(LocatedPosition pos) {
            this.pos = pos;
            if (headers != null && headers.length > 0) {
                cells.add(pos.toString());
            }
            return this;
        }

        /** 填充下一列的文本。 */
        public RowBuilder text(String text) {
            cells.add(text != null ? text : "");
            return this;
        }

        /** 为最近一次填充的列设置 tooltip。 */
        public RowBuilder withTooltip(List<Text> tooltip) {
            if (tooltip != null && !tooltip.isEmpty()) {
                int col = cells.size() - 1;
                if (col >= 0) {
                    cellTooltips.computeIfAbsent(rowIdx, k -> new HashMap<>()).put(col, tooltip);
                }
            }
            return this;
        }

        /** 为最近一次填充的列设置文字颜色 (ARGB)。 */
        public RowBuilder withColor(int argb) {
            int col = cells.size() - 1;
            if (col >= 0) {
                cellColors.computeIfAbsent(rowIdx, k -> new HashMap<>()).put(col, argb);
            }
            return this;
        }

        /**
         * 为最近一次填充的列设置物品图标。
         * 图标渲染在该列文本位置（16×16），会覆盖文本显示。
         */
        public RowBuilder item(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                int col = cells.size() - 1;
                if (col >= 0) {
                    cellItems.computeIfAbsent(rowIdx, k -> new HashMap<>()).put(col, stack);
                }
            }
            return this;
        }

        /**
         * 提交当前行。
         *
         * <p>表格模式下会校验列数是否与 headers 一致，不一致时抛出
         * {@link IllegalStateException}。列表模式不作校验。</p>
         */
        public void done() {
            if (headers != null && cells.size() != headers.length) {
                throw new IllegalStateException(
                        "Row " + rowIdx + " has " + cells.size()
                                + " columns, expected " + headers.length);
            }
            rows.add(cells.toArray(new String[0]));
            positions.add(pos);
        }
    }
}
