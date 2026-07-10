package com.billy65536.chunkscanner.screen;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.components.db.DbFileUtil;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.gui.KvPageRenderer;
import com.billy65536.chunkscanner.gui.ScrollManager;
import com.billy65536.chunkscanner.gui.ScrollableListPanel;
import com.billy65536.chunkscanner.integration.XaeroWaypointHelper;

/**
 * 数据库浏览器 GUI。
 *
 * 两个页面：
 *   1. 文件列表 — 浏览所有已保存的扫描数据库
 *   2. KV 视图  — 查看数据库内容（原始字节或特化视图）
 */
public class DatabaseScreen extends Screen {

    private static final int WIDTH = 340;
    private static final int ITEM_HEIGHT = 20;
    private static final int SCROLLBAR_X_OFFSET = WIDTH - 6; // relative to left margin
    private static final int SCROLLBAR_RESERVE = 8; // 为交叉滚动条预留的空间

    private final String initialScanId;

    // ==================== 文件列表页 ====================

    private List<DbFileUtil.FileMeta> dbFiles;
    private final ScrollableListPanel fileListPanel = new ScrollableListPanel(ITEM_HEIGHT);
    /** 文件列表中被悬停的行索引（渲染时填充，用于 tooltip）。 */
    private int hoveredFileIdx = -1;

    // ==================== KV 视图页 ====================

    private BinaryChunkDb openedDb;
    private DbViewProvider currentView;
    private boolean showingKvView = false;

    private final ScrollableListPanel kvPanel = new ScrollableListPanel(ITEM_HEIGHT);
    private final ScrollManager kvHScroll = new ScrollManager();

    /** 当前页面渲染器（封装原始 KV 视图或特化视图的渲染逻辑与数据）。 */
    private KvPageRenderer pageRenderer;

    /** 悬停行索引（渲染时填充，用于 tooltip）。 */
    private int hoveredKvIdx = -1;

    /** 悬停列索引（特化视图下渲染时填充，用于位置点击）。 */
    private int hoveredKvCol = -1;

    // ==================== 视图类型选择 ====================

    private List<DbViewProvider.Type> viewTypes;
    private int selectedViewTypeIdx = 0;
    private ButtonWidget providerButton;
    private ButtonWidget filterButton;

    /** 从筛选界面返回后需要重建渲染器。 */
    private boolean pendingRebuild = false;

    // ==================== 构造与初始化 ====================

    public DatabaseScreen(String scanId) {
        super(Text.translatable("chunkscanner.gui.database.title"));
        this.initialScanId = scanId;
    }

    @Override
    protected void init() {
        super.init();

        // 从筛选界面返回时，Minecraft 会重新调用 init()，必须保留现有状态
        if (pendingRebuild && openedDb != null) {
            // 重建按钮以恢复 UI（providerButton、filterButton 等已被清除）
            rebuildKvButtons();
            return;
        }

        showingKvView = false;
        openedDb = null;
        currentView = null;
        pageRenderer = null;
        fileListPanel.endDrag();
        kvPanel.endDrag();
        kvHScroll.endDrag();

        viewTypes = new ArrayList<>(DbViewProvider.Registry.getAll());
        selectedViewTypeIdx = 0;

        scanDbFiles();

        // 自动打开指定数据库
        if (initialScanId != null && !initialScanId.isEmpty()) {
            for (DbFileUtil.FileMeta m : dbFiles) {
                if (m.scanId().equals(initialScanId)) {
                    openDatabase(m);
                    return;
                }
            }
        }

        rebuildFileListButtons();
    }

    // ==================== 文件列表扫描 ====================

    private void scanDbFiles() {
        dbFiles = DbFileUtil.listAllDbFiles();
    }

    // ==================== 打开/关闭数据库 ====================

    private void openDatabase(DbFileUtil.FileMeta meta) {
        if (openedDb != null) {
            openedDb.close();
        }
        ChunkScannerMod.LOGGER.debug("Opening database: scanId={} analyzer={}", meta.scanId(), meta.analyzerName());
        Path fileDir = meta.filePath() != null ? meta.filePath().getParent() : null;
        BinaryChunkDb db = new BinaryChunkDb(meta.scanId(), meta.analyzerName(), true, fileDir);
        openedDb = db;
        try {
            openedDb.open();
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to open database: {}", e.getMessage());
            openedDb = null;
            return;
        }

        rebuildPageRenderer(true);
        showingKvView = true;
        kvPanel.setOffset(0);
        kvHScroll.setOffset(0);
        rebuildKvButtons();
    }

    private void closeOpenedDb() {
        if (openedDb != null) {
            openedDb.close();
            openedDb = null;
            currentView = null;
        }
    }

    // ==================== 视图提供者 ====================

    /**
     * 统一重建页面渲染器，将 view provider 创建、数据加载和渲染器构造合并。
     * 替代了旧版的 refreshCurrentView() + loadViewData()。
     *
     * @param forceRecreate 是否强制重新创建视图提供者（筛选返回时不需重建，保留筛选状态）
     */
    private void rebuildPageRenderer(boolean forceRecreate) {
        if (openedDb == null) {
            pageRenderer = null;
            return;
        }

        // —— 选择/创建视图提供者 ——
        if (viewTypes.isEmpty()) {
            currentView = openedDb;
        } else {
            DbViewProvider.Type selectedType = viewTypes.get(selectedViewTypeIdx);
            if (forceRecreate || currentView == null) {
                try {
                    currentView = selectedType.create(openedDb);
                } catch (Exception e) {
                    ChunkScannerMod.LOGGER.warn("Failed to create view provider '{}': {}", selectedType.getId(), e.getMessage());
                    currentView = null;
                }
                if (currentView == null) {
                    currentView = openedDb;
                }
            }
        }

        // —— 加载公共数据 ——
        List<ChunkDb.ChunkMeta> metas;
        try {
            metas = currentView.getAllChunkMetas();
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to load chunk metas: {}", e.getMessage());
            metas = List.of();
        }
        int metaCount = metas != null ? metas.size() : 0;

        // —— 判断视图类型并构造渲染器 ——
        boolean specialized = false;
        try {
            specialized = currentView.isSpecialized();
        } catch (Exception e) {
            // 视为非特化视图
        }

        if (specialized) {
            List<String[]> rows;
            String[] headers;
            try {
                rows = currentView.getSpecializedRows();
                headers = currentView.getSpecializedHeaders();
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("Failed to get specialized data: {}", e.getMessage());
                rows = List.of();
                headers = new String[0];
            }
            pageRenderer = new KvPageRenderer.Specialized(textRenderer,
                    rows != null ? rows : List.of(),
                    headers != null ? headers : new String[0],
                    metaCount);
        } else {
            List<ChunkDb.Entry> entries;
            try {
                entries = currentView.getAllEntries();
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("Failed to load entries: {}", e.getMessage());
                entries = List.of();
            }
            pageRenderer = new KvPageRenderer.Entries(textRenderer,
                    entries != null ? entries : List.of(),
                    metaCount);
        }
    }

    private void cycleViewType() {
        if (viewTypes.isEmpty()) return;
        selectedViewTypeIdx = (selectedViewTypeIdx + 1) % viewTypes.size();
        ChunkScannerMod.LOGGER.debug("Cycled to view type: {}", viewTypes.get(selectedViewTypeIdx).getId());
        rebuildPageRenderer(true);
        kvPanel.setOffset(0);
        kvHScroll.setOffset(0);
        rebuildKvButtons();
    }

    private void openFilter() {
        if (currentView == null || !currentView.supportsFilter()) return;
        Screen filterScreen = currentView.createFilterScreen(this);
        if (filterScreen != null) {
            pendingRebuild = true;
            MinecraftClient.getInstance().setScreen(filterScreen);
        }
    }

    private boolean isCurrentTypeApplicable() {
        if (openedDb == null || viewTypes.isEmpty()) return true;
        DbViewProvider.Type selectedType = viewTypes.get(selectedViewTypeIdx);
        Set<String> applicable = selectedType.applicableAnalyzers();
        if (applicable.isEmpty()) return true;
        return applicable.contains(openedDb.analyzerName());
    }

    private boolean isUniversallyApplicable() {
        if (viewTypes.isEmpty()) return true;
        return viewTypes.get(selectedViewTypeIdx).applicableAnalyzers().isEmpty();
    }

    private Formatting getProviderColor() {
        if (!isCurrentTypeApplicable()) return Formatting.RED;
        if (isUniversallyApplicable()) return Formatting.YELLOW;
        return Formatting.GREEN;
    }

    // ==================== 按钮重建 ====================

    private void rebuildFileListButtons() {
        clearChildren();
        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;

        // 任务视图按钮（与 ChunkScannerScreen 的 "数据库" 按钮对齐：y=8）
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.database.task_view"),
                btn -> backToMainGui())
                .dimensions(leftX + 4, 8, 56, 16).build());

        int bottomY = this.height - 30;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.database.open_folder"),
                btn -> openFolder())
                .dimensions(leftX + 4, bottomY, WIDTH - 8, 20).build());
    }

    private void rebuildKvButtons() {
        clearChildren();
        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;

        // 返回按钮
        addDrawableChild(ButtonWidget.builder(
                Text.literal("<").formatted(Formatting.WHITE),
                btn -> backToList())
                .dimensions(leftX + 4, 8, 20, 16).build());

        // 视图类型选择按钮 + 筛选按钮
        int btnRight = leftX + WIDTH;
        filterButton = null;

        if (!viewTypes.isEmpty()) {
            DbViewProvider.Type vt = viewTypes.get(selectedViewTypeIdx);
            // 筛选按钮 "..."（在视图选择器右侧）
            if (currentView != null && currentView.supportsFilter()) {
                filterButton = ButtonWidget.builder(
                        Text.literal("...").formatted(
                                currentView.isFilterActive() ? Formatting.GREEN : Formatting.GRAY),
                        btn -> openFilter())
                        .dimensions(btnRight - 18, 8, 16, 16).build();
                addDrawableChild(filterButton);

                // 视图选择器左移，给筛选按钮留空间
                providerButton = ButtonWidget.builder(
                        Text.literal(vt.getName()).formatted(getProviderColor()),
                        btn -> cycleViewType())
                        .dimensions(btnRight - 88, 8, 68, 16).build();
            } else {
                providerButton = ButtonWidget.builder(
                        Text.literal(vt.getName()).formatted(getProviderColor()),
                        btn -> cycleViewType())
                        .dimensions(btnRight - 84, 8, 70, 16).build();
            }
            addDrawableChild(providerButton);
        }

        int bottomY = this.height - 30;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.database.save_as"),
                btn -> saveAs())
                .dimensions(leftX + 4, bottomY, WIDTH - 8, 20).build());
    }

    // ==================== 导航 ====================

    private void backToList() {
        closeOpenedDb();
        showingKvView = false;
        pageRenderer = null;
        fileListPanel.endDrag();
        kvPanel.endDrag();
        kvHScroll.endDrag();
        viewTypes = new ArrayList<>(DbViewProvider.Registry.getAll());
        selectedViewTypeIdx = 0;
        clearChildren();
        rebuildFileListButtons();
    }

    private void backToMainGui() {
        closeOpenedDb();
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ChunkScannerScreen(ChunkScannerMod.getScanner()));
    }

    private void openFolder() {
        Path dir = BinaryChunkDb.getDbRoot();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to open folder: {}", e.getMessage());
        }
    }

    private void saveAs() {
        if (openedDb == null) return;
        Path dir = BinaryChunkDb.getDbRoot();

        // 在后台线程调用 JFileChooser，避免 AWT 模态对话框阻塞 GL 渲染线程
        new Thread(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(Text.translatable("chunkscanner.gui.database.save_as").getString());
            chooser.setSelectedFile(new File(openedDb.scanId() + "_export.txt"));
            chooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));

            // 设置默认目录
            if (Files.exists(dir)) {
                chooser.setCurrentDirectory(dir.toFile());
            }

            int returnVal = chooser.showSaveDialog(null);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                Path outPath = chooser.getSelectedFile().toPath();
                // 确保扩展名为 .txt
                if (!outPath.getFileName().toString().contains(".")) {
                    outPath = outPath.resolveSibling(outPath.getFileName() + ".txt");
                }
                try {
                    Files.createDirectories(outPath.getParent());
                    exportToFile(outPath);
                } catch (Exception e) {
                    ChunkScannerMod.LOGGER.warn("Failed to export database: {}", e.getMessage());
                }
            }
        }, "ChunkScanner-FileSave").start();
    }

    private void exportToFile(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (pageRenderer != null) {
            pageRenderer.export(sb);
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    // ==================== 主渲染 ====================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 从筛选界面返回后重建视图（不重建 provider，保留筛选状态）
        if (pendingRebuild && showingKvView) {
            rebuildPageRenderer(false);
            rebuildKvButtons();
            pendingRebuild = false;
        }

        renderBackground(context);
        int centerX = this.width / 2;

        // 重置悬停状态
        hoveredFileIdx = -1;
        hoveredKvIdx = -1;
        hoveredKvCol = -1;

        if (showingKvView) {
            renderKvView(context, mouseX, mouseY, centerX);
        } else {
            renderFileList(context, mouseX, mouseY, centerX);
        }

        super.render(context, mouseX, mouseY, delta);

        // drawTooltip 必须在 super.render() 之后调用（在按钮之上渲染）

        // provider 按钮 tooltip
        if (showingKvView && providerButton != null && providerButton.isMouseOver(mouseX, mouseY)
                && !viewTypes.isEmpty()) {
            DbViewProvider.Type vt = viewTypes.get(selectedViewTypeIdx);
            context.drawTooltip(textRenderer,
                    Text.literal(vt.getDescription()).formatted(getProviderColor()),
                    mouseX, mouseY);
        }

        // 筛选按钮 tooltip
        if (showingKvView && filterButton != null && filterButton.isMouseOver(mouseX, mouseY)) {
            Formatting color = currentView != null && currentView.isFilterActive()
                    ? Formatting.GREEN : Formatting.GRAY;
            context.drawTooltip(textRenderer,
                    Text.translatable("chunkscanner.gui.filter.tooltip").formatted(color),
                    mouseX, mouseY);
        }

        // 文件列表悬停 — 显示数据库路径
        if (!showingKvView && hoveredFileIdx >= 0 && hoveredFileIdx < dbFiles.size()) {
            DbFileUtil.FileMeta meta = dbFiles.get(hoveredFileIdx);
            Path filePath = meta.filePath();
            context.drawTooltip(textRenderer,
                    Text.literal(filePath != null ? filePath.toAbsolutePath().toString() : meta.scanId()).formatted(Formatting.GRAY),
                    mouseX, mouseY);
        }

        // KV 视图悬停 — 位置列 tooltip
        if (showingKvView && hoveredKvIdx >= 0 && hoveredKvCol >= 0
                && pageRenderer instanceof KvPageRenderer.Specialized spec) {
            if (spec.isPositionColumn(hoveredKvCol)) {
                String key = XaeroWaypointHelper.isAvailable()
                        ? "chunkscanner.tooltip.create_waypoint"
                        : "chunkscanner.tooltip.print_coords";
                context.drawTooltip(textRenderer,
                        Text.translatable(key).formatted(Formatting.AQUA),
                        mouseX, mouseY);
            }
        }
        if (showingKvView && hoveredKvIdx >= 0 && pageRenderer != null) {
            List<Text> tooltip = pageRenderer.getTooltip(hoveredKvIdx);
            if (tooltip != null) {
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
            }
        }
    }

    // ==================== 文件列表渲染 ====================

    private void renderFileList(DrawContext context, int mouseX, int mouseY, int centerX) {
        int leftX = centerX - WIDTH / 2;
        int x = leftX + 4;

        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.title")
                        .formatted(Formatting.GOLD, Formatting.BOLD),
                centerX, 12, 0xFFFFFF);

        int headerY = 30;
        context.drawTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.file").formatted(Formatting.GRAY),
                x, headerY, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.size").formatted(Formatting.GRAY),
                x + 170, headerY, 0xFFFFFF);

        context.drawHorizontalLine(x, x + WIDTH - 8, headerY + 12, 0xFF555555);

        int listTop = headerY + 16;
        int listBottom = this.height - 30;
        int sbX = leftX + SCROLLBAR_X_OFFSET;
        fileListPanel.setBounds(listTop, listBottom, sbX);
        int maxVisible = fileListPanel.clamp(dbFiles.size());

        if (dbFiles.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("chunkscanner.gui.database.no_files").formatted(Formatting.GRAY),
                    centerX, listTop + 20, 0xFFFFFF);
            return;
        }

        for (int i = 0; i < maxVisible; i++) {
            int idx = fileListPanel.getOffset() + i;
            if (idx >= dbFiles.size()) break;
            DbFileUtil.FileMeta meta = dbFiles.get(idx);
            int rowY = listTop + i * ITEM_HEIGHT;

            // 行悬停检测（排除右侧按钮区域）
            boolean hovered = GuiUtil.isInRect(mouseX, mouseY, x, rowY, WIDTH - 52, ITEM_HEIGHT);

            // 悬停背景高亮
            if (hovered) {
                hoveredFileIdx = idx;
                context.fill(x, rowY, x + WIDTH - 52, rowY + ITEM_HEIGHT, 0x33FFFFAA);
            }

            int color = hovered ? 0xFFFF55 : 0xFFFFFF;

            String label = meta.scanId();
            String aName = meta.analyzerName();
            if (aName != null && !aName.isEmpty()) {
                label = label + " [" + GuiUtil.getAnalyzerDisplayName(aName) + "]";
            }
            context.drawTextWithShadow(textRenderer,
                    Text.literal(label).formatted(Formatting.YELLOW), x, rowY, color);
            context.drawTextWithShadow(textRenderer,
                    Text.literal(GuiUtil.formatSize(meta.fileSize())).formatted(Formatting.GRAY),
                    x + 160, rowY, color);

            // 右侧按钮区域（横向布局：恢复在左，删除在右，同 ChunkScannerScreen）
            int btnRight = x + WIDTH - 8;
            int rebootBtnRight = btnRight - 20;
            int rebootBtnLeft = btnRight - 38;
            int delBtnLeft = btnRight - 18;
            int delBtnRight = btnRight;

            // 恢复扫描按钮 [↺]（左）
            boolean rebootHover = mouseX >= rebootBtnLeft && mouseX <= rebootBtnRight
                    && mouseY >= rowY && mouseY < rowY + ITEM_HEIGHT;
            context.drawTextWithShadow(textRenderer,
                    Text.literal("[↺]").formatted(rebootHover ? Formatting.AQUA : Formatting.DARK_AQUA),
                    rebootBtnLeft + 2, rowY, 0xFFFFFF);

            // 删除按钮 [✕]（右）
            boolean delHover = mouseX >= delBtnLeft && mouseX <= delBtnRight
                    && mouseY >= rowY && mouseY < rowY + ITEM_HEIGHT;
            context.drawTextWithShadow(textRenderer,
                    Text.literal("[✕]").formatted(delHover ? Formatting.RED : Formatting.DARK_RED),
                    delBtnLeft + 2, rowY, 0xFFFFFF);
        }

        // 垂直滚动条
        fileListPanel.drawScrollbar(context, dbFiles.size());
    }

    // ==================== KV 视图渲染 ====================

    private void renderKvView(DrawContext context, int mouseX, int mouseY, int centerX) {
        int leftX = centerX - WIDTH / 2;

        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.kv_title",
                        openedDb != null ? openedDb.scanId() : "?")
                        .formatted(Formatting.GOLD, Formatting.BOLD),
                centerX, 12, 0xFFFFFF);

        int margin = 4;
        int infoY = 28;

        int kvSize = pageRenderer != null ? pageRenderer.getItemCount() : 0;
        int metaSize = pageRenderer != null ? pageRenderer.getMetaCount() : 0;
        String aName = openedDb != null ? GuiUtil.getAnalyzerDisplayName(openedDb.analyzerName()) : "";
        String vName = viewTypes.isEmpty() ? "" : viewTypes.get(selectedViewTypeIdx).getName();

        context.drawTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.records", kvSize, metaSize, aName, vName)
                        .formatted(Formatting.GRAY),
                margin, infoY, 0xFFFFFF);

        context.drawHorizontalLine(leftX, leftX + WIDTH, infoY + 12, 0xFF555555);

        int listTop = infoY + 16;
        int listBottomV = this.height - 34 - SCROLLBAR_RESERVE;

        // 特化视图：渲染表头，数据区域下移
        if (pageRenderer instanceof KvPageRenderer.Specialized spec) {
            int headerH = spec.renderHeader(context, listTop, margin, kvHScroll.getOffset());
            listTop += headerH;
            listBottomV = Math.min(listBottomV, this.height - 34 - SCROLLBAR_RESERVE);
        }

        // 设置面板边界并 clamp
        kvPanel.setBounds(listTop, listBottomV, this.width - 6);
        int maxVisible = kvPanel.clamp(kvSize);

        if (kvSize == 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("chunkscanner.gui.database.no_kv").formatted(Formatting.GRAY),
                    centerX, listTop + 10, 0xFFFFFF);
            return;
        }

        // 委托渲染器逐行渲染
        for (int i = 0; i < maxVisible; i++) {
            int idx = kvPanel.getOffset() + i;
            if (idx >= kvSize) break;
            int rowY = listTop + i * ITEM_HEIGHT;
            int hov = pageRenderer.renderRow(context, idx, rowY, margin,
                    kvHScroll.getOffset(), mouseX, mouseY);
            if (hov >= 0) hoveredKvIdx = hov;
        }

        // 捕获特化视图的列级悬停
        if (pageRenderer instanceof KvPageRenderer.Specialized spec) {
            hoveredKvCol = spec.getHoveredCol();
        }

        // 垂直滚动条
        kvPanel.drawScrollbar(context, kvSize);

        // 水平滚动条（铺满全屏，留右侧给垂直滚动条）
        int hLeft = 4;
        int hRight = this.width - 6;
        int visibleW = this.width - 10;
        int totalW = Math.max(computeContentWidth(), visibleW + 1);
        kvHScroll.drawHorizontal(context, this.height - 38, hLeft, hRight, totalW);
    }

    /** 计算当前视图内容的总宽度（像素），用于水平滚动条。 */
    private int computeContentWidth() {
        return pageRenderer != null ? pageRenderer.computeContentWidth() : 0;
    }

    /** 水平内容可见宽度（屏幕像素）。留出左右各约 5px 的边距。 */
    private int horizVisibleWidth() {
        return this.width - 10;
    }

    // ==================== 鼠标交互（点击） ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;

        if (showingKvView) {
            return handleKvClick(mouseX, mouseY, button);
        }

        // --- 文件列表页：滚动条拖拽 ---
        int listTop = 46;
        int listBottom = this.height - 30;
        fileListPanel.setBounds(listTop, listBottom, leftX + SCROLLBAR_X_OFFSET);

        if (fileListPanel.handleClick(mouseX, mouseY, dbFiles.size())) {
            return true;
        }

        // --- 文件列表项按钮 ---
        int x = leftX + 4;
        int maxVisible = fileListPanel.getMaxVisible(dbFiles.size());
        for (int i = 0; i < maxVisible; i++) {
            int idx = fileListPanel.getOffset() + i;
            if (idx >= dbFiles.size()) break;
            DbFileUtil.FileMeta meta = dbFiles.get(idx);
            int rowY = listTop + i * ITEM_HEIGHT;

            int btnRight = x + WIDTH - 8;
            int rebootBtnRight = btnRight - 20;
            int rebootBtnLeft = btnRight - 38;
            int delBtnLeft = btnRight - 18;
            int delBtnRight = btnRight;

            // 恢复扫描按钮 [↺]（左）
            if (mouseX >= rebootBtnLeft && mouseX <= rebootBtnRight
                    && mouseY >= rowY && mouseY < rowY + ITEM_HEIGHT) {
                doRebootScan(meta);
                return true;
            }

            // 删除按钮 [✕]（右）
            if (mouseX >= delBtnLeft && mouseX <= delBtnRight
                    && mouseY >= rowY && mouseY < rowY + ITEM_HEIGHT) {
                confirmDeleteDbFile(meta);
                return true;
            }

            // 打开数据库
            if (GuiUtil.isInRect(mouseX, mouseY, x, rowY, WIDTH - 52, ITEM_HEIGHT)) {
                openDatabase(meta);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleKvClick(double mouseX, double mouseY, int button) {
        int infoY = 28;
        int listTop = infoY + 16;
        int listBottomV = this.height - 34 - SCROLLBAR_RESERVE;
        int kvSize = pageRenderer != null ? pageRenderer.getItemCount() : 0;

        // KV 垂直滚动条
        kvPanel.setBounds(listTop, listBottomV, this.width - 6);
        if (kvPanel.handleClick(mouseX, mouseY, kvSize)) {
            return true;
        }

        // KV 水平滚动条（铺满全屏）
        int hY = this.height - 38;
        int hLeft = 4;
        int hRight = this.width - 6;
        int visibleW = horizVisibleWidth();
        int totalW = Math.max(computeContentWidth(), visibleW + 1);
        if (kvHScroll.handleHorizontalClick(mouseX, mouseY, hY, hLeft, hRight, totalW)) {
            return true;
        }

        // 位置列点击：创建 Xaero 路径点
        if (hoveredKvIdx >= 0 && hoveredKvCol >= 0
                && pageRenderer instanceof KvPageRenderer.Specialized spec
                && spec.isPositionColumn(hoveredKvCol)
                && currentView != null) {
            LocatedPosition pos = currentView.getPositionAt(hoveredKvIdx);
            if (pos != null) {
                ChunkScannerConfig cfg = ChunkScannerMod.CONFIG;
                XaeroWaypointHelper.tryCreateWaypoint(pos,
                        cfg.waypointName, cfg.waypointInitials, cfg.waypointGroup);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!showingKvView) {
            // 文件列表垂直拖拽
            int centerX = this.width / 2;
            int leftX = centerX - WIDTH / 2;
            int listTop = 46;
            int listBottom = this.height - 30;
            fileListPanel.setBounds(listTop, listBottom, leftX + SCROLLBAR_X_OFFSET);
            if (fileListPanel.handleDrag(mouseY, dbFiles.size())) {
                return true;
            }
        } else {
            // KV 垂直拖拽
            int infoY = 28;
            int listTop = infoY + 16;
            int listBottomV = this.height - 34 - SCROLLBAR_RESERVE;
            int kvSize = pageRenderer != null ? pageRenderer.getItemCount() : 0;
            kvPanel.setBounds(listTop, listBottomV, this.width - 6);
            if (kvPanel.handleDrag(mouseY, kvSize)) {
                return true;
            }

            // KV 水平拖拽（铺满全屏）
            int hLeft = 4;
            int hRight = this.width - 6;
            int visibleW = horizVisibleWidth();
            int totalW = Math.max(computeContentWidth(), visibleW + 1);
            if (kvHScroll.handleHorizontalDrag(mouseX, hLeft, hRight, totalW)) {
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            fileListPanel.endDrag();
            kvPanel.endDrag();
            kvHScroll.endDrag();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (showingKvView) {
            return handleKvScroll(amount);
        }

        int centerX = this.width / 2;
        int leftX = centerX - WIDTH / 2;
        int listTop = 46;
        int listBottom = this.height - 30;
        fileListPanel.setBounds(listTop, listBottom, leftX + SCROLLBAR_X_OFFSET);
        fileListPanel.handleScroll(amount, dbFiles.size());
        return true;
    }

    private boolean handleKvScroll(double amount) {
        int infoY = 28;
        int listTop = infoY + 16;
        int listBottomV = this.height - 34 - SCROLLBAR_RESERVE;
        int kvSize = pageRenderer != null ? pageRenderer.getItemCount() : 0;

        if (hasShiftDown()) {
            int visibleW = horizVisibleWidth();
            int totalW = Math.max(computeContentWidth(), visibleW + 1);
            kvHScroll.handleHorizontalScroll(amount * 10, totalW, visibleW);
        } else {
            kvPanel.setBounds(listTop, listBottomV, this.width - 6);
            kvPanel.handleScroll(amount, kvSize);
        }
        return true;
    }

    // ==================== 操作 ====================

    private void confirmDeleteDbFile(DbFileUtil.FileMeta meta) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(
                confirmed -> {
                    client.setScreen(this);
                    if (confirmed) deleteDbFile(meta);
                },
                Text.translatable("chunkscanner.confirm.delete_db.title"),
                Text.translatable("chunkscanner.confirm.delete_db.message", meta.scanId())
        ));
    }

    private void doRebootScan(DbFileUtil.FileMeta meta) {
        MinecraftClient client = MinecraftClient.getInstance();
        ChunkScanner scanner = ChunkScannerMod.getScanner();
        if (scanner == null || client.player == null || client.world == null) return;

        ChunkAnalyzer analyzer = scanner.getAnalyzer(meta.analyzerName());
        if (analyzer == null) return;

        Path fileDir = meta.filePath() != null ? meta.filePath().getParent() : null;
        BinaryChunkDb existingDb = new BinaryChunkDb(meta.scanId(), meta.analyzerName(), false, fileDir);
        TaskConfig storedConfig = existingDb.getTaskConfig();
        if (storedConfig != null) {
            ChunkScannerMod.LOGGER.info("Restored TaskConfig from DB for '{}': {}", meta.scanId(), storedConfig.toDisplayString());
        }
        scanner.startWithDb(client, meta.scanId(), meta.analyzerName(), storedConfig, existingDb);
    }

    private void deleteDbFile(DbFileUtil.FileMeta meta) {
        try {
            DbFileUtil.deleteDbFile(meta.scanId());
            closeOpenedDb();
            showingKvView = false;
            pageRenderer = null;
            scanDbFiles();
            clearChildren();
            rebuildFileListButtons();
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to delete DB: {}", e.getMessage());
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        closeOpenedDb();
        super.close();
    }
}
