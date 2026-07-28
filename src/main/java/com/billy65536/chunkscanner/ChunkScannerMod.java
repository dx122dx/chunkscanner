package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.components.analyzer.ItemTranslator;
import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import com.billy65536.chunkscanner.components.analyzer.QShopHighlightRenderer;
import com.billy65536.chunkscanner.components.analyzer.SignAnalyzer;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.components.db.DbFileUtil;
import com.billy65536.chunkscanner.components.view_provider.QShopDbViewProvider;
import com.billy65536.chunkscanner.components.view_provider.RawDbProvider;
import com.billy65536.chunkscanner.components.view_provider.SignDbViewProvider;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.integration.ClothConfigIntegration;
import com.billy65536.chunkscanner.screen.ChunkScannerScreen;
import com.billy65536.chunkscanner.screen.DatabaseScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChunkScannerMod implements ClientModInitializer {
    public static final String MOD_ID = "chunkscanner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ChunkScannerConfig CONFIG = new ChunkScannerConfig();
    private ChunkScanner scanner;

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
                ChunkScanner sc = getScanner();
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

    private static ChunkScannerMod instance;

    public static ChunkScanner getScanner() {
        return instance != null ? instance.scanner : null;
    }

    // ==================== 数据库路径管理 ====================

    /** 数据库文件存放子目录名。 */
    private static final String DB_DIR = "chunkscanner";

    /**
     * 返回所有 DB 文件的根目录（不区分服务器/世界上下文）。
     * DB 浏览器应使用此方法获取根目录。
     */
    public static Path getDbRoot() {
        return FabricLoader.getInstance().getGameDir().resolve(DB_DIR);
    }

    /**
     * 根据当前游戏上下文返回数据库存储目录。
     *
     * 路径结构：
     *   gameDir/chunkscanner/{contextType}/{contextName}/
     *
     * contextType 判定逻辑：
     * - local：本地单机世界 → 使用世界名称
     * - server：多人服务器 → 使用服务器地址
     * - other：无法确定上下文（如主菜单） → "unknown"
     *
     * 路径中的特殊字符会被替换为下划线。
     */
    public static Path getDbDir() {
        MinecraftClient client = MinecraftClient.getInstance();
        String context, type;
        if (client != null && client.isIntegratedServerRunning() && client.getServer() != null) {
            type = "local";
            context = client.getServer().getSaveProperties().getLevelName();
        } else if (client != null && client.getCurrentServerEntry() != null) {
            type = "server";
            context = client.getCurrentServerEntry().address;
        } else {
            type = "other";
            context = "unknown";
        }
        return getDbRoot().resolve(type).resolve(sanitizePath(context));
    }

    private static String sanitizePath(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("ChunkScanner mod initializing...");

        // 加载全局配置（优先 Cloth Config，fallback 到 JSON 文件）
        ConfigLoader.load(CONFIG);
        scanner = new ChunkScanner(CONFIG);

        // 注册数据库工厂（必须最先注册，ScanSession 依赖它创建数据库）
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());

        // 注册分析器
        AnalyzerRegistry.register(new SignAnalyzer());
        AnalyzerRegistry.register(new QShopAnalyzer());

        // 注册 DbViewProvider 类型（提供数据库浏览的不同视图）
        DbViewProviderRegistry.register(new RawDbProvider.Type());
        DbViewProviderRegistry.register(new SignDbViewProvider.Type());
        DbViewProviderRegistry.register(new QShopDbViewProvider.Type());

        // 注册命令（/chunkscanner 和 /cs 两个别名）
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildCommands("chunkscanner"));
            dispatcher.register(buildCommands("cs"));
        });

        // 初始化 QShop 告示牌高亮渲染器
        QShopHighlightRenderer.initialize();

        // 注册客户端 tick 回调：每帧执行扫描调度 + QShop 聊天监听批量处理
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            scanner.onClientTick(client);
            QShopChatListener.tick();
        });

        // 注册连接事件：进入服务器/世界时构建物品译名映射表 + 注册聊天监听
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Joined world, building item translation mapping...");
            ItemTranslator.buildMapping();
            LOGGER.info("Item translation mapping built: {} entries", ItemTranslator.size());
            QShopChatListener.register();
        });

        // 注册断连事件：退出服务器/世界时清理所有扫描会话和映射表
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Disconnected from server, shutting down all scan sessions...");
            scanner.shutdown();
            ItemTranslator.clear();
        });

        // JVM 关闭钩子：确保数据库正确关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down...");
            scanner.shutdown();
        }, "ChunkScanner-Shutdown"));

        LOGGER.info("ChunkScanner initialized! /cs help");
    }

    // ==================== 命令构建 ====================

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildCommands(String name) {
        var root = ClientCommandManager.literal(name);

        // ===== /cs task =====
        var taskNode = ClientCommandManager.literal("task");

        taskNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openTaskGui(ctx.getSource().getClient())));

        // /cs task begin <analyzer> [id] [config...]
        // config 格式: key=value [key=value ...]
        // 支持的键: revisit, tasks, initTasks, targetNs, flush, threads, radius
        taskNode.then(ClientCommandManager.literal("begin")
                .then(ClientCommandManager.argument("analyzer", StringArgumentType.word())
                        .suggests(ANALYZER_SUGGESTIONS)
                        .then(ClientCommandManager.argument("id", StringArgumentType.word())
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
                .then(ClientCommandManager.argument("id", StringArgumentType.word())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> openDbGui(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "id")))));

        // /cs db delete <id>
        dbNode.then(ClientCommandManager.literal("delete")
                .then(ClientCommandManager.argument("id", StringArgumentType.word())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            deleteDbFile(id, ctx.getSource().getClient());
                            return 1;
                        })));

        // /cs db reboot <id>
        dbNode.then(ClientCommandManager.literal("reboot")
                .then(ClientCommandManager.argument("id", StringArgumentType.word())
                        .suggests(DB_FILE_ID_SUGGESTIONS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            rebootScanFromDb(id, ctx.getSource().getClient());
                            return 1;
                        })));

        // /cs db list
        dbNode.then(ClientCommandManager.literal("list")
                .executes(ctx -> {
                    DbFileUtil.chatListDbFiles(ctx.getSource().getClient());
                    return 1;
                }));

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
        componentsNode.then(qshopComponentNode);

        root.then(componentsNode);

        // /cs help
        root.then(ClientCommandManager.literal("help")
                .executes(ctx -> { scanner.showHelp(ctx.getSource().getClient()); return 1; }));

        return root;
    }

    // ==================== 命令辅助 ====================

    /** 简化带 id 参数的命令注册。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> cmdArg(
            String literal, SuggestionProvider<FabricClientCommandSource> suggestions,
            java.util.function.BiConsumer<MinecraftClient, String> action) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("id", StringArgumentType.word())
                        .suggests(suggestions)
                        .executes(ctx -> {
                            action.accept(ctx.getSource().getClient(),
                                    StringArgumentType.getString(ctx, "id"));
                            return 1;
                        }));
    }

    private static int openTaskGui(MinecraftClient client) {
        client.send(() -> client.setScreen(new ChunkScannerScreen(getScanner())));
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
        ConfigLoader.load(CONFIG);
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

    // ==================== DB 文件操作 ====================

    /**
     * 删除指定 scanId 对应的数据库文件。
     */
    private static void deleteDbFile(String scanId, MinecraftClient client) {
        if (instance == null || instance.scanner == null) {
            LOGGER.warn("deleteDbFile called before initialization, ignoring for scanId: {}", scanId);
            return;
        }
        
        if (instance.scanner.getActiveScanIds().contains(scanId)) {
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
}
