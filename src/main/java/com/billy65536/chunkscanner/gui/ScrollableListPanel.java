package com.billy65536.chunkscanner.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * 可复用的垂直滚动列表面板，封装滚动机制（ScrollManager + 边界计算 + 滚动条渲染 + 交互）。
 *
 * 由 ChunkScannerScreen 和 DatabaseScreen 使用，减少滚动逻辑的样板代码。
 *
 * 用法：
 * <pre>{@code
 *   ScrollableListPanel panel = new ScrollableListPanel(ITEM_HEIGHT);
 *   // 每帧
 *   int maxVis = panel.clamp(totalItems);
 *   for (int i = 0; i < maxVis; i++) {
 *       int idx = panel.getOffset() + i;
 *       // 渲染第 idx 项
 *   }
 *   panel.drawScrollbar(ctx, totalItems);
 *   // 交互
 *   if (panel.handleClick(mx, my, totalItems)) return true;
 *   panel.handleDrag(my, totalItems);
 *   panel.handleScroll(amount, totalItems);
 * }</pre>
 */
public class ScrollableListPanel {
    private final ScrollManager scrollManager = new ScrollManager();
    private final int itemHeight;
    private int listTop, listBottom;
    private int scrollbarX;

    public ScrollableListPanel(int itemHeight) {
        this.itemHeight = itemHeight;
    }

    public ScrollManager getScrollManager() { return scrollManager; }
    public int getOffset() { return scrollManager.getOffset(); }
    public void setOffset(int v) { scrollManager.setOffset(v); }
    public int getItemHeight() { return itemHeight; }
    public int getListTop() { return listTop; }
    public int getListBottom() { return listBottom; }
    public int getScrollbarX() { return scrollbarX; }

    /** 设置面板边界和滚动条 X 坐标。 */
    public void setBounds(int listTop, int listBottom, int scrollbarX) {
        this.listTop = listTop;
        this.listBottom = listBottom;
        this.scrollbarX = scrollbarX;
    }

    /** 计算最大可见项数。 */
    public int getMaxVisible(int totalItems) {
        int visibleH = listBottom - listTop;
        if (visibleH <= 0 || itemHeight <= 0) return 0;
        return Math.min(totalItems, visibleH / itemHeight);
    }

    /** Clamp 滚动偏移并返回最大可见项数。 */
    public int clamp(int totalItems) {
        int maxVis = getMaxVisible(totalItems);
        scrollManager.clamp(totalItems, maxVis);
        return maxVis;
    }

    /** 绘制垂直滚动条（数据不足时无操作）。 */
    public void drawScrollbar(DrawContext ctx, int totalItems) {
        scrollManager.drawVertical(ctx, scrollbarX, listTop, listBottom,
                totalItems, getMaxVisible(totalItems));
    }

    /** 处理滚动条点击。返回 true 表示事件已消费。 */
    public boolean handleClick(double mx, double my, int totalItems) {
        return scrollManager.handleVerticalClick(mx, my, scrollbarX, listTop, listBottom,
                totalItems, getMaxVisible(totalItems));
    }

    /** 处理滚动条拖拽。返回 true 表示事件已消费。 */
    public boolean handleDrag(double my, int totalItems) {
        return scrollManager.handleVerticalDrag(my, listTop, listBottom,
                totalItems, getMaxVisible(totalItems));
    }

    /** 处理鼠标滚轮。 */
    public void handleScroll(double amount, int totalItems) {
        scrollManager.handleVerticalScroll(amount, totalItems, getMaxVisible(totalItems));
    }

    /** 结束拖拽。 */
    public void endDrag() {
        scrollManager.endDrag();
    }

    /** 是否正在拖拽滚动条。 */
    public boolean isDragging() {
        return scrollManager.isDragging();
    }
}
