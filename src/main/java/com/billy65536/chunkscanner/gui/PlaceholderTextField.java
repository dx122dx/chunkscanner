package com.billy65536.chunkscanner.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 带 placeholder 占位提示的文本输入框。
 *
 * <p>封装了 MC {@link TextFieldWidget} 的 suggestion 机制，实现标准的
 * placeholder 行为：
 * <ul>
 *   <li>字段为空且聚焦时：显示灰色提示文字（使用 MC 内置 suggestion）</li>
 *   <li>字段为空且未聚焦时：手动绘制灰色 placeholder 文本</li>
 *   <li>输入文本后：placeholder 自动隐藏</li>
 * </ul>
 *
 * <p>使用方式与原 TextFieldWidget 一致，通过 {@link #setMaxLength}、
 * {@link #setTextPredicate} 等方法设置约束。
 */
public class PlaceholderTextField extends TextFieldWidget {

    private final TextRenderer textRenderer;
    private String placeholder;

    /**
     * 创建带 placeholder 的文本字段。
     *
     * @param textRenderer 字体渲染器
     * @param x            X 坐标
     * @param y            Y 坐标
     * @param width        宽度
     * @param height       高度
     * @param placeholder  占位提示文本，可为 null（不显示 placeholder）
     */
    public PlaceholderTextField(TextRenderer textRenderer, int x, int y, int width, int height,
                                String placeholder) {
        super(textRenderer, x, y, width, height, Text.literal(""));
        this.textRenderer = textRenderer;
        this.placeholder = placeholder;

        // 初始状态：内容为空时显示 placeholder（通过 suggestion 机制）
        if (placeholder != null && !placeholder.isEmpty()) {
            setSuggestion(placeholder);
        }

        // 文本变更时自动切换 suggestion
        setChangedListener(text -> {
            if (text.isEmpty() && this.placeholder != null && !this.placeholder.isEmpty()) {
                setSuggestion(this.placeholder);
            } else {
                setSuggestion(null);
            }
        });
    }

    /**
     * 更新 placeholder 文本。
     * 如果当前字段为空，立即刷新 suggestion。
     */
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        if (getText().isEmpty() && placeholder != null && !placeholder.isEmpty()) {
            setSuggestion(placeholder);
        } else if (getText().isEmpty()) {
            setSuggestion(null);
        }
    }

    /** 获取当前 placeholder 文本。 */
    public String getPlaceholder() {
        return placeholder;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderButton(context, mouseX, mouseY, delta);

        // MC 的 suggestion 仅在聚焦时显示；未聚焦且内容为空时手动绘制 placeholder
        if (getText().isEmpty() && placeholder != null && !placeholder.isEmpty() && !isFocused()) {
            int textY = getY() + (height - 8) / 2;
            context.drawTextWithShadow(textRenderer, placeholder, getX() + 4, textY, 0x808080);
        }
    }
}
