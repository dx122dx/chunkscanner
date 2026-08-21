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
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbManager;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.IDbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.integration.XaeroWaypointHelper;
import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.layout.ILayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;

/**
 * 数据库浏览器 GUI。
 *
 * 两个页面：
 *   1. 文件列表 — 浏览所有已保存的扫描数据库
 *   2. KV 视图  — 查看数据库内容（原始字节或特化视图）
 *
 * 基于 infrastructure 新框架（{@link ScreenContainer} + {@link TableLayout}）：
 * 表格的虚拟滚动 / 双滚动条 / 表头 / 点击分派均由 TableLayout 内置处理。
 */
public class DatabaseScreen extends ScreenContainer {

    /** 内容区宽度（与旧版 DatabaseScreen 保持一致）。 */
    private static final int WIDTH = 340;
    /** 标题区高度（2x 标题 + 金色分隔线之下），与 qab 样板一致。 */
    private static final int HEADER_Y = 28;
    /** 底部操作按钮区预留高度。 */
    private static final int FOOTER_H = 30;
    /** 文件列表操作按钮列宽。 */
    private static final int FILE_ACTION_COL_W = 36;
    /** 底部区域纵向起始（按钮区）。 */
    private static final int BUTTON_Y_OFFSET = FOOTER_H + 4;

    private final String initialScanId;

    // ==================== 文件列表页 ====================

    private List<DbPackage.Info> dbFiles;

    // ==================== KV 视图页 ====================

    private IDbViewProvider openedDb;
    /** 当前打开的数据库包，是访问包内数据的唯一入口，负责其生命周期。 */
    private DbPackage openedPackage;
    private IDbViewProvider currentView;
    private boolean showingKvView = false;

    /** 当前页面渲染器（封装原始 KV 视图或特化视图的渲染逻辑与数据）。 */
    private ILayout layout;

    // ==================== 视图类型选择 ====================

    private List<DbViewProviderRegistry.ITypeDescriptor> viewTypes;
    private int selectedViewTypeIdx = 0;
    private ButtonWidget providerButton;
    private ButtonWidget filterButton;

    /** 从筛选界面返回后需要重建渲染器。 */
    private boolean pendingRebuild = false;

    /** 打开数据库时从 DB 中读取的任务配置（路径点命名等参数），可为 null。 */
    private TaskConfig cachedTaskConfig;

    // ==================== 构造与初始化 ====================

    public DatabaseScreen(String scanId) {
        super(Text.translatable("chunkscanner.gui.database.title"));
        this.initialScanId = scanId;
    }

    @Override
    protected void init() {
        // 从筛选界面返回时，Minecraft 会重新调用 init()，必须保留现有状态
        if (pendingRebuild && openedDb != null) {
            super.init();
            applyLayoutBounds();
            rebuildKvButtons();
            return;
        }

        showingKvView = false;
        closeOpenedDb();
        currentView = null;
        layout = null;

        // 视图列表在打开具体数据库包后按其 adaptorId 过滤得出
        viewTypes = new ArrayList<>();
        selectedViewTypeIdx = 0;

        scanDbFiles();

        // 自动打开指定数据库
        if (initialScanId != null && !initialScanId.isEmpty()) {
            for (DbPackage.Info m : dbFiles) {
                if (m.scanId().equals(initialScanId)) {
                    openDatabase(m);
                    super.init();
                    applyLayoutBounds();
                    return;
                }
            }
        }

        rebuildFileListButtons();
        setLayout(buildFileListLayout());
        super.init();
        applyLayoutBounds();
    }

    // ==================== 文件列表扫描 ====================

    private void scanDbFiles() {
        dbFiles = DbManager.listAll();
    }

    // ==================== 打开/关闭数据库 ====================

    private void openDatabase(DbPackage.Info meta) {
        closeOpenedDb();
        ChunkScannerMod.LOGGER.debug("Opening database: scanId={} analyzer={}", meta.scanId(), meta.analyzerId());
        DbPackage pkg;
        try {
            pkg = DbPackage.open(meta.dir());
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to open database package: {}", e.getMessage());
            return;
        }
        openedPackage = pkg;
        // 视图候选：只保留声明了本包 adaptorId 的视图（一个都没有时回退到 raw 视图）
        viewTypes = new ArrayList<>(DbViewProviderRegistry.forAdaptor(pkg.getAdaptorId()));
        selectedViewTypeIdx = 0;
        try {
            openedDb = createDefaultViewProvider(pkg);
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to open database: {}", e.getMessage());
            closeOpenedDb();
            return;
        }
        if (openedDb == null) {
            ChunkScannerMod.LOGGER.warn("No usable view provider for adaptor '{}'", pkg.getAdaptorId());
            closeOpenedDb();
            return;
        }

        // 读取包内存储的任务配置，用于路径点命名等
        cachedTaskConfig = pkg.getTaskConfig();
        if (cachedTaskConfig != null) {
            ChunkScannerMod.LOGGER.debug("Loaded TaskConfig from DB for '{}': {}", meta.scanId(), cachedTaskConfig.toDisplayString());
        }

        rebuildPageRenderer(true);
        showingKvView = true;
        rebuildKvButtons();
    }

    private void closeOpenedDb() {
        if (openedPackage != null) {
            openedPackage.close();
        }
        openedPackage = null;
        openedDb = null;
        currentView = null;
        cachedTaskConfig = null;
    }

    /**
     * 创建包的默认视图提供者：取候选视图列表的第一项。
     *
     * <p>候选列表已由 {@link DbViewProviderRegistry#forAdaptor} 按包的 adaptorId 过滤，
     * 并在无匹配时回退到 raw 视图；本方法不直接依赖任何具体的 view_provider 实现
     * （避免 screen→components 耦合）。</p>
     *
     * @return 视图提供者；连 raw 视图都未注册时返回 {@code null}
     */
    private IDbViewProvider createDefaultViewProvider(DbPackage pkg) {
        if (viewTypes.isEmpty()) return null;
        return viewTypes.get(0).create(pkg);
    }

    // ==================== 视图提供者 ====================

    /**
     * 统一重建页面渲染器，将 view provider 创建、数据加载和渲染器构造合并。
     *
     * @param forceRecreate 是否强制重新创建视图提供者（筛选返回时不需重建，保留筛选状态）
     */
    private void rebuildPageRenderer(boolean forceRecreate) {
        if (openedDb == null) {
            layout = null;
            return;
        }

        // —— 选择/创建视图提供者 ——
        if (viewTypes.isEmpty()) {
            currentView = openedDb;
        } else {
            DbViewProviderRegistry.ITypeDescriptor selectedType = viewTypes.get(selectedViewTypeIdx);
            if (forceRecreate || currentView == null) {
                try {
                    currentView = selectedType.create(openedPackage);
                } catch (Exception e) {
                    ChunkScannerMod.LOGGER.warn("Failed to create view provider '{}': {}", selectedType.getId(), e.getMessage());
                    currentView = null;
                }
                if (currentView == null) {
                    currentView = openedDb;
                }
            }
        }

        // —— 直接从视图提供者获取渲染布局 ——
        try {
            layout = currentView.getLayout(textRenderer);
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to get layout from view provider '{}': {}", currentView.getClass().getSimpleName(), e.getMessage());
            layout = null;
        }
        // 注入容器根节点；provider 异常时 layout 为 null，setLayout(null) 使页面退化为空背景
        setLayout(layout);
        applyLayoutBounds();
    }

    /** 按当前页面重排布局：给 layout 设置相对容器坐标并触发列宽 reflow（或通用 layout）。 */
    private void applyLayoutBounds() {
        applyLayoutBounds(layout);
    }

    /** 对目标布局设置内容区 bounds；{@link TableLayout} 需显式 reflow 计算列宽（其 layout() 为空实现）。 */
    private void applyLayoutBounds(ILayout target) {
        if (target == null) return;
        target.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y - FOOTER_H);
        if (target instanceof TableLayout table) {
            table.reflow(this.width);
        } else {
            target.layout();
        }
    }

    private void cycleViewType() {
        if (viewTypes.isEmpty()) return;
        selectedViewTypeIdx = (selectedViewTypeIdx + 1) % viewTypes.size();
        ChunkScannerMod.LOGGER.debug("Cycled to view type: {}", viewTypes.get(selectedViewTypeIdx).getId());
        rebuildPageRenderer(true);
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

    /**
     * 当前视图是否显式声明支持本包的适配器。
     *
     * <p>视图列表已按 adaptorId 过滤，唯一的例外是无匹配时回退的 raw 视图。</p>
     */
    private boolean isDeclaredForCurrentAdaptor() {
        if (openedPackage == null || viewTypes.isEmpty()) return false;
        Set<Identifier> applicable = viewTypes.get(selectedViewTypeIdx).applicableAdaptors();
        return applicable != null && applicable.contains(openedPackage.getAdaptorId());
    }

    private Formatting getProviderColor() {
        return isDeclaredForCurrentAdaptor() ? Formatting.GREEN : Formatting.YELLOW;
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

        int bottomY = this.height - BUTTON_Y_OFFSET;
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
            DbViewProviderRegistry.ITypeDescriptor vt = viewTypes.get(selectedViewTypeIdx);
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
                        vt.getName().copy().formatted(getProviderColor()),
                        btn -> cycleViewType())
                        .dimensions(btnRight - 88, 8, 68, 16).build();
            } else {
                providerButton = ButtonWidget.builder(
                        vt.getName().copy().formatted(getProviderColor()),
                        btn -> cycleViewType())
                        .dimensions(btnRight - 84, 8, 70, 16).build();
            }
            addDrawableChild(providerButton);
        }

        int bottomY = this.height - BUTTON_Y_OFFSET;
        int btnWidth = (WIDTH - 12) / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.database.export_tsv"),
                btn -> exportAsTsv())
                .dimensions(leftX + 4, bottomY, btnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("chunkscanner.gui.database.export_db"),
                btn -> exportDatabase())
                .dimensions(leftX + 4 + btnWidth + 4, bottomY, btnWidth, 20).build());
    }

    // ==================== 布局构建 ====================

    /** 构建当前页面布局并注入容器（文件列表页 / KV 视图页二选一）。 */
    private void buildLayout() {
        ILayout newLayout = showingKvView ? buildKvLayout() : buildFileListLayout();
        setLayout(newLayout);
        applyLayoutBounds(newLayout);
    }

    /** 文件列表页：scanId / 大小 / [↺] / [✕]。 */
    private ILayout buildFileListLayout() {
        if (dbFiles == null) return null;
        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(80),
                TableLayout.ColumnSpec.ofFixed(70, TableLayout.ColumnSpec.Align.RIGHT),
                TableLayout.ColumnSpec.ofFixed(FILE_ACTION_COL_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofFixed(FILE_ACTION_COL_W, TableLayout.ColumnSpec.Align.CENTER),
        };
        String[] headers = {
                Text.translatable("chunkscanner.gui.database.file").getString(),
                Text.translatable("chunkscanner.gui.database.size").getString(),
                "", "",
        };
        TableLayoutBuilder lb = new TableLayoutBuilder(textRenderer, headers, specs);

        for (DbPackage.Info meta : dbFiles) {
            MutableText label = Text.literal(meta.scanId());
            Identifier analyzerId = meta.analyzerId();
            if (analyzerId != null && !ChunkScannerMod.ID_UNKNOWN.equals(analyzerId)) {
                label = label.append(" [").append(GuiUtil.getAnalyzerDisplayName(analyzerId)).append("]").formatted(Formatting.YELLOW);
            }
            lb.addRow()
                    .button(label, () -> openDatabase(meta))
                    .text(Text.literal(GuiUtil.formatSize(meta.size())).formatted(Formatting.GRAY))
                    .button("[↺]", () -> doRebootScan(meta))
                    .button(Text.literal("[✕]"), 0xFFFF5555, 0xFFFF8888, () -> confirmDeleteDbFile(meta))
                    .done();
        }
        return lb.build();
    }

    /** KV 视图页：直接使用 provider 返回的布局。 */
    private ILayout buildKvLayout() {
        return layout;
    }

    // ==================== 导航 ====================

    private void backToList() {
        closeOpenedDb();
        showingKvView = false;
        layout = null;
        viewTypes = new ArrayList<>(DbViewProviderRegistry.getAll());
        selectedViewTypeIdx = 0;
        clearChildren();
        rebuildFileListButtons();
        buildLayout();
    }

    private void backToMainGui() {
        closeOpenedDb();
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ChunkScannerScreen(ChunkScannerMod.getScanner()));
    }

    private void openFolder() {
        Path dir = ChunkScannerMod.getDbRoot();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to open folder: {}", e.getMessage());
        }
    }

    private void exportAsTsv() {
        if (openedPackage == null) return;
        final DbPackage pkg = openedPackage;

        // 在后台线程通过 EDT 调度 JFileChooser，避免 AWT 模态对话框阻塞 GL 渲染线程
        new Thread(() -> {
            try {
                final JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(Text.translatable("chunkscanner.gui.database.export_tsv").getString());
                chooser.setSelectedFile(new File(DbExportUtil.buildDefaultFileName(
                        pkg.getAnalyzerId(), pkg.getScanId(), "tsv")));
                chooser.setFileFilter(new FileNameExtensionFilter("TSV Files (*.tsv)", "tsv"));

                // 设置默认目录：与 ZIP 导出统一为 export 目录，并确保其存在，避免首次回落主目录
                chooser.setCurrentDirectory(DbExportUtil.ensureExportDir().toFile());

                final int[] returnVal = new int[1];
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    returnVal[0] = chooser.showSaveDialog(null);
                });
                if (returnVal[0] == JFileChooser.APPROVE_OPTION) {
                    Path outPath = resolveSavePath(chooser.getSelectedFile().toPath(), "tsv");
                    try {
                        pkg.flush(); // 确保数据最新
                        Files.createDirectories(outPath.getParent());
                        exportToFile(outPath);
                    } catch (Exception e) {
                        ChunkScannerMod.LOGGER.warn("Failed to export TSV: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("File save dialog failed: {}", e.getMessage());
            }
        }, "ChunkScanner-FileSave").start();
    }

    private void exportDatabase() {
        if (openedPackage == null) return;
        final DbPackage pkg = openedPackage;

        // JFileChooser 选择保存路径
        new Thread(() -> {
            try {
                final JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(Text.translatable("chunkscanner.gui.database.export_db").getString());
                chooser.setSelectedFile(new File(DbExportUtil.buildDefaultFileName(
                        pkg.getAnalyzerId(), pkg.getScanId(), "zip")));
                chooser.setFileFilter(new FileNameExtensionFilter("ZIP Archives (*.zip)", "zip"));

                // 设置默认目录：确保 export 目录存在，避免首次使用回落系统主目录
                chooser.setCurrentDirectory(DbExportUtil.ensureExportDir().toFile());

                final int[] returnVal = new int[1];
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    returnVal[0] = chooser.showSaveDialog(null);
                });
                if (returnVal[0] == JFileChooser.APPROVE_OPTION) {
                    Path outPath = resolveSavePath(chooser.getSelectedFile().toPath(), "zip");
                    try {
                        Files.createDirectories(outPath.getParent());
                        DbExportUtil.exportRawZip(pkg, outPath);
                        ChunkScannerMod.LOGGER.info("Database exported to: {}", outPath);
                    } catch (Exception e) {
                        ChunkScannerMod.LOGGER.warn("Failed to export database: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("Export dialog failed: {}", e.getMessage());
            }
        }, "ChunkScanner-FileSave").start();
    }

    /**
     * 对用户在保存对话框中选定的路径做文件名净化与后缀补充。
     *
     * <p>GUI 保存对话框允许用户导航到任意目录，故此处仅清洗文件名段：经
     * {@link DbExportUtil#sanitizeExportFileName} 剥离目录成分、过滤非法字符并
     * 补充扩展名后，用 {@link Path#resolveSibling} 保留用户所选父目录。</p>
     *
     * @param selected 用户在对话框中选定的完整路径
     * @param ext      期望扩展名（不含点号）
     * @return 净化后的保存路径（父目录不变，文件名已清洗并补全后缀）
     */
    private Path resolveSavePath(Path selected, String ext) {
        Path fileName = selected.getFileName();
        String cleaned = DbExportUtil.sanitizeExportFileName(
                fileName == null ? null : fileName.toString(), ext);
        if (cleaned == null) {
            // 理论不可达（JFileChooser 已保证非空文件名），兜底避免空路径
            cleaned = "export." + ext;
        }
        return selected.resolveSibling(cleaned);
    }

    private void exportToFile(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (layout instanceof TableLayout table) {
            table.export(sb);
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

        super.render(context, mouseX, mouseY, delta);
        if (isErrorState()) {
            return;
        }

        renderTitleHeader(context);
        renderWidgets(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        if (showingKvView) {
            renderKvOverlays(context, mouseX, mouseY, centerX);
        } else {
            renderFileListOverlay(context, mouseX, mouseY, centerX);
        }
    }

    /** 标题（2x）+ 金色分隔线。 */
    private void renderTitleHeader(DrawContext ctx) {
        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        ctx.fill(0, 24, this.width, 25, 0xFFFFAA00);
    }

    // ==================== 页面覆层渲染 ====================

    /** 文件列表页的辅助信息：空列表提示 + 悬停路径 tooltip。 */
    private void renderFileListOverlay(DrawContext context, int mouseX, int mouseY, int centerX) {
        if (dbFiles == null || dbFiles.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("chunkscanner.gui.database.no_files").formatted(Formatting.GRAY),
                    centerX, HEADER_Y + 40, 0xFFFFFF);
            return;
        }

        // 悬停行：显示数据库路径（仅当不在操作按钮列上时）
        if (layout instanceof TableLayout table) {
            int row = table.getHoveredRow();
            int col = table.getHoveredCol();
            if (row >= 0 && row < dbFiles.size() && (col == 0 || col == 1)) {
                DbPackage.Info meta = dbFiles.get(row);
                Path dir = meta.dir();
                context.drawTooltip(textRenderer,
                        Text.literal(dir != null ? dir.toAbsolutePath().toString() : meta.scanId()).formatted(Formatting.GRAY),
                        mouseX, mouseY);
            }
        }
    }

    /** KV 视图页覆层：records 行 + 导航队列指示器 + tooltip。 */
    private void renderKvOverlays(DrawContext context, int mouseX, int mouseY, int centerX) {
        int margin = 4;
        int infoY = HEADER_Y - 6;

        int kvSize = layout instanceof TableLayout table ? table.getRowCount() : 0;
        Text aName = openedPackage != null ? GuiUtil.getAnalyzerDisplayName(openedPackage.getAnalyzerId()) : Text.empty();
        Text vName = viewTypes.isEmpty() ? Text.empty() : viewTypes.get(selectedViewTypeIdx).getName();

        context.drawTextWithShadow(textRenderer,
                Text.translatable("chunkscanner.gui.database.records", kvSize, aName, vName)
                        .formatted(Formatting.GRAY),
                margin, infoY, 0xFFFFFF);

        // 导航队列指示器（底部一行显示前 3 个目标）
        ChunkScannerNavigation navFacade = ChunkScannerNavigation.get();
        if (!navFacade.list().isEmpty()) {
            int navY = this.height - FOOTER_H - 4;
            List<NavigationEntry> entries = navFacade.list();
            int showCount = Math.min(3, entries.size());
            StringBuilder sb = new StringBuilder();
            sb.append("[Nav: ").append(entries.size()).append("]");
            for (int i = 0; i < showCount; i++) {
                NavigationEntry e = entries.get(i);
                sb.append(" (").append(e.x()).append(", ").append(e.y()).append(", ").append(e.z()).append(")");
                if (i < showCount - 1) sb.append(" →");
            }
            if (entries.size() > showCount) sb.append(" → ...");
            context.drawTextWithShadow(textRenderer,
                    Text.literal(sb.toString()).formatted(Formatting.GRAY),
                    margin, navY, 0xFFFFFF);
        }

        // provider 按钮 tooltip
        if (providerButton != null && providerButton.isMouseOver(mouseX, mouseY)
                && !viewTypes.isEmpty()) {
            DbViewProviderRegistry.ITypeDescriptor vt = viewTypes.get(selectedViewTypeIdx);
            context.drawTooltip(textRenderer,
                    vt.getDescription().copy().formatted(getProviderColor()),
                    mouseX, mouseY);
        }

        // 筛选按钮 tooltip
        if (filterButton != null && filterButton.isMouseOver(mouseX, mouseY)) {
            Formatting color = currentView != null && currentView.isFilterActive()
                    ? Formatting.GREEN : Formatting.GRAY;
            context.drawTooltip(textRenderer,
                    Text.translatable("chunkscanner.gui.filter.tooltip").formatted(color),
                    mouseX, mouseY);
        }

        // KV 视图悬停 tooltip（位置列 / 物品图标 / 单元格）
        if (layout instanceof TableLayout table) {
            // JEI 风格物品图标 tooltip（优先于文字 tooltip）
            ItemStack hoveredStack = table.getHoveredItemStack();
            if (hoveredStack != null && !hoveredStack.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    List<Text> itemTooltip = Screen.getTooltipFromItem(client, hoveredStack);
                    context.drawTooltip(textRenderer, itemTooltip,
                            hoveredStack.getTooltipData(), mouseX, mouseY);
                }
                return;
            }

            // 位置列 tooltip / 单元格 tooltip
            int row = table.getHoveredRow();
            int col = table.getHoveredCol();
            if (row >= 0 && col >= 0) {
                if (table.isPositionColumn(col)) {
                    String key = XaeroWaypointHelper.isAvailable()
                            ? "chunkscanner.tooltip.create_waypoint"
                            : "chunkscanner.tooltip.print_coords";
                    context.drawTooltip(textRenderer,
                            Text.translatable(key).formatted(Formatting.AQUA),
                            mouseX, mouseY);
                } else {
                    List<Text> cellTooltip = table.getCellTooltip(row, col);
                    if (cellTooltip != null && !cellTooltip.isEmpty()) {
                        context.drawTooltip(textRenderer, cellTooltip, mouseX, mouseY);
                    }
                }
            }
        }
    }

    // ==================== 操作 ====================

    private void confirmDeleteDbFile(DbPackage.Info meta) {
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

    private void doRebootScan(DbPackage.Info meta) {
        MinecraftClient client = MinecraftClient.getInstance();
        ChunkScanner scanner = ChunkScannerMod.getScanner();
        if (scanner == null || client.player == null || client.world == null) return;

        IChunkAnalyzer analyzer = AnalyzerRegistry.get(meta.analyzerId());
        if (analyzer == null) return;

        // 恢复扫描需要独占数据库包，先关闭浏览器中打开的实例
        closeOpenedDb();

        DbPackage pkg;
        try {
            pkg = DbPackage.open(meta.dir());
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to open database package: {}", e.getMessage());
            return;
        }
        TaskConfig storedConfig = pkg.getTaskConfig();
        if (storedConfig != null) {
            ChunkScannerMod.LOGGER.info("Restored TaskConfig from DB for '{}': {}", meta.scanId(), storedConfig.toDisplayString());
        }
        scanner.startWithDb(client, meta.scanId(), meta.analyzerId(), storedConfig, pkg);
    }

    private void deleteDbFile(DbPackage.Info meta) {
        try {
            closeOpenedDb();
            DbManager.deletePackage(meta.scanId());
            showingKvView = false;
            layout = null;
            scanDbFiles();
            clearChildren();
            rebuildFileListButtons();
            buildLayout();
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
