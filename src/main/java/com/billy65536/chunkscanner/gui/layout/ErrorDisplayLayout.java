package com.billy65536.chunkscanner.gui.layout;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常信息展示布局，实现 {@link ILayout}，用于在 GUI 中展示 {@link Throwable} 详情。
 *
 * <p>展示内容包括五个部分：</p>
 * <ol>
 *   <li><b>基础描述信息</b> — 异常类型、错误消息、本地化消息</li>
 *   <li><b>堆栈轨迹</b> — 完整调用栈，每帧包含类名、方法名、文件名与行号</li>
 *   <li><b>异常链</b> — 通过 {@link Throwable#getCause()} 递归追溯根因</li>
 *   <li><b>被抑制的异常</b> — 通过 {@link Throwable#getSuppressed()} 获取</li>
 *   <li><b>诊断元数据</b> — 栈深度、serialVersionUID</li>
 * </ol>
 *
 * <p>每行渲染为带颜色的单行文本，支持水平滚动。不包含表格模式功能（无位置列、物品图标等）。</p>
 */
public class ErrorDisplayLayout implements ILayout {

    private static final int ROW_HEIGHT = 20;
    private static final int LEFT_PADDING = 8;

    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_RED = 0xFFFF5555;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_GOLD = 0xFFFFAA00;
    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_DARK_GRAY = 0xFF555555;

    private final TextRenderer textRenderer;
    private final List<ErrorRow> rows;
    private final int contentWidth;

    /**
     * 构建异常信息展示布局。
     *
     * @param textRenderer 文本渲染器
     * @param exception    要展示的异常实例
     */
    public ErrorDisplayLayout(TextRenderer textRenderer, Throwable exception) {
        this.textRenderer = textRenderer;
        this.rows = new ArrayList<>();
        buildRows(exception);
        int maxW = 0;
        for (ErrorRow row : rows) {
            int w = textRenderer.getWidth(row.text);
            if (w > maxW) maxW = w;
        }
        this.contentWidth = maxW + LEFT_PADDING;
    }

    // ==================== 构建数据行 ====================

    private void buildRows(Throwable e) {
        addRow("====================", COLOR_GOLD);
        addRow(Text.translatable("chunkscanner.dbview.error.title").getString(), COLOR_GOLD);
        addRow("====================", COLOR_GOLD);
        addBlank();
        addRow(Text.translatable("chunkscanner.dbview.error.msg").getString(), COLOR_RED);
        addBlank();

        // ---- Section 1: 基础描述信息 ----
        addSection("=== Exception Details ===");

        // 1.1 异常类型
        addLabeled("Type:", e.getClass().getName(), COLOR_RED, COLOR_RED);
        // 1.2 错误消息
        String message = e.getMessage();
        addLabeled("Message:", message != null ? message : "(null)", COLOR_RED, COLOR_RED);
        // 1.3 本地化消息
        String localMsg = e.getLocalizedMessage();
        addLabeled("Localized:", localMsg != null ? localMsg : "(null)", COLOR_GRAY, COLOR_GRAY);

        addBlank();

        // ---- Section 2: 堆栈轨迹 ----
        addSection("--- Stack Trace ---");
        StackTraceElement[] stackTrace = e.getStackTrace();
        if (stackTrace.length == 0) {
            addRow("  (stack trace unavailable — possibly optimized out by -XX:+OmitStackTraceInFastThrow)", COLOR_DARK_GRAY);
        } else {
            for (StackTraceElement ste : stackTrace) {
                addRow("  " + formatFrame(ste), COLOR_WHITE);
            }
        }

        // ---- Section 3: 异常链 (Cause) ----
        Throwable cause = e.getCause();
        if (cause != null) {
            addBlank();
            addSection("--- Caused by ---");
            renderCauseChain(cause, 0);
        }

        // ---- Section 4: 被抑制的异常 (Suppressed) ----
        Throwable[] suppressed = e.getSuppressed();
        if (suppressed.length > 0) {
            addBlank();
            addSection("--- Suppressed Exceptions (" + suppressed.length + ") ---");
            for (int i = 0; i < suppressed.length; i++) {
                Throwable sup = suppressed[i];
                addRow("  [" + i + "] " + sup.getClass().getName()
                        + ": " + (sup.getMessage() != null ? sup.getMessage() : "(null)"), COLOR_YELLOW);
                StackTraceElement[] supTrace = sup.getStackTrace();
                for (StackTraceElement ste : supTrace) {
                    addRow("      " + formatFrame(ste), COLOR_GRAY);
                }
                addBlank();
            }
        }

        // ---- Section 5: 诊断与序列化元数据 ----
        addBlank();
        addSection("--- Diagnostics ---");
        addLabeled("Stack depth:", String.valueOf(stackTrace.length), COLOR_GRAY, COLOR_GRAY);
        addLabeled("serialVersionUID:", getSerialVersionUID(e), COLOR_GRAY, COLOR_GRAY);
    }

    /**
     * 递归渲染异常链（Cause），嵌套深度递增。
     */
    private void renderCauseChain(Throwable cause, int depth) {
        String indent = "  ".repeat(depth);
        int msgColor = (depth == 0) ? COLOR_YELLOW : COLOR_GRAY;

        addRow(indent + cause.getClass().getName()
                + ": " + (cause.getMessage() != null ? cause.getMessage() : "(null)"), msgColor);

        StackTraceElement[] trace = cause.getStackTrace();
        for (StackTraceElement ste : trace) {
            addRow(indent + "  " + formatFrame(ste), COLOR_GRAY);
        }

        Throwable nested = cause.getCause();
        if (nested != null) {
            addRow(indent + "  Caused by:", COLOR_YELLOW);
            renderCauseChain(nested, depth + 1);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化堆栈帧为标准 Java 格式。
     * <p>示例：{@code at com.example.Foo.bar(Foo.java:42)}</p>
     */
    private static String formatFrame(StackTraceElement ste) {
        StringBuilder sb = new StringBuilder("at ");
        sb.append(ste.getClassName()).append('.').append(ste.getMethodName());
        if (ste.isNativeMethod()) {
            sb.append("(Native Method)");
        } else {
            String fileName = ste.getFileName();
            int lineNumber = ste.getLineNumber();
            if (fileName != null && lineNumber >= 0) {
                sb.append('(').append(fileName).append(':').append(lineNumber).append(')');
            } else if (fileName != null) {
                sb.append('(').append(fileName).append(')');
            } else {
                sb.append("(Unknown Source)");
            }
        }
        return sb.toString();
    }

    /**
     * 通过 {@link ObjectStreamClass} 获取异常的 serialVersionUID。
     * 若类未显式定义 serialVersionUID，则返回 JVM 计算的值。
     */
    private static String getSerialVersionUID(Throwable e) {
        try {
            long uid = ObjectStreamClass.lookup(e.getClass()).getSerialVersionUID();
            return uid + "L";
        } catch (Exception ex) {
            return "(unavailable)";
        }
    }

    // ---- 行构建辅助 ----

    private void addSection(String text) {
        rows.add(new ErrorRow(text, COLOR_GOLD));
    }

    private void addRow(String text, int color) {
        rows.add(new ErrorRow(text, color));
    }

    private void addLabeled(String label, String value, int labelColor, int valueColor) {
        rows.add(new ErrorRow("  " + label + " " + value, valueColor));
    }

    private void addBlank() {
        rows.add(new ErrorRow("", COLOR_WHITE));
    }

    // ==================== ILayout 实现 ====================

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getMetaCount() {
        return 0;
    }

    @Override
    public int computeContentWidth() {
        return contentWidth;
    }

    @Override
    public int getHeaderHeight() {
        return 0;
    }

    @Override
    public int renderHeader(DrawContext ctx, int listTop, int margin, int hScrollOffset) {
        return 0;
    }

    @Override
    public int renderRow(DrawContext ctx, int idx, int rowY,
                          int margin, int hScrollOffset, int mouseX, int mouseY) {
        ErrorRow row = rows.get(idx);
        if (row.text.isEmpty()) return -1;

        int rx = margin - hScrollOffset;
        ctx.drawTextWithShadow(textRenderer, Text.literal(row.text), rx, rowY, row.color);

        boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                && mouseX >= rx && mouseX < rx + textRenderer.getWidth(row.text);
        return hovered ? idx : -1;
    }

    @Override
    public void export(StringBuilder sb) {
        for (ErrorRow row : rows) {
            sb.append(row.text).append('\n');
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * 单行错误展示条目。
     *
     * @param text  展示文本
     * @param color ARGB 颜色值
     */
    private record ErrorRow(String text, int color) {}
}
