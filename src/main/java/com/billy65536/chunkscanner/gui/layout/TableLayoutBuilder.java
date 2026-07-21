package com.billy65536.chunkscanner.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * {@link TableLayout} 的流式构建器。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * TableLayoutBuilder b = new TableLayoutBuilder(textRenderer, metaCount,
 *     new String[]{"Pos", "Side", "Line 1"});
 * b.addRow()
 *     .position(pos)
 *     .text("Front")
 *     .text("Hello").withColor(0xFFFF0000).done();
 * return b.build();
 * }</pre>
 *
 * <p>每个单元格是 {@link IContentCell} 的一个子类型：
 * {@link PositionCell}（位置）、{@link TextCell}（文本）、{@link ItemCell}（物品图标）。
 * 所有单元格属性（颜色、tooltip、物品）统一存储于 CellContent 中，不再需要分离的 Map。</p>
 */
public class TableLayoutBuilder {

    private final TextRenderer textRenderer;
    private final int metaCount;
    private final String[] headers;

    private final List<List<IContentCell>> rows = new ArrayList<>();

    /**
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

    /** 构建并返回 {@link TableLayout} 实例。 */
    public TableLayout build() {
        return new TableLayout(textRenderer, headers, rows, metaCount);
    }

    // ==================== RowBuilder ====================

    /**
     * 行构建器。按从左到右的顺序填充各列，最后以 {@link #done()} 结束。
     *
     * <p>每个单元格的类型由调用的方法决定：
     * <ul>
     *   <li>{@link #position} → {@link PositionCell}</li>
     *   <li>{@link #text} → {@link TextCell}</li>
     *   <li>{@link #item} → {@link ItemCell}</li>
     * </ul>
     *
     * <p>{@link #withTooltip} / {@link #withColor} 作用在最近一次填充的
     * {@link TextCell} 单元格上。</p>
     */
    public class RowBuilder {

        private final int rowIdx;
        private final List<IContentCell> cells = new ArrayList<>();
        RowBuilder(int rowIdx) {
            this.rowIdx = rowIdx;
        }

        /**
         * 填充一个空白列。
         */
        public RowBuilder blank() {
            return this.text(Text.empty());
        }

        /**
         * 为此行设置世界位置，并在当前位置添加一个 {@link PositionCell}。
         * 位置列对应的文本显示由 {@link PositionCell} 自动处理。
         */
        public RowBuilder position(LocatedPosition pos) {
            cells.add(PositionCell.of(pos));
            return this;
        }

        /** 填充一个白色无 tooltip 的文本列。 */
        public RowBuilder text(String text) {
            cells.add(TextCell.of(text != null ? text : ""));
            return this;
        }

        /** 填充一个白色无 tooltip 的文本列。 */
        public RowBuilder text(Text text) {
            cells.add(TextCell.of(text));
            return this;
        }

        /**
         * 为最近一次填充的列设置 tooltip。
         * 仅当最近一个单元格是 {@link TextCell} 时生效。
         */
        public RowBuilder withTooltip(String[] tooltip) {
            return this.withTooltip(Arrays.stream(tooltip).map(Text::literal).toArray(Text[]::new));
        }

        /**
         * 为最近一次填充的列设置 tooltip。
         * 仅当最近一个单元格是 {@link TextCell} 时生效。
         */
        public RowBuilder withTooltip(Text[] tooltip) {
            if (tooltip != null && tooltip.length > 0
                    && !cells.isEmpty()
                    && cells.get(cells.size() - 1) instanceof TextCell rt) {
                cells.set(cells.size() - 1, rt.withTooltip(tooltip));
            }
            return this;
        }

        /**
         * 为最近一次填充的列设置 tooltip（便捷方法，接受 {@link List}&lt;{@link Text}&gt;）。
         */
        public RowBuilder withTooltip(List<Text> tooltip) {
            if (tooltip != null && !tooltip.isEmpty()) {
                return withTooltip(tooltip.toArray(Text[]::new));
            }
            return this;
        }

        /**
         * 为最近一次填充的列设置文字颜色 (ARGB)。
         * 仅当最近一个单元格是 {@link TextCell} 时生效。
         */
        public RowBuilder withColor(int argb) {
            if (!cells.isEmpty()
                    && cells.get(cells.size() - 1) instanceof TextCell rt) {
                cells.set(cells.size() - 1, rt.withColor(argb));
            }
            return this;
        }

        /**
         * 为最近一次填充的列设置格式。
         * 仅当最近一个单元格是 {@link TextCell} 时生效。
         */
        public RowBuilder withFormat(Formatting... formattings) {
            if (!cells.isEmpty()
                    && cells.get(cells.size() - 1) instanceof TextCell rt) {
                cells.set(cells.size() - 1, rt.withFormat(formattings));
            }
            return this;
        }

        /**
         * 为最近一次填充的列设置物品图标。
         * 追加一个新的 {@link ItemCell} 到单元格列表（不影响文本列）。
         */
        public RowBuilder item(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                cells.add(ItemCell.of(stack));
            }
            return this;
        }

        /**
         * 提交当前行。
         *
         * <p>会校验列数是否与 headers 一致，不一致时抛出
         * {@link IllegalStateException}。</p>
         */
        public void done() {
            if (cells.size() != headers.length) {
                throw new IllegalStateException(
                        "Row " + rowIdx + " has " + cells.size()
                                + " columns, expected " + headers.length);
            }
            rows.add(new ArrayList<>(cells));
        }
    }
}
