package com.billy65536.chunkscanner.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

    // ==================== 缓存反射句柄 ====================

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
            minimapAvailable = true;
            return true;
        }

        // 方式 2：Class.forName 回退
        try {
            Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class.forName("xaero.hud.minimap.BuiltInHudModules");
            LOGGER.info("Xaero mod detected via Class.forName (fallback)");
            minimapAvailable = true;
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.info("Xaero mod not found");
            minimapAvailable = false;
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

            // session.getWorldManager().getCurrentWorld().getWaypointSet(group) / getCurrentWaypointSet()
            Object waypointSet = resolveCurrentWaypointSet(session, group);
            if (waypointSet == null) return false;

            Object wp = createWaypoint(pos, name, initials);
            if (wp == null) return false;

            // 通过 WaypointSet.add(Waypoint) 添加
            if (!addWaypointToSet(waypointSet, wp)) {
                LOGGER.warn("Failed to add waypoint to set");
                return false;
            }

            // 触发持久化保存（避免维度切换后丢失）
            saveCurrentWorld(session);

            sendSuccessMessage(pos, name);
            return true;
        } catch (Exception e) {
            LOGGER.info("Minimap waypoint creation failed: {}", e.toString());
            return false;
        }
    }

    /**
     * 从 MinimapSession 出发，获取 WaypointSet。
     * 优先按 group 名称获取指定组，回退到当前 WaypointSet。
     */
    private static Object resolveCurrentWaypointSet(Object session, String group) {
        try {
            // session.getWorldManager() → WorldManager
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) {
                LOGGER.warn("getWorldManager() returned null");
                return resolveViaLegacyApi(session);
            }

            // worldManager.getCurrentWorld() → MinimapWorld
            Object world = getCurrentWorld.invoke(worldManager);
            if (world == null) {
                LOGGER.warn("getCurrentWorld() returned null");
                return null;
            }

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
                            LOGGER.info("Created new waypoint set \"{}\" in current world", group);
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
                            addWaypointSetByName.invoke(world, "gui.waypoints");
                            waypointSet = getCurrentWaypointSet.invoke(world);
                            if (waypointSet == null) {
                                // 设置了默认组但仍为 null，尝试 getWaypointSet
                                waypointSet = getNamedWaypointSet != null
                                        ? getNamedWaypointSet.invoke(world, "gui.waypoints") : null;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (waypointSet == null) {
                        return resolveFallbackWaypointSet(world);
                    }
                }
                return waypointSet;
            }

            return resolveFallbackWaypointSet(world);
        } catch (Exception e) {
            LOGGER.warn("resolveCurrentWaypointSet failed: {}", e.getMessage());
            return null;
        }
    }

    /** 旧版 API 备用路径：getWaypointsManager().getCurrentContainer() */
    private static Object resolveViaLegacyApi(Object session) {
        try {
            Method gwm = session.getClass().getMethod("getWaypointsManager");
            Object wpManager = gwm.invoke(session);
            if (wpManager == null) return null;

            Method gcc = wpManager.getClass().getMethod("getCurrentContainer");
            return gcc.invoke(wpManager);
        } catch (Exception e) {
            LOGGER.debug("Legacy API fallback also failed: {}", e.getMessage());
            return null;
        }
    }

    /** 备用：通过 world.getIterableWaypointSets() 获取路径点集并返回第一个非空集合。 */
    private static Object resolveFallbackWaypointSet(Object world) {
        try {
            // 尝试 getWaypointSet(String) 用默认 set 名
            try {
                Method getWaypointSet = world.getClass().getMethod("getWaypointSet", String.class);
                Object waypointSet = getWaypointSet.invoke(world, "gui.waypoints");
                if (waypointSet != null) return waypointSet;
            } catch (NoSuchMethodException ignored) {}

            // 尝试 getCurrentWaypointSet()（如果 main path 没走到这里就说明已经试过了，但作为 fallback 再试）
            try {
                Method getCurrent = world.getClass().getMethod("getCurrentWaypointSet");
                Object waypointSet = getCurrent.invoke(world);
                if (waypointSet != null) return waypointSet;
            } catch (NoSuchMethodException ignored) {}

            // 尝试 getIterableWaypointSets() 返回第一个非空集合
            try {
                Method getIterable = world.getClass().getMethod("getIterableWaypointSets");
                @SuppressWarnings("unchecked")
                Iterable<Object> sets = (Iterable<Object>) getIterable.invoke(world);
                if (sets != null) {
                    for (Object set : sets) {
                        if (set != null) return set;
                    }
                }
            } catch (NoSuchMethodException ignored) {}

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
                wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
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
     */
    private static void saveCurrentWorld(Object session) {
        try {
            if (getWorldManagerIO == null || saveWorldMethod == null) return;
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) return;
            Object world = getCurrentWorld.invoke(worldManager);
            if (world == null) return;
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
    private static boolean initMinimapReflection() {
        // 用 waypointConstructor 作为"已完成完整初始化"的标志
        if (waypointConstructor != null) return true;
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
                minimapAvailable = false;
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

            // 6. 持久化保存：MinimapSession.getWorldManagerIO() → MinimapWorldManagerIO
            try {
                getWorldManagerIO = minimapSessionClass.getMethod("getWorldManagerIO");
                Class<?> ioClass = getWorldManagerIO.getReturnType();
                saveWorldMethod = ioClass.getMethod("saveWorld", worldClass);
            } catch (NoSuchMethodException e) {
                getWorldManagerIO = null;
                saveWorldMethod = null;
                LOGGER.debug("WorldManagerIO.saveWorld not available, waypoints will be saved on world unload");
            }

            minimapAvailable = true;
            LOGGER.info("Xaero's Minimap integration enabled");
            return true;
        } catch (Exception e) {
            minimapAvailable = false;
            LOGGER.info("Xaero's Minimap not available: {}", e.getMessage());
            return false;
        }
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
            boolean secondLastIsBoolean = count > 1 && paramTypes[count - 2] == boolean.class;

            switch (count) {
                case 9:
                    // 完整版：(x, y, z, name, initials, color/purpose, temp, yIncluded)
                    if (secondLastIsBoolean && lastIsBoolean) {
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials,
                                defaultWaypointColor != null ? defaultWaypointColor
                                        : paramTypes[5] == int.class ? 0 : null,
                                defaultWaypointPurpose != null ? defaultWaypointPurpose
                                        : paramTypes[6] == int.class ? 0 : null,
                                false, true);
                    }
                    break;

                case 8:
                    // (x, y, z, name, initials, color/purpose, temp)
                    // 或 (x, y, z, name, initials, int, int, boolean)
                    if (paramTypes[5] == int.class && paramTypes[6] == int.class && lastIsBoolean) {
                        // int 颜色版：(x, y, z, name, initials, colorInt, purposeOrdinal, temp)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, 0xFF55FFFF, 0, false);
                    } else if (lastIsBoolean) {
                        // 枚举版：(x, y, z, name, initials, WaypointColor, WaypointPurpose, temp)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials,
                                defaultWaypointColor, defaultWaypointPurpose,
                                false);
                    }
                    break;

                case 7:
                    if (paramTypes[5] == int.class && paramTypes[6] == int.class) {
                        // int 颜色版：(x, y, z, name, initials, colorInt, purposeOrdinal)
                        return waypointConstructor.newInstance(
                                pos.x(), pos.y(), pos.z(),
                                name, initials, 0xFF55FFFF, 0);
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
                                name, initials, 0xFF55FFFF);
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

            LOGGER.warn("Unhandled Waypoint constructor pattern: {} params", count);
            return null;
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
