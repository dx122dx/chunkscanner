package com.billy65536.chunkscanner.api;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.core.LocatedPosition;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationCondition;
import com.billy65536.chunkscanner.core.navigation.NavigationConditionRegistry;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.chunkscanner.core.navigation.NavigationTickDispatcher;

/**
 * 导航公共 API。
 *
 * <p>提供三类能力：</p>
 * <ol>
 *   <li><b>全局导航操作</b> —— 直接对 ChunkScanner 主导航队列入队与控制，
 *       与命令 {@code /cs nav *} 操作同一队列。</li>
 *   <li><b>独立导航实例</b> —— {@link #createNavigation(String)} 创建互不干扰的导航，
 *       各自持有队列与配置；配合 {@link #manageTick(ChunkScannerNavigation)} 可交由
 *       ChunkScanner 自动推进。</li>
 *   <li><b>到达条件注册</b> —— 注册自定义 {@link NavigationCondition} 工厂，
 *       之后即可按 id 复用。</li>
 * </ol>
 *
 * <p><b>线程约束</b>：所有方法应在客户端主线程调用。导航依赖 {@link MinecraftClient}
 * 的玩家与世界状态，在其他线程调用行为未定义。</p>
 *
 * <p><b>Baritone 约束</b>：路径执行是全局唯一资源，多个导航实例同时活动会互相抢占目标，
 * 调用方须保证同一时刻只有一个实例处于 {@link ChunkScannerNavigation#isActive() 活动}状态。
 * 通过 {@link #isBaritoneAvailable()} 判断 Baritone 是否可用；不可用时 ChunkScanner
 * 会自动降级为创建 Xaero 路径点。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 全局导航
 * NavigationApi.enqueue(100, 64, -200, "minecraft:overworld");
 * NavigationApi.start();
 *
 * // 独立导航（外部模组推荐）
 * ChunkScannerNavigation nav = NavigationApi.createNavigation("qab");
 * NavigationApi.manageTick(nav);
 * nav.enqueue(100, 64, -200, "minecraft:overworld");
 * nav.start();
 * }</pre>
 *
 * @see RegistryApi
 * @see DatabaseApi
 */
public final class NavigationApi {

    private NavigationApi() {}

    // ==================== 实例获取 ====================

    /**
     * 获取 ChunkScanner 的全局共享导航实例。
     *
     * <p>该实例由本模组每 tick 自动推进，命令与内置 GUI 操作的都是它。
     * 外部模组若不希望与用户操作互相干扰，应改用 {@link #createNavigation(String)}。</p>
     */
    public static ChunkScannerNavigation global() {
        return ChunkScannerNavigation.get();
    }

    /**
     * 创建一个独立导航实例。
     *
     * <p>独立实例拥有自己的队列、导航模式、回调与回退路径点分组。
     * 默认<b>不会</b>被自动推进，需调用 {@link #manageTick(ChunkScannerNavigation)}
     * 托管，或自行在客户端 tick 中调用 {@link ChunkScannerNavigation#tick(MinecraftClient)}。</p>
     *
     * @param name 实例名称，建议使用调用方 modid，用于日志与路径点分组隔离
     * @return 新建的独立导航实例，不为 {@code null}
     */
    public static ChunkScannerNavigation createNavigation(String name) {
        return ChunkScannerNavigation.create(name);
    }

    /**
     * 将独立导航实例交由 ChunkScanner 每 tick 自动推进。
     *
     * <p>全局实例会被忽略（它已被内部驱动）。</p>
     *
     * @return {@code true} 表示本次调用完成了托管注册
     */
    public static boolean manageTick(ChunkScannerNavigation navigation) {
        return NavigationTickDispatcher.register(navigation);
    }

    /**
     * 取消对独立导航实例的 tick 托管。
     *
     * @return {@code true} 表示确实取消了一个托管
     */
    public static boolean unmanageTick(ChunkScannerNavigation navigation) {
        return NavigationTickDispatcher.unregister(navigation);
    }

    // ==================== 全局导航：入队 ====================

    /**
     * 向全局导航队列入队一个目标（使用默认到达条件：玩家靠近目标点）。
     *
     * @param dimensionId 维度 id，如 {@code minecraft:overworld}
     */
    public static void enqueue(int x, int y, int z, String dimensionId) {
        global().enqueue(x, y, z, dimensionId);
    }

    /**
     * 向全局导航队列入队一个目标并指定自定义到达条件。
     */
    public static void enqueue(int x, int y, int z, String dimensionId, NavigationCondition condition) {
        global().enqueue(x, y, z, dimensionId, condition);
    }

    /**
     * 向全局导航队列入队一个 {@link LocatedPosition}。
     *
     * @param pos 目标位置，{@code null} 时忽略
     */
    public static void enqueue(LocatedPosition pos) {
        global().enqueue(pos);
    }

    /**
     * 批量入队。
     *
     * @param positions 目标位置列表，{@code null} 元素会被跳过
     */
    public static void enqueueAll(List<LocatedPosition> positions) {
        if (positions == null) return;
        ChunkScannerNavigation nav = global();
        for (LocatedPosition p : positions) {
            nav.enqueue(p);
        }
    }

    /**
     * 使用已注册的条件 id 入队。
     *
     * @param conditionId 通过 {@link #registerCondition} 注册的条件 id；
     *                    未注册时回退为默认的靠近判定
     */
    public static void enqueueWithCondition(int x, int y, int z, String dimensionId, Identifier conditionId) {
        global().enqueueWithCondition(x, y, z, dimensionId, conditionId);
    }

    // ==================== 全局导航：控制与查询 ====================

    /** 启动全局导航。队列为空时无效果。 */
    public static void start() {
        global().start();
    }

    /** 停止全局导航并清空队列。 */
    public static void stop() {
        global().stop();
    }

    /** 全局导航队列快照（只读）。 */
    public static List<NavigationEntry> list() {
        return global().list();
    }

    /** 全局导航队列剩余目标数。 */
    public static int size() {
        return global().size();
    }

    /** 全局导航是否进行中。 */
    public static boolean isActive() {
        return global().isActive();
    }

    /**
     * Baritone 是否可用。
     *
     * <p>为 {@code false} 时导航会降级为创建 Xaero 路径点（若 Xaero 也不可用则无视觉反馈）。</p>
     */
    public static boolean isBaritoneAvailable() {
        return ChunkScannerNavigation.isBaritoneAvailable();
    }

    // ==================== 到达条件注册 ====================

    /**
     * 注册一个到达条件工厂，之后可按 id 复用。
     *
     * <p>重复注册同 id 会覆盖旧值。建议使用调用方自己的命名空间以避免冲突。</p>
     *
     * @param id      条件唯一标识符
     * @param factory 条件工厂，接收目标坐标与维度返回判定条件
     * @return {@code true} 表示注册成功
     */
    public static boolean registerCondition(Identifier id, NavigationConditionRegistry.Factory factory) {
        return NavigationConditionRegistry.register(id, factory);
    }

    /**
     * 注销一个到达条件工厂。内置条件不可注销。
     *
     * @return {@code true} 表示确实移除了一个工厂
     */
    public static boolean unregisterCondition(Identifier id) {
        return NavigationConditionRegistry.unregister(id);
    }

    /** 指定条件 id 是否已注册。 */
    public static boolean hasCondition(Identifier id) {
        return NavigationConditionRegistry.contains(id);
    }

    /** 所有已注册的条件 id（只读，保持注册顺序）。 */
    public static java.util.Collection<Identifier> conditionIds() {
        return NavigationConditionRegistry.ids();
    }

    /**
     * 按 id 为指定坐标构建到达条件。
     *
     * @return 构建出的条件；id 未注册时回退为默认的靠近判定
     */
    public static NavigationCondition createCondition(Identifier id, int x, int y, int z, String dimensionId) {
        return NavigationConditionRegistry.create(id, x, y, z, dimensionId);
    }

    /** 内置"玩家靠近目标点"条件的 id。 */
    public static Identifier playerNearConditionId() {
        return NavigationConditionRegistry.PLAYER_NEAR;
    }
}
