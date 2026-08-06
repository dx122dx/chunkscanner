package com.billy65536.chunkscanner.core.navigation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.MinecraftClient;

import com.billy65536.chunkscanner.ChunkScannerMod;

/**
 * 独立导航实例的 tick 托管器。
 *
 * <p>{@link ChunkScannerNavigation#create(String)} 创建的独立实例默认不会被自动推进。
 * 将实例注册到本托管器后，ChunkScanner 会在每个客户端 tick 中代为调用
 * {@link ChunkScannerNavigation#tick(MinecraftClient)}，调用方无需自行挂钩事件。</p>
 *
 * <p>全局实例由模组内部直接驱动，无需也不应注册到此处。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ChunkScannerNavigation nav = ChunkScannerNavigation.create("qab");
 * NavigationTickDispatcher.register(nav);
 * // ... 使用完毕后
 * NavigationTickDispatcher.unregister(nav);
 * }</pre>
 */
public final class NavigationTickDispatcher {

    private static final List<ChunkScannerNavigation> MANAGED = new CopyOnWriteArrayList<>();

    private NavigationTickDispatcher() {}

    /**
     * 注册一个独立导航实例，由 ChunkScanner 代为每 tick 推进。
     *
     * <p>重复注册同一实例无效果；全局实例（{@link ChunkScannerNavigation#get()}）会被忽略，
     * 因为它已由模组内部驱动，重复 tick 会导致到达判定被执行两次。</p>
     *
     * @param navigation 独立导航实例，{@code null} 时忽略
     * @return {@code true} 表示本次调用完成了注册
     */
    public static boolean register(ChunkScannerNavigation navigation) {
        if (navigation == null || navigation.isGlobal()) return false;
        if (MANAGED.contains(navigation)) return false;
        MANAGED.add(navigation);
        ChunkScannerMod.LOGGER.info("Registered managed navigation instance: {}", navigation.getName());
        return true;
    }

    /**
     * 注销托管的导航实例。
     *
     * @return {@code true} 表示确实移除了一个实例
     */
    public static boolean unregister(ChunkScannerNavigation navigation) {
        if (navigation == null) return false;
        boolean removed = MANAGED.remove(navigation);
        if (removed) {
            ChunkScannerMod.LOGGER.info("Unregistered managed navigation instance: {}", navigation.getName());
        }
        return removed;
    }

    /** 返回当前被托管的实例数量。 */
    public static int size() {
        return MANAGED.size();
    }

    /**
     * 推进所有被托管的导航实例。
     *
     * <p>由 ChunkScanner 客户端 tick 内部调用，外部模组无需调用。
     * 单个实例抛出的异常会被捕获并记录，不影响其他实例。</p>
     */
    public static void tickAll(MinecraftClient client) {
        if (MANAGED.isEmpty()) return;
        for (ChunkScannerNavigation nav : MANAGED) {
            try {
                nav.tick(client);
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("Managed navigation '{}' threw during tick: {}",
                        nav.getName(), e.toString());
            }
        }
    }
}
