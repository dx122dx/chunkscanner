package com.billy65536.chunkscanner.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * 数据库视图渲染协议接口。
 *
 * <p>将 KV 具体渲染逻辑从 {@code DatabaseScreen} 中剥离出来，由 {@link TableLayout}
 * 统一实现。通过 default 方法提供表格模式专属方法的空实现，使得列表模式无需关心这些方法。</p>
 *
 * <p>接口方法分为两组：</p>
 * <ul>
 *   <li><b>基础方法</b>（所有模式都需要）：{@link #getItemCount()}, {@link #getMetaCount()},
 *       {@link #computeContentWidth()}, {@link #getHeaderHeight()}, {@link #renderHeader},
 *       {@link #renderRow}, {@link #getTooltip}, {@link #export}</li>
 *   <li><b>表格模式方法</b>（default 空实现）：{@link #getHeaders()}, {@link #isPositionColumn},
 *       {@link #getHoveredCol()}, {@link #getHoveredItemStack()}, {@link #getCellTooltip},
 *       {@link #beginFrame()}, {@link #getPositionAt}, {@link #getRowAt}</li>
 * </ul>
 */
public interface ViewLayout {

    // ==================== 基础方法 ====================

    /** 数据行总数。 */
    int getItemCount();

    /** 关联的区块元数据数量（用于信息行显示）。 */
    int getMetaCount();

    /** 内容总像素宽度（用于水平滚动条）。 */
    int computeContentWidth();

    /**
     * 表头占用的像素高度。
     * 表格模式返回 16，列表模式返回 0。
     */
    int getHeaderHeight();

    /**
     * 渲染表头。列表模式无操作。
     *
     * @return 表头占用的像素高度
     */
    int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset);

    /**
     * 渲染单个数据行。
     *
     * @param idx          实际行索引
     * @param rowY         行 Y 坐标（屏幕坐标）
     * @param margin       左边距
     * @param hScrollOffset 水平滚动偏移
     * @param mouseX       鼠标 X
     * @param mouseY       鼠标 Y
     * @return 悬停行索引（鼠标悬停在此行），否则 -1
     */
    int renderRow(DrawContext ctx, int idx, int rowY,
                  int margin, int hScrollOffset, int mouseX, int mouseY);

    /** 返回指定行的 tooltip 列表，无 tooltip 返回 null。 */
    List<Text> getTooltip(int idx);

    /** 导出为文本，追加到 StringBuilder。 */
    void export(StringBuilder sb);

    // ==================== 表格模式方法（default 空实现） ====================

    /** 列标题数组，列表模式返回 null。 */
    default String[] getHeaders() { return null; }

    /** 指定列是否为位置列（可点击创建路径点）。 */
    default boolean isPositionColumn(int colIdx) { return false; }

    /** 上次渲染时检测到的悬停列索引，无悬停列返回 -1。 */
    default int getHoveredCol() { return -1; }

    /** 上次渲染时检测到的悬停物品图标，无悬停返回 null。 */
    default ItemStack getHoveredItemStack() { return null; }

    /**
     * 指定行列的单元格 tooltip，无 tooltip 返回 null。
     * 仅当鼠标不悬停在物品图标上时使用。
     */
    default List<Text> getCellTooltip(int rowIdx, int colIdx) { return null; }

    /** 每帧渲染前调用，重置帧级悬停状态。表格模式实现此方法。 */
    default void beginFrame() {}

    /** 返回指定行对应的世界位置，无位置信息返回 null。 */
    default LocatedPosition getPositionAt(int rowIdx) { return null; }

    /** 返回指定行的各列值数组，用于路径点占位符替换。列表模式返回 null。 */
    default String[] getRowAt(int rowIdx) { return null; }
}
