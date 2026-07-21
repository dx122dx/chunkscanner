package com.billy65536.chunkscanner.gui.layout;

import net.minecraft.item.ItemStack;

/**
 * 物品图标单元格。
 *
 * <p>渲染为 16×16 物品图标（覆盖文本显示），
 * 悬停时显示原版物品 tooltip（由 {@code DatabaseScreen} 统一处理）。</p>
 */
public record ItemCell(ItemStack stack) implements IContentCell {
    public static ItemCell of(ItemStack stack) { return new ItemCell(stack); }
}
