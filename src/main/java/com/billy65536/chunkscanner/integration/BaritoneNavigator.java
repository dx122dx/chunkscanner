package com.billy65536.chunkscanner.integration;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import com.billy65536.chunkscanner.ChunkScannerMod;

/**
 * Baritone 自动寻路集成（反射，可选依赖）。
 *
 * <p>与 {@link XaeroWaypointHelper} 同风格：通过 {@link FabricLoader#isModLoaded(String)}
 * 检测 Baritone 是否安装，使用反射调用 Baritone API 避免硬编译依赖。</p>
 *
 * <p>核心职责：只管"走"，不关心为何走或走到后做什么。</p>
 */
public final class BaritoneNavigator {

    private static volatile Boolean available;
    private static boolean initialized;
    private static Object baritone;
    private static Object customGoalProcess;
    private static Constructor<?> goalBlockCtorInt;
    private static Constructor<?> goalBlockCtorBlockPos;
    private static Constructor<?> blockPosCtor;
    private static Constructor<?> goalCompositeCtor;
    private static Class<?> goalClass;
    private static Method setGoalAndPath;
    private static Method onLostControl;

    private BaritoneNavigator() {}

    /* ==================== 可用性检测 ==================== */

    /**
     * 当前环境中 Baritone 是否可用。
     * 首次调用时若 mod 已安装会触发反射初始化；初始化失败则返回 false。
     */
    public static boolean isAvailable() {
        if (available == null) {
            available = FabricLoader.getInstance().isModLoaded("baritone");
            if (available) {
                ensureInit();
            }
        }
        return available != null && available;
    }

    /* ==================== 懒初始化 ==================== */

    private static void ensureInit() {
        if (!FabricLoader.getInstance().isModLoaded("baritone")) return;
        if (initialized) return;
        synchronized (BaritoneNavigator.class) {
            if (initialized) return;
            try {
                initReflection();
                initialized = true;
                available = true;
                ChunkScannerMod.LOGGER.info("BaritoneNavigator: reflection initialized successfully.");
            } catch (Exception e) {
                ChunkScannerMod.LOGGER.warn("BaritoneNavigator: failed to init reflection: {}", e.getMessage());
                available = false;
            }
        }
    }

    private static void initReflection() throws Exception {
        ClassLoader cl = BaritoneNavigator.class.getClassLoader();

        // BaritoneAPI.getProvider()
        Class<?> baritoneApiClass = Class.forName("baritone.api.BaritoneAPI", true, cl);
        Method getProvider = baritoneApiClass.getMethod("getProvider");
        Object provider = getProvider.invoke(null);
        if (provider == null) {
            throw new IllegalStateException("Baritone provider returned null (not in a world?)");
        }

        // provider.getPrimaryBaritone()
        Method getPrimaryBaritone = provider.getClass().getMethod("getPrimaryBaritone");
        baritone = getPrimaryBaritone.invoke(provider);
        if (baritone == null) {
            throw new IllegalStateException("Baritone#getPrimaryBaritone returned null");
        }

        // baritone.getCustomGoalProcess()
        Method getCustomGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess");
        customGoalProcess = getCustomGoalProcess.invoke(baritone);
        if (customGoalProcess == null) {
            throw new IllegalStateException("Baritone#getCustomGoalProcess returned null");
        }

        // GoalBlock 构造：优先 int,int,int；不兼容版本回退 BlockPos
        Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock", true, cl);
        try {
            goalBlockCtorInt = goalBlockClass.getConstructor(int.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            ChunkScannerMod.LOGGER.warn("BaritoneNavigator: GoalBlock(int,int,int) not found, "
                    + "falling back to GoalBlock(BlockPos).");
            goalBlockCtorInt = null;
        }

        // 如果 int 构造不存在，准备 BlockPos 回退路径
        if (goalBlockCtorInt == null) {
            blockPosCtor = BlockPos.class.getConstructor(int.class, int.class, int.class);
            // GoalBlock(BlockPos) 接受的参数类型是 baritone 自己的 BlockPos，但 BetterBlockPos extends BlockPos
            // 且 GoalBlock 构造也接受 Minecraft 原生 BlockPos（BetterBlockPos 的超类）
            goalBlockCtorBlockPos = goalBlockClass.getConstructor(BlockPos.class);
        }

        // GoalComposite(Goal[])
        goalClass = Class.forName("baritone.api.pathing.goals.Goal", true, cl);
        Class<?> goalCompositeClass = Class.forName("baritone.api.pathing.goals.GoalComposite", true, cl);
        goalCompositeCtor = goalCompositeClass.getConstructor(goalClass.arrayType());

        // ICustomGoalProcess.setGoalAndPath(Goal)
        setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);

        // ICustomGoalProcess.onLostControl() — 取消当前导航
        try {
            onLostControl = customGoalProcess.getClass().getMethod("onLostControl");
        } catch (NoSuchMethodException e) {
            onLostControl = null;
        }
    }

    /* ==================== GoalBlock 构造辅助 ==================== */

    /**
     * 反射构造一个 GoalBlock 实例。
     */
    private static Object makeGoalBlock(int x, int y, int z) throws Exception {
        if (goalBlockCtorInt != null) {
            return goalBlockCtorInt.newInstance(x, y, z);
        }
        Object bp = blockPosCtor.newInstance(x, y, z);
        return goalBlockCtorBlockPos.newInstance(bp);
    }

    /* ==================== 导航操作 ==================== */

    /**
     * 设置单个目标的导航路径。
     *
     * @return 是否成功
     */
    public static boolean navigateTo(int x, int y, int z) {
        ensureInit();
        if (customGoalProcess == null || setGoalAndPath == null) return false;
        if (goalBlockCtorInt == null && goalBlockCtorBlockPos == null) return false;
        try {
            Object goal = makeGoalBlock(x, y, z);
            setGoalAndPath.invoke(customGoalProcess, goal);
            return true;
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("BaritoneNavigator: navigateTo({},{},{}) failed: {}", x, y, z, e.getMessage());
            return false;
        }
    }

    /**
     * 使用 GoalComposite 打包多个坐标，让 Baritone 自动选择最近点行走。
     *
     * @param positions 二维数组 {@code [[x,y,z], ...]}
     * @return 是否成功
     */
    public static boolean navigateComposite(int[][] positions) {
        ensureInit();
        if (customGoalProcess == null || goalCompositeCtor == null
                || setGoalAndPath == null || goalClass == null) return false;
        if (goalBlockCtorInt == null && goalBlockCtorBlockPos == null) return false;
        if (positions == null || positions.length == 0) return false;
        try {
            // 用 Array.newInstance 构造真正的 Goal[] 数组，消除 Object[] 类型不匹配
            Object goalsArray = Array.newInstance(goalClass, positions.length);
            for (int i = 0; i < positions.length; i++) {
                Array.set(goalsArray, i, makeGoalBlock(positions[i][0], positions[i][1], positions[i][2]));
            }
            // newInstance(new Object[]{goalsArray}) 确保 Goal[] 不被展开为单个 GoalBlock 参数
            Object composite = goalCompositeCtor.newInstance(new Object[]{ goalsArray });
            setGoalAndPath.invoke(customGoalProcess, composite);
            return true;
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("BaritoneNavigator: navigateComposite failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 取消当前导航。
     */
    public static void cancel() {
        ensureInit();
        if (customGoalProcess == null) return;
        try {
            if (onLostControl != null) {
                onLostControl.invoke(customGoalProcess);
            }
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("BaritoneNavigator: cancel via onLostControl failed: {}", e.getMessage());
            // 回退：某些版本无 onLostControl，尝试 setGoalAndPath(null)
            try {
                if (setGoalAndPath != null) {
                    setGoalAndPath.invoke(customGoalProcess, (Object) null);
                }
            } catch (Exception e2) {
                ChunkScannerMod.LOGGER.warn("BaritoneNavigator: cancel fallback also failed: {}", e2.getMessage());
            }
        }
    }
}
