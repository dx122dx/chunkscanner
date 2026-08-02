package com.billy65536.chunkscanner.core.navigation;

import java.util.List;

import net.minecraft.client.MinecraftClient;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.integration.BaritoneNavigator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChunkScanner 导航统一门面（对外 API 入口）。
 *
 * <p>其他 mod 通过 {@link #get()} 获取单例，调用 enqueue/start/stop/list/clear
 * 等方法操作导航队列。支持自定义 {@link NavigationCondition} 到达判定与
 * 独立指定导航模式（覆盖全局配置）。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ChunkScannerNavigation nav = ChunkScannerNavigation.get();
 * nav.enqueue(x, y, z, dimensionId);                     // 默认 PlayerNearCondition
 * nav.enqueue(x, y, z, dimensionId, customCondition);    // 自定义到达条件
 * nav.setAutoEnabled(true);                               // 覆盖全局配置
 * nav.start();
 * }</pre>
 */
public final class ChunkScannerNavigation {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.nav");

    private static final ChunkScannerNavigation INSTANCE = new ChunkScannerNavigation();

    private final NavigationQueue queue = new NavigationQueue();
    private boolean active;
    private boolean pausedByDim;
    private String startDimension;
    private int tickCounter;
    private boolean autoEnabled;
    private Runnable onNavFailed;
    private java.util.function.BiConsumer<String, String> onDimChanged;
    private java.util.function.Consumer<String> onDimResumed;

    private ChunkScannerNavigation() {
        this.autoEnabled = ChunkScannerConfigHolder.navAutoEnabled();
    }

    /** 获取门面单例。 */
    public static ChunkScannerNavigation get() {
        return INSTANCE;
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
        boolean changed = queue.tick(client);

        if (queue.isEmpty()) {
            active = false;
            BaritoneNavigator.cancel();
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
            active = false;
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
     * 配置值访问中介，打破 core.navigation 对 config 包的硬引用。
     * 由 ChunkScannerMod 在初始化时注入实际值。
     */
    public static final class ChunkScannerConfigHolder {
        private static volatile ChunkScannerConfig config;

        public static void set(ChunkScannerConfig cfg) {
            config = cfg;
        }

        static boolean navAutoEnabled() {
            return config != null && config.navAutoEnabled;
        }

        static double navReachDist() {
            return config != null ? config.navReachDist : 3.0;
        }

        static int navCompositeLimit() {
            return config != null ? config.navCompositeLimit : 128;
        }
    }
}
