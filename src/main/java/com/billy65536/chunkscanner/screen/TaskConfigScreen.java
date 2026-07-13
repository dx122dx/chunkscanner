package com.billy65536.chunkscanner.screen;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.chunkscanner.gui.PlaceholderTextField;
import com.billy65536.chunkscanner.gui.ScrollManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务配置对话框。
 * 允许用户选择分析器、设置扫描 ID，并自定义任务参数（或留空使用默认值）。
 *
 * 布局：
 *   ┌──────────────────────────────────┐
 *   │ [<] [id___] [分析器▼]  [创建]    │
 *   │  ───────────────────────────────  │
 *   │  ┌ 可滚动配置区域 ──────────────┐  │
 *   │  │ revisit (秒):  [3600]        │  │
 *   │  │ tasks:         [16]          │  │
 *   │  │ ...                          │  │
 *   │  └──────────────────────────────┘  │
 *   └──────────────────────────────────┘
 *
 * 所有字段的默认值以灰色提示文字显示在输入框内。
 * 用户输入内容后灰色提示消失，文字变为白色。
 * 配置区域支持垂直滚动，方便未来分析器扩展专用参数。
 */
public class TaskConfigScreen extends Screen {

    private final Screen parent;
    private final ChunkScanner scanner;

    /** 编辑模式：为 true 时修改已有任务的配置，不创建新任务。 */
    private final boolean editMode;
    /** 编辑模式下的目标会话。 */
    private final ScanSession editSession;
    /** 编辑模式下的扫描名称文本。 */
    private Text scanNameText;

    /** 顶部控件 */
    private PlaceholderTextField idField;
    private ButtonWidget analyzerButton;
    private ButtonWidget createButton;

    /** 底部按钮 */
    private ButtonWidget cancelButton;
    private ButtonWidget applyButton;

    /** 分析器列表 */
    private List<ChunkAnalyzer> analyzerList;
    private int selectedAnalyzerIdx = 0;

    /** 默认扫描 ID（当前时间戳） */
    private String defaultScanId;

    /** 配置字段信息 */
    private static class FieldInfo {
        final String labelKey;
        final PlaceholderTextField widget;

        FieldInfo(String labelKey, PlaceholderTextField widget) {
            this.labelKey = labelKey;
            this.widget = widget;
        }
    }

    private final List<FieldInfo> fields = new ArrayList<>();

    /** 当前配置（用于预填充） */
    private final TaskConfig existingConfig;
    private final ChunkScannerConfig globalConfig;

    /** 默认值（用于灰色提示） */
    private final String[] defaults;

    // ==================== 布局常量 ====================

    private static final int DIALOG_WIDTH = 280;
    private static final int TOP_BAR_Y = 8;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_SPACING = 24;
    private static final int LABEL_X_OFFSET = 2;
    private static final int FIELD_X_OFFSET = 110;
    private static final int FIELD_WIDTH = 160;
    private static final int CONFIG_TOP = 34;

    // ==================== 滚动 ====================

    private final ScrollManager scrollManager = new ScrollManager();
    /** 内容总数 = 字段行数 */
    private int totalRows = 0;
    /** 可见行数 */
    private int maxVisibleRows = 0;
    /** 可视区域边界（屏幕坐标，init 时计算） */
    private int scrollTop, scrollRight, scrollBottom;
    /** 所有字段的绝对基准 Y（屏幕坐标），等于 scrollTop。此值固定不变。 */
    private int fieldsBaseY;

    // ==================== 构造与初始化 ====================

    public TaskConfigScreen(Screen parent, ChunkScanner scanner, String initialAnalyzerId, String initialScanId) {
        this(parent, scanner, initialAnalyzerId, initialScanId, null);
    }

    public TaskConfigScreen(Screen parent, ChunkScanner scanner,
                            String initialAnalyzerId, String initialScanId,
                            TaskConfig existingConfig) {
        super(Text.translatable("chunkscanner.task_config.title"));
        this.parent = parent;
        this.scanner = scanner;
        this.editMode = false;
        this.editSession = null;
        this.existingConfig = existingConfig;
        this.globalConfig = scanner.getConfig();

        // 分析器列表及初始选中
        this.analyzerList = new ArrayList<>(AnalyzerRegistry.getAll());
        this.selectedAnalyzerIdx = 0;
        if (initialAnalyzerId != null) {
            for (int i = 0; i < analyzerList.size(); i++) {
                if (analyzerList.get(i).getId().equals(initialAnalyzerId)) {
                    selectedAnalyzerIdx = i;
                    break;
                }
            }
        }

        // 默认扫描 ID
        this.defaultScanId = (initialScanId != null && !initialScanId.isEmpty())
                ? initialScanId : String.valueOf(System.currentTimeMillis() / 1000);

        this.defaults = buildDefaults();
    }

    /**
     * 编辑模式构造器：打开已有任务的配置页面。
     */
    public TaskConfigScreen(Screen parent, ChunkScanner scanner, ScanSession session) {
        super(Text.translatable("chunkscanner.task_config.title"));
        this.parent = parent;
        this.scanner = scanner;
        this.editMode = true;
        this.editSession = session;
        this.existingConfig = session.getTaskConfig();
        this.globalConfig = scanner.getConfig();

        // 编辑模式下不需要分析器列表，但为了兼容性保留空列表
        this.analyzerList = new ArrayList<>();

        // 扫描名称
        String analyzerName = session.analyzer.getName().getString();
        this.scanNameText = Text.literal("[" + analyzerName + "] " + session.scanId)
                .formatted(Formatting.YELLOW);
        this.defaultScanId = session.scanId;

        this.defaults = buildDefaults();
    }

    private String[] buildDefaults() {
        return new String[]{
                String.valueOf(globalConfig.minRevisitIntervalSec),
                String.valueOf(globalConfig.maxTasksPerTick),
                String.valueOf(globalConfig.initialTasksPerTick),
                String.valueOf(globalConfig.targetTickNs),
                String.valueOf(globalConfig.flushIntervalTicks),
                String.valueOf(globalConfig.workerThreads),
                String.valueOf(globalConfig.scanRadiusMultiplier),
                globalConfig.waypointName,
                globalConfig.waypointInitials,
                globalConfig.waypointGroup
        };
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int leftX = centerX - DIALOG_WIDTH / 2;

        // 可视区域边界
        scrollTop = CONFIG_TOP;
        scrollRight = leftX + DIALOG_WIDTH;
        // 编辑模式底部按钮需要留空间
        scrollBottom = editMode ? this.height - 36 : this.height - 8;

        // 字段的绝对基准 Y
        fieldsBaseY = scrollTop;

        fields.clear();

        // ==================== 顶部栏 ====================

        // 返回按钮（两种模式共用，同 DB 内页样式）
        addDrawableChild(ButtonWidget.builder(
                Text.literal("<").formatted(Formatting.WHITE),
                btn -> doBack())
                .dimensions(leftX + 2, TOP_BAR_Y, 18, 20).build());

        if (!editMode) {
            // 创建模式：ID 输入框 + 分析器选择按钮 + 创建按钮

            // ID 输入框（使用 placeholder 显示默认值）
            idField = new PlaceholderTextField(textRenderer, leftX + 22, TOP_BAR_Y, 144, 20,
                    defaultScanId);
            idField.setMaxLength(64);
            addDrawableChild(idField);

            // 分析器选择按钮
            analyzerButton = ButtonWidget.builder(getAnalyzerButtonText(), btn -> {
                if (!analyzerList.isEmpty()) {
                    selectedAnalyzerIdx = (selectedAnalyzerIdx + 1) % analyzerList.size();
                    btn.setMessage(getAnalyzerButtonText());
                }
            }).dimensions(leftX + 168, TOP_BAR_Y, 64, 20).build();
            addDrawableChild(analyzerButton);

            // 创建按钮（右对齐，确保不超过 DIALOG_WIDTH）
            createButton = ButtonWidget.builder(
                    Text.translatable("chunkscanner.gui.create"),
                    btn -> doStart())
                    .dimensions(leftX + DIALOG_WIDTH - 46, TOP_BAR_Y, 44, 20).build();
            addDrawableChild(createButton);
        }

        // ==================== 底部按钮（仅编辑模式） ====================

        if (editMode) {
            int bottomY = this.height - 30;
            cancelButton = ButtonWidget.builder(
                    Text.translatable("chunkscanner.task_config.cancel"),
                    btn -> doBack())
                    .dimensions(leftX + 4, bottomY, 56, 20).build();
            addDrawableChild(cancelButton);

            applyButton = ButtonWidget.builder(
                    Text.translatable("chunkscanner.task_config.apply"),
                    btn -> doApply())
                    .dimensions(leftX + DIALOG_WIDTH - 60, bottomY, 56, 20).build();
            addDrawableChild(applyButton);
        }

        // ==================== 配置字段 ====================

        String[] labelKeys = {
                "chunkscanner.task_config.field.revisit",
                "chunkscanner.task_config.field.tasks",
                "chunkscanner.task_config.field.initTasks",
                "chunkscanner.task_config.field.targetNs",
                "chunkscanner.task_config.field.flush",
                "chunkscanner.task_config.field.threads",
                "chunkscanner.task_config.field.radius",
                "chunkscanner.task_config.field.wpName",
                "chunkscanner.task_config.field.wpInit",
                "chunkscanner.task_config.field.wpGroup"
        };

        String[] initialValues = getInitialValues();

        int fieldX = leftX + FIELD_X_OFFSET;

        for (int i = 0; i < labelKeys.length; i++) {
            PlaceholderTextField fw = createField(fieldX, 0, FIELD_WIDTH, initialValues[i], defaults[i]);
            fields.add(new FieldInfo(labelKeys[i], fw));
            addDrawableChild(fw);
        }

        totalRows = fields.size();

        // 更新字段位置和可见度
        updateFieldPositions();
    }

    private Text getAnalyzerButtonText() {
        if (analyzerList.isEmpty()) {
            return Text.translatable("chunkscanner.label.none").formatted(Formatting.GRAY);
        }
        ChunkAnalyzer a = analyzerList.get(selectedAnalyzerIdx);
        return a.getName().copy().formatted(Formatting.YELLOW);
    }

    /** 创建带灰色默认值提示的文本字段。 */
    private PlaceholderTextField createField(int x, int y, int width, String initialText, String defaultSuggestion) {
        PlaceholderTextField field = new PlaceholderTextField(textRenderer, x, y, width, FIELD_HEIGHT,
                defaultSuggestion);
        field.setMaxLength(32);
        if (initialText != null && !initialText.isEmpty()) {
            field.setText(initialText);
        }
        return field;
    }

    /** 根据滚动偏移更新所有字段的 Y 位置和可见性。 */
    private void updateFieldPositions() {
        maxVisibleRows = (scrollBottom - scrollTop) / FIELD_SPACING;
        if (maxVisibleRows <= 0) maxVisibleRows = 1;
        scrollManager.clamp(totalRows, maxVisibleRows);

        int offset = scrollManager.getOffset();
        for (int i = 0; i < fields.size(); i++) {
            FieldInfo fi = fields.get(i);
            int rowY = fieldsBaseY + i * FIELD_SPACING - offset * FIELD_SPACING;
            fi.widget.setY(rowY);
            // 将完全不可见的字段移到很远的地方，避免它们捕获点击
            boolean visible = i >= offset && i < offset + maxVisibleRows
                    && rowY + FIELD_HEIGHT > scrollTop && rowY < scrollBottom;
            fi.widget.visible = visible;
        }
    }

    private String[] getInitialValues() {
        if (existingConfig != null) {
            return new String[]{
                    existingConfig.minRevisitIntervalSec != null ? String.valueOf(existingConfig.minRevisitIntervalSec) : "",
                    existingConfig.maxTasksPerTick != null ? String.valueOf(existingConfig.maxTasksPerTick) : "",
                    existingConfig.initialTasksPerTick != null ? String.valueOf(existingConfig.initialTasksPerTick) : "",
                    existingConfig.targetTickNs != null ? String.valueOf(existingConfig.targetTickNs) : "",
                    existingConfig.flushIntervalTicks != null ? String.valueOf(existingConfig.flushIntervalTicks) : "",
                    existingConfig.workerThreads != null ? String.valueOf(existingConfig.workerThreads) : "",
                    existingConfig.scanRadiusMultiplier != null ? String.valueOf(existingConfig.scanRadiusMultiplier) : "",
                    existingConfig.waypointName != null ? existingConfig.waypointName : "",
                    existingConfig.waypointInitials != null ? existingConfig.waypointInitials : "",
                    existingConfig.waypointGroup != null ? existingConfig.waypointGroup : ""
            };
        }
        return new String[]{"", "", "", "", "", "", "", "", "", ""};
    }

    // ==================== 渲染 ====================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int leftX = centerX - DIALOG_WIDTH / 2;

        // --- 分隔线 ---
        int sepY = CONFIG_TOP - 2;
        context.drawHorizontalLine(leftX, leftX + DIALOG_WIDTH, sepY, 0xFF555555);

        // --- 编辑模式：显示扫描名称 ---
        if (editMode && scanNameText != null) {
            int nameY = TOP_BAR_Y + (FIELD_HEIGHT - textRenderer.fontHeight) / 2;
            context.drawTextWithShadow(textRenderer, scanNameText,
                    leftX + 24, nameY, 0xFFFFFF);
        }

        // --- 可滚动配置区域标签 ---
        int offset = scrollManager.getOffset();
        int labelX = leftX + LABEL_X_OFFSET;

        for (int i = 0; i < fields.size(); i++) {
            if (i < offset || i >= offset + maxVisibleRows) continue;
            FieldInfo fi = fields.get(i);
            int rowY = fieldsBaseY + i * FIELD_SPACING - offset * FIELD_SPACING;
            int textY = rowY + (FIELD_HEIGHT - textRenderer.fontHeight) / 2;

            // 渲染标签
            context.drawTextWithShadow(textRenderer,
                    Text.translatable(fi.labelKey).formatted(Formatting.WHITE),
                    labelX, textY, 0xFFFFFF);
        }

        // 渲染所有 widget（顶部按钮 + 配置字段）。字段可见性由 visible 标记控制。
        super.render(context, mouseX, mouseY, delta);

        // --- 滚动条（在可视区域右侧） ---
        int sbX = scrollRight - 6;
        scrollManager.drawVertical(context, sbX, scrollTop, scrollBottom,
                totalRows, maxVisibleRows);
    }

    // ==================== 交互 ====================

    private void doBack() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    private void doStart() {
        TaskConfig config = buildConfig();
        if (config == null) {
            // buildConfig 返回 null 表示存在无效数值输入，取消创建并提示用户
            return;
        }
        String id = idField.getText().trim();
        if (id.isEmpty()) id = defaultScanId;
        if (analyzerList.isEmpty()) return;
        String analyzerId = analyzerList.get(selectedAnalyzerIdx).getId();
        MinecraftClient client = MinecraftClient.getInstance();
        scanner.start(client, analyzerId, id, config);
        client.setScreen(parent);
    }

    /** 编辑模式：应用配置更改到运行中的任务。 */
    private void doApply() {
        TaskConfig config = buildConfig();
        // buildConfig 返回 null 表示所有字段为空 → 清除任务级配置
        if (editSession != null) {
            editSession.updateTaskConfig(config);
        }
        MinecraftClient.getInstance().setScreen(parent);
    }

    private TaskConfig buildConfig() {
        TaskConfig config = new TaskConfig();
        boolean hasValue = false;

        // 按字段索引映射到 TaskConfig
        String[] fieldIds = {"revisit", "tasks", "initTasks", "targetNs", "flush", "threads", "radius",
                "wpName", "wpInit", "wpGroup"};
        for (int i = 0; i < fields.size() && i < fieldIds.length; i++) {
            String text = fields.get(i).widget.getText().trim();
            if (text.isEmpty()) continue;
            try {
                switch (fieldIds[i]) {
                    case "revisit" -> { config.minRevisitIntervalSec = Integer.parseInt(text); hasValue = true; }
                    case "tasks"   -> { config.maxTasksPerTick = Integer.parseInt(text); hasValue = true; }
                    case "initTasks" -> { config.initialTasksPerTick = Integer.parseInt(text); hasValue = true; }
                    case "targetNs" -> { config.targetTickNs = Long.parseLong(text); hasValue = true; }
                    case "flush"   -> { config.flushIntervalTicks = Integer.parseInt(text); hasValue = true; }
                    case "threads"  -> { config.workerThreads = Integer.parseInt(text); hasValue = true; }
                    case "radius"  -> { config.scanRadiusMultiplier = Double.parseDouble(text); hasValue = true; }
                    case "wpName"  -> { config.waypointName = text; hasValue = true; }
                    case "wpInit"  -> { config.waypointInitials = text; hasValue = true; }
                    case "wpGroup" -> { config.waypointGroup = text; hasValue = true; }
                }
            } catch (NumberFormatException e) {
                ChunkScannerMod.LOGGER.warn("TaskConfig: invalid value for field '{}': '{}'", fieldIds[i], text);
                return null; // 用户输入了无效数值，放弃所有配置
            }
        }

        return hasValue ? config : null;
    }

    // ==================== 鼠标事件 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 滚动条点击
            int sbX = scrollRight - 6;
            if (scrollManager.handleVerticalClick(mouseX, mouseY, sbX, scrollTop, scrollBottom,
                    totalRows, maxVisibleRows)) {
                updateFieldPositions();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (scrollManager.handleVerticalDrag(mouseY, scrollTop, scrollBottom,
                totalRows, maxVisibleRows)) {
            updateFieldPositions();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            scrollManager.endDrag();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollManager.handleVerticalScroll(amount, totalRows, maxVisibleRows);
        updateFieldPositions();
        return true;
    }

    // ==================== 键盘事件 ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 将按键转发给 ID 输入框
        if (idField != null && idField.isFocused() && idField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // 将按键转发给所有可见字段（让当前聚焦字段处理）
        for (FieldInfo fi : fields) {
            if (fi.widget.isFocused() && fi.widget.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (idField != null && idField.isFocused() && idField.charTyped(chr, modifiers)) {
            return true;
        }
        for (FieldInfo fi : fields) {
            if (fi.widget.isFocused() && fi.widget.charTyped(chr, modifiers)) {
                return true;
            }
        }
        return super.charTyped(chr, modifiers);
    }

    // ==================== 生命周期 ====================

    @Override
    public void tick() {
        if (idField != null) idField.tick();
        for (FieldInfo fi : fields) {
            fi.widget.tick();
        }
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
