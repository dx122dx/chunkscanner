package com.billy65536.chunkscanner.gui.layout;

/**
 * 带颜色和可选 tooltip 的文本单元格。
 *
 * @param text    显示文本
 * @param tooltip 悬停 tooltip 行数组，{@code null} 表示无 tooltip
 * @param color   ARGB 颜色值（如 {@code 0xFFFFFFFF} 为白色）
 */
public record RichText(String text, String[] tooltip, int color) implements CellContent {

    public RichText withTooltip(String tooltop[]) {
        return new RichText(text(), tooltop, color());
    }

    public RichText withColor(int argb) {
        return new RichText(text(), tooltip(), argb);
    }

    /** 创建白色无 tooltip 的默认文本单元格。 */
    public static RichText of(String text) {
        return new RichText(text, null, 0xFFFFFFFF);
    }
}
