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
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationTickDispatcher;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.core.navigation.NavigationQueue;
import com.billy65536.chunkscanner.integration.BaritoneNavigator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ChunkScannerMod implements ClientModInitializer {
    public static final String MOD_ID = "chunkscanner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ChunkScanner scanner;
    private ChunkScannerCommands commands;

    /**
     * 返回 AutoConfig 持有的活动配置实例。
     *
     * <p>不可缓存返回值：AutoConfig 的 ConfigHolder 在 {@code load()} 时会替换内部实例，
     * 缓存引用会读到陈旧对象。每次访问都应重新调用本方法。
     */
    public static ChunkScannerConfig getConfig() {
        return ConfigLoader.get();
    }

    /** 返回本模组版本（来自 fabric.mod.json 元数据），供模块登记时上报。 */
    public static String getVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    private final ChunkScannerNavigation nav = ChunkScannerNavigation.get();

    private static ChunkScannerMod instance;

    public static ChunkScanner getScanner() {
        return instance != null ? instance.scanner : null;
    }

    public static ChunkScannerCommands getCommands() {
        return instance != null ? instance.commands : null;
    }

    /**
     * @deprecated 外部调用请使用
     *             {@link com.billy65536.chunkscanner.api.NavigationApi}；
     *             内部请使用 {@link ChunkScannerNavigation#get()} 门面。
     */
    @Deprecated
    public static NavigationQueue getNavQueue() {
        return instance != null ? instance.nav.getQueue() : null;
    }

    /**
     * 启动全局导航（由命令层调用）。
     *
     * @deprecated 外部调用请使用
     *             {@link com.billy65536.chunkscanner.api.NavigationApi#start()}。
     */
    @Deprecated
    public static void startNavigation() {
        if (instance != null) instance.nav.start();
    }

    /**
     * 清空全局导航队列并取消导航。
     *
     * @deprecated 外部调用请使用
     *             {@link com.billy65536.chunkscanner.api.NavigationApi#stop()}。
     */
    @Deprecated
    public static void clearNavigation() {
        if (instance != null) instance.nav.clear();
    }

    /**
     * 将位置加入全局导航队列。
     *
     * @deprecated 外部调用请使用
     *             {@link com.billy65536.chunkscanner.api.NavigationApi#enqueue(int, int, int, String)}。
     */
    @Deprecated
    public static void enqueueNavigation(NavigationEntry entry) {
        if (instance != null) instance.nav.enqueue(entry.x(), entry.y(), entry.z(), entry.dimensionId());
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

    /**
     * 将上下文名（世界名 / 服务器地址）转换为安全的单层目录名。
     *
     * <p>除过滤文件系统非法字符外，还必须排除 {@code "."} / {@code ".."} 与空串：
     * 这三者会让 {@code resolve()} 指向父目录或根目录本身，造成数据库写到
     * {@code chunkscanner/} 之外的位置。</p>
     */
    private static String sanitizePath(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        // 非法字符 + 控制字符
        String s = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_");
        // Windows 不允许目录名以点或空格结尾
        s = s.replaceAll("[. ]+$", "");
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return "unnamed";
        return s;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("ChunkScanner mod initializing...");

        // 注册 AutoConfig（必须在任何 getConfig() 调用之前）
        ConfigLoader.register();
        ChunkScannerConfig config = getConfig();

        // 启动时检查 Baritone 风险警告配置，若为 BARITONE_DISABLED 则立即禁用
        if (config.integration.baritone.riskWarning
                == ChunkScannerConfig.BaritoneRiskWarning.BARITONE_DISABLED) {
            BaritoneNavigator.setConfigDisabled(true);
            LOGGER.info("Baritone disabled by config (BaritoneRiskWarning=BARITONE_DISABLED).");
        }

        // 注入配置供给器到导航门面并注册回调（供给器而非实例，避免 reload 后引用失效）
        // 导航模式不再快照到 nav 字段：isAutoEnabled() 会实时读取配置，
        // 这样 GUI / 命令改动配置后无需再手动同步。
        ChunkScannerNavigation.ChunkScannerConfigHolder.set(ChunkScannerMod::getConfig);
        nav.setOnNavFailed(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(
                        Text.translatable("chunkscanner.msg.nav_failed")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        nav.setOnDimensionChanged((from, to) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(
                        Text.translatable("chunkscanner.msg.nav_dimension_changed", from, to)
                                .formatted(Formatting.YELLOW),
                        false);
            }
        });
        nav.setOnDimensionResumed(dim -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(
                        Text.translatable("chunkscanner.msg.nav_dimension_resumed", dim)
                                .formatted(Formatting.GREEN),
                        false);
            }
        });
        scanner = new ChunkScanner(config);

        // 注册数据库工厂（必须最先注册，ScanSession 依赖它创建数据库）
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());

        // 注册分析器
        AnalyzerRegistry.register(new SignAnalyzer());
        AnalyzerRegistry.register(new QShopAnalyzer(), id("qshop_view"));

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

        // 注册客户端 tick 回调：每帧执行扫描调度 + QShop 聊天监听批量处理 + 导航
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            scanner.onClientTick(client);
            QShopChatListener.tick();
            nav.tick(client);
            // 推进外部模组注册的独立导航实例
            NavigationTickDispatcher.tickAll(client);
        });

        // 注册连接事件：进入服务器/世界时构建物品译名映射表 + 注册聊天监听 + Baritone 风险警告
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Joined world, building item translation mapping...");
            ItemTranslator.buildMapping();
            LOGGER.info("Item translation mapping built: {} entries", ItemTranslator.size());
            QShopChatListener.register();

            // 若 Baritone 可用且风险警告为 SHOWN，展示警告消息
            if (getConfig().integration.baritone.riskWarning
                    == ChunkScannerConfig.BaritoneRiskWarning.SHOWN
                    && BaritoneNavigator.isAvailable()) {
                client.execute(() -> showBaritoneRiskWarning(client));
            }
        });

        // 注册断连事件：退出服务器/世界时清理所有扫描会话和映射表。
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

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    /** 预定义的"未知/未定义"哨兵（用于兼容旧文件或缺失 analyzerId 的场景）。 */
    public static final Identifier ID_UNKNOWN = Identifier.of("undefined", "undefined");

    /**
     * 展示 Baritone 风险警告消息（加入服务器/世界时触发）。
     * 包含功能说明、风险警告、可点击的"禁用 Baritone"链接及配置提示。
     */
    private static void showBaritoneRiskWarning(MinecraftClient client) {
        if (client.player == null) return;

        // 标题行
        client.player.sendMessage(
                Text.literal("")
                        .append(Text.literal("==== ").formatted(Formatting.GOLD))
                        .append(Text.translatable("chunkscanner.msg.baritone_risk_title")
                                .formatted(Formatting.RED, Formatting.BOLD))
                        .append(Text.literal(" ====").formatted(Formatting.GOLD)),
                false);

        // 功能说明
        client.player.sendMessage(
                Text.translatable("chunkscanner.msg.baritone_risk_desc"),
                false);

        // 风险警告
        client.player.sendMessage(
                Text.translatable("chunkscanner.msg.baritone_risk_warning")
                        .formatted(Formatting.YELLOW),
                false);

        // a) 可点击的"禁用 Baritone"文本
        MutableText disableText = Text.literal("[")
                .formatted(Formatting.GRAY)
                .append(Text.translatable("chunkscanner.msg.baritone_risk_disable")
                        .formatted(Formatting.RED, Formatting.UNDERLINE))
                .append(Text.literal("]").formatted(Formatting.GRAY));
        disableText.styled(s -> s
                .withClickEvent(
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/cs config set integration.baritone.riskWarning BARITONE_DISABLED"))
                .withHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Text.translatable("chunkscanner.msg.baritone_risk_disable_desc")
                    )
                ));
        client.player.sendMessage(disableText, false);

        // b) 配置提示
        client.player.sendMessage(
                Text.translatable("chunkscanner.msg.baritone_risk_config_hint")
                        .formatted(Formatting.GRAY),
                false);
    }
}
