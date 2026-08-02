package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.integration.BaritoneNavigator;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.integration.ClothConfigIntegration;
import com.billy65536.chunkscanner.screen.ChunkScannerScreen;
import com.billy65536.chunkscanner.screen.DatabaseScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 命令注册与命令执行逻辑。
 *
 * 从 {@link ChunkScannerMod} 中抽出，使模组入口类只负责装配（注册分析器、数据库工厂、
 * 事件回调等），命令相关代码集中在此。运行时通过持有的 {@link ChunkScannerMod} 实例
 * 访问扫描引擎、全局配置与数据库目录。
 */
public class ChunkScannerCommands {

    private final ChunkScanner scanner;

    public ChunkScannerCommands() {
        this.scanner = ChunkScannerMod.getScanner();
    }

    // ==================== 自动补全 ====================

    private static final SuggestionProvider<FabricClientCommandSource> ANALYZER_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (IChunkAnalyzer a : AnalyzerRegistry.getAll()) {
                    if (a.getId().toLowerCase().startsWith(remaining)) {
                        builder.suggest(a.getId());
                    }
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> SCAN_ID_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                ChunkScanner sc = ChunkScannerMod.getScanner();
                if (sc != null) {
                    for (String id : sc.getActiveScanIds()) {
                        if (id.toLowerCase().startsWith(remaining)) {
                            builder.suggest(id);
                        }
                    }
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> DB_FILE_ID_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (String id : DbFileUtil.listAllScanIds()) {
                    if (id.toLowerCase().startsWith(remaining)) {
                        builder.suggest(id);
                    }
                }
                return builder.buildFuture();
            };

    // ==================== 命令构建 ====================

    public com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildCommands(String name) {
        var root = ClientCommandManager.literal(name);

        // ===== /cs task =====
        var taskNode = ClientCommandManager.literal("task");

        taskNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openTaskGui(ctx.getSource().getClient())));

        // /cs task begin <analyzer> [id] [config...]
        // config 格式: key=value [key=value ...]
        // 支持的键: revisit, tasks, initTasks, targetNs, flush, threads, radius
        taskNode.then(ClientCommandManager.literal("begin")
                .then(ClientCommandManager.argument("analyzer", StringArgumentType.string())
                        .suggests(ANALYZER_SUGGESTIONS)
                        .then(ClientCommandManager.argument("id", StringArgumentType.string())
                                .then(ClientCommandManager.argument("config", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String analyzerId = StringArgumentType.getString(ctx, "analyzer");
                                            String scanId = StringArgumentType.getString(ctx, "id");
                                            String configStr = StringArgumentType.getString(ctx, "config");
                                            TaskConfig taskConfig = TaskConfig.parse(configStr);
                                            scanner.start(ctx.getSource().getClient(), analyzerId, scanId, taskConfig);
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    scanner.start(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "name"),
                                            StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            scanner.start(ctx.getSource().getClient(),
                                    StringArgumentType.getString(ctx, "name"),
                                    String.valueOf(System.currentTimeMillis()));
                            return 1;
                        })));

        // /cs task stop <id>
        taskNode.then(cmdArg("stop", SCAN_ID_SUGGESTIONS,
                (client, id) -> scanner.stop(client, id)));

        // /cs task pause <id>
        taskNode.then(cmdArg("pause", SCAN_ID_SUGGESTIONS,
                (client, id) -> scanner.pause(client, id)));

        // /cs task resume <id>
        taskNode.then(cmdArg("resume", SCAN_ID_SUGGESTIONS,
                (client, id) -> scanner.resume(client, id)));

        // /cs task stopall
        taskNode.then(ClientCommandManager.literal("stopall")
                .executes(ctx -> { scanner.stopAll(ctx.getSource().getClient()); return 1; }));

        // /cs task status
        taskNode.then(ClientCommandManager.literal("status")
                .executes(ctx -> { scanner.reportStatus(ctx.getSource().getClient()); return 1; }));

        // /cs task list
        taskNode.then(ClientCommandManager.literal("list")
                .executes(ctx -> { scanner.listAnalyzers(ctx.getSource().getClient()); return 1; }));

        // /cs task help
        taskNode.then(ClientCommandManager.literal("help")
                .executes(ctx -> { scanner.showHelp(ctx.getSource().getClient()); return 1; }));

        root.then(taskNode);

        // ===== /cs db =====
        var dbNode = ClientCommandManager.literal("db");

        dbNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openDbGui(ctx.getSource().getClient(), null)));

        // /cs db open [id]
        dbNode.then(ClientCommandManager.literal("open")
                .executes(ctx -> openDbGui(ctx.getSource().getClient(), null))
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> openDbGui(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "id")))));

        // /cs db delete <id>
        dbNode.then(ClientCommandManager.literal("delete")
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            deleteDbFile(id, ctx.getSource().getClient());
                            return 1;
                        })));

        // /cs db reboot <id>
        dbNode.then(ClientCommandManager.literal("reboot")
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            rebootScanFromDb(id, ctx.getSource().getClient());
                            return 1;
                        })));

        // /cs db list
        dbNode.then(ClientCommandManager.literal("list")
                .executes(ctx -> {
                    chatListDbFiles(ctx.getSource().getClient());
                    return 1;
                }));

        // /cs db export raw|tsv <id> [filename]
        var exportNode = ClientCommandManager.literal("export");
        exportNode.then(buildExportNode("raw", this::exportDbRaw));
        exportNode.then(buildExportNode("tsv", this::exportDbTsv));
        dbNode.then(exportNode);

        root.then(dbNode);

        // ===== /cs config =====
        var configNode = ClientCommandManager.literal("config");

        // /cs config gui → 打开 Cloth Config 界面或提示未安装
        configNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openConfigGui(ctx.getSource().getClient())));

        // /cs config reload → 完全重启（重载配置 + 重建所有会话）
        var configReloadNode = ClientCommandManager.literal("reload")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), true));
        // /cs config reload quick → 轻量热重载
        configReloadNode.then(ClientCommandManager.literal("quick")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), false)));
        configNode.then(configReloadNode);

        root.then(configNode);

        // ===== /cs nav =====
        var navNode = ClientCommandManager.literal("nav");

        navNode.then(ClientCommandManager.literal("go")
                .executes(ctx -> {
                    navStart(ctx.getSource().getClient());
                    return 1;
                }));

        navNode.then(ClientCommandManager.literal("clear")
                .executes(ctx -> {
                    navClear(ctx.getSource().getClient());
                    return 1;
                }));

        navNode.then(ClientCommandManager.literal("list")
                .executes(ctx -> {
                    navList(ctx.getSource().getClient());
                    return 1;
                }));

        navNode.then(ClientCommandManager.literal("toggle")
                .executes(ctx -> {
                    navToggle(ctx.getSource().getClient());
                    return 1;
                }));

        root.then(navNode);

        // ===== /cs baritone =====
        var baritoneNode = ClientCommandManager.literal("baritone");

        var baritoneRiskNode = ClientCommandManager.literal("risk");
        baritoneRiskNode.then(ClientCommandManager.literal("disable")
                .executes(ctx -> {
                    disableBaritoneRisk(ctx.getSource().getClient());
                    return 1;
                }));
        baritoneNode.then(baritoneRiskNode);

        root.then(baritoneNode);

        // ===== /cs components =====
        // 可扩展的组件命令入口：/cs components <componentName> <action> [args...]
        var componentsNode = ClientCommandManager.literal("components");

        // /cs components qshop commitEnhancement
        var qshopComponentNode = ClientCommandManager.literal("qshop");
        qshopComponentNode.then(ClientCommandManager.literal("commitEnhancement")
                .executes(ctx -> {
                    Text result = QShopChatListener.commitManualEnhance(ctx.getSource().getClient());
                    sendMsg(ctx.getSource().getClient(), result);
                    return 1;
                }));
        qshopComponentNode.then(ClientCommandManager.literal("removeEnhancement")
                .executes(ctx -> {
                    Text result = QShopChatListener.commitManualRemoveEnhance(ctx.getSource().getClient());
                    sendMsg(ctx.getSource().getClient(), result);
                    return 1;
                }));
        componentsNode.then(qshopComponentNode);

        root.then(componentsNode);

        // /cs help
        root.then(ClientCommandManager.literal("help")
                .executes(ctx -> { scanner.showHelp(ctx.getSource().getClient()); return 1; }));

        return root;
    }

    // ==================== /csc 快捷命令 ====================

    /** 构建 /csc 别名命令（等同于 /cs components）。 */
    public com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildCscCommands(String name) {
        var root = ClientCommandManager.literal(name);

        // /csc qshop commitEnhancement
        var qshopNode = ClientCommandManager.literal("qshop");
        qshopNode.then(ClientCommandManager.literal("commitEnhancement")
                .executes(ctx -> {
                    Text result = QShopChatListener.commitManualEnhance(ctx.getSource().getClient());
                    sendMsg(ctx.getSource().getClient(), result);
                    return 1;
                }));
        qshopNode.then(ClientCommandManager.literal("removeEnhancement")
                .executes(ctx -> {
                    Text result = QShopChatListener.commitManualRemoveEnhance(ctx.getSource().getClient());
                    sendMsg(ctx.getSource().getClient(), result);
                    return 1;
                }));
        root.then(qshopNode);

        return root;
    }

    // ==================== 命令辅助 ====================

    /** 简化带 id 参数的命令注册。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdArg(
            String literal, SuggestionProvider<FabricClientCommandSource> suggestions,
            java.util.function.BiConsumer<MinecraftClient, String> action) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(suggestions)
                        .executes(ctx -> {
                            action.accept(ctx.getSource().getClient(),
                                    StringArgumentType.getString(ctx, "id"));
                            return 1;
                        }));
    }

    /** 构建带 id + 可选 filename 参数的导出命令节点。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildExportNode(
            String label, ExportCommand command) {
        return ClientCommandManager.literal(label)
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .executes(ctx -> {
                                    command.execute(
                                            StringArgumentType.getString(ctx, "id"),
                                            StringArgumentType.getString(ctx, "filename"),
                                            ctx.getSource().getClient());
                                    return 1;
                                }))
                        .executes(ctx -> {
                            command.execute(
                                    StringArgumentType.getString(ctx, "id"),
                                    null,
                                    ctx.getSource().getClient());
                            return 1;
                        }));
    }

    private static int openTaskGui(MinecraftClient client) {
        client.send(() -> client.setScreen(new ChunkScannerScreen(ChunkScannerMod.getScanner())));
        return 1;
    }

    private static int openDbGui(MinecraftClient client, String scanId) {
        client.send(() -> client.setScreen(new DatabaseScreen(scanId)));
        return 1;
    }

    private static int openConfigGui(MinecraftClient client) {
        Screen configScreen = ClothConfigIntegration.createConfigScreen(client.currentScreen);
        if (configScreen == null) {
            sendMsg(client, Text.translatable("chunkscanner.msg.cloth_config_not_available")
                    .formatted(Formatting.RED));
        } else {
            client.send(() -> client.setScreen(configScreen));
        }
        return 1;
    }

    private int reloadConfig(MinecraftClient client, boolean restart) {
        ConfigLoader.load(ChunkScannerMod.CONFIG);
        if (restart) {
            int restarted = scanner.restartAllSessions(client);
            sendMsg(client, Text.translatable("chunkscanner.msg.config_restarted", restarted)
                    .formatted(Formatting.GREEN));
        } else {
            int affected = scanner.reloadConfig();
            sendMsg(client, Text.translatable("chunkscanner.msg.config_reloaded", affected)
                    .formatted(Formatting.GREEN));
        }
        return 1;
    }

    // ==================== 导出辅助接口 ====================

    /** 导出操作函数接口。 */
    @FunctionalInterface
    private interface ExportAction {
        Path export(IChunkDb db, Path outFile) throws IOException;
    }

    /** 导出命令执行接口。 */
    @FunctionalInterface
    private interface ExportCommand {
        void execute(String scanId, String customFileName, MinecraftClient client);
    }

    // ==================== DB 文件操作 ====================

    /**
     * 删除指定 scanId 对应的数据库文件。
     */
    private void deleteDbFile(String scanId, MinecraftClient client) {
        if (scanner == null) {
            ChunkScannerMod.LOGGER.warn("deleteDbFile called before initialization, ignoring for scanId: {}", scanId);
            return;
        }

        if (scanner.getActiveScanIds().contains(scanId)) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_using", scanId, scanId));
            return;
        }

        try {
            if (DbFileUtil.deleteDbFile(scanId)) {
                sendMsg(client, Text.translatable("chunkscanner.msg.db_deleted", scanId)
                        .formatted(Formatting.GREEN));
            } else {
                sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                        .formatted(Formatting.RED));
            }
        } catch (Exception e) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_delete_failed",
                    scanId, e.getMessage()).formatted(Formatting.RED));
        }
    }

    /**
     * 通用数据库导出流程：验证 → 刷写 → 打开 DB → 执行导出 → 反馈。
     */
    private void exportDb(String scanId, String customFileName,
                          MinecraftClient client,
                          ExportAction action,
                          String successKey, String logLabel) {
        if (scanner == null) {
            sendMsg(client, Text.literal("ChunkScanner not initialized.").formatted(Formatting.RED));
            return;
        }

        // 若扫描活跃则先刷写，确保导出包含最新数据
        ScanSession activeSession = scanner.getSession(scanId);
        if (activeSession != null) {
            activeSession.db.flush();
        }

        Path file = DbFileUtil.resolveFilePath(scanId);
        if (!Files.exists(file)) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                    .formatted(Formatting.RED));
            return;
        }

        DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
        if (meta == null || meta == DbFileUtil.FileMeta.EMPTY) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                    .formatted(Formatting.RED));
            return;
        }

        try {
            IChunkDb.IFactory dbFactory = IChunkDb.FactoryRegistry.getDefault();
            IChunkDb db = dbFactory.create(scanId, meta.analyzerId(), ChunkScannerMod.getDbDir());

            Path outFile = null;
            if (customFileName != null && !customFileName.isBlank()) {
                outFile = DbExportUtil.getExportDir().resolve(customFileName);
            }
            Path exported = action.export(db, outFile);
            sendMsg(client, Text.translatable(successKey,
                    exported.getFileName().toString()).formatted(Formatting.GREEN));
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to export {} database: {}", logLabel, e.getMessage());
            sendMsg(client, Text.translatable("chunkscanner.msg.db_export_failed",
                    e.getMessage()).formatted(Formatting.RED));
        }
    }

    /** 导出数据库为 ZIP（raw）格式，包含原始文件与 metadata.json。 */
    private void exportDbRaw(String scanId, String customFileName,
                             MinecraftClient client) {
        exportDb(scanId, customFileName, client,
                DbExportUtil::exportRawZip,
                "chunkscanner.msg.db_export_raw_success", "raw");
    }

    /** 导出数据库为 TSV 格式（hex key + tab + hex value）。 */
    private void exportDbTsv(String scanId, String customFileName,
                             MinecraftClient client) {
        exportDb(scanId, customFileName, client,
                DbExportUtil::exportTsv,
                "chunkscanner.msg.db_export_tsv_success", "tsv");
    }

    /**
     * 从已有的数据库文件恢复/重启扫描任务。
     * 读取文件的 scanId 和 analyzerId 元数据，创建 BinaryChunkDb 实例，
     * 读取存储在 DB 中的 TaskConfig 并恢复应用，
     * 通过 scanner.startWithDb() 恢复扫描（保留已有数据）。
     */
    private void rebootScanFromDb(String scanId, MinecraftClient client) {
        Path file = DbFileUtil.resolveFilePath(scanId);
        if (!Files.exists(file)) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                    .formatted(Formatting.RED));
            return;
        }

        DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
        if (meta.isEmpty() || meta.analyzerId().isEmpty()) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_corrupt")
                    .formatted(Formatting.RED));
            return;
        }

        IChunkDb existingDb;
        try {
            Path fileDir = file.getParent();
            IChunkDb.IFactory dbFactory = IChunkDb.FactoryRegistry.getDefault();
            existingDb = dbFactory.create(meta.scanId(), meta.analyzerId(), fileDir);
        } catch (Exception e) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_corrupt")
                    .formatted(Formatting.RED));
            return;
        }

        // 读取存储在数据库中的任务配置
        TaskConfig storedConfig = existingDb.getTaskConfig();
        if (storedConfig != null) {
            ChunkScannerMod.LOGGER.info("Restored TaskConfig from DB for '{}': {}", scanId, storedConfig.toDisplayString());
        }

        scanner.startWithDb(client, meta.scanId(), meta.analyzerId(), storedConfig, existingDb);
    }

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    // ==================== 导航命令 ====================

    private void navStart(MinecraftClient client) {
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        if (nav.size() == 0) {
            sendMsg(client, Text.translatable("chunkscanner.msg.nav_empty").formatted(Formatting.YELLOW));
            return;
        }
        if (!ChunkScannerNavigation.isBaritoneAvailable()) {
            // Baritone 不可用，使用路径点回退模式
            sendMsg(client, Text.translatable("chunkscanner.msg.nav_no_baritone").formatted(Formatting.YELLOW));
            sendMsg(client, Text.translatable("chunkscanner.msg.nav_fallback_enabled").formatted(Formatting.GREEN));
        }
        ChunkScannerMod.startNavigation();
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_start", nav.size())
                .formatted(Formatting.GREEN));
    }

    private void navClear(MinecraftClient client) {
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        int size = nav.size();
        ChunkScannerMod.clearNavigation();
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_cleared", size)
                .formatted(Formatting.GREEN));
    }

    private void navList(MinecraftClient client) {
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        if (nav.size() == 0) {
            sendMsg(client, Text.translatable("chunkscanner.msg.nav_empty").formatted(Formatting.YELLOW));
            return;
        }
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_list_header",
                nav.size(),
                nav.isAutoEnabled()
                        ? Text.translatable("chunkscanner.gui.nav.mode.composite")
                        : Text.translatable("chunkscanner.gui.nav.mode.relay"))
                .formatted(Formatting.GOLD));

        int i = 1;
        for (NavigationEntry e : nav.list()) {
            sendMsg(client, Text.literal("  " + i + ". ")
                    .append(Text.literal("(" + e.x() + ", " + e.y() + ", " + e.z() + ")")
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" " + e.dimensionId()).formatted(Formatting.GRAY)));
            i++;
        }
    }

    /** 通过命令禁用 Baritone 风险警告（设置 BARITONE_DISABLED + 立即禁用）。 */
    private void disableBaritoneRisk(MinecraftClient client) {
        ChunkScannerMod.CONFIG.baritoneRiskWarning = ChunkScannerConfig.BaritoneRiskWarning.BARITONE_DISABLED;
        BaritoneNavigator.setConfigDisabled(true);
        ConfigLoader.save(ChunkScannerMod.CONFIG);
        sendMsg(client, Text.translatable("chunkscanner.msg.baritone_risk_disabled")
                .formatted(Formatting.GREEN));
    }

    private void navToggle(MinecraftClient client) {
        ChunkScannerMod.CONFIG.navAutoEnabled = !ChunkScannerMod.CONFIG.navAutoEnabled;
        ConfigLoader.save(ChunkScannerMod.CONFIG);
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        nav.setAutoEnabled(ChunkScannerMod.CONFIG.navAutoEnabled);
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_toggle",
                ChunkScannerMod.CONFIG.navAutoEnabled
                        ? Text.translatable("chunkscanner.gui.nav.mode.composite").formatted(Formatting.AQUA)
                        : Text.translatable("chunkscanner.gui.nav.mode.relay").formatted(Formatting.YELLOW))
                .formatted(Formatting.GREEN));
    }

    /**
     * 在聊天中列出所有 DB 文件及其大小、分析器。
     */
    public static void chatListDbFiles(MinecraftClient client) {
        List<DbFileUtil.FileMeta> files = DbFileUtil.listAllDbFiles();
        if (files.isEmpty()) {
            sendMsg(client, Text.translatable("chunkscanner.gui.database.no_files").formatted(Formatting.GRAY));
            return;
        }
        sendMsg(client, Text.translatable("chunkscanner.gui.database.title")
                .formatted(Formatting.GOLD, Formatting.BOLD));
        for (DbFileUtil.FileMeta meta : files) {
            String sizeStr = GuiUtil.formatSize(meta.fileSize());
            String aName = meta.analyzerId() != null && !meta.analyzerId().isEmpty()
                    ? meta.analyzerId() : "?";
            sendMsg(client, Text.literal("  ")
                    .append(Text.literal(meta.scanId()).formatted(Formatting.YELLOW))
                    .append(Text.literal(" [" + aName + "]").formatted(Formatting.GRAY))
                    .append(Text.literal(" " + sizeStr).formatted(Formatting.WHITE)));
        }
    }
}
