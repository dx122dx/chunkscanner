package com.billy65536.chunkscanner.screen;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.gui.PlaceholderTextField;
import com.billy65536.chunkscanner.gui.ScrollableListPanel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 主 GUI —— 通过 /chunkscanner 打开。
 *
 * 布局：
 *   [数据库]     [id 输入] [分析器▼] [创建]
 *   ─────────────────────────────────────────
 *   [analyzer] id                         [✕][⏸]
 *   pending:N | scanned:N | found:N | errors:N
 *   ...（可滚动列表）
 *   ─────────────────────────────────────────
 *   [           全部停止           ]
 */
public class ChunkScannerScreen extends Screen {

    private static final int WIDTH = 340;
    private static final int LIST_ITEM_HEIGHT = 36;
    private static final int SCROLLBAR_X_OFFSET = WIDTH - 12; // relative to left margin
    private static final int SEPARATOR_Y = 52;
    private static final int LIST_TOP_OFFSET = 4;

    private final ChunkScanner scanner;

    // 顶部控件
    private PlaceholderTextField idField;
    private ButtonWidget analyzerButton;
    private ButtonWidget createButton;

    /** 默认扫描 ID（当前时间戳），用于 idField 的 suggestion 和回退。 */
    private String defaultScanId;

    // 底部
    private ButtonWidget stopAllButton;
    private ButtonWidget databaseButton;

    // 分析器列表
    private List<ChunkAnalyzer> analyzerList;
    private int selectedAnalyzerIdx = 0;

    // 活跃任务列表滚动
    private final ScrollableListPanel sessionPanel = new ScrollableListPanel(LIST_ITEM_HEIGHT);

    /** 悬停的会话索引（渲染时填充，用于 tooltip）。 */
    private int hoveredSessionIdx = -1;

    public ChunkScannerScreen(ChunkScanner scanner) {
        super(Text.translatable("chunkscanner.gui.title"));
        this.scanner = scanner;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;
        int topY = 28;

        // 分析器列表
        analyzerList = new ArrayList<>(scanner.getAnalyzers());

        // 左上角：数据库按钮（与 DatabaseScreen 的 "任务视图" 按钮对齐：y=8）
        databaseButton = ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.open_database"),
                btn -> doOpenDatabase())
                .dimensions(leftX + 4, 8, 54, 16).build();
        addDrawableChild(databaseButton);

        // --- 顶部栏 ---
        // id 输入框：使用 placeholder 模式（灰色提示），用户输入后自动隐藏
        defaultScanId = String.valueOf(System.currentTimeMillis() / 1000);
        idField = new PlaceholderTextField(textRenderer, leftX + 4, topY, 140, 20,
                defaultScanId);
        idField.setMaxLength(48);
        addDrawableChild(idField);
        setInitialFocus(idField);

        // 分析器选择按钮
        analyzerButton = ButtonWidget.builder(getAnalyzerButtonText(), btn -> {
            if (!analyzerList.isEmpty()) {
                selectedAnalyzerIdx = (selectedAnalyzerIdx + 1) % analyzerList.size();
                btn.setMessage(getAnalyzerButtonText());
            }
        }).dimensions(leftX + 146, topY, 76, 20).build();
        addDrawableChild(analyzerButton);

        // 创建按钮（直接使用默认配置创建任务）
        createButton = ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.create"),
                btn -> doCreateDirect())
                .dimensions(leftX + 226, topY, 46, 20).build();
        addDrawableChild(createButton);

        // 高级按钮（打开配置页面）
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.advanced"),
                btn -> doAdvanced())
                .dimensions(leftX + 274, topY, 58, 20).build());

        // --- 底部（与列表等宽） ---
        int bottomY = this.height - 30;
        stopAllButton = ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.stop_all"),
                btn -> doStopAll())
                .dimensions(leftX + 4, bottomY, WIDTH - 8, 20).build();
        addDrawableChild(stopAllButton);
    }

    private Text getAnalyzerButtonText() {
        if (analyzerList.isEmpty()) {
            return Text.translatable("chunkscanner.label.none").formatted(Formatting.GRAY);
        }
        ChunkAnalyzer a = analyzerList.get(selectedAnalyzerIdx);
        return a.getName().copy().formatted(Formatting.YELLOW);
    }

    /**
     * 直接创建扫描任务（使用默认配置），不打开配置页面。
     */
    private void doCreateDirect() {
        String id = idField.getText().trim();
        if (id.isEmpty()) {
            id = defaultScanId;
        }
        if (analyzerList.isEmpty()) return;
        String analyzerId = analyzerList.get(selectedAnalyzerIdx).getId();
        ChunkScannerMod.LOGGER.debug("GUI create scan: analyzer={} id={}", analyzerId, id);
        MinecraftClient client = MinecraftClient.getInstance();
        scanner.start(client, analyzerId, id, null);
    }

    /**
     * 打开高级配置页面，允许用户自定义参数后再创建扫描任务。
     */
    private void doAdvanced() {
        String id = idField.getText().trim();
        if (id.isEmpty()) {
            id = defaultScanId;
        }
        if (analyzerList.isEmpty()) return;
        String analyzerId = analyzerList.get(selectedAnalyzerIdx).getId();
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new TaskConfigScreen(this, scanner, analyzerId, id));
    }

    private void doStopAll() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(this);
                    if (confirmed) scanner.stopAll(client);
                },
                Text.translatable("chunkscanner.confirm.stop_all.title"),
                Text.translatable("chunkscanner.confirm.stop_all.message")
        ));
    }

    private void doOpenDatabase() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new DatabaseScreen(null));
    }

    private void doStop(String scanId) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(this);
                    if (confirmed) scanner.stop(client, scanId);
                },
                Text.translatable("chunkscanner.confirm.stop.title"),
                Text.translatable("chunkscanner.confirm.stop.message", scanId)
        ));
    }

    private void doPause(String scanId) {
        MinecraftClient client = MinecraftClient.getInstance();
        scanner.pause(client, scanId);
    }

    private void doResume(String scanId) {
        MinecraftClient client = MinecraftClient.getInstance();
        scanner.resume(client, scanId);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;

        // 重置悬停状态
        hoveredSessionIdx = -1;

        // 标题
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.title").formatted(Formatting.GOLD, Formatting.BOLD),
                centerX, 12, 0xFFFFFF);

        // 分隔线
        int sepY = SEPARATOR_Y;
        context.drawHorizontalLine(leftX, leftX + WIDTH, sepY, 0xFF555555);

        // 渲染活跃任务列表
        Collection<ScanSession> sessions = scanner.getActiveSessions();
        List<ScanSession> list = new ArrayList<>(sessions);

        // 列表区域
        int listTop = sepY + LIST_TOP_OFFSET;
        int listBottom = this.height - 38;
        sessionPanel.setBounds(listTop, listBottom, leftX + SCROLLBAR_X_OFFSET);
        int maxVisible = sessionPanel.clamp(list.size());

        if (list.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("chunkscanner.gui.no_scans").formatted(Formatting.GRAY),
                    centerX, listTop + 20, 0xFFFFFF);
        } else {
            for (int i = 0; i < maxVisible; i++) {
                int idx = sessionPanel.getOffset() + i;
                if (idx >= list.size()) break;
                ScanSession s = list.get(idx);
                int y = listTop + i * LIST_ITEM_HEIGHT;
                renderSessionRow(context, s, idx, y, centerX, leftX, mouseX, mouseY);
            }
        }

        // 垂直滚动条
        sessionPanel.drawScrollbar(context, list.size());

        super.render(context, mouseX, mouseY, delta);

        // 分析器选择按钮悬停 tooltip
        if (analyzerButton != null && analyzerButton.isMouseOver(mouseX, mouseY)) {
            if (!analyzerList.isEmpty()) {
                ChunkAnalyzer a = analyzerList.get(selectedAnalyzerIdx);
                context.drawTooltip(textRenderer, a.getDescription(), mouseX, mouseY);
            }
        }

        // 会话行悬停 tooltip — 显示完整统计
        if (hoveredSessionIdx >= 0 && hoveredSessionIdx < list.size()) {
            ScanSession s = list.get(hoveredSessionIdx);
            MinecraftClient client = MinecraftClient.getInstance();
            ChunkScanner.ChunkStatusBreakdown bd = s.getStatusBreakdown(client);
            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.literal(s.analyzer.getName().getString() + " — " + s.scanId)
                    .formatted(Formatting.GOLD));
            tooltip.add(Text.literal(""));
            tooltip.add(Text.translatable("chunkscanner.tooltip.session.pending", bd.pending())
                    .formatted(Formatting.WHITE));
            tooltip.add(Text.translatable("chunkscanner.tooltip.session.scanned",
                    bd.scannedNoFind() + bd.scannedFound(),
                    bd.scannedNoFind(), bd.scannedFound())
                    .formatted(Formatting.GREEN));
            tooltip.add(Text.translatable("chunkscanner.tooltip.session.past_revisit",
                    bd.pastRevisitNoFind() + bd.pastRevisitFound(),
                    bd.pastRevisitNoFind(), bd.pastRevisitFound())
                    .formatted(Formatting.BLUE));
            tooltip.add(Text.translatable("chunkscanner.tooltip.session.errors",
                    bd.error(), bd.foundError())
                    .formatted(Formatting.RED));
            tooltip.add(Text.literal(""));
            tooltip.add(Text.translatable("chunkscanner.tooltip.session.total",
                    s.totalScannedChunks.get(), s.totalFoundChunks.get(), s.totalErrors.get())
                    .formatted(Formatting.GRAY));
            int dbSize = s.db != null ? s.db.size() : 0;
            Text dbRateText;
            if (s.paused) {
                dbRateText = Text.translatable("chunkscanner.tooltip.session.db_rate_paused", dbSize, s.getTasksPerTick())
                        .formatted(Formatting.GRAY);
            } else {
                dbRateText = Text.translatable("chunkscanner.tooltip.session.db_rate", dbSize, s.getTasksPerTick())
                        .formatted(Formatting.GRAY);
            }
            tooltip.add(dbRateText);
            // 显示任务配置（如有）
            TaskConfig tc = s.getTaskConfig();
            if (tc != null && !tc.toDisplayString().isEmpty()) {
                tooltip.add(Text.literal(""));
                tooltip.add(Text.literal("[" + tc.toDisplayString() + "]").formatted(Formatting.DARK_GRAY));
            }
            context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
        }
    }

    /**
     * 渲染单个扫描任务行（三行布局）：
     *   第 1 行：[analyzer] scanId                  [⏸/▸] [✕]
     *   第 2 行：████████████████  多彩状态条
     *   第 3 行：已扫:N  错误:N  发现:N  [⏸]
     *
     * 状态条颜色含义：
     *   白色  = 待扫描（pending）
     *   深绿  = 已扫无发现（scannedNoFind）
     *   亮绿  = 已扫有发现（scannedFound）
     *   深蓝  = 超重访无发现（pastRevisitNoFind）
     *   亮蓝  = 超重访有发现（pastRevisitFound）
     *   红色  = 错误（error）
     *   黄色  = 有发现+错误（foundError）
     *   灰色  = 未分配段 / 无数据
     */
    private void renderSessionRow(DrawContext context, ScanSession s, int listIdx,
                                   int y, int centerX, int leftX, int mouseX, int mouseY) {
        int x = leftX + 4;

        // --- 第 1 行：分析器名称和 id ---
        String header = "[" + s.analyzer.getName().getString() + "] " + s.scanId;
        context.drawTextWithShadow(textRenderer,
                Text.literal(header).formatted(Formatting.YELLOW),
                x, y, 0xFFFFFF);

        // 右侧按钮区域（预留滚动条空间）：左侧暂停/继续，右侧停止
        int btnRight = leftX + SCROLLBAR_X_OFFSET - 4;
        // 左按钮：暂停/继续 [⏸]/[▸]，宽度 26px
        int leftBtnLeft = btnRight - 56;
        int leftBtnRight = btnRight - 30;
        // 右按钮：停止 [✕]，宽度 28px
        int rightBtnLeft = btnRight - 28;
        int rightBtnRight = btnRight;

        // 暂停/继续按钮（左半部分）
        boolean pauseHovered = mouseX >= leftBtnLeft && mouseX <= leftBtnRight
                && mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT;
        if (s.paused) {
            // s.paused 时可点击继续：▶ 按钮
            context.drawTextWithShadow(textRenderer,
                    Text.literal("[▸]").formatted(pauseHovered ? Formatting.GREEN : Formatting.DARK_GREEN),
                    leftBtnLeft + 2, y, 0xFFFFFF);
        } else {
            // 正常运行时：⏸ 按钮
            context.drawTextWithShadow(textRenderer,
                    Text.literal("[⏸]").formatted(pauseHovered ? Formatting.YELLOW : Formatting.GOLD),
                    leftBtnLeft + 2, y, 0xFFFFFF);
        }

        // 停止按钮（右半部分）
        boolean stopHovered = mouseX >= rightBtnLeft && mouseX <= rightBtnRight
                && mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT;
        context.drawTextWithShadow(textRenderer,
                Text.literal("[✕]").formatted(stopHovered ? Formatting.RED : Formatting.DARK_RED),
                rightBtnLeft + 2, y, 0xFFFFFF);

        // --- 第 2 行：多彩状态条（按比例分段填充） ---
        MinecraftClient client = MinecraftClient.getInstance();
        ChunkScanner.ChunkStatusBreakdown bd = s.getStatusBreakdown(client);
        int barY = y + 12;
        int barH = 4;
        int barW = SCROLLBAR_X_OFFSET - 8; // 留出滚动条空间
        int barX = x;
        int total = bd.total();
        if (total > 0) {
            // 状态条 7 种颜色的定义（与 ChunkStatusBreakdown 字段一一对应）
            int[] colors = {
                0xFFFFFFFF, // 0: pending (白色)
                0xFF006600, // 1: scannedNoFind (深绿)
                0xFF00CC00, // 2: scannedFound (亮绿)
                0xFF0044AA, // 3: pastRevisitNoFind (深蓝)
                0xFF0088FF, // 4: pastRevisitFound (亮蓝)
                0xFFCC0000, // 5: error (红色)
                0xFFCCAA00  // 6: foundError (黄色)
            };
            int[] counts = {
                bd.pending(),
                bd.scannedNoFind(), bd.scannedFound(),
                bd.pastRevisitNoFind(), bd.pastRevisitFound(),
                bd.error(), bd.foundError()
            };
            int drawn = 0;
            // 按比例绘制各段，每段至少 1 像素宽（确保即使占比很小的状态也可见）
            for (int i = 0; i < colors.length; i++) {
                int segW = counts[i] > 0 ? Math.max(1, counts[i] * barW / total) : 0;
                if (segW > 0 && drawn + segW <= barW) {
                    context.fill(barX + drawn, barY, barX + drawn + segW, barY + barH, colors[i]);
                    drawn += segW;
                }
            }
            // 剩余空间填充灰色（如果总像素因整数除法未完全分配）
            if (drawn < barW) {
                context.fill(barX + drawn, barY, barX + barW, barY + barH, 0xFF888888);
            }
        } else {
            // 无数据时显示全灰条（尚未进入任何新区块）
            context.fill(barX, barY, barX + barW, barY + barH, 0xFF888888);
        }

        // --- 第 3 行：紧凑数值统计 ---
        int numY = barY + barH + 2;
        String numText = Text.translatable("chunkscanner.gui.scanned").getString()
                + ":" + s.totalScannedChunks.get()
                + "  " + Text.translatable("chunkscanner.gui.errors").getString()
                + ":" + s.totalErrors.get()
                + "  " + Text.translatable("chunkscanner.gui.found").getString()
                + ":" + s.totalFoundChunks.get();
        if (s.paused) numText += "  [⏸]";
        context.drawTextWithShadow(textRenderer,
                Text.literal(numText).formatted(Formatting.DARK_GRAY),
                x, numY, 0xFFFFFF);

        // 检测悬停（排除右侧按钮区域，防止与按钮 hover 冲突）
        if (GuiUtil.isInRect(mouseX, mouseY, x, y, SCROLLBAR_X_OFFSET - 32, LIST_ITEM_HEIGHT)) {
            hoveredSessionIdx = listIdx;
        }
    }

    /* ==================== 滚动条拖拽 ==================== */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int centerX = this.width / 2;
            int leftX = centerX - WIDTH / 2;

            List<ScanSession> list = new ArrayList<>(scanner.getActiveSessions());

            // 滚动条交互
            if (sessionPanel.handleClick(mouseX, mouseY, list.size())) {
                return true;
            }

            // 检查列表项按钮
            int listTop = SEPARATOR_Y + LIST_TOP_OFFSET;
            int maxVisible = sessionPanel.getMaxVisible(list.size());
            for (int i = 0; i < maxVisible; i++) {
                int idx = sessionPanel.getOffset() + i;
                if (idx >= list.size()) break;
                ScanSession s = list.get(idx);
                int y = listTop + i * LIST_ITEM_HEIGHT;
                int btnRight = leftX + SCROLLBAR_X_OFFSET - 4;
                int leftBtnLeft = btnRight - 56;
                int leftBtnRight = btnRight - 30;
                int rightBtnLeft = btnRight - 28;
                int rightBtnRight = btnRight;

                // 暂停/继续按钮（左半部分）
                if (mouseX >= leftBtnLeft && mouseX <= leftBtnRight
                        && mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT) {
                    if (s.paused) {
                        doResume(s.scanId);
                    } else {
                        doPause(s.scanId);
                    }
                    return true;
                }

                // 停止按钮（右半部分）
                if (mouseX >= rightBtnLeft && mouseX <= rightBtnRight
                        && mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT) {
                    doStop(s.scanId);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        List<ScanSession> list = new ArrayList<>(scanner.getActiveSessions());
        if (sessionPanel.handleDrag(mouseY, list.size())) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            sessionPanel.endDrag();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        List<ScanSession> list = new ArrayList<>(scanner.getActiveSessions());
        sessionPanel.handleScroll(amount, list.size());
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (idField != null && idField.isFocused()) {
            if (idField.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (idField != null && idField.isFocused()) {
            return idField.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        if (idField != null) idField.tick();
    }
}
