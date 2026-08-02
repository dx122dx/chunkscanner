package com.billy65536.chunkscanner.config;

/**
 * 配置文件数据模型。
 * 全局配置仅作为默认值；每个扫描任务持有自己的副本，可独立修改。
 *
 * <p>配置结构（chunkscanner.json）：
 * <pre>
 * {
 *   "defaults": {
 *     "minRevisitIntervalSec": 60, ...
 *   },
 *   "waypoint": {
 *     "name": "选中的坐标点",
 *     "initials": "目标",
 *     "group": "chunkscanner"
 *   }
 * }
 * </pre>
 */
public class ChunkScannerConfig {

    /**
     * 聊天增强信息获取模式。
     * <ul>
     *   <li>{@link #StrictAutomatic} — 时间窗口 + 商品名双重匹配（默认）</li>
     *   <li>{@link #WeakAutomatic} — 仅时间窗口匹配，不检查商品名</li>
     *   <li>{@link #SemiAutomatic} — 从聊天捕获物品，收到消息后自动提交增强，不自动检测点击</li>
     *   <li>{@link #NonAutomatic} — 从聊天捕获物品但不自动增强，由 /cs components qshop commitEnhancement 命令手动提交</li>
     *   <li>{@link #Disabled} — 禁用增强匹配</li>
     * </ul>
     */
    public enum EnhanceMatchMode {
        StrictAutomatic,
        WeakAutomatic,
        SemiAutomatic,
        NonAutomatic,
        Disabled
    }

    /**
     * 聊天消息拦截方式，决定 QuickShop 物品消息通过哪个通道被捕获。
     * <ul>
     *   <li>{@link #SYSTEM_MIXIN} — 仅拦截系统聊天包（{@code ClientboundSystemChatPacket}，通过 Mixin）</li>
     *   <li>{@link #GAME_EVENT} — 仅通过 Fabric {@code ClientReceiveMessageEvents.GAME} 事件</li>
     *   <li>{@link #BOTH} — 双通道同时启用（默认，兼容旧行为）</li>
     * </ul>
     *
     * <p>当服务器配置导致同一消息通过两个通道重复捕获时（如 Semi-Automatic 模式
     * 重复提交增强），可切换为单一通道解决。</p>
     */
    public enum ChatInterceptionMethod {
        SYSTEM_MIXIN,
        GAME_EVENT,
        BOTH
    }

    // ==================== 扫描默认值 ====================

    /** 最小重访间隔（秒）。默认 60。 */
    public int minRevisitIntervalSec = 60;

    /** 每 tick 最大 chunk 数。默认 16。 */
    public int maxTasksPerTick = 16;

    /** 初始每 tick chunk 数。默认 2。 */
    public int initialTasksPerTick = 2;

    /** 目标 tick 耗时（纳秒）。默认 5ms。 */
    public long targetTickNs = 5_000_000L;

    /** 批量刷写间隔（tick）。默认 100。 */
    public int flushIntervalTicks = 100;

    /** 工作线程数。默认 2。 */
    public int workerThreads = 2;

    /** 扫描视距倍率。默认 1.0。 */
    public double scanRadiusMultiplier = 1.0;

    // ==================== QShop 分析器配置 ====================

    /**
     * QShop 第二行"出售/收购 + 数量"的正则模式。
     * 必须包含两个捕获组：group(1) = 出售/收购关键词，group(2) = 数量数字。
     */
    public String qshopSellBuyPattern = "^\\s*(出售|收购)\\s+(\\d+)";

    /** QShop 第二行"出售/收购 + 无限"的正则模式。group(1) = 出售/收购关键词。 */
    public String qshopInfinitePattern = "^\\s*(出售|收购)\\s+无限";

    /** QShop 第二行"缺货"的正则模式。 */
    public String qshopOutOfStockPattern = "^\\s*缺货";

    /** QShop 第二行"空间不足"的正则模式。 */
    public String qshopOutOfSpacePattern = "^\\s*空间不足";

    /** QShop 第四行单价的正则模式。group(1) = 价格文本（去除前缀后）。 */
    public String qshopPricePattern = "单价[：:]\\s*(.+)";

    /** 出售关键词，用于匹配 sellBuyPattern/infinitePattern 的 group(1)。 */
    public String qshopSellKeyword = "出售";

    /** 收购关键词，用于匹配 sellBuyPattern/infinitePattern 的 group(1)。 */
    public String qshopBuyKeyword = "收购";

    /** 是否启用 QShop 告示牌高亮边框。默认 false。 */
    public boolean qshopHighlightEnabled = false;

    /** 高亮范围（chunk 环数，0 表示仅当前 chunk）。默认 1。 */
    public int qshopHighlightRadius = 1;

    /** 高亮颜色渐变时长（毫秒）。增强数据在此时间内从绿色渐变到黄色。默认 86400000（1 天）。 */
    public long qshopHighlightGradientMs = 86400_000L;

    /** 聊天增强信息获取模式。默认 StrictAutomatic。 */
    public EnhanceMatchMode qshopEnhanceMatchMode = EnhanceMatchMode.StrictAutomatic;

    /** Non-Automatic / Semi-Automatic 模式下缓存的聊天物品过期时间（毫秒）。默认 30000（30 秒）。 */
    public long qshopManualEnhanceItemExpireMs = 30_000L;

    /** 聊天消息拦截方式。默认 BOTH（双通道）。 */
    public ChatInterceptionMethod qshopChatInterceptionMethod = ChatInterceptionMethod.BOTH;

    // ==================== 路径点默认值 ====================

    /** 路径点名称。默认 "选中的坐标点"。 */
    public String waypointName = "选中的坐标点";

    /** 路径点缩写/符号。默认 "目标"。 */
    public String waypointInitials = "目标";

    /** 路径点所在组（WaypointSet 名称）。默认 "chunkscanner"。 */
    public String waypointGroup = "chunkscanner";

    // ==================== 导航默认值 ====================

    /** 是否启用 Baritone 自动寻路（GoalComposite 重排队列）。默认 false。 */
    public boolean navAutoEnabled = false;

    /** 导航到达判定距离（格）。玩家距目标在此范围内即判定到达。默认 3.0。 */
    public double navReachDist = 3.0;

    /** 创建一份配置副本，供每个扫描任务独立持有。 */
    public ChunkScannerConfig copy() {
        ChunkScannerConfig c = new ChunkScannerConfig();
        c.minRevisitIntervalSec = this.minRevisitIntervalSec;
        c.maxTasksPerTick = this.maxTasksPerTick;
        c.initialTasksPerTick = this.initialTasksPerTick;
        c.targetTickNs = this.targetTickNs;
        c.flushIntervalTicks = this.flushIntervalTicks;
        c.workerThreads = this.workerThreads;
        c.scanRadiusMultiplier = this.scanRadiusMultiplier;
        c.qshopSellBuyPattern = this.qshopSellBuyPattern;
        c.qshopInfinitePattern = this.qshopInfinitePattern;
        c.qshopOutOfStockPattern = this.qshopOutOfStockPattern;
        c.qshopOutOfSpacePattern = this.qshopOutOfSpacePattern;
        c.qshopPricePattern = this.qshopPricePattern;
        c.qshopSellKeyword = this.qshopSellKeyword;
        c.qshopBuyKeyword = this.qshopBuyKeyword;
        c.qshopHighlightEnabled = this.qshopHighlightEnabled;
        c.qshopHighlightRadius = this.qshopHighlightRadius;
        c.qshopHighlightGradientMs = this.qshopHighlightGradientMs;
        c.qshopEnhanceMatchMode = this.qshopEnhanceMatchMode;
        c.qshopManualEnhanceItemExpireMs = this.qshopManualEnhanceItemExpireMs;
        c.qshopChatInterceptionMethod = this.qshopChatInterceptionMethod;
        c.waypointName = this.waypointName;
        c.waypointInitials = this.waypointInitials;
        c.waypointGroup = this.waypointGroup;
        c.navAutoEnabled = this.navAutoEnabled;
        c.navReachDist = this.navReachDist;
        return c;
    }
}
