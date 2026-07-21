package com.billy65536.chunkscanner.gui.layout;

import java.util.Arrays;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 带颜色和可选 tooltip 的文本单元格。
 *
 * @param text    显示文本
 * @param tooltip 悬停 tooltip 行数组，{@code null} 表示无 tooltip
 * @param color   ARGB 颜色值（如 {@code 0xFFFFFFFF} 为白色）
 */
public record TextCell(Text text, Text[] tooltip, int color) implements IContentCell {

    public TextCell withTooltip(Text tooltop[]) {
        return new TextCell(text(), tooltop, color());
    }

    public TextCell withTooltip(String tooltop[]) {
        return this.withTooltip(Arrays.stream(tooltop).map(Text::literal).toArray(Text[]::new));
    }

    public TextCell withColor(int argb) {
        return new TextCell(text(), tooltip(), argb);
    }

    public TextCell withFormat(Formatting... formattings) {
        return new TextCell(text().copy().formatted(formattings), tooltip(), color());
    }

    /** 创建白色无 tooltip 的默认文本单元格。 */
    public static TextCell of(Text text) {
        return new TextCell(text, null, 0xFFFFFFFF);
    }

    /** 创建白色无 tooltip 的默认文本单元格。 */
    public static TextCell of(String string) {
        return TextCell.of(Text.literal(string));
    }
}
