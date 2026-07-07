package com.billy65536.chunkscanner.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * 管理单方向滚动偏移、clamp、滚动条渲染与鼠标交互。
 *
 * 每个实例负责一个方向（垂直或水平）。
 * 双轴滚动（如 DatabaseScreen KV 视图）使用两个实例分别管理。
 *
 * 用法：
 * <pre>{@code
 *   ScrollManager vScroll = new ScrollManager();
 *   // 每帧更新
 *   vScroll.clamp(total, maxVisible);
 *   // 渲染
 *   vScroll.drawVertical(ctx, sbX, listTop, listBottom, total, maxVisible);
 *   // 交互
 *   if (vScroll.handleVerticalClick(mx, my, sbX, ...)) return true;
 *   vScroll.handleVerticalDrag(my, ...);
 *   vScroll.handleVerticalScroll(amount, total, maxVisible);
 * }</pre>
 */
public class ScrollManager {

    private int offset = 0;
    private boolean dragging = false;

    // ==================== 基本状态 ====================

    public int getOffset() {
        return offset;
    }

    public void setOffset(int v) {
        this.offset = v;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void endDrag() {
        dragging = false;
    }

    /** 将 offset clamp 到 [0, total - maxVisible]。 */
    public void clamp(int total, int maxVisible) {
        int maxOff = Math.max(0, total - maxVisible);
        offset = GuiUtil.clamp(offset, 0, maxOff);
    }

    // ==================== 垂直方向 ====================

    /** 绘制垂直滚动条（数据不足时不显示）。 */
    public void drawVertical(DrawContext ctx, int x, int top, int bottom,
                             int total, int maxVisible) {
        if (total <= maxVisible || total == 0) return;
        ScrollbarUtil.drawVertical(ctx, x, top, bottom, total, offset, maxVisible);
    }

    /**
     * 处理垂直滚动条上的鼠标点击。
     * @return true 表示事件已消费
     */
    public boolean handleVerticalClick(double mx, double my, int sbX, int top, int bottom,
                                        int total, int maxVisible) {
        if (total <= maxVisible) return false;

        int[] thumb = ScrollbarUtil.verticalThumbBounds(top, bottom, total, offset, maxVisible);

        // 拖拽滑块
        if (thumb != null && mx >= sbX && mx < sbX + ScrollbarUtil.THICKNESS
                && my >= thumb[0] && my < thumb[1]) {
            dragging = true;
            return true;
        }

        // 点击轨道跳跃
        if (ScrollbarUtil.isOnVerticalTrack(mx, my, sbX, top, bottom)) {
            int newOff = ScrollbarUtil.verticalOffsetFromMouse(my, top, bottom, total, maxVisible);
            offset = GuiUtil.clamp(newOff, 0, Math.max(0, total - maxVisible));
            dragging = true;
            return true;
        }

        return false;
    }

    /** 处理垂直拖拽。@return true 表示事件已消费 */
    public boolean handleVerticalDrag(double my, int top, int bottom, int total, int maxVisible) {
        if (!dragging) return false;
        int newOff = ScrollbarUtil.verticalOffsetFromMouse(my, top, bottom, total, maxVisible);
        offset = GuiUtil.clamp(newOff, 0, Math.max(0, total - maxVisible));
        return true;
    }

    /** 处理鼠标滚轮（垂直）。 */
    public void handleVerticalScroll(double amount, int total, int maxVisible) {
        int maxOff = Math.max(0, total - maxVisible);
        offset = GuiUtil.clamp(offset - (int) amount, 0, maxOff);
    }

    // ==================== 水平方向 ====================

    /** 绘制水平滚动条。 */
    public void drawHorizontal(DrawContext ctx, int y, int left, int right, int totalWidth) {
        int visibleW = right - left;
        if (totalWidth <= visibleW) return;
        ScrollbarUtil.drawHorizontal(ctx, y, left, right, totalWidth, offset);
    }

    /**
     * 处理水平滚动条上的鼠标点击。
     * @return true 表示事件已消费
     */
    public boolean handleHorizontalClick(double mx, double my, int hY, int left, int right,
                                          int totalWidth) {
        int visibleW = right - left;
        if (totalWidth <= visibleW) return false;

        int[] thumb = ScrollbarUtil.horizontalThumbBounds(left, right, totalWidth, offset);

        // 拖拽滑块
        if (thumb != null && my >= hY && my < hY + ScrollbarUtil.THICKNESS
                && mx >= thumb[0] && mx < thumb[1]) {
            dragging = true;
            return true;
        }

        // 点击轨道跳跃
        if (ScrollbarUtil.isOnHorizontalTrack(mx, my, hY, left, right)) {
            int newOff = ScrollbarUtil.horizontalOffsetFromMouse(mx, left, right, totalWidth);
            offset = GuiUtil.clamp(newOff, 0, Math.max(0, totalWidth - visibleW));
            dragging = true;
            return true;
        }

        return false;
    }

    /** 处理水平拖拽。@return true 表示事件已消费 */
    public boolean handleHorizontalDrag(double mx, int left, int right, int totalWidth) {
        if (!dragging) return false;
        int visibleW = right - left;
        int newOff = ScrollbarUtil.horizontalOffsetFromMouse(mx, left, right, totalWidth);
        offset = GuiUtil.clamp(newOff, 0, Math.max(0, totalWidth - visibleW));
        return true;
    }

    /** 处理鼠标滚轮（水平，通常 shift+滚轮）。 */
    public void handleHorizontalScroll(double amount, int totalWidth, int visibleWidth) {
        int maxOff = Math.max(0, totalWidth - visibleWidth);
        offset = GuiUtil.clamp(offset - (int) amount, 0, maxOff);
    }
}
