package com.billy65536.chunkscanner.gui.layout;

import com.billy65536.chunkscanner.core.LocatedPosition;

/**
 * 世界位置单元格。
 *
 * <p>渲染为青色文本（悬停时高亮为黄色），点击可创建 Xaero 路径点。
 * 导出和列宽计算使用 {@link LocatedPosition#toString()}。</p>
 */
public record PositionCell(LocatedPosition pos) implements IContentCell {
    public static PositionCell of(LocatedPosition pos) { return new PositionCell(pos); }
}