package com.billy65536.chunkscanner;

import com.billy65536.chunkscanner.components.analyzer.ItemTranslator;
import com.billy65536.chunkscanner.components.analyzer.QShopAnalyzer;
import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import com.billy65536.chunkscanner.components.analyzer.QShopHighlightRenderer;
import com.billy65536.chunkscanner.components.analyzer.SignAnalyzer;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.components.view_provider.QShopDbViewProvider;
import com.billy65536.chunkscanner.components.view_provider.RawDbProvider;
import com.billy65536.chunkscanner.components.view_provider.SignDbViewProvider;
import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.config.ConfigLoader;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.IChunkDb;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ChunkScannerMod implements ClientModInitializer {
    public static final String MOD_ID = "chunkscanner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ChunkScannerConfig CONFIG = new ChunkScannerConfig();
    private ChunkScanner scanner;
    private ChunkScannerCommands commands;

    private static ChunkScannerMod instance;

    public static ChunkScanner getScanner() {
        return instance != null ? instance.scanner : null;
    }

    public static ChunkScannerCommands getCommands() {
        return instance != null ? instance.commands : null;
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
        AnalyzerRegistry.register(new QShopAnalyzer(), "qshop_view");

        // 注册 DbViewProvider 类型（提供数据库浏览的不同视图）
        DbViewProviderRegistry.register(new RawDbProvider.Type());
        DbViewProviderRegistry.register(new SignDbViewProvider.Type());
        DbViewProviderRegistry.register(new QShopDbViewProvider.Type());

        // 注册命令（/chunkscanner 和 /cs 两个别名，/csc 作为 /cs components 的快捷入口）
        commands = new ChunkScannerCommands();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(commands.buildCommands("chunkscanner"));
            dispatcher.register(commands.buildCommands("cs"));
            dispatcher.register(commands.buildCscCommands("csc"));
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
}
