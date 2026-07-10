package com.billy65536.chunkscanner.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * 可复用的滚动条渲染与交互工具。
 *
 * 用于 DatabaseScreen、ChunkScannerScreen 等需要滚动条的 GUI。
 */
public final class ScrollbarUtil {

    public static final int THICKNESS = 4;

    private ScrollbarUtil() {}

    /* ==================== 绘制 ==================== */

    /**
     * 绘制垂直滚动条。
     *
     * @param x         滚动条 X 坐标
     * @param top       轨道上端 Y
     * @param bottom    轨道下端 Y
     * @param total     总条目数
     * @param offset    当前偏移量
     * @param maxVisible 最多可见条目数
     */
    public static void drawVertical(DrawContext context, int x, int top, int bottom,
                                     int total, int offset, int maxVisible) {
        int[] bounds = verticalThumbBounds(top, bottom, total, offset, maxVisible);
        if (bounds == null) return;
        context.fill(x, top, x + THICKNESS, bottom, 0x22FFFFFF);
        context.fill(x, bounds[0], x + THICKNESS, bounds[1], 0x88CCCCCC);
    }

    /**
     * 绘制横向滚动条。
     *
     * @param y          滚动条 Y 坐标
     * @param left       轨道左端 X
     * @param right      轨道右端 X
     * @param totalWidth 内容总宽度
     * @param offset     当前偏移量
     */
    public static void drawHorizontal(DrawContext context, int y, int left, int right,
                                       int totalWidth, int offset) {
        int[] bounds = horizontalThumbBounds(left, right, totalWidth, offset);
        if (bounds == null) return;
        context.fill(left, y, right, y + THICKNESS, 0x22FFFFFF);
        context.fill(bounds[0], y, bounds[1], y + THICKNESS, 0x88CCCCCC);
    }

    /* ==================== 拇指边界查询（用于拖拽命中检测） ==================== */

    /**
     * @return {thumbTop, thumbBottom}，不需要滚动条时返回 null。
     */
    public static int[] verticalThumbBounds(int top, int bottom, int total, int offset, int maxVisible) {
        if (total <= maxVisible || total == 0) return null;
        int trackHeight = bottom - top;
        int thumbH = Math.max(8, (int) ((long) trackHeight * maxVisible / total));
        int travel = trackHeight - thumbH;
        int maxOff = total - maxVisible;
        int t = top + (maxOff > 0 ? (int) ((long) travel * offset / maxOff) : 0);
        return new int[]{t, t + thumbH};
    }

    /**
     * @return {thumbLeft, thumbRight}，不需要滚动条时返回 null。
     */
    public static int[] horizontalThumbBounds(int left, int right, int totalWidth, int offset) {
        int visible = right - left;
        if (totalWidth <= visible) return null;
        int trackW = right - left;
        int thumbW = Math.max(16, (int) ((long) trackW * visible / totalWidth));
        int travel = trackW - thumbW;
        int maxOff = totalWidth - visible;
        int l = left + (maxOff > 0 ? (int) ((long) travel * offset / maxOff) : 0);
        return new int[]{l, l + thumbW};
    }

    /* ==================== 拖拽偏移计算 ==================== */

    /**
     * 根据鼠标在垂直轨道内的位置计算新的偏移量。
     *
     * @param mouseY    鼠标 Y（绝对值）
     * @param top       轨道 top
     * @param bottom    轨道 bottom
     * @param total     总条目数
     * @param maxVisible 可见条目数
     * @return 新的 offset（未 clamp）
     */
    public static int verticalOffsetFromMouse(double mouseY, int top, int bottom,
                                               int total, int maxVisible) {
        if (total <= 0 || total <= maxVisible) return 0;
        int trackH = bottom - top;
        int thumbH = Math.max(8, (int) ((long) trackH * maxVisible / total));
        int travel = trackH - thumbH;
        int maxOff = total - maxVisible;
        if (travel <= 0) return 0;
        double frac = ((double) mouseY - top - thumbH / 2.0) / travel;
        return (int) Math.round(frac * maxOff);
    }

    /**
     * 根据鼠标在水平轨道内的位置计算新的偏移量。
     */
    public static int horizontalOffsetFromMouse(double mouseX, int left, int right,
                                                 int totalWidth) {
        int visible = right - left;
        if (totalWidth <= 0 || totalWidth <= visible) return 0;
        int trackW = right - left;
        int thumbW = Math.max(16, (int) ((long) trackW * visible / totalWidth));
        int travel = trackW - thumbW;
        int maxOff = totalWidth - visible;
        if (travel <= 0) return 0;
        double frac = ((double) mouseX - left - thumbW / 2.0) / travel;
        return (int) Math.round(frac * maxOff);
    }

    /** @return true 如果鼠标在垂直滚动条轨道范围内（包含背景轨道）。 */
    public static boolean isOnVerticalTrack(double mx, double my, int x, int top, int bottom) {
        return mx >= x && mx < x + THICKNESS && my >= top && my < bottom;
    }

    /** @return true 如果鼠标在水平滚动条轨道范围内（包含背景轨道）。 */
    public static boolean isOnHorizontalTrack(double mx, double my, int y, int left, int right) {
        return my >= y && my < y + THICKNESS && mx >= left && mx < right;
    }
}
