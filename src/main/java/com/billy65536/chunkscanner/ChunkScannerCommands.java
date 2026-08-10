package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.api.DatabaseApi;
import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.components.view_provider.QShopFilter;
import com.billy65536.chunkscanner.components.view_provider.QShopFilterConfig;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbManager;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.chunkscanner.screen.ChunkScannerScreen;
import com.billy65536.chunkscanner.screen.DatabaseScreen;

import com.billy65536.infrastructure.util.cli.CliCompletion;
import com.billy65536.infrastructure.util.reflect.FlatConfigs;
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
                for (String id : DbManager.listAllScanIds()) {
                    if (id.toLowerCase().startsWith(remaining)) {
                        builder.suggest(id);
                    }
                }
                return builder.buildFuture();
            };

    /**
     * /cs task begin 的 config 参数补全：基于 infrastructure 的 {@link CliCompletion}。
     *
     * <p>采用「assignment + multiple」层级模式：
     * <ul>
     *   <li>{@code assignment=true}：键名形如 {@code key=}，选中后自动补 {@code =} 并按需提示取值；</li>
     *   <li>{@code multiple=true}：多个 {@code key=value} 以空格分隔，尾随空格表示要追加新条目；
     *       仅对「当前正在输入的那个片段」做补全，不影响已写好的前序片段。</li>
     * </ul>
     * 配合 {@link TaskConfig#KNOWN_KEYS} 提供键名候选，从源头杜绝 {@code rivist} 之类的拼写错误。
     */
    private static final SuggestionProvider<FabricClientCommandSource> TASK_CONFIG_SUGGESTIONS =
            CliCompletion.forFlatConfig(TaskConfig.class, (ctx, key) -> taskConfigDefaultValues(key));

    private static final SuggestionProvider<FabricClientCommandSource> QSHOP_FILTER_SUGGESTIONS =
            CliCompletion.forFlatConfig(QShopFilterConfig.class);
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
                                .suggests(TASK_CONFIG_SUGGESTIONS)
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

        // /cs task modify <id> [config...]  —— 增量修改已有任务的配置
        taskNode.then(ClientCommandManager.literal("modify")
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(SCAN_ID_SUGGESTIONS) // 仅补全已有扫描 id
                        .then(ClientCommandManager.argument("config", StringArgumentType.greedyString())
                                .suggests(TASK_CONFIG_SUGGESTIONS)
                                .executes(ctx -> {
                                    scanner.modify(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "id"),
                                            StringArgumentType.getString(ctx, "config"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            scanner.modify(ctx.getSource().getClient(),
                                    StringArgumentType.getString(ctx, "id"), null);
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

        // /cs db copy <src> <dst>
        dbNode.then(ClientCommandManager.literal("copy")
                .then(ClientCommandManager.argument("src", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("dst", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    copyDb(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "src"),
                                            StringArgumentType.getString(ctx, "dst"));
                                    return 1;
                                }))));

        // /cs db filtercopy <src> <dst> [filterArgs...]
        dbNode.then(ClientCommandManager.literal("filtercopy")
                .then(ClientCommandManager.argument("src", StringArgumentType.string())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("dst", StringArgumentType.string())
                                .then(ClientCommandManager.argument("filter", StringArgumentType.greedyString())
                                        .suggests(QSHOP_FILTER_SUGGESTIONS)
                                        .executes(ctx -> {
                                            filterCopyDb(ctx.getSource().getClient(),
                                                    StringArgumentType.getString(ctx, "src"),
                                                    StringArgumentType.getString(ctx, "dst"),
                                                    StringArgumentType.getString(ctx, "filter"));
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    // 无 filter 参数 → 等同于普通 copy
                                    copyDb(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "src"),
                                            StringArgumentType.getString(ctx, "dst"));
                                    return 1;
                                }))));

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
        Path export(DbPackage pkg, Path outFile) throws IOException;
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
            if (DbManager.deletePackage(scanId)) {
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

    /** 全量复制数据库到新的 scanId（原库只读，不动）。 */
    private void copyDb(MinecraftClient client, String srcScanId, String dstScanId) {
        try {
            DatabaseApi.copyDatabase(srcScanId, dstScanId);
            sendMsg(client, Text.translatable("chunkscanner.msg.db_copied",
                    srcScanId, dstScanId).formatted(Formatting.GREEN));
        } catch (Exception e) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_copy_failed",
                    e.getMessage()).formatted(Formatting.RED));
        }
    }

    /** 复制数据库 → 打开副本 → 按过滤条件原地删除不匹配记录 → 关闭。 */
    private void filterCopyDb(MinecraftClient client, String srcScanId,
                              String dstScanId, String filterArgs) {
        QShopFilter filter = new QShopFilter(FlatConfigs.createFrom(filterArgs, QShopFilterConfig.class));

        try {
            // Step 1: 全量复制
            DatabaseApi.copyDatabase(srcScanId, dstScanId);

            // Step 2: 打开副本 → 原地过滤
            DbPackage dstPkg = DatabaseApi.openPackage(dstScanId);
            if (dstPkg == null) {
                sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found",
                        dstScanId).formatted(Formatting.RED));
                return;
            }
            int removed;
            try (DbPackage pkg = dstPkg) {
                removed = pkg.getAdaptor(QShopDbAdapter.class).filterInPlace(filter);
            }

            sendMsg(client, Text.translatable("chunkscanner.msg.db_filtercopy_success",
                    srcScanId, dstScanId, removed).formatted(Formatting.GREEN));
        } catch (Exception e) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_copy_failed",
                    e.getMessage()).formatted(Formatting.RED));
        }
    }

    /**
     * 通用数据库导出流程：验证 → 刷写 → 打开数据库包 → 执行导出 → 反馈。
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
            activeSession.pkg.flush();
        }

        Path dir = DbManager.findDir(scanId);
        if (dir == null) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                    .formatted(Formatting.RED));
            return;
        }

        try (DbPackage pkg = DbPackage.open(dir)) {
            Path outFile = null;
            if (customFileName != null && !customFileName.isBlank()) {
                outFile = DbExportUtil.getExportDir().resolve(customFileName);
            }
            Path exported = action.export(pkg, outFile);
            sendMsg(client, Text.translatable(successKey,
                    exported.getFileName().toString()).formatted(Formatting.GREEN));
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("Failed to export {} database: {}", logLabel, e.getMessage());
            sendMsg(client, Text.translatable("chunkscanner.msg.db_export_failed",
                    e.getMessage()).formatted(Formatting.RED));
        }
    }

    /** 导出数据库为 ZIP（raw）格式，包含负载文件与 metadata.json。 */
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
                (pkg, outFile) -> DbExportUtil.exportTsv(pkg, outFile),
                "chunkscanner.msg.db_export_tsv_success", "tsv");
    }

    /**
     * 从已有的数据库包恢复/重启扫描任务。
     * 读取包元信息中的 scanId、analyzerId 与 TaskConfig，
     * 通过 scanner.startWithDb() 恢复扫描（保留已有数据）。
     */
    private void rebootScanFromDb(String scanId, MinecraftClient client) {
        Path dir = DbManager.findDir(scanId);
        if (dir == null) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_not_found", scanId)
                    .formatted(Formatting.RED));
            return;
        }

        DbPackage pkg;
        try {
            pkg = DbPackage.open(dir);
        } catch (Exception e) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_corrupt")
                    .formatted(Formatting.RED));
            return;
        }

        Identifier aid = pkg.getAnalyzerId();
        if (aid == null || aid.getPath().isEmpty() || ChunkScannerMod.ID_UNKNOWN.equals(aid)) {
            pkg.close();
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_corrupt")
                    .formatted(Formatting.RED));
            return;
        }

        // 读取存储在包元信息中的任务配置
        TaskConfig storedConfig = pkg.getTaskConfig();
        if (storedConfig != null) {
            ChunkScannerMod.LOGGER.info("Restored TaskConfig from DB for '{}': {}", scanId, storedConfig.toDisplayString());
        }

        scanner.startWithDb(client, pkg.getScanId(), aid, storedConfig, pkg);
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

    /**
     * 为 config 的某键提供取值候选（{@link CliCompletion} assignment 模式）。
     * 数值型键返回当前全局默认值作为提示；自由文本键（路径点名/缩写/组）无候选。
     */
    private static List<String> taskConfigDefaultValues(String key) {
        ChunkScannerConfig c = ChunkScannerMod.getConfig();
        if (c == null) return List.of();
        return switch (key) {
            case "revisit"  -> List.of(String.valueOf(c.scanner.minRevisitIntervalSec));
            case "tasks"    -> List.of(String.valueOf(c.scanner.maxTasksPerTick));
            case "inittasks"-> List.of(String.valueOf(c.scanner.initialTasksPerTick));
            case "targetns" -> List.of(String.valueOf(c.scanner.targetTickNs));
            case "flush"    -> List.of(String.valueOf(c.scanner.flushIntervalTicks));
            case "threads"  -> List.of(String.valueOf(c.scanner.workerThreads));
            case "radius"   -> List.of(String.valueOf(c.scanner.scanRadiusMultiplier));
            default         -> List.of(); // wpname / wpinit / wpgroup 为自由文本
        };
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
        List<DbPackage.Info> files = DbManager.listAll();
        if (files.isEmpty()) {
            sendMsg(client, Text.translatable("chunkscanner.gui.database.no_files").formatted(Formatting.GRAY));
            return;
        }
        sendMsg(client, Text.translatable("chunkscanner.gui.database.title")
                .formatted(Formatting.GOLD, Formatting.BOLD));
        for (DbPackage.Info meta : files) {
            String sizeStr = GuiUtil.formatSize(meta.size());
            String aName = meta.analyzerId() != null && !ChunkScannerMod.ID_UNKNOWN.equals(meta.analyzerId())
                    ? meta.analyzerId().toString() : "?";
            sendMsg(client, Text.literal("  ")
                    .append(Text.literal(meta.scanId()).formatted(Formatting.YELLOW))
                    .append(Text.literal(" [" + aName + "]").formatted(Formatting.GRAY))
                    .append(Text.literal(" " + sizeStr).formatted(Formatting.WHITE)));
        }
    }
}
