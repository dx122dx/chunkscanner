package com.billy65536.chunkscanner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 与 Xaero's World Map / Minimap 联动的反射工具类。
 *
 * <p>因为 Xaero 的 mod 是可选依赖，所有调用均通过反射完成。
 * 如果 Xaero 未安装，则回退到聊天消息显示坐标。</p>
 *
 * <p>路径点命名：名称 "选中的坐标点"，缩写/符号 "目标"。</p>
 *
 * <p>API 校验来源：XaeroPlus WaypointAPI，正确的调用链为：
 * BuiltInHudModules.MINIMAP.getCurrentSession().getWorldManager()
 *   .getCurrentWorld().getCurrentWaypointSet()</p>
 */
public final class XaeroWaypointHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkScanner|Xaero");

    private static final String WAYPOINT_NAME = "选中的坐标点";
    private static final String WAYPOINT_SYMBOL = "目标";
    private static final int WAYPOINT_COLOR = 0xFF55FFFF; // 青色

    // ==================== 缓存反射句柄 ====================

    private static volatile Boolean minimapAvailable;
    private static volatile Constructor<?> waypointConstructor;
    private static volatile Method getCurrentSession;
    private static volatile Method getWorldManager;
    private static volatile Method getCurrentWorld;
    private static volatile Method getCurrentWaypointSet;
    private static volatile Field waypointSetListField;

    private XaeroWaypointHelper() {}

    /**
     * 检查 Xaero Minimap 是否已加载（用于决定 UI 提示文本）。
     * 只做轻量的类存在性检查，不初始化完整反射链。
     */
    public static boolean isAvailable() {
        Boolean cached = minimapAvailable;
        if (cached != null) return cached;
        try {
            Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class.forName("xaero.hud.minimap.BuiltInHudModules");
            minimapAvailable = true;
            return true;
        } catch (ClassNotFoundException e) {
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
                            .append(Text.literal("坐标: ").formatted(Formatting.WHITE))
                            .append(Text.literal(pos.toString()).formatted(Formatting.AQUA)),
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

            MinimapRefs refs = resolveWaypointList(session, pos);
            if (refs == null) return false;

            Object wp = createWaypoint(pos);
            if (wp == null) return false;

            refs.waypointList().add(wp);
            sendSuccessMessage(pos);
            return true;
        } catch (Exception e) {
            LOGGER.info("Minimap waypoint creation failed: {}", e.toString());
            return false;
        }
    }

    /**
     * 从 MinimapSession 出发，解析到 Waypoint 列表。
     * 参考 XaeroPlus：session.getWorldManager().getCurrentWorld().getCurrentWaypointSet()
     */
    private static MinimapRefs resolveWaypointList(Object session, LocatedPosition pos) {
        try {
            // session.getWorldManager() → WorldManager
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) {
                LOGGER.debug("getWorldManager() returned null");
                // 备用策略：尝试 getWaypointsManager（旧版 API 名称）
                return resolveViaLegacyApi(session, pos);
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
                // 备用：尝试 getWaypointSet(String name) 用 "gui.waypoints" 作为默认 set 名
                return resolveFallbackWaypointSet(world, pos);
            }

            List<?> list = accessWaypointSetList(waypointSet);
            if (list == null) return null;

            return new MinimapRefs(list);
        } catch (Exception e) {
            LOGGER.debug("resolveWaypointList failed: {}", e.getMessage());
            return null;
        }
    }

    /** 旧版 API 备用路径：getWaypointsManager().getCurrentContainer().getList() */
    private static MinimapRefs resolveViaLegacyApi(Object session, LocatedPosition pos) {
        try {
            Method gwm = session.getClass().getMethod("getWaypointsManager");
            Object wpManager = gwm.invoke(session);
            if (wpManager == null) return null;

            Method gcc = wpManager.getClass().getMethod("getCurrentContainer");
            Object container = gcc.invoke(wpManager);
            if (container == null) return null;

            @SuppressWarnings("rawtypes")
            List list = (List) container.getClass().getMethod("getList").invoke(container);
            return new MinimapRefs(list);
        } catch (Exception e) {
            LOGGER.debug("Legacy API fallback also failed: {}", e.getMessage());
            return null;
        }
    }

    /** 备用：通过 world.getWaypointSet("gui.waypoints") 获取路径点集。 */
    private static MinimapRefs resolveFallbackWaypointSet(Object world, LocatedPosition pos) {
        try {
            Method getWaypointSet = world.getClass().getMethod("getWaypointSet", String.class);
            Object waypointSet = getWaypointSet.invoke(world, "gui.waypoints");
            if (waypointSet == null) {
                // 尝试不带参数的另一途径
                Method getWaypoints = world.getClass().getMethod("getWaypoints");
                waypointSet = getWaypoints.invoke(world);
            }
            if (waypointSet == null) return null;

            List<?> list = accessWaypointSetList(waypointSet);
            if (list == null) return null;

            return new MinimapRefs(list);
        } catch (Exception e) {
            LOGGER.debug("Fallback WaypointSet resolution failed: {}", e.getMessage());
            return null;
        }
    }

    /** 通过反射访问 WaypointSet 的私有 list 字段（XaeroPlus 通过 AccessorWaypointSet mixin 实现）。 */
    @SuppressWarnings("rawtypes")
    private static List accessWaypointSetList(Object waypointSet) {
        try {
            if (waypointSetListField == null) {
                // 尝试常见的字段名
                Class<?> wsClass = waypointSet.getClass();
                for (String fieldName : new String[]{"list", "waypoints", "waypointList"}) {
                    try {
                        waypointSetListField = wsClass.getDeclaredField(fieldName);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (waypointSetListField == null) {
                    // 所有已知字段名都失败，尝试任何 List 类型的字段
                    for (Field f : wsClass.getDeclaredFields()) {
                        if (List.class.isAssignableFrom(f.getType())) {
                            waypointSetListField = f;
                            break;
                        }
                    }
                }
                if (waypointSetListField == null) {
                    LOGGER.debug("Could not find List field in WaypointSet");
                    return null;
                }
                waypointSetListField.setAccessible(true);
            }
            return (List) waypointSetListField.get(waypointSet);
        } catch (Exception e) {
            LOGGER.debug("Failed to access WaypointSet list: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 反射初始化 ====================

    private static boolean initMinimapReflection() {
        if (minimapAvailable != null && minimapAvailable) return true;
        try {
            // 1. Waypoint 类及构造函数
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            try {
                waypointConstructor = waypointClass.getConstructor(
                        int.class, int.class, int.class, String.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                waypointConstructor = waypointClass.getConstructor(
                        int.class, int.class, int.class, String.class, String.class);
            }

            // 2. BuiltInHudModules.MINIMAP 及其 getCurrentSession()
            Class<?> hudModulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = hudModulesClass.getField("MINIMAP");
            Object minimap = minimapField.get(null);
            Class<?> hudModuleClass = minimap.getClass();
            getCurrentSession = hudModuleClass.getMethod("getCurrentSession");

            // 3. MinimapSession → getWorldManager()（正确 API）
            Class<?> sessionClass = getCurrentSession.getReturnType();
            getWorldManager = sessionClass.getMethod("getWorldManager");

            // 4. WorldManager → getCurrentWorld()
            Class<?> worldManagerClass = getWorldManager.getReturnType();
            getCurrentWorld = worldManagerClass.getMethod("getCurrentWorld");

            // 5. MinimapWorld → getCurrentWaypointSet()
            Class<?> minimapWorldClass = getCurrentWorld.getReturnType();
            try {
                getCurrentWaypointSet = minimapWorldClass.getMethod("getCurrentWaypointSet");
            } catch (NoSuchMethodException e) {
                // 备用：getWaypointSet(String)
                getCurrentWaypointSet = minimapWorldClass.getMethod("getWaypointSet", String.class);
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

    private static Object getMinimapModule() throws Exception {
        Class<?> hudModulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
        Field minimapField = hudModulesClass.getField("MINIMAP");
        return minimapField.get(null);
    }

    /** 通过反射创建 Waypoint 实例。 */
    private static Object createWaypoint(LocatedPosition pos) {
        try {
            if (waypointConstructor == null && !initMinimapReflection()) return null;
            if (waypointConstructor == null) return null;

            if (waypointConstructor.getParameterCount() == 6) {
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(), WAYPOINT_NAME, WAYPOINT_SYMBOL, WAYPOINT_COLOR);
            } else {
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
                            .append(Text.literal("已创建路径点 \"" + WAYPOINT_NAME + "\" → ").formatted(Formatting.GREEN))
                            .append(Text.literal(pos.toString()).formatted(Formatting.AQUA)),
                    false);
        }
    }

    /** 解析结果：Waypoint 列表引用。 */
    @SuppressWarnings("rawtypes")
    private record MinimapRefs(List waypointList) {}
}
