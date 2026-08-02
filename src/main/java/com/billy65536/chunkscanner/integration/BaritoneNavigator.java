package com.billy65536.chunkscanner.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;

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
    private static Constructor<?> goalBlockCtor;
    private static Constructor<?> goalCompositeCtor;
    private static Method setGoalAndPath;
    private static Method onLostControl;

    private BaritoneNavigator() {}

    /* ==================== 可用性检测 ==================== */

    /**
     * 当前环境中是否安装了 Baritone。
     */
    public static boolean isAvailable() {
        if (available == null) {
            available = FabricLoader.getInstance().isModLoaded("baritone");
        }
        return available;
    }

    /* ==================== 懒初始化 ==================== */

    private static void ensureInit() {
        if (!isAvailable()) return;
        if (initialized) return;
        synchronized (BaritoneNavigator.class) {
            if (initialized) return;
            try {
                initReflection();
                initialized = true;
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

        // provider.getPrimaryBaritone()
        Method getPrimaryBaritone = provider.getClass().getMethod("getPrimaryBaritone");
        baritone = getPrimaryBaritone.invoke(provider);

        // baritone.getCustomGoalProcess()
        Method getCustomGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess");
        customGoalProcess = getCustomGoalProcess.invoke(baritone);

        // GoalBlock(int, int, int)
        Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock", true, cl);
        goalBlockCtor = goalBlockClass.getConstructor(int.class, int.class, int.class);

        // GoalComposite(Goal[])
        Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal", true, cl);
        Class<?> goalCompositeClass = Class.forName("baritone.api.pathing.goals.GoalComposite", true, cl);
        goalCompositeCtor = goalCompositeClass.getConstructor(goalClass.arrayType());

        // ICustomGoalProcess.setGoalAndPath(Goal)
        setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);

        // ICustomGoalProcess.onLostControl() — 取消当前导航
        try {
            onLostControl = customGoalProcess.getClass().getMethod("onLostControl");
        } catch (NoSuchMethodException e) {
            // 某些版本可能没有此方法，回退到 setGoalAndPath(null)
            onLostControl = null;
        }
    }

    /* ==================== 导航操作 ==================== */

    /**
     * 设置单个目标的导航路径。
     *
     * @return 是否成功
     */
    public static boolean navigateTo(int x, int y, int z) {
        ensureInit();
        if (customGoalProcess == null || goalBlockCtor == null || setGoalAndPath == null) return false;
        try {
            Object goal = goalBlockCtor.newInstance(x, y, z);
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
        if (customGoalProcess == null || goalBlockCtor == null || goalCompositeCtor == null
                || setGoalAndPath == null) return false;
        if (positions == null || positions.length == 0) return false;
        try {
            Object[] goals = new Object[positions.length];
            for (int i = 0; i < positions.length; i++) {
                goals[i] = goalBlockCtor.newInstance(positions[i][0], positions[i][1], positions[i][2]);
            }
            Object composite = goalCompositeCtor.newInstance((Object) goals);
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
            } else {
                // 回退：尝试 setGoalAndPath(null)
                setGoalAndPath.invoke(customGoalProcess, (Object) null);
            }
        } catch (Exception e) {
            ChunkScannerMod.LOGGER.warn("BaritoneNavigator: cancel failed: {}", e.getMessage());
        }
    }
}
