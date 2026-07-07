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
 */
public final class XaeroWaypointHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkScanner|Xaero");

    private static final String WAYPOINT_NAME = "选中的坐标点";
    private static final String WAYPOINT_SYMBOL = "目标";
    private static final int WAYPOINT_COLOR = 0xFF55FFFF; // 青色

    private static volatile Boolean minimapAvailable;
    private static volatile Constructor<?> waypointConstructor;
    private static volatile Method getCurrentSession;
    private static volatile Method getWaypointsManager;
    private static volatile Method getCurrentContainer;
    private static volatile Method containerGetList;

    private XaeroWaypointHelper() {}

    /**
     * 尝试创建 Xaero 路径点。
     *
     * @return true 表示成功创建（或至少 Xaero 可用且尝试创建），false 表示 Xaero 不可用
     */
    public static boolean tryCreateWaypoint(LocatedPosition pos) {
        if (pos == null) return false;

        if (tryViaMinimap(pos)) return true;
        if (tryViaWorldmap(pos)) return true;

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

    // ==================== Minimap 集成 ====================

    private static boolean tryViaMinimap(LocatedPosition pos) {
        try {
            if (!initMinimapReflection()) return false;

            // BuiltInHudModules.MINIMAP.getCurrentSession()
            Object session = getCurrentSession.invoke(getMinimapModule());
            if (session == null) return false;

            // session.getWaypointsManager()
            Object wpManager = getWaypointsManager.invoke(session);
            if (wpManager == null) return false;

            // manager.getCurrentContainer()
            Object container = getCurrentContainer.invoke(wpManager);
            if (container == null) {
                // 尝试备用路径：直接访问 session 的 worlds
                return tryMinimapViaWorlds(session, pos);
            }

            // container.getList()
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) containerGetList.invoke(container);
            if (list == null) return false;

            Object wp = createWaypoint(pos);
            if (wp == null) return false;

            list.add(wp);
            sendSuccessMessage(pos);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Minimap waypoint creation failed: {}", e.getMessage());
            return false;
        }
    }

    /** 备用路径：通过 session.getWorlds() 或 getCurrentWorld() 获取 waypoint 集。 */
    private static boolean tryMinimapViaWorlds(Object session, LocatedPosition pos) {
        try {
            // session.getWorld(name)
            Method getWorld = session.getClass().getMethod("getWorld", String.class);
            Object world = getWorld.invoke(session, pos.dimensionId());
            if (world == null) {
                // 尝试 getCurrentWorld()
                Method getCurrentWorld = session.getClass().getMethod("getCurrentWorld");
                world = getCurrentWorld.invoke(session);
            }
            if (world == null) return false;

            // world.getWaypoints()
            Method getWaypoints = world.getClass().getMethod("getWaypoints");
            Object waypointSet = getWaypoints.invoke(world);
            if (waypointSet == null) return false;

            // waypointSet.getList()
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) waypointSet.getClass()
                    .getMethod("getList").invoke(waypointSet);
            if (list == null) return false;

            Object wp = createWaypoint(pos);
            if (wp == null) return false;

            list.add(wp);
            sendSuccessMessage(pos);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Minimap fallback waypoint creation failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== WorldMap 集成 ====================

    private static boolean tryViaWorldmap(LocatedPosition pos) {
        try {
            // xaero.map.WorldMap 或 xaero.map.gui.GuiMap
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            if (worldMapClass == null) return false;

            // WorldMap.getCurrentSession() 类似
            Method getSession = worldMapClass.getMethod("getCurrentSession");
            Object session = getSession.invoke(null);
            if (session == null) {
                // 尝试 getSettings() → waypoints
                Method getSettings = worldMapClass.getMethod("getSettings");
                Object settings = getSettings.invoke(null);
                if (settings != null) {
                    return tryWorldmapViaSettings(settings, pos);
                }
                return false;
            }

            // 类似 minimap 的路径
            return tryMinimapViaWorlds(session, pos);
        } catch (Exception e) {
            LOGGER.debug("WorldMap waypoint creation failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean tryWorldmapViaSettings(Object settings, LocatedPosition pos) {
        try {
            // 尝试通过 WorldMap 的 waypoints 数据添加
            // 不同的 WorldMap 版本 API 差异较大，做保守尝试
            Method getMapProcessor = settings.getClass().getMethod("getMapProcessor");
            Object processor = getMapProcessor.invoke(settings);
            if (processor == null) return false;

            Method getCurrentWorld = processor.getClass().getMethod("getCurrentWorld");
            Object world = getCurrentWorld.invoke(processor);
            if (world == null) return false;

            Method getWaypoints = world.getClass().getMethod("getWaypoints");
            Object waypointSet = getWaypoints.invoke(world);
            if (waypointSet == null) return false;

            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) waypointSet.getClass()
                    .getMethod("getList").invoke(waypointSet);
            if (list == null) return false;

            Object wp = createWaypoint(pos);
            if (wp == null) return false;

            list.add(wp);
            sendSuccessMessage(pos);
            return true;
        } catch (Exception e) {
            LOGGER.debug("WorldMap settings waypoint creation failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 反射初始化 ====================

    private static boolean initMinimapReflection() {
        if (minimapAvailable != null) return minimapAvailable;
        try {
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            // Waypoint(int x, int y, int z, String name, String symbol, int color)
            try {
                waypointConstructor = waypointClass.getConstructor(
                        int.class, int.class, int.class, String.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                // 尝试 4 参数的版本
                waypointConstructor = waypointClass.getConstructor(
                        int.class, int.class, int.class, String.class, String.class);
            }

            Class<?> hudModulesClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = hudModulesClass.getField("MINIMAP");
            Object minimap = minimapField.get(null);
            Class<?> hudModuleClass = minimap.getClass();
            getCurrentSession = hudModuleClass.getMethod("getCurrentSession");

            Class<?> sessionClass = getCurrentSession.getReturnType();
            // try getWaypointsManager first
            try {
                getWaypointsManager = sessionClass.getMethod("getWaypointsManager");
                Class<?> wpManagerClass = getWaypointsManager.getReturnType();
                getCurrentContainer = wpManagerClass.getMethod("getCurrentContainer");
                containerGetList = getCurrentContainer.getReturnType().getMethod("getList");
            } catch (NoSuchMethodException e) {
                // fallback: getWorld() + getWaypoints() path, handled in tryMinimapViaWorlds
                getWaypointsManager = null;
                getCurrentContainer = null;
                containerGetList = null;
            }

            minimapAvailable = true;
            LOGGER.info("Xaero's Minimap integration enabled");
            return true;
        } catch (Exception e) {
            minimapAvailable = false;
            LOGGER.debug("Xaero's Minimap not available: {}", e.getMessage());
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
            if (waypointConstructor == null && !initMinimapReflection()) {
                // 尝试 WorldMap 的 Waypoint 类
                try {
                    Class<?> wmWaypoint = Class.forName("xaero.map.waypoint.Waypoint");
                    waypointConstructor = wmWaypoint.getConstructor(
                            int.class, int.class, int.class, String.class, String.class, int.class);
                } catch (Exception e2) {
                    return null;
                }
            }
            if (waypointConstructor == null) return null;

            if (waypointConstructor.getParameterCount() == 6) {
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(), WAYPOINT_NAME, WAYPOINT_SYMBOL, WAYPOINT_COLOR);
            } else {
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(), WAYPOINT_NAME, WAYPOINT_SYMBOL);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to create Waypoint object: {}", e.getMessage());
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
}
