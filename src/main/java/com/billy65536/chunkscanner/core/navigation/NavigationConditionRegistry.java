package com.billy65536.chunkscanner.core.navigation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

/**
 * 导航到达条件工厂全局注册表。
 *
 * <p>{@link NavigationCondition} 本身是函数式接口，可直接 new 后传给
 * {@code enqueue}。本注册表解决的是<b>按 id 复用条件类型</b>的需求：
 * 外部模组注册一个条件工厂后，任何持有 id 的一方（包括命令、GUI、其他模组）
 * 都能据此为某个坐标构建对应的到达判定。</p>
 *
 * <p>内置条件：{@code chunkscanner:player_near}（玩家靠近目标点即判定到达，
 * 距离取自全局配置）。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 注册自定义条件
 * NavigationConditionRegistry.register(
 *         new Identifier("qab", "chest_opened"),
 *         (x, y, z, dim) -> client -> myChestOpenedCheck(x, y, z));
 *
 * // 按 id 构建并入队
 * NavigationCondition cond = NavigationConditionRegistry.create(
 *         new Identifier("qab", "chest_opened"), x, y, z, dimensionId);
 * nav.enqueue(x, y, z, dimensionId, cond);
 * }</pre>
 */
public final class NavigationConditionRegistry {

    /** 内置条件 id：玩家靠近目标点。 */
    public static final Identifier PLAYER_NEAR = ChunkScannerMod.id("player_near");

    private static final Map<Identifier, Factory> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put(PLAYER_NEAR, (x, y, z, dimensionId) ->
                new PlayerNearCondition(x, y, z,
                        ChunkScannerNavigation.ChunkScannerConfigHolder.reachDist()));
    }

    private NavigationConditionRegistry() {}

    /**
     * 条件工厂：为指定坐标构建一个到达判定条件。
     */
    @FunctionalInterface
    public interface Factory {
        /**
         * @param x           目标方块 X
         * @param y           目标方块 Y
         * @param z           目标方块 Z
         * @param dimensionId 目标维度 id（如 {@code minecraft:overworld}）
         * @return 到达判定条件，不可为 {@code null}
         */
        NavigationCondition create(int x, int y, int z, String dimensionId);
    }

    /**
     * 注册一个条件工厂。重复注册同 id 会覆盖旧值。
     *
     * @param id      条件唯一标识符，建议使用调用方自己的命名空间
     * @param factory 条件工厂
     * @return {@code true} 表示注册成功；参数非法时返回 {@code false}
     */
    public static boolean register(Identifier id, Factory factory) {
        if (id == null || factory == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register navigation condition with null id or factory, ignored");
            return false;
        }
        FACTORIES.put(id, factory);
        ChunkScannerMod.LOGGER.info("Registered navigation condition: {}", id);
        return true;
    }

    /**
     * 注销一个条件工厂。内置条件 {@link #PLAYER_NEAR} 不可注销。
     *
     * @return {@code true} 表示确实移除了一个工厂
     */
    public static boolean unregister(Identifier id) {
        if (id == null || PLAYER_NEAR.equals(id)) return false;
        return FACTORIES.remove(id) != null;
    }

    /** 指定 id 是否已注册。 */
    public static boolean contains(Identifier id) {
        return id != null && FACTORIES.containsKey(id);
    }

    /** 通过 id 获取条件工厂，未注册返回 {@code null}。 */
    public static Factory get(Identifier id) {
        return id == null ? null : FACTORIES.get(id);
    }

    /** 获取所有已注册的条件 id（只读，保持注册顺序）。 */
    public static Collection<Identifier> ids() {
        return Collections.unmodifiableCollection(FACTORIES.keySet());
    }

    /**
     * 按 id 为指定坐标构建到达条件。
     *
     * @return 构建出的条件；id 未注册时回退为内置 {@link #PLAYER_NEAR}
     */
    public static NavigationCondition create(Identifier id, int x, int y, int z, String dimensionId) {
        Factory factory = get(id);
        if (factory == null) {
            ChunkScannerMod.LOGGER.warn("Navigation condition '{}' not registered, falling back to {}", id, PLAYER_NEAR);
            factory = FACTORIES.get(PLAYER_NEAR);
        }
        return factory.create(x, y, z, dimensionId);
    }
}
