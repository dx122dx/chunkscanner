package com.billy65536.chunkscanner.core.navigation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.MinecraftClient;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.integration.BaritoneNavigator;
import com.billy65536.chunkscanner.integration.XaeroWaypointHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkScanner 导航门面。
 *
 * <p>支持两种使用方式：</p>
 * <ul>
 *   <li><b>全局共享导航</b> —— {@link #get()} 返回本模组主导航实例，
 *       由 ChunkScanner 自身的命令与 GUI 驱动，每 tick 自动推进。</li>
 *   <li><b>独立导航实例</b> —— {@link #create(String)} 创建互不干扰的
 *       独立导航，各自持有队列、导航模式与回调。独立实例<b>不会</b>被
 *       ChunkScanner 自动 tick，调用方须自行在客户端 tick 中调用
 *       {@link #tick(MinecraftClient)}，或通过
 *       {@link NavigationTickDispatcher#register(ChunkScannerNavigation)} 托管。</li>
 * </ul>
 *
 * <p>注意：Baritone 路径执行是全局唯一资源，多个导航实例同时 {@link #start()}
 * 会互相抢占目标。调用方应保证同一时刻只有一个实例处于活动状态。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 全局导航
 * ChunkScannerNavigation nav = ChunkScannerNavigation.get();
 * nav.enqueue(x, y, z, dimensionId);                     // 默认 PlayerNearCondition
 * nav.enqueue(x, y, z, dimensionId, customCondition);    // 自定义到达条件
 * nav.setAutoEnabled(true);                               // 覆盖全局配置
 * nav.start();
 *
 * // 独立导航（外部模组推荐）
 * ChunkScannerNavigation mine = ChunkScannerNavigation.create("qab");
 * NavigationTickDispatcher.register(mine);                // 托管自动 tick
 * mine.enqueue(x, y, z, dimensionId);
 * mine.start();
 * }</pre>
 */
public final class ChunkScannerNavigation {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.nav");

    private static final ChunkScannerNavigation INSTANCE = new ChunkScannerNavigation("global");

    /** 实例名称，用于日志区分与回退路径点分组。 */
    private final String name;

    private final NavigationQueue queue = new NavigationQueue();
    private final Set<NavigationEntry> fallbackWaypoints = new HashSet<>();
    private boolean active;
    private boolean pausedByDim;
    private String startDimension;
    private int tickCounter;
    private boolean autoEnabled;
    private Runnable onNavFailed;
    private java.util.function.BiConsumer<String, String> onDimChanged;
    private java.util.function.Consumer<String> onDimResumed;

    /** 回退路径点组名前缀（导航不可用时的路径点分组，避免与用户手动创建的路径点混淆）。 */
    private static final String FALLBACK_WP_GROUP_BASE = "chunkscanner_nav";

    /** 本实例专属的回退路径点组名。 */
    private final String fallbackWpGroup;

    private ChunkScannerNavigation(String name) {
        this.name = (name == null || name.isBlank()) ? "unnamed" : name;
        this.fallbackWpGroup = "global".equals(this.name)
                ? FALLBACK_WP_GROUP_BASE
                : FALLBACK_WP_GROUP_BASE + "_" + this.name;
        this.autoEnabled = ChunkScannerConfigHolder.navAutoEnabled();
    }

    /**
     * 获取本模组的全局共享导航实例。
     *
     * <p>该实例由 ChunkScanner 自身每 tick 驱动，命令 {@code /cs nav *} 操作的即为此实例。</p>
     */
    public static ChunkScannerNavigation get() {
        return INSTANCE;
    }

    /**
     * 创建一个独立导航实例（外部模组推荐入口）。
     *
     * <p>独立实例拥有自己的队列、导航模式、回调与回退路径点分组，与全局实例互不干扰。
     * 但 <b>不会</b>被自动 tick，需调用方自行调用 {@link #tick(MinecraftClient)}，
     * 或使用 {@link NavigationTickDispatcher#register(ChunkScannerNavigation)} 托管。</p>
     *
     * @param name 实例名称，用于日志与回退路径点分组隔离（建议使用调用方 modid）
     * @return 新的独立导航实例
     */
    public static ChunkScannerNavigation create(String name) {
        return new ChunkScannerNavigation(name);
    }

    /** 返回本实例名称。 */
    public String getName() {
        return name;
    }

    /** 本实例是否为全局共享导航。 */
    public boolean isGlobal() {
        return this == INSTANCE;
    }

    // ==================== 入队 ====================

    /**
     * 入队（默认 {@link PlayerNearCondition}，距离取自全局配置）。
     */
    public void enqueue(int x, int y, int z, String dimensionId) {
        NavigationEntry entry = new NavigationEntry(dimensionId, x, y, z);
        PlayerNearCondition cond = new PlayerNearCondition(x, y, z,
                ChunkScannerConfigHolder.navReachDist());
        queue.enqueue(entry, cond);
        LOGGER.debug("Nav enqueue (default cond): ({}, {}, {}) dim={} queue size={}",
                x, y, z, dimensionId, queue.size());
    }

    /**
     * 入队并指定自定义到达判定条件（其他 mod 重点接口）。
     */
    public void enqueue(int x, int y, int z, String dimensionId, NavigationCondition condition) {
        NavigationEntry entry = new NavigationEntry(dimensionId, x, y, z);
        queue.enqueue(entry, condition);
        LOGGER.debug("Nav enqueue (custom cond): ({}, {}, {}) dim={} queue size={}",
                x, y, z, dimensionId, queue.size());
    }

    /**
     * 直接以 {@link NavigationEntry} + {@link NavigationCondition} 入队。
     */
    public void enqueue(NavigationEntry entry, NavigationCondition condition) {
        queue.enqueue(entry, condition);
        LOGGER.debug("Nav enqueue (direct): ({}, {}, {}) dim={} queue size={}",
                entry.x(), entry.y(), entry.z(), entry.dimensionId(), queue.size());
    }

    /**
     * 以 {@link LocatedPosition} 入队（默认 {@link PlayerNearCondition}）。
     *
     * @param pos 目标位置，{@code null} 时忽略
     */
    public void enqueue(LocatedPosition pos) {
        if (pos == null) return;
        enqueue(pos.x(), pos.y(), pos.z(), pos.dimensionId());
    }

    /**
     * 以 {@link LocatedPosition} + 自定义条件入队。
     *
     * @param pos       目标位置，{@code null} 时忽略
     * @param condition 到达判定条件
     */
    public void enqueue(LocatedPosition pos, NavigationCondition condition) {
        if (pos == null) return;
        enqueue(new NavigationEntry(pos.dimensionId(), pos.x(), pos.y(), pos.z()), condition);
    }

    /**
     * 使用 {@link NavigationConditionRegistry} 中注册的条件 id 入队。
     *
     * <p>id 未注册时回退为内置的
     * {@link NavigationConditionRegistry#PLAYER_NEAR}。</p>
     *
     * @param conditionId 已注册的条件标识符
     */
    public void enqueueWithCondition(int x, int y, int z, String dimensionId,
                                     net.minecraft.util.Identifier conditionId) {
        NavigationCondition cond =
                NavigationConditionRegistry.create(conditionId, x, y, z, dimensionId);
        enqueue(new NavigationEntry(dimensionId, x, y, z), cond);
    }

    // ==================== 控制 ====================

    /** 启动导航。 */
    public void start() {
        if (queue.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            startDimension = client.world.getDimensionKey().getValue().toString();
        }
        active = true;
        tickCounter = 0;
        updateGoal();
        LOGGER.info("Navigation started, {} target(s) in queue.", queue.size());
    }

    /** 停止导航并清空队列。 */
    public void stop() {
        active = false;
        pausedByDim = false;
        startDimension = null;
        cleanupFallbackWaypoints();
        queue.clear();
        BaritoneNavigator.cancel();
        LOGGER.info("Navigation stopped and queue cleared.");
    }

    /** 停止导航并清空队列（与 {@link #stop()} 行为一致）。 */
    public void clear() {
        stop();
    }

    // ==================== 查询 ====================

    /** 获取队列快照。 */
    public List<NavigationEntry> list() {
        return queue.getEntries();
    }

    /** 队列大小。 */
    public int size() {
        return queue.size();
    }

    /** 导航是否进行中。 */
    public boolean isActive() {
        return active;
    }

    /** Baritone 是否可用。 */
    public static boolean isBaritoneAvailable() {
        return BaritoneNavigator.isAvailable();
    }

    // ==================== 模式 ====================

    /**
     * 覆盖导航模式（仅影响本门面驱动的导航，不影响全局 CONFIG）。
     */
    public void setAutoEnabled(boolean auto) {
        this.autoEnabled = auto;
    }

    /** 获取当前导航模式。 */
    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    // ==================== 回调 ====================

    /**
     * 注册导航失败回调（如发送红字提示）。
     */
    public void setOnNavFailed(Runnable callback) {
        this.onNavFailed = callback;
    }

    /**
     * 注册维度变化回调（fromDim, toDim），用于发送暂停提示。
     */
    public void setOnDimensionChanged(
            java.util.function.BiConsumer<String, String> callback) {
        this.onDimChanged = callback;
    }

    /**
     * 注册维度恢复回调（currentDim），用于发送恢复提示。
     */
    public void setOnDimensionResumed(
            java.util.function.Consumer<String> callback) {
        this.onDimResumed = callback;
    }

    // ==================== Tick 驱动 ====================

    /**
     * 每 tick 调用（由 ChunkScannerMod.onClientTick 驱动）。
     * 包含维度暂停/恢复、到达判定、节流刷新。
     */
    public void tick(MinecraftClient client) {
        if (!active || client.player == null || client.world == null) return;

        // 维度切换暂停
        String currentDim = client.world.getDimensionKey().getValue().toString();
        if (startDimension != null && !startDimension.equals(currentDim)) {
            if (!pausedByDim) {
                pausedByDim = true;
                BaritoneNavigator.cancel();
                if (onDimChanged != null) {
                    onDimChanged.accept(startDimension, currentDim);
                }
                LOGGER.info("Navigation paused: dimension changed from {} to {}.",
                        startDimension, currentDim);
            }
            return;
        }
        // 维度恢复
        if (pausedByDim && startDimension != null && startDimension.equals(currentDim)) {
            pausedByDim = false;
            tickCounter = 0;
            updateGoal();
            if (onDimResumed != null) {
                onDimResumed.accept(currentDim);
            }
            LOGGER.info("Navigation resumed in dimension {}.", currentDim);
        }
        if (pausedByDim) return;

        // 到达判定
        NavigationEntry beforeTick = queue.peek();
        boolean changed = queue.tick(client);

        // 清理已出队目标的回退路径点
        if (changed && beforeTick != null) {
            removeFallbackWaypoint(beforeTick);
        }

        if (queue.isEmpty()) {
            active = false;
            BaritoneNavigator.cancel();
            cleanupFallbackWaypoints();
            LOGGER.info("Navigation complete.");
            return;
        }

        // 节流刷新
        tickCounter++;
        if (changed || tickCounter >= 20) {
            tickCounter = 0;
            updateGoal();
        }
    }

    // ==================== 底层队列访问器（向后兼容） ====================

    /** @deprecated 使用 {@link #list()} / {@link #size()} / {@link #enqueue} 替代。 */
    @Deprecated
    public NavigationQueue getQueue() {
        return queue;
    }

    // ==================== 内部 ====================

    private void updateGoal() {
        if (!BaritoneNavigator.isAvailable()) {
            // Baritone 不可用（未安装或被配置禁用），取消已有导航并启用路径点回退
            BaritoneNavigator.cancel();
            updateFallbackWaypoints();
            return;
        }
        if (queue.isEmpty()) {
            BaritoneNavigator.cancel();
            return;
        }

        boolean ok;
        if (autoEnabled) {
            List<NavigationEntry> entries = queue.getEntries();
            int limit = Math.min(entries.size(),
                    Math.max(1, ChunkScannerConfigHolder.navCompositeLimit()));
            int[][] positions = new int[limit][3];
            for (int i = 0; i < limit; i++) {
                NavigationEntry e = entries.get(i);
                positions[i][0] = e.x();
                positions[i][1] = e.y();
                positions[i][2] = e.z();
            }
            ok = BaritoneNavigator.navigateComposite(positions);
        } else {
            NavigationEntry e = queue.peek();
            if (e != null) {
                ok = BaritoneNavigator.navigateTo(e.x(), e.y(), e.z());
            } else {
                ok = false;
            }
        }

        if (!ok) {
            if (onNavFailed != null) {
                onNavFailed.run();
            }
            clear();
        }
    }

    /**
     * 路径点回退：当 Baritone 不可用时，将队头坐标添加为 Xaero 路径点。
     * 参考 DatabaseScreen 左键点击坐标创建路径点的逻辑。
     */
    private void updateFallbackWaypoints() {
        NavigationEntry front = queue.peek();
        if (front == null) return;

        // 如果队头已有回退路径点则跳过
        if (fallbackWaypoints.contains(front)) return;

        LocatedPosition pos = new LocatedPosition(
                front.dimensionId(), front.x(), front.y(), front.z());
        String wpName = "[N] " + ChunkScannerConfigHolder.waypointName();
        String wpInit = ChunkScannerConfigHolder.waypointInitials();
        String wpGroup = fallbackWpGroup;

        if (XaeroWaypointHelper.tryCreateWaypoint(pos, wpName, wpInit, wpGroup)) {
            fallbackWaypoints.add(front);
            LOGGER.debug("Fallback waypoint created for nav entry: {}", pos);
        }
    }

    /** 删除指定导航条目的回退路径点。 */
    private void removeFallbackWaypoint(NavigationEntry entry) {
        if (!fallbackWaypoints.remove(entry)) return;
        LocatedPosition pos = new LocatedPosition(
                entry.dimensionId(), entry.x(), entry.y(), entry.z());
        XaeroWaypointHelper.tryRemoveWaypoint(pos, fallbackWpGroup);
        LOGGER.debug("Fallback waypoint removed for nav entry: {}", pos);
    }

    /** 删除所有回退路径点。 */
    private void cleanupFallbackWaypoints() {
        for (NavigationEntry entry : fallbackWaypoints) {
            LocatedPosition pos = new LocatedPosition(
                    entry.dimensionId(), entry.x(), entry.y(), entry.z());
            XaeroWaypointHelper.tryRemoveWaypoint(pos, fallbackWpGroup);
        }
        fallbackWaypoints.clear();
    }

    /**
     * 配置值访问中介，打破 core.navigation 对 config 包的硬引用。
     * 由 ChunkScannerMod 在初始化时注入配置供给器。
     *
     * <p>注入的是 {@link Supplier} 而非实例：AutoConfig 的 ConfigHolder 在 reload 时
     * 会替换内部实例，缓存实例引用会读到陈旧配置。
     */
    public static final class ChunkScannerConfigHolder {
        private static volatile Supplier<ChunkScannerConfig> supplier;

        public static void set(Supplier<ChunkScannerConfig> configSupplier) {
            supplier = configSupplier;
        }

        private static ChunkScannerConfig cfg() {
            Supplier<ChunkScannerConfig> s = supplier;
            return s == null ? null : s.get();
        }

        static boolean navAutoEnabled() {
            ChunkScannerConfig c = cfg();
            return c != null && c.integration.baritone.autoEnabled;
        }

        static double navReachDist() {
            ChunkScannerConfig c = cfg();
            return c != null ? c.integration.baritone.reachDist : 3.0;
        }

        /**
         * 全局配置的到达判定距离（方块）。
         *
         * <p>公开供构建 {@link PlayerNearCondition} 等条件时读取默认距离。</p>
         */
        public static double reachDist() {
            return navReachDist();
        }

        static int navCompositeLimit() {
            ChunkScannerConfig c = cfg();
            return c != null ? c.integration.baritone.compositeLimit : 128;
        }

        static String waypointName() {
            ChunkScannerConfig c = cfg();
            return c != null && c.integration.xaero.name != null
                    ? c.integration.xaero.name : "选中的坐标点";
        }

        static String waypointInitials() {
            ChunkScannerConfig c = cfg();
            return c != null && c.integration.xaero.initials != null
                    ? c.integration.xaero.initials : "目标";
        }
    }
}
