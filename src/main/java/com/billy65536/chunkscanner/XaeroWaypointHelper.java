package com.billy65536.chunkscanner;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 与 Xaero's World Map / Minimap 联动的反射工具类。
 *
 * <p>因为 Xaero 的 mod 是可选依赖，所有调用均通过反射完成。
 * 如果 Xaero 未安装，则回退到聊天消息显示坐标。</p>
 *
 * <p>路径点命名：名称 "选中的坐标点"，缩写/符号 "目标"。</p>
 *
 * <p>API 参考 XaeroPlus：
 * BuiltInHudModules.MINIMAP.getCurrentSession().getWorldManager()
 *   .getCurrentWorld().getCurrentWaypointSet()
 *   再通过 WaypointSet.addWaypoint(Waypoint) 添加（会触发自动 save）。</p>
 */
public final class XaeroWaypointHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkScanner|Xaero");

    private static final String WAYPOINT_NAME = "选中的坐标点";
    private static final String WAYPOINT_SYMBOL = "目标";

    // ==================== 缓存反射句柄 ====================

    private static volatile Boolean minimapAvailable;
    private static volatile Constructor<?> waypointConstructor;
    private static volatile Object defaultWaypointColor;  // WaypointColor 枚举实例
    private static volatile Object defaultWaypointPurpose; // WaypointPurpose.NORMAL
    private static volatile Method getCurrentSession;
    private static volatile Method getWorldManager;
    private static volatile Method getCurrentWorld;
    private static volatile Method getCurrentWaypointSet;

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
     * 尝试创建 Xaero 路径点。
     *
     * @return true 表示成功创建，false 表示 Xaero 不可用（回退聊天消息）
     */
    public static boolean tryCreateWaypoint(LocatedPosition pos) {
        if (pos == null) return false;

        if (tryCreateViaMinimap(pos)) return true;

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

    /** 通过 Xaero Minimap 创建路径点（参考 XaeroPlus WaypointAPI）。 */
    private static boolean tryCreateViaMinimap(LocatedPosition pos) {
        try {
            if (!initMinimapReflection()) return false;

            // BuiltInHudModules.MINIMAP.getCurrentSession() → MinimapSession
            Object session = getCurrentSession.invoke(getMinimapModule());
            if (session == null) {
                LOGGER.debug("getCurrentSession() returned null");
                return false;
            }

            // session.getWorldManager().getCurrentWorld().getCurrentWaypointSet()
            Object waypointSet = resolveCurrentWaypointSet(session);
            if (waypointSet == null) return false;

            Object wp = createWaypoint(pos);
            if (wp == null) return false;

            // 通过 WaypointSet.addWaypoint() 添加（会触发自动 save）
            addWaypointToSet(waypointSet, wp);

            sendSuccessMessage(pos);
            return true;
        } catch (Exception e) {
            LOGGER.info("Minimap waypoint creation failed: {}", e.toString());
            return false;
        }
    }

    /**
     * 从 MinimapSession 出发，获取当前 WaypointSet。
     * 参考 XaeroPlus：session.getWorldManager().getCurrentWorld().getCurrentWaypointSet()
     */
    private static Object resolveCurrentWaypointSet(Object session) {
        try {
            // session.getWorldManager() → WorldManager
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) {
                LOGGER.debug("getWorldManager() returned null");
                return resolveViaLegacyApi(session);
            }

            // worldManager.getCurrentWorld() → MinimapWorld
            Object world = getCurrentWorld.invoke(worldManager);
            if (world == null) {
                LOGGER.debug("getCurrentWorld() returned null");
                return null;
            }

            // world.getCurrentWaypointSet() → WaypointSet
            Object waypointSet = getCurrentWaypointSet.invoke(world);
            if (waypointSet == null) {
                LOGGER.debug("getCurrentWaypointSet() returned null");
                return resolveFallbackWaypointSet(world);
            }

            return waypointSet;
        } catch (Exception e) {
            LOGGER.debug("resolveCurrentWaypointSet failed: {}", e.getMessage());
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

    /** 备用：通过 world.getWaypointSet("gui.waypoints") 获取路径点集。 */
    private static Object resolveFallbackWaypointSet(Object world) {
        try {
            // 尝试 getWaypointSet(String) 用默认 set 名
            try {
                Method getWaypointSet = world.getClass().getMethod("getWaypointSet", String.class);
                Object waypointSet = getWaypointSet.invoke(world, "gui.waypoints");
                if (waypointSet != null) return waypointSet;
            } catch (NoSuchMethodException ignored) {}

            // 尝试 getWaypoints() 无参
            try {
                Method getWaypoints = world.getClass().getMethod("getWaypoints");
                return getWaypoints.invoke(world);
            } catch (NoSuchMethodException ignored) {}

            return null;
        } catch (Exception e) {
            LOGGER.debug("Fallback WaypointSet resolution failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 WaypointSet.addWaypoint(Waypoint) 添加路径点。
     * 如果找不到 addWaypoint 方法，则回退到直接操作内部 List。
     */
    @SuppressWarnings("unchecked")
    private static void addWaypointToSet(Object waypointSet, Object waypoint) {
        try {
            // 优先使用 addWaypoint() 方法（会触发自动 save）
            try {
                Method addWaypoint = waypointSet.getClass().getMethod("addWaypoint",
                        Class.forName("xaero.common.minimap.waypoints.Waypoint"));
                addWaypoint.invoke(waypointSet, waypoint);
                return;
            } catch (NoSuchMethodException ignored) {}

            // 回退：直接操作内部 list 字段
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
            } else {
                LOGGER.debug("Could not find a way to add Waypoint to set");
            }
        } catch (Exception e) {
            LOGGER.debug("addWaypointToSet failed: {}", e.getMessage());
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
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");

            // WaypointColor/WaypointPurpose 为可选：新版 Xaero 可能不存在这些枚举类
            Class<?> waypointColorClass = tryLoadClass(
                    "xaero.common.minimap.waypoints.WaypointColor",
                    "xaero.common.minimap.waypoint.WaypointColor");
            Class<?> waypointPurposeClass = tryLoadClass(
                    "xaero.common.minimap.waypoints.WaypointPurpose",
                    "xaero.common.minimap.waypoint.WaypointPurpose");

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
            Object minimap = minimapField.get(null);
            getCurrentSession = minimap.getClass().getMethod("getCurrentSession");

            // 3. MinimapSession → getWorldManager()
            Class<?> sessionClass = getCurrentSession.getReturnType();
            getWorldManager = sessionClass.getMethod("getWorldManager");

            // 4. WorldManager → getCurrentWorld()
            Class<?> worldManagerClass = getWorldManager.getReturnType();
            getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");

            // 5. MinimapWorld → getCurrentWaypointSet()
            //    如果不存在则设为 null，由 resolveCurrentWaypointSet 中的 fallback 处理
            Class<?> worldClass = getCurrentWorld.getReturnType();
            try {
                getCurrentWaypointSet = worldClass.getMethod("getCurrentWaypointSet");
            } catch (NoSuchMethodException e) {
                // 旧版 API 无此方法，在 resolveCurrentWaypointSet 中会走 fallback
                getCurrentWaypointSet = null;
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
     * 优先级：9 参数完整版 > 6 参数枚举颜色版 > 6 参数 int 颜色版 > 5 参数最简版
     * colorClass 和 purposeClass 可以为 null（当对应枚举类不存在时跳过）。
     */
    private static Constructor<?> findBestConstructor(
            Class<?> wpClass, Class<?> colorClass, Class<?> purposeClass) {

        Constructor<?>[] ctors = wpClass.getConstructors();

        // 优先级 1：9 参数完整版
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

        // 优先级 2：6 参数 (int, int, int, String, String, WaypointColor)
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

        // 优先级 3：6 参数 (int, int, int, String, String, int) 颜色用 int
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 6
                    && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3] == String.class && p[4] == String.class
                    && p[5] == int.class) {
                return c;
            }
        }

        // 优先级 4：5 参数 (int, int, int, String, String) 最简版
        for (Constructor<?> c : ctors) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 5
                    && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3] == String.class && p[4] == String.class) {
                return c;
            }
        }

        return null;
    }

    private static Object getMinimapModule() throws Exception {
        Class<?> hudModulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
        Field minimapField = hudModulesClass.getField("MINIMAP");
        return minimapField.get(null);
    }

    /** 通过反射创建 Waypoint 实例。根据构造函数参数数量动态适配。 */
    private static Object createWaypoint(LocatedPosition pos) {
        try {
            if (waypointConstructor == null && !initMinimapReflection()) return null;
            if (waypointConstructor == null) return null;

            Class<?>[] paramTypes = waypointConstructor.getParameterTypes();
            int count = paramTypes.length;

            if (count == 9) {
                // 完整版：(x, y, z, name, initials, color, purpose, temp, yIncluded)
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(),
                        WAYPOINT_NAME, WAYPOINT_SYMBOL,
                        defaultWaypointColor, defaultWaypointPurpose,
                        false, true);
            } else if (count == 6) {
                if (paramTypes[5] == int.class) {
                    // int 颜色版：(x, y, z, name, initials, colorInt)
                    return waypointConstructor.newInstance(
                            pos.x(), pos.y(), pos.z(),
                            WAYPOINT_NAME, WAYPOINT_SYMBOL, 0xFF55FFFF);
                } else {
                    // 枚举颜色版：(x, y, z, name, initials, WaypointColor)
                    return waypointConstructor.newInstance(
                            pos.x(), pos.y(), pos.z(),
                            WAYPOINT_NAME, WAYPOINT_SYMBOL, defaultWaypointColor);
                }
            } else {
                // 最简版：(x, y, z, name, initials)
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(), WAYPOINT_NAME, WAYPOINT_SYMBOL);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to create Waypoint: {}", e.getMessage());
            return null;
        }
    }

    /** 成功创建后发送提示消息。 */
    private static void sendSuccessMessage(LocatedPosition pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[ChunkScanner] ").formatted(Formatting.GOLD)
                            .append(Text.translatable("chunkscanner.waypoint.created",
                                    WAYPOINT_NAME, pos.toString()).formatted(Formatting.GREEN)),
                    false);
        }
    }
}
