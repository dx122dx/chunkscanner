package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.screen.ChunkScannerScreen;
import com.billy65536.chunkscanner.screen.DatabaseScreen;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

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
 *
 * <p>配置访问（get/set/reset/gui/reload）已全部迁移至 infrastructure 的 {@code /inf config}，
 * 本类不再包含 {@code /cs config} 节点。</p>
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
                    String idStr = a.getId().toString();
                    if (idStr.toLowerCase().startsWith(remaining)) {
                        builder.suggest(idStr);
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
        return buildChunkscannerCommands(name);
    }

    /**
     * 构建 chunkscanner 命令树（实例方法，依赖 {@code scanner} 等实例成员）。
     * 不含已移除的 /cs config 节点（配置访问已移至 {@code /inf config}）。
     */
    public com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            buildChunkscannerCommands(String name) {
        var root = ClientCommandManager.literal(name);

        // ===== /cs task =====
        var taskNode = ClientCommandManager.literal("task");

        taskNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openTaskGui(ctx.getSource().getClient())));

        // /cs task begin <analyzer> [id] [config...]
        // analyzer 使用 IdentifierArgumentType，可直接输入含冒号的 id（如 chunkscanner:qshop），无需引号
        // config 使用 greedyString，可输入多个 key=value（如 revisit=60 tasks=16 radius=1.0）
        // 支持的键: revisit, tasks, initTasks, targetNs, flush, threads, radius
        taskNode.then(ClientCommandManager.literal("begin")
                .then(ClientCommandManager.argument("analyzer", IdentifierArgumentType.identifier())
                        .suggests(ANALYZER_SUGGESTIONS)
                        .then(ClientCommandManager.argument("id", StringArgumentType.string())
                                .then(ClientCommandManager.argument("config", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            Identifier analyzerId = normalizeAnalyzerId(
                                                    ctx.getArgument("analyzer", Identifier.class));
                                            String scanId = StringArgumentType.getString(ctx, "id");
                                            String configStr = StringArgumentType.getString(ctx, "config");
                                            TaskConfig taskConfig = TaskConfig.parse(configStr);
                                            scanner.start(ctx.getSource().getClient(), analyzerId, scanId, taskConfig);
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    scanner.start(ctx.getSource().getClient(),
                                            normalizeAnalyzerId(ctx.getArgument("analyzer", Identifier.class)),
                                            StringArgumentType.getString(ctx, "id"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            scanner.start(ctx.getSource().getClient(),
                                    normalizeAnalyzerId(ctx.getArgument("analyzer", Identifier.class)),
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
        Identifier aid = meta.analyzerId();
        if (meta.isEmpty() || aid == null || aid.getPath().isEmpty()
                || ChunkScannerMod.ID_UNKNOWN.equals(aid)) {
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

    /**
     * 归一化 {@link IdentifierArgumentType#identifier()} 解析出的分析器 id。
     *
     * <p>{@link IdentifierArgumentType#identifier()} 会把裸名（如 {@code qshop}）补成
     * {@code minecraft} 命名空间。分析器均以自身命名空间注册（如 {@code chunkscanner:qshop}），
     * 因此<b>显式带命名空间的输入必须原样保留</b>；只有裸名才需要在注册表中按 path 回查真实命名空间。</p>
     *
     * @param id 命令解析出的分析器标识符
     * @return 归一化后的分析器标识符
     */
    private static Identifier normalizeAnalyzerId(Identifier id) {
        if (id == null) return ChunkScannerMod.ID_UNKNOWN;
        // 非 minecraft 命名空间 = 用户显式书写，原样尊重
        if (!"minecraft".equals(id.getNamespace())) return id;
        // 裸名：在注册表中按 path 回查真实命名空间
        Identifier matched = null;
        for (IChunkAnalyzer a : AnalyzerRegistry.getAll()) {
            if (a.getId().getPath().equals(id.getPath())) {
                // 多个命名空间下同名，无法判定，退回本模组命名空间由调用方报「未找到」
                if (matched != null) return ChunkScannerMod.id(id.getPath());
                matched = a.getId();
            }
        }
        return (matched != null) ? matched : ChunkScannerMod.id(id.getPath());
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
        nav.start();
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_start", nav.size())
                .formatted(Formatting.GREEN));
    }

    private void navClear(MinecraftClient client) {
        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
        int size = nav.size();
        nav.clear();
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

    private void navToggle(MinecraftClient client) {
        ChunkScannerConfig.Integration.Baritone baritone =
                ChunkScannerMod.getConfig().integration.baritone;
        baritone.autoEnabled = !baritone.autoEnabled;
        ConfigLoader.save();
        // 全局导航实例实时读取配置，无需再显式同步（显式 set 反而会固化覆盖值）
        sendMsg(client, Text.translatable("chunkscanner.msg.nav_toggle",
                baritone.autoEnabled
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
            String aName = meta.analyzerId() != null && !ChunkScannerMod.ID_UNKNOWN.equals(meta.analyzerId())
                    ? meta.analyzerId().toString() : "?";
            sendMsg(client, Text.literal("  ")
                    .append(Text.literal(meta.scanId()).formatted(Formatting.YELLOW))
                    .append(Text.literal(" [" + aName + "]").formatted(Formatting.GRAY))
                    .append(Text.literal(" " + sizeStr).formatted(Formatting.WHITE)));
        }
    }
}
