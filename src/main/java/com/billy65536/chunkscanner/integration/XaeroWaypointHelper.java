package com.billy65536.chunkscanner.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.chunkscanner.core.LocatedPosition;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 与 Xaero's World Map / Minimap 联动的反射工具类。
 *
 * <p>因为 Xaero 的 mod 是可选依赖，所有调用均通过反射完成。
 * 如果 Xaero 未安装，则回退到聊天消息显示坐标。</p>
 *
 * <p>路径点参数（名称、缩写、组）由配置决定，可通过全局配置或任务级配置覆盖。</p>
 *
 * <p>API 调用链（Xaero's Minimap 24.x）：
 * BuiltInHudModules.MINIMAP.getCurrentSession() → MinimapSession
 *   .getWorldManager() → MinimapWorldManager
 *   .getCurrentWorld() → MinimapWorld
 *   .getWaypointSet(group) / .getCurrentWaypointSet() → WaypointSet
 *   再通过 WaypointSet.add(Waypoint) 添加。</p>
 */
public final class XaeroWaypointHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkScanner|Xaero");

    /** 默认路径点名称。 */
    private static final String DEFAULT_WAYPOINT_NAME = "选中的坐标点";
    /** 默认路径点缩写。 */
    private static final String DEFAULT_WAYPOINT_INITIALS = "目标";
    /** 默认路径点颜色 (ARGB AQUA)。 */
    private static final int DEFAULT_WAYPOINT_COLOR = 0xFF55FFFF;
    /** Xaero 默认世界容器键名。 */
    private static final String DEFAULT_WORLD_KEY = "waypoints";
    /** Xaero 默认路径点组名。 */
    private static final String DEFAULT_WAYPOINT_SET_NAME = "gui.waypoints";

    // ==================== 缓存反射句柄 ====================

    private static volatile boolean initialized;
    private static volatile Boolean minimapAvailable;
    private static volatile Constructor<?> waypointConstructor;
    private static volatile Class<?> waypointClass;       // xaero.common.minimap.waypoints.Waypoint
    private static volatile Object minimapModule;         // BuiltInHudModules.MINIMAP 实例
    private static volatile Object defaultWaypointColor;  // WaypointColor 枚举实例
    private static volatile Object defaultWaypointPurpose; // WaypointPurpose.NORMAL
    private static volatile Method getCurrentSession;
    private static volatile Method getWorldManager;
    private static volatile Method getCurrentWorld;
    private static volatile Method getCurrentWaypointSet;
    private static volatile Method getNamedWaypointSet;   // getWaypointSet(String)
    private static volatile Method addWaypointSetByName;  // addWaypointSet(String) — 创建新组
    private static volatile Method getWorldManagerIO;     // MinimapSession.getWorldManagerIO()
    private static volatile Method saveWorldMethod;       // MinimapWorldManagerIO.saveWorld(MinimapWorld)

    // 跨维度路径点创建所需的反射句柄
    // XaeroPath：用于构建到目标维度容器的路径
    private static volatile Class<?> xaeroPathClass;              // xaero.hud.path.XaeroPath
    private static volatile Method xaeroPathRoot;                 // XaeroPath.root(String)
    private static volatile Method xaeroPathResolve;              // XaeroPath.resolve(String)
    private static volatile Method xaeroPathGetRoot;              // XaeroPath.getRoot()
    private static volatile Method xaeroPathGetLastNode;          // XaeroPath.getLastNode()
    // MinimapWorldManager.addWorldContainer(XaeroPath)：获取维度容器，配合 getFirstWorld() 避免子世界冲突
    private static volatile Method worldManagerAddWorldContainer; // MinimapWorldManager.addWorldContainer(XaeroPath)
    private static volatile Method containerGetFirstWorld;        // MinimapWorldContainer.getFirstWorld()
    private static volatile Method containerAddWorldMethod;       // MinimapWorldContainer.addWorld(String)
    // worldState：获取 autoWorldPath 以提取根容器名
    private static volatile Method getWorldState;                 // MinimapSession.getWorldState()
    private static volatile Method getAutoWorldPath;              // MinimapWorldState.getAutoWorldPath()
    // 验证与维度目录名解析
    private static volatile Method getWorldDimIdMethod;           // MinimapWorld.getDimId() → RegistryKey<World>
    private static volatile Method getDimensionHelperMethod;      // MinimapSession.getDimensionHelper()
    private static volatile Method getDimensionDirNameMethod;     // MinimapDimensionHelper.getDimensionDirectoryName(RegistryKey<World>)

    // 回退路径反射句柄（在 initMinimapReflection 中缓存，避免 resolveFallbackWaypointSet 每次扫描类元数据）
    private static volatile Method fallbackGetWaypointSet;
    private static volatile Method fallbackGetCurrentWaypointSet;
    private static volatile Method fallbackGetIterableWaypointSets;

    private XaeroWaypointHelper() {}

    /**
     * 检查 Xaero 地图 mod 是否已加载（用于决定 UI 提示文本）。
     * 优先使用 FabricLoader.isModLoaded()（Fabric 标准方式），
     * 回退到 Class.forName() 检查关键类。
     *
     * <p>支持 Xaero's Minimap (xaerominimap) 和 Xaero's World Map (xaeroworldmap)。</p>
     */
    public static boolean isAvailable() {
        if (minimapAvailable != null) return minimapAvailable;

        // 方式 1：FabricLoader.isModLoaded（最可靠）
        boolean loaded = FabricLoader.getInstance().isModLoaded("xaerominimap")
                || FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        if (loaded) {
            LOGGER.info("Xaero mod detected via FabricLoader");
            minimapAvailable = initMinimapReflection();
            return minimapAvailable;
        }

        // 方式 2：Class.forName 回退
        try {
            Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class.forName("xaero.hud.minimap.BuiltInHudModules");
            LOGGER.info("Xaero mod detected via Class.forName (fallback)");
            minimapAvailable = initMinimapReflection();
            return minimapAvailable;
        } catch (ClassNotFoundException e) {
            LOGGER.info("Xaero mod not found");
            minimapAvailable = false;
            initialized = true; // ClassNotFoundException 是永久性失败，标记已初始化避免重试
            return false;
        }
    }

    /**
     * 尝试创建 Xaero 路径点（使用默认名称、缩写和组）。
     *
     * @return true 表示成功创建，false 表示 Xaero 不可用（回退聊天消息）
     */
    public static boolean tryCreateWaypoint(LocatedPosition pos) {
        return tryCreateWaypoint(pos, DEFAULT_WAYPOINT_NAME, DEFAULT_WAYPOINT_INITIALS, null);
    }

    /**
     * 尝试创建 Xaero 路径点（使用指定的名称、缩写和组）。
     *
     * @param pos      目标坐标
     * @param name     路径点名称（null 使用默认值）
     * @param initials 路径点缩写（null 使用默认值）
     * @param group    路径点组名（null 使用当前组）
     * @return true 表示成功创建，false 表示回退聊天消息
     */
    public static boolean tryCreateWaypoint(LocatedPosition pos, String name, String initials, String group) {
        if (pos == null) return false;

        String wpName = (name != null && !name.isEmpty()) ? name : DEFAULT_WAYPOINT_NAME;
        String wpInit = (initials != null && !initials.isEmpty()) ? initials : DEFAULT_WAYPOINT_INITIALS;

        if (tryCreateViaMinimap(pos, wpName, wpInit, group)) return true;

        // Xaero 不可用，发送聊天消息
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[ChunkScanner] ").formatted(Formatting.GOLD)
                            .append(Text.translatable("chunkscanner.waypoint.coordinates",
                                    pos.toString()).formatted(Formatting.AQUA)),
                    false);
        }
        return false;
    }

    // ==================== Minimap 集成（主路径） ====================

    /** 通过 Xaero Minimap 创建路径点。 */
    private static boolean tryCreateViaMinimap(LocatedPosition pos, String name, String initials, String group) {
        try {
            if (!initMinimapReflection()) return false;

            // BuiltInHudModules.MINIMAP.getCurrentSession() → MinimapSession
            Object session = getCurrentSession.invoke(getMinimapModule());
            if (session == null) {
                LOGGER.warn("getCurrentSession() returned null — minimap session not active?");
                return false;
            }

            // 查找路径点所属维度的 MinimapWorld（而非玩家当前维度）
            Object targetWorld = getWorldForDimension(session, pos.dimensionId());
            if (targetWorld == null) {
                LOGGER.warn("Could not find MinimapWorld for dimension {}", pos.dimensionId());
                return false;
            }

            // 在目标世界中解析 WaypointSet
            Object waypointSet = resolveWaypointSetForWorld(targetWorld, group);
            if (waypointSet == null) return false;

            Object wp = createWaypoint(pos, name, initials);
            if (wp == null) return false;

            // 通过 WaypointSet.add(Waypoint) 添加
            if (!addWaypointToSet(waypointSet, wp)) {
                LOGGER.warn("Failed to add waypoint to set");
                return false;
            }

            // 触发持久化保存（避免维度切换后丢失）
            saveWorld(session, targetWorld);

            sendSuccessMessage(pos, name);
            return true;
        } catch (Exception e) {
            LOGGER.info("Minimap waypoint creation failed: {}", e.toString());
            return false;
        }
    }

    /**
     * 根据目标维度 ID 查找或创建对应的 MinimapWorld。
     *
     * <p>Xaero 内部使用多级容器树组织不同维度的路径点。每个维度容器
     * （如 dim%0）内有一个 {@code Map<String, MinimapWorld> worlds}，
     * 默认 key 为 {@code "waypoints"}。</p>
     *
     * <p>当玩家在维度内创建"子世界"时，Xaero 调用
     * {@code container.addWorld("子世界名")}，该方法会找到已有
     * {@code worlds["waypoints"]}，将其重命名（setNode）后存入
     * {@code worlds["子世界名"]}，并从 Map 中移除
     * {@code worlds["waypoints"]}。</p>
     *
     * <p>此后如果再调用 {@code container.addWorld("waypoints")}，
     * 因为 "waypoints" key 已被移除，Xaero 会创建一个全新的空世界，
     * 导致路径点写入孤立子世界而非已有子世界。</p>
     *
     * <p>因此：
     * <ul>
     *   <li>目标维度 = 玩家当前维度 → 使用 {@code getCurrentWorld()}
     *       以正确处理子世界</li>
     *   <li>目标维度 ≠ 玩家当前维度 → 先通过
     *       {@code addWorldContainer(dimPath)} 获取维度容器，
     *       再调用 {@code container.getFirstWorld()} 读取已有世界
     *       （无视键名，避免 addWorld 的重命名副作用）。
     *       仅在容器为空时创建默认 {@code "waypoints"} 世界。</li>
     * </ul></p>
     *
     * @param session     MinimapSession
     * @param dimensionId 目标维度标识符（如 {@code "minecraft:the_end"}）
     * @return 目标维度对应的 MinimapWorld，失败返回 {@code null}
     */
    private static Object getWorldForDimension(Object session, String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) return null;
        try {
            // 1. 构造 RegistryKey<World>
            RegistryKey<World> targetKey = RegistryKey.of(
                    RegistryKeys.WORLD, new Identifier(dimensionId));

            // 2. 获取当前世界和 worldManager
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) {
                LOGGER.warn("getWorldManager() returned null");
                return null;
            }
            Object currentWorld = getCurrentWorld.invoke(worldManager);
            if (currentWorld == null) {
                LOGGER.warn("getCurrentWorld() returned null");
                return null;
            }

            // 3. 检查目标维度是否与当前维度相同
            //    同维度 → 直接使用 getCurrentWorld()（正确处理子世界）
            if (getWorldDimIdMethod != null) {
                try {
                    Object currentDimKey = getWorldDimIdMethod.invoke(currentWorld);
                    if (targetKey.equals(currentDimKey)) {
                        LOGGER.debug("Target dimension {} matches current world, using getCurrentWorld()",
                                dimensionId);
                        return currentWorld;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to compare dimension keys for current world, falling back to cross-dimension resolution: {}", e.getMessage());
                }
            }

            // 4. 跨维度：通过 autoWorldPath 提取根容器名
            if (getWorldState == null || getAutoWorldPath == null) {
                LOGGER.warn("WorldState reflection not available");
                return null;
            }
            Object worldState = getWorldState.invoke(session);
            if (worldState == null) return null;
            Object autoWorldPath = getAutoWorldPath.invoke(worldState);
            if (autoWorldPath == null) return null;

            // 5. 提取根容器名（autoWorldPath.getRoot().getLastNode()）
            Object autoRootPath = xaeroPathGetRoot.invoke(autoWorldPath);
            String rootName = (String) xaeroPathGetLastNode.invoke(autoRootPath);

            // 6. 通过 DimensionHelper 获取目标维度目录名
            if (getDimensionHelperMethod == null || getDimensionDirNameMethod == null) {
                LOGGER.warn("DimensionHelper reflection not available");
                return null;
            }
            Object dimHelper = getDimensionHelperMethod.invoke(session);
            String dimDirName = (String) getDimensionDirNameMethod.invoke(dimHelper, targetKey);
            if (dimDirName == null) {
                LOGGER.warn("Dimension directory name is null for {}", dimensionId);
                return null;
            }

            // 7. 构建目标维度容器路径：root(rootName).resolve(dimDirName)
            if (xaeroPathClass == null || xaeroPathRoot == null || xaeroPathResolve == null) {
                LOGGER.warn("XaeroPath reflection not available");
                return null;
            }
            if (worldManagerAddWorldContainer == null) {
                LOGGER.warn("worldManager.addWorldContainer(XaeroPath) not available");
                return null;
            }
            Object rootPath = xaeroPathRoot.invoke(null, rootName);
            Object dimPath = xaeroPathResolve.invoke(rootPath, dimDirName);

            // 8. 获取目标维度容器，然后通过 getFirstWorld() 获取已有世界
            //    关键：不能使用 addWorld(XaeroPath) 或 container.addWorld("waypoints")，
            //    因为如果该维度已有子世界（worlds["waypoints"] 被 remove），
            //    addWorld("waypoints") 会创建全新空世界导致路径点写入孤立子世界。
            //    正确做法：getFirstWorld() 直接读取 worlds Map 的第一个值，无视键名。
            Object dimContainer = worldManagerAddWorldContainer.invoke(worldManager, dimPath);
            if (dimContainer == null) {
                LOGGER.warn("addWorldContainer() returned null for dimension {}", dimensionId);
                return null;
            }
            Object targetWorld;
            if (containerGetFirstWorld != null) {
                try {
                    targetWorld = containerGetFirstWorld.invoke(dimContainer);
                } catch (Exception e) {
                    LOGGER.warn("getFirstWorld() invocation failed: {}", e.getMessage());
                    return null;
                }
            } else {
                LOGGER.warn("MinimapWorldContainer.getFirstWorld() not available");
                return null;
            }
            if (targetWorld == null) {
                // 维度容器中没有任何世界，首次访问，创建默认世界
                LOGGER.debug("No existing world in container for {}, creating new", dimensionId);
                if (containerAddWorldMethod != null) {
                    try {
                        targetWorld = containerAddWorldMethod.invoke(dimContainer, DEFAULT_WORLD_KEY);
                    } catch (Exception e) {
                        LOGGER.warn("addWorld() invocation failed: {}", e.getMessage());
                        return null;
                    }
                } else {
                    LOGGER.warn("MinimapWorldContainer.addWorld(String) not available");
                    return null;
                }
            }
            if (targetWorld == null) {
                LOGGER.warn("Failed to resolve MinimapWorld for dimension {}", dimensionId);
                return null;
            }

            // 9. 安全校验：确认获取到的世界确实属于目标维度
            if (getWorldDimIdMethod != null) {
                try {
                    Object dimKey = getWorldDimIdMethod.invoke(targetWorld);
                    if (!targetKey.equals(dimKey)) {
                        LOGGER.warn("getWorldForDimension returned world with mismatched dimId: expected {}, got {}",
                                dimensionId, dimKey);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to verify dimension match after cross-dim resolution: {}", e.getMessage());
                }
            }

            LOGGER.debug("Resolved MinimapWorld for dimension {} (root={}, dir={})",
                    dimensionId, rootName, dimDirName);
            return targetWorld;
        } catch (Exception e) {
            LOGGER.warn("getWorldForDimension failed for {}: {}", dimensionId, e.getMessage());
            return null;
        }
    }

    /**
     * 在指定的 MinimapWorld 中获取 WaypointSet。
     * 优先按 group 名称获取指定组，回退到当前 WaypointSet。
     */
    private static Object resolveWaypointSetForWorld(Object world, String group) {
        try {
            // 优先：按配置的 group 获取指定 WaypointSet
            // 如果组不存在，自动创建（否则切换维度后自定义组不存在导致创建失败）
            if (group != null && !group.isEmpty() && getNamedWaypointSet != null) {
                try {
                    Object namedSet = getNamedWaypointSet.invoke(world, group);
                    if (namedSet != null) {
                        return namedSet;
                    }
                    // 组不存在，尝试创建（MinimapWorld.addWaypointSet(String)）
                    if (addWaypointSetByName != null) {
                        addWaypointSetByName.invoke(world, group);
                        namedSet = getNamedWaypointSet.invoke(world, group);
                        if (namedSet != null) {
                            LOGGER.info("Created new waypoint set \"{}\" in target world", group);
                            return namedSet;
                        }
                    }
                    LOGGER.debug("getWaypointSet(\"{}\") returned null, falling back to current set", group);
                } catch (Exception e) {
                    LOGGER.debug("getWaypointSet(\"{}\") failed: {}", group, e.getMessage());
                }
            }

            // 回退：world.getCurrentWaypointSet()
            if (getCurrentWaypointSet != null) {
                Object waypointSet = getCurrentWaypointSet.invoke(world);
                if (waypointSet == null) {
                    // currentWaypointSetId 可能未初始化，尝试创建默认组
                    LOGGER.warn("getCurrentWaypointSet() returned null, trying to create default set");
                    if (addWaypointSetByName != null) {
                        try {
                            addWaypointSetByName.invoke(world, DEFAULT_WAYPOINT_SET_NAME);
                            waypointSet = getCurrentWaypointSet.invoke(world);
                            if (waypointSet == null) {
                                waypointSet = getNamedWaypointSet != null
                                        ? getNamedWaypointSet.invoke(world, DEFAULT_WAYPOINT_SET_NAME) : null;
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Failed to create default waypoint set: {}", e.getMessage());
                        }
                    }
                    if (waypointSet == null) {
                        return resolveFallbackWaypointSet(world);
                    }
                }
                return waypointSet;
            }

            return resolveFallbackWaypointSet(world);
        } catch (Exception e) {
            LOGGER.warn("resolveWaypointSetForWorld failed: {}", e.getMessage());
            return null;
        }
    }

    /** 备用：通过缓存的反射句柄获取路径点集并返回第一个非空集合。 */
    private static Object resolveFallbackWaypointSet(Object world) {
        try {
            // 尝试 getWaypointSet(String) 用默认 set 名
            if (fallbackGetWaypointSet != null) {
                try {
                    Object waypointSet = fallbackGetWaypointSet.invoke(world, DEFAULT_WAYPOINT_SET_NAME);
                    if (waypointSet != null) return waypointSet;
                } catch (Exception ignored) {}
            }

            // 尝试 getCurrentWaypointSet()
            if (fallbackGetCurrentWaypointSet != null) {
                try {
                    Object waypointSet = fallbackGetCurrentWaypointSet.invoke(world);
                    if (waypointSet != null) return waypointSet;
                } catch (Exception ignored) {}
            }

            // 尝试 getIterableWaypointSets() 返回第一个非空集合
            if (fallbackGetIterableWaypointSets != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Iterable<Object> sets = (Iterable<Object>) fallbackGetIterableWaypointSets.invoke(world);
                    if (sets != null) {
                        for (Object set : sets) {
                            if (set != null) return set;
                        }
                    }
                } catch (Exception ignored) {}
            }

            return null;
        } catch (Exception e) {
            LOGGER.warn("Fallback WaypointSet resolution failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 WaypointSet.add(Waypoint) 添加路径点。
     * 新版 Xaero (24.x) 方法名为 add，旧版可能为 addWaypoint。
     *
     * @return true 如果成功添加到路径点集
     */
    @SuppressWarnings("unchecked")
    private static boolean addWaypointToSet(Object waypointSet, Object waypoint) {
        try {
            Class<?> wpClass = waypointClass;
            if (wpClass == null) {
                LOGGER.warn("Waypoint class not initialized, cannot add waypoint to set");
                return false;
            }

            // 优先使用 add(Waypoint)（新版 Xaero 24.x）
            try {
                Method add = waypointSet.getClass().getMethod("add", wpClass);
                add.invoke(waypointSet, waypoint);
                return true;
            } catch (NoSuchMethodException ignored) {}

            // 回退：addWaypoint(Waypoint)（旧版 Xaero）
            try {
                Method addWaypoint = waypointSet.getClass().getMethod("addWaypoint", wpClass);
                addWaypoint.invoke(waypointSet, waypoint);
                return true;
            } catch (NoSuchMethodException ignored) {}

            // 最后回退：直接操作内部 list 字段
            Field listField = null;
            Class<?> wsClass = waypointSet.getClass();
            for (String fieldName : new String[]{"list", "waypoints", "waypointList"}) {
                try {
                    listField = wsClass.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (listField == null) {
                for (Field f : wsClass.getDeclaredFields()) {
                    if (java.util.List.class.isAssignableFrom(f.getType())) {
                        listField = f;
                        break;
                    }
                }
            }
            if (listField != null) {
                listField.setAccessible(true);
                java.util.List<Object> list = (java.util.List<Object>) listField.get(waypointSet);
                list.add(waypoint);
                return true;
            }

            LOGGER.warn("Could not find a way to add Waypoint to set — no add method, no list field");
            return false;
        } catch (Exception e) {
            LOGGER.warn("addWaypointToSet failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 通过 MinimapSession.getWorldManagerIO().saveWorld(world) 触发路径点持久化。
     * <p>Xaero's Minimap 在维度卸载时才保存路径点。如果玩家在保存前切换维度或退出，
     * 新增的路径点会丢失。显式调用 saveWorld 可立即持久化。</p>
     *
     * @param session MinimapSession
     * @param world   要保存的 MinimapWorld（非当前世界时也需要显式传入）
     */
    private static void saveWorld(Object session, Object world) {
        try {
            if (getWorldManagerIO == null || saveWorldMethod == null) return;
            if (session == null || world == null) return;
            Object io = getWorldManagerIO.invoke(session);
            if (io == null) return;
            saveWorldMethod.invoke(io, world);
        } catch (Exception e) {
            LOGGER.debug("Failed to save world waypoints: {}", e.getMessage());
        }
    }

    // ==================== 反射初始化 ====================

    /**
     * 完整初始化反射句柄。
     * 注意：不使用 minimapAvailable 作为初始化完成标志，
     * 因为 isAvailable() 也会设置它（仅代表类存在）。
     *
     * <p>WaypointColor 和 WaypointPurpose 为可选依赖：
     * 新版 Xaero 可能不存在这些类，此时自动降级使用无需枚举的构造函数。</p>
     */
    private static synchronized boolean initMinimapReflection() {
        if (initialized) return minimapAvailable != null && minimapAvailable;
        try {
            // 1. Waypoint 类及构造函数、相关枚举
            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");

            // WaypointColor/WaypointPurpose: 新版 Xaero (24.x) 位于 xaero.hud.minimap.waypoint
            Class<?> waypointColorClass = tryLoadClass(
                    "xaero.hud.minimap.waypoint.WaypointColor",
                    "xaero.common.minimap.waypoints.WaypointColor");
            Class<?> waypointPurposeClass = tryLoadClass(
                    "xaero.hud.minimap.waypoint.WaypointPurpose",
                    "xaero.common.minimap.waypoints.WaypointPurpose");

            if (waypointColorClass == null && waypointPurposeClass == null) {
                LOGGER.info("WaypointColor/WaypointPurpose not found, using basic constructors");
            }

            // 解析枚举（仅在类存在时）
            if (waypointColorClass != null) {
                defaultWaypointColor = resolveEnumValue(waypointColorClass,
                        "AQUA", "CYAN", "LIGHT_BLUE", "BLUE", "GREEN");
            }
            if (waypointPurposeClass != null) {
                defaultWaypointPurpose = resolveEnumValue(waypointPurposeClass, "NORMAL");
            }

            // 按优先级尝试构造函数
            waypointConstructor = findBestConstructor(
                    waypointClass, waypointColorClass, waypointPurposeClass);
            if (waypointConstructor == null) {
                LOGGER.error("Could not find any usable Waypoint constructor");
                return false;
            }
            LOGGER.info("Waypoint constructor: {} params", waypointConstructor.getParameterCount());

            // 2. BuiltInHudModules.MINIMAP 及其 getCurrentSession()
            Class<?> hudModulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = hudModulesClass.getField("MINIMAP");
            minimapModule = minimapField.get(null);
            getCurrentSession = minimapModule.getClass().getMethod("getCurrentSession");

            // 3. getWorldManager() 存在于 MinimapSession 上（而非其父类 ModuleSession）
            //    必须显式从 MinimapSession 类获取，不能通过 getCurrentSession() 的返回类型
            Class<?> minimapSessionClass = Class.forName("xaero.hud.minimap.module.MinimapSession");
            getWorldManager = minimapSessionClass.getMethod("getWorldManager");

            // 4. WorldManager → getCurrentWorld()
            Class<?> worldManagerClass = getWorldManager.getReturnType();
            getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");

            // 5. MinimapWorld → getCurrentWaypointSet() / getWaypointSet(String)
            Class<?> worldClass = getCurrentWorld.getReturnType();
            try {
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
            } catch (NoSuchMethodException e) {
                getCurrentWaypointSet = null;
            }
            // getWaypointSet(String) 用于按组名获取路径点集
            try {
                getNamedWaypointSet = worldClass.getMethod("getWaypointSet", String.class);
            } catch (NoSuchMethodException e) {
                getNamedWaypointSet = null;
            }
            // addWaypointSet(String) 用于创建不存在的路径点组
            try {
                addWaypointSetByName = worldClass.getMethod("addWaypointSet", String.class);
            } catch (NoSuchMethodException e) {
                addWaypointSetByName = null;
            }
            // 缓存回退路径反射句柄（避免 resolveFallbackWaypointSet 每次扫描类元数据）
            try {
                fallbackGetWaypointSet = worldClass.getMethod("getWaypointSet", String.class);
            } catch (NoSuchMethodException e) {
                fallbackGetWaypointSet = null;
            }
            try {
                fallbackGetCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
            } catch (NoSuchMethodException e) {
                fallbackGetCurrentWaypointSet = null;
            }
            try {
                fallbackGetIterableWaypointSets = worldClass.getMethod("getIterableWaypointSets");
            } catch (NoSuchMethodException e) {
                fallbackGetIterableWaypointSets = null;
            }

            // 6. XaeroPath：用于构建目标维度路径
            try {
                xaeroPathClass = Class.forName("xaero.hud.path.XaeroPath");
                xaeroPathRoot = xaeroPathClass.getMethod("root", String.class);
                xaeroPathResolve = xaeroPathClass.getMethod("resolve", String.class);
                xaeroPathGetRoot = xaeroPathClass.getMethod("getRoot");
                xaeroPathGetLastNode = xaeroPathClass.getMethod("getLastNode");
            } catch (Exception e) {
                xaeroPathClass = null;
                xaeroPathRoot = null;
                xaeroPathResolve = null;
                xaeroPathGetRoot = null;
                xaeroPathGetLastNode = null;
                LOGGER.debug("XaeroPath not available, cross-dimension waypoint creation limited");
            }

            // 7. MinimapWorldManager.addWorldContainer(XaeroPath)：安全获取维度容器（不触发 addWorld 重命名）
            try {
                worldManagerAddWorldContainer = worldManagerClass.getMethod("addWorldContainer", xaeroPathClass);
                // 同时缓存容器类的 getFirstWorld() 和 addWorld(String)
                Class<?> containerClass = worldManagerAddWorldContainer.getReturnType();
                try {
                    containerGetFirstWorld = containerClass.getMethod("getFirstWorld");
                } catch (NoSuchMethodException e) {
                    containerGetFirstWorld = null;
                    LOGGER.debug("MinimapWorldContainer.getFirstWorld() not available");
                }
                try {
                    containerAddWorldMethod = containerClass.getMethod("addWorld", String.class);
                } catch (NoSuchMethodException e) {
                    containerAddWorldMethod = null;
                    LOGGER.debug("MinimapWorldContainer.addWorld(String) not available");
                }
            } catch (NoSuchMethodException e) {
                worldManagerAddWorldContainer = null;
                containerGetFirstWorld = null;
                containerAddWorldMethod = null;
                LOGGER.debug("worldManager.addWorldContainer(XaeroPath) not available");
            }

            // 8. MinimapSession.getWorldState() / MinimapWorldState.getAutoWorldPath()
            try {
                getWorldState = minimapSessionClass.getMethod("getWorldState");
                Class<?> worldStateClass = getWorldState.getReturnType();
                getAutoWorldPath = worldStateClass.getMethod("getAutoWorldPath");
            } catch (NoSuchMethodException e) {
                getWorldState = null;
                getAutoWorldPath = null;
                LOGGER.debug("MinimapWorldState not available");
            }

            // 9. MinimapWorld.getDimId()：用于创建后校验维度匹配
            try {
                getWorldDimIdMethod = worldClass.getMethod("getDimId");
            } catch (NoSuchMethodException e) {
                getWorldDimIdMethod = null;
            }

            // 10. MinimapSession.getDimensionHelper() → MinimapDimensionHelper
            try {
                getDimensionHelperMethod = minimapSessionClass.getMethod("getDimensionHelper");
                Class<?> dimHelperClass = getDimensionHelperMethod.getReturnType();
                getDimensionDirNameMethod = dimHelperClass.getMethod("getDimensionDirectoryName",
                        Class.forName("net.minecraft.class_5321")); // RegistryKey<World>
            } catch (NoSuchMethodException e) {
                getDimensionHelperMethod = null;
                getDimensionDirNameMethod = null;
                LOGGER.debug("DimensionHelper not available");
            }

            // 11. 持久化保存：MinimapSession.getWorldManagerIO() → MinimapWorldManagerIO
            try {
                getWorldManagerIO = minimapSessionClass.getMethod("getWorldManagerIO");
                Class<?> ioClass = getWorldManagerIO.getReturnType();
                saveWorldMethod = ioClass.getMethod("saveWorld", worldClass);
            } catch (NoSuchMethodException e) {
                getWorldManagerIO = null;
                saveWorldMethod = null;
                LOGGER.debug("WorldManagerIO.saveWorld not available, waypoints will be saved on world unload");
            }

            LOGGER.info("Xaero's Minimap integration enabled");
            initialized = true;
            minimapAvailable = true;
            return true;
        } catch (Exception e) {
            LOGGER.info("Xaero's Minimap not available: {}", e.getMessage());
            resetReflectionHandles();
            initialized = true; // 标记已初始化，永久性失败不重复重试
            return false;
        }
    }

    /** 重置所有反射句柄，防止部分初始化状态泄漏导致后续 NPE。 */
    private static void resetReflectionHandles() {
        waypointClass = null;
        waypointConstructor = null;
        minimapModule = null;
        defaultWaypointColor = null;
        defaultWaypointPurpose = null;
        getCurrentSession = null;
        getWorldManager = null;
        getCurrentWorld = null;
        getCurrentWaypointSet = null;
        getNamedWaypointSet = null;
        addWaypointSetByName = null;
        getWorldManagerIO = null;
        saveWorldMethod = null;
        xaeroPathClass = null;
        xaeroPathRoot = null;
        xaeroPathResolve = null;
        xaeroPathGetRoot = null;
        xaeroPathGetLastNode = null;
        worldManagerAddWorldContainer = null;
        containerGetFirstWorld = null;
        containerAddWorldMethod = null;
        getWorldState = null;
        getAutoWorldPath = null;
        getWorldDimIdMethod = null;
        getDimensionHelperMethod = null;
        getDimensionDirNameMethod = null;
        fallbackGetWaypointSet = null;
        fallbackGetCurrentWaypointSet = null;
        fallbackGetIterableWaypointSets = null;
    }

    /**
     * 尝试按顺序加载类，返回第一个成功的，全失败返回 null。
     */
    private static Class<?> tryLoadClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    /**
     * 查找枚举值，尝试多个名称，返回找到的第一个。
     * 如果全不匹配则返回该枚举的第一个常量。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveEnumValue(Class<?> enumClass, String... names) {
        if (enumClass == null) return null;
        for (String name : names) {
            try {
                return Enum.valueOf((Class) enumClass, name);
            } catch (IllegalArgumentException ignored) {}
        }
        Object[] constants = enumClass.getEnumConstants();
        if (constants != null && constants.length > 0) {
            LOGGER.debug("Using fallback enum value for {}: {}",
                    enumClass.getSimpleName(), constants[0]);
            return constants[0];
        }
        return null;
    }

    /**
     * 按优先级查找最佳可用构造函数。
     * 优先级：9 参数完整版 > 8 参数枚举版 > 7 参数枚举版 > 6 参数枚举颜色版
     *      > 8 参数 int 颜色版 > 7 参数 int 颜色版 > 6 参数 int 颜色版
     * colorClass 和 purposeClass 可以为 null（当对应枚举类不存在时跳过）。
     *
     * <p>基于 Xaero's Minimap 24.7.1 反编译分析，Waypoint 提供以下构造器：</p>
     * <ul>
     *   <li>6 参数 int:  (x, y, z, name, initials, colorInt)</li>
     *   <li>7 参数 int:  (x, y, z, name, initials, colorInt, purposeOrdinal)</li>
     *   <li>8 参数 int:  (x, y, z, name, initials, colorInt, purposeOrdinal, temporary)</li>
     *   <li>9 参数 int:  (x, y, z, name, initials, colorInt, purposeOrdinal, temporary, yIncluded)</li>
     *   <li>6 参数 enum: (x, y, z, name, initials, WaypointColor)</li>
     *   <li>7 参数 enum: (x, y, z, name, initials, WaypointColor, WaypointPurpose)</li>
     *   <li>8 参数 enum: (x, y, z, name, initials, WaypointColor, WaypointPurpose, temporary)</li>
     *   <li>9 参数 enum: (x, y, z, name, initials, WaypointColor, WaypointPurpose, temporary, yIncluded) ← 主构造器</li>
     * </ul>
     */
    private static Constructor<?> findBestConstructor(
            Class<?> wpClass, Class<?> colorClass, Class<?> purposeClass) {

        Constructor<?>[] ctors = wpClass.getConstructors();

        // 优先级 1：9 参数完整版（枚举版本，主构造器）
        // (int x, int y, int z, String name, String initials,
        //  WaypointColor color, WaypointPurpose purpose, boolean temp, boolean yIncluded)
        if (colorClass != null && purposeClass != null) {
            for (Constructor<?> c : ctors) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 9
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && p[3] == String.class && p[4] == String.class
                        && p[5] == colorClass && p[6] == purposeClass
                        && p[7] == boolean.class && p[8] == boolean.class) {
                    return c;
                }
            }
        }

        // 优先级 2：8 参数 (x, y, z, name, initials, WaypointColor, WaypointPurpose, boolean)
        if (colorClass != null && purposeClass != null) {
            for (Constructor<?> c : ctors) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 8
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && p[3] == String.class && p[4] == String.class
                        && p[5] == colorClass && p[6] == purposeClass
                        && p[7] == boolean.class) {
                    return c;
                }
            }
        }

        // 优先级 3：7 参数 (x, y, z, name, initials, WaypointColor, WaypointPurpose)
        if (colorClass != null && purposeClass != null) {
            for (Constructor<?> c : ctors) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 7
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && p[3] == String.class && p[4] == String.class
                        && p[5] == colorClass && p[6] == purposeClass) {
                    return c;
                }
            }
        }

        // 优先级 4：6 参数 (int, int, int, String, String, WaypointColor)
        if (colorClass != null) {
            for (Constructor<?> c : ctors) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 6
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && p[3] == String.class && p[4] == String.class
                        && p[5] == colorClass) {
                    return c;
                }
            }
        }

        // 优先级 5：8 参数 int 版 (x, y, z, name, initials, int, int, boolean)
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 8
                    && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3] == String.class && p[4] == String.class
                    && p[5] == int.class && p[6] == int.class
                    && p[7] == boolean.class) {
                return c;
            }
        }

        // 优先级 6：7 参数 int 版 (x, y, z, name, initials, int, int)
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 7
                    && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3] == String.class && p[4] == String.class
                    && p[5] == int.class && p[6] == int.class) {
                return c;
            }
        }

        // 优先级 7：6 参数 int 版 (x, y, z, name, initials, int) 颜色用 int
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 6
                    && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3] == String.class && p[4] == String.class
                    && p[5] == int.class) {
                return c;
            }
        }

        LOGGER.error("No usable Waypoint constructor found among {} candidates", ctors.length);
        return null;
    }

    /** 获取缓存的 BuiltInHudModules.MINIMAP 实例（需先调用 initMinimapReflection）。 */
    private static Object getMinimapModule() {
        return minimapModule;
    }

    /** 通过反射创建 Waypoint 实例。根据构造函数参数数量动态适配。 */
    private static Object createWaypoint(LocatedPosition pos, String name, String initials) {
        try {
            if (waypointConstructor == null && !initMinimapReflection()) return null;
            if (waypointConstructor == null) return null;

            Class<?>[] paramTypes = waypointConstructor.getParameterTypes();
            int count = paramTypes.length;
            boolean lastIsBoolean = count > 0 && paramTypes[count - 1] == boolean.class;

            switch (count) {
                case 9:
                    // 完整版：(x, y, z, name, initials, color/purpose, temp, yIncluded)
                    // findBestConstructor 保证 count==9 时末尾两个参数必为 boolean.class
                    return waypointConstructor.newInstance(
                            pos.x(), pos.y(), pos.z(),
                            name, initials,
                            defaultWaypointColor != null ? defaultWaypointColor
                                    : paramTypes[5] == int.class ? 0 : null,
                            defaultWaypointPurpose != null ? defaultWaypointPurpose
                                    : paramTypes[6] == int.class ? 0 : null,
                            false, true);
                case 8:
                    // (x, y, z, name, initials, color/purpose, temp)
                    // 或 (x, y, z, name, initials, int, int, boolean)
                    if (paramTypes[5] == int.class && paramTypes[6] == int.class && lastIsBoolean) {
                        // int 颜色版：(x, y, z, name, initials, colorInt, purposeOrdinal, temp)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, DEFAULT_WAYPOINT_COLOR, 0, false);
                    } else if (lastIsBoolean) {
                        // 枚举版：(x, y, z, name, initials, WaypointColor, WaypointPurpose, temp)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials,
                                defaultWaypointColor, defaultWaypointPurpose,
                                false);
                    }
                    LOGGER.warn("Unhandled 8-param Waypoint constructor pattern");
                    return null;

                case 7:
                    if (paramTypes[5] == int.class && paramTypes[6] == int.class) {
                        // int 颜色版：(x, y, z, name, initials, colorInt, purposeOrdinal)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, DEFAULT_WAYPOINT_COLOR, 0);
                    } else {
                        // 枚举版：(x, y, z, name, initials, WaypointColor, WaypointPurpose)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials,
                                defaultWaypointColor, defaultWaypointPurpose);
                    }

                case 6:
                    if (paramTypes[5] == int.class) {
                        // int 颜色版：(x, y, z, name, initials, colorInt)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, DEFAULT_WAYPOINT_COLOR);
                    } else {
                        // 枚举颜色版：(x, y, z, name, initials, WaypointColor)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, defaultWaypointColor);
                    }

                default:
                    LOGGER.warn("Unsupported Waypoint constructor param count: {}", count);
                    return null;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to create Waypoint: {}", e.getMessage());
            return null;
        }
    }

    /** 成功创建后发送提示消息。 */
    private static void sendSuccessMessage(LocatedPosition pos, String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[ChunkScanner] ").formatted(Formatting.GOLD)
                            .append(Text.translatable("chunkscanner.waypoint.created",
                                    name, pos.toString()).formatted(Formatting.GREEN)),
                    false);
        }
    }
}
