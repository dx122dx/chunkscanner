package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.analyzer.SignAnalyzer;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.components.db.DbFileUtil;
import com.billy65536.chunkscanner.components.view_provider.QShopDbViewProvider;
import com.billy65536.chunkscanner.components.view_provider.SignDbViewProvider;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.config.TaskConfig;
import com.billy65536.chunkscanner.core.ChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.screen.ChunkScannerScreen;
import com.billy65536.chunkscanner.screen.DatabaseScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
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
                for (ChunkAnalyzer a : getScanner().getAnalyzers()) {
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

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("ChunkScanner mod initializing...");

        // 加载全局配置（优先 Cloth Config，fallback 到 JSON 文件）
        ConfigLoader.load(CONFIG);
        scanner = new ChunkScanner(CONFIG);
        scanner.registerAnalyzer(new SignAnalyzer());
        scanner.registerAnalyzer(new QShopAnalyzer());

        // 注册 DbViewProvider 类型（提供数据库浏览的不同视图）
        DbViewProvider.Registry.register(new BinaryChunkDb.DbViewProviderType());
        DbViewProvider.Registry.register(new SignDbViewProvider.DbViewProviderType());
        DbViewProvider.Registry.register(new QShopDbViewProvider.DbViewProviderType());

        // 注册命令（/chunkscanner 和 /cs 两个别名）
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildCommands("chunkscanner"));
            dispatcher.register(buildCommands("cs"));
        });

        // 注册客户端 tick 回调：每帧执行扫描调度
        ClientTickEvents.END_CLIENT_TICK.register(scanner::onClientTick);

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

        // /cs gui → 打开任务视图 GUI（不放在 root executes 以避免 [<args>] 提示）
        root.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openTaskGui(ctx.getSource().getClient())));

        // ===== /cs task =====
        var taskNode = ClientCommandManager.literal("task");

        taskNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openTaskGui(ctx.getSource().getClient())));

        // /cs task begin <name> [id] [config...]
        // config 格式: key=value [key=value ...]
        // 支持的键: revisit, tasks, initTasks, targetNs, flush, threads, radius
        taskNode.then(ClientCommandManager.literal("begin")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .suggests(ANALYZER_SUGGESTIONS)
                        .then(ClientCommandManager.argument("id", StringArgumentType.word())
                                .then(ClientCommandManager.argument("config", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String analyzerName = StringArgumentType.getString(ctx, "name");
                                            String scanId = StringArgumentType.getString(ctx, "id");
                                            String configStr = StringArgumentType.getString(ctx, "config");
                                            TaskConfig taskConfig = TaskConfig.parse(configStr);
                                            scanner.start(ctx.getSource().getClient(), analyzerName, scanId, taskConfig);
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
                                    String.valueOf(System.currentTimeMillis() / 1000));
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

        // /cs task reload [restart]
        var taskReloadNode = ClientCommandManager.literal("reload")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), false));
        taskReloadNode.then(ClientCommandManager.literal("restart")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), true)));
        taskNode.then(taskReloadNode);

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

        // /cs help
        root.then(ClientCommandManager.literal("help")
                .executes(ctx -> { scanner.showHelp(ctx.getSource().getClient()); return 1; }));

        // /cs reload [restart]
        var reloadNode = ClientCommandManager.literal("reload")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), false));
        reloadNode.then(ClientCommandManager.literal("restart")
                .executes(ctx -> reloadConfig(ctx.getSource().getClient(), true)));
        root.then(reloadNode);

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
     * 读取文件的 scanId 和 analyzerName 元数据，创建 BinaryChunkDb 实例，
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
        if (meta.isEmpty() || meta.analyzerName().isEmpty()) {
            sendMsg(client, Text.translatable("chunkscanner.msg.db_file_corrupt")
                    .formatted(Formatting.RED));
            return;
        }

        BinaryChunkDb existingDb;
        try {
            existingDb = new BinaryChunkDb(meta.scanId(), meta.analyzerName());
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

        scanner.startWithDb(client, meta.scanId(), meta.analyzerName(), storedConfig, existingDb);
    }

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }
}
