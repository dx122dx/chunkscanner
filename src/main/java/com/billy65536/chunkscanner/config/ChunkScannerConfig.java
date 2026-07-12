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

    // ==================== 路径点默认值 ====================

    /** 路径点名称。默认 "选中的坐标点"。 */
    public String waypointName = "选中的坐标点";

    /** 路径点缩写/符号。默认 "目标"。 */
    public String waypointInitials = "目标";

    /** 路径点所在组（WaypointSet 名称）。默认 "chunkscanner"。 */
    public String waypointGroup = "chunkscanner";

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
        c.waypointName = this.waypointName;
        c.waypointInitials = this.waypointInitials;
        c.waypointGroup = this.waypointGroup;
        return c;
    }
}
