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
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

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

    // ==================== 跨纬度延迟创建路径点 ====================
    // Xaero 的代码到现在也没有搞清楚…… 反正不知道为什么就是不能跨纬度创建…… 裱糊匠做法

    /**
     * 路径点创建请求，封装创建路径点所需的全部信息。
     *
     * @param pos      目标坐标（包含维度坐标）
     * @param name     路径点名称
     * @param initials 路径点缩写（在地图上显示的简称）
     * @param group    路径点所属分组
     */
    public record CreateWaypointRequest(LocatedPosition pos, String name, String initials, String group) { }

    /**
     * 跨纬度待创建路径点请求队列。
     * <p>Key 为维度 ID（如 {@code minecraft:the_nether}），Value 为该维度下暂存的请求列表。</p>
     * <p>当用户点击的路径点不在当前维度时，请求会暂存于此；待玩家进入目标维度后，由
     * {@link #onClientTick(MinecraftClient)} 消费并创建。</p>
     * <p>使用 {@link ConcurrentHashMap} 保障 DISCONNECT 回调（网络线程）与 tick/UI（渲染线程）的并发安全。</p>
     */
    private static final ConcurrentHashMap<String, ArrayList<CreateWaypointRequest>> creationRequests = new ConcurrentHashMap<>();

    /**
     * 清理所有待创建的路径点请求。
     * <p>在退出世界/服务器时调用，防止残留请求在下次进入世界时被意外创建。</p>
     */
    public static void clearPendingRequests() {
        creationRequests.clear();
    }

    // ==================== 缓存反射句柄 ====================

    private static volatile Boolean minimapAvailable;
    private static volatile Constructor<?> waypointConstructor;
    private static volatile Object defaultWaypointColor;  // WaypointColor 枚举实例
    private static volatile Object defaultWaypointPurpose; // WaypointPurpose.NORMAL
    private static volatile Method getCurrentSession;
    private static volatile Method getWorldManager;
    private static volatile Method getCurrentWorld;
    private static volatile Method getCurrentWaypointSet;
    private static volatile Method getNamedWaypointSet;   // getWaypointSet(String)

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
     * @param client Minecraft 客户端实例
     * @param pos    目标坐标
     */
    public static void tryCreateWaypoint(MinecraftClient client, LocatedPosition pos) {
        tryCreateWaypoint(client, pos, DEFAULT_WAYPOINT_NAME, DEFAULT_WAYPOINT_INITIALS, null);
    }

    /**
     * 尝试创建 Xaero 路径点（使用指定的名称、缩写和组）。
     * <p>跨纬度策略：</p>
     * <ul>
     *   <li>Xaero 不可用 → 立即发送聊天消息（不受纬度限制）</li>
     *   <li>Xaero 可用且当前维度与目标一致 → 立即创建</li>
     *   <li>Xaero 可用但当前维度与目标不同 → 暂存到 {@link #creationRequests}，
     *       待 {@link #onClientTick(MinecraftClient)} 在玩家进入目标维度后消费</li>
     * </ul>
     *
     * @param client   Minecraft 客户端实例
     * @param pos      目标坐标（包含维度信息）
     * @param name     路径点名称（null 使用默认值）
     * @param initials 路径点缩写（null 使用默认值）
     * @param group    路径点组名（null 使用当前组）
     */
    public static void tryCreateWaypoint(MinecraftClient client, LocatedPosition pos, String name, String initials, String group) {
        if (pos == null || client.world == null) return;
        
        String dimId = client.world.getRegistryKey().getValue().toString();
        if (pos.dimensionId().equals(dimId) || !isAvailable()) { // 立即创建
            doCreateWaypoint(pos, name, initials, group);
        } else {
            String targetDim = pos.dimensionId();
            creationRequests.computeIfAbsent(targetDim, k -> new ArrayList<>())
                    .add(new CreateWaypointRequest(pos, name, initials, group));
        }
    }

    /**
     * 执行路径点创建（反射调用 Xaero Minimap API），若 Xaero 不可用则回退到聊天消息。
     */
    private static void doCreateWaypoint(LocatedPosition pos, String name, String initials, String group) {

        String wpName = (name != null && !name.isEmpty()) ? name : DEFAULT_WAYPOINT_NAME;
        String wpInit = (initials != null && !initials.isEmpty()) ? initials : DEFAULT_WAYPOINT_INITIALS;

        if (tryCreateViaMinimap(pos, wpName, wpInit, group)) return;

        // Xaero 不可用，发送聊天消息
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[ChunkScanner] ").formatted(Formatting.GOLD)
                            .append(Text.translatable("chunkscanner.waypoint.coordinates",
                                    pos.toString()).formatted(Formatting.AQUA)),
                    false);
        }
    }

    /** 每 tick 最多创建的路径点数量，防止大量积压请求造成帧卡顿。 */
    private static final int MAX_WAYPOINTS_PER_TICK = 10;

    /**
     * 客户端每帧回调，负责消费跨纬度暂存的路径点创建请求。
     * <p>当玩家所在维度的 {@link #creationRequests} 中存在待处理请求时，每 tick 最多处理
     * {@link #MAX_WAYPOINTS_PER_TICK} 个，避免一次性大量创建导致卡顿。</p>
     *
     * @param client Minecraft 客户端实例
     */
    public static void onClientTick(MinecraftClient client) {
        if (client.world == null) return;
        String dimId = client.world.getRegistryKey().getValue().toString();
        ArrayList<CreateWaypointRequest> requests = creationRequests.get(dimId);
        if (requests != null && !requests.isEmpty()) {
            int processed = 0;
            while (!requests.isEmpty() && processed < MAX_WAYPOINTS_PER_TICK) {
                CreateWaypointRequest request = requests.remove(requests.size() - 1);
                doCreateWaypoint(request.pos(), request.name(), request.initials(), request.group());
                processed++;
            }
            if (requests.isEmpty()) {
                creationRequests.remove(dimId);
            }
        }
    }

    // ==================== Minimap 集成（主路径） ====================

    /** 通过 Xaero Minimap 创建路径点。 */
    private static boolean tryCreateViaMinimap(LocatedPosition pos, String name, String initials, String group) {
        try {
            if (!initMinimapReflection()) return false;

            // BuiltInHudModules.MINIMAP.getCurrentSession() → MinimapSession
            Object session = getCurrentSession.invoke(getMinimapModule());
            if (session == null) {
                LOGGER.debug("getCurrentSession() returned null");
                return false;
            }

            // session.getWorldManager().getCurrentWorld().getWaypointSet(group) / getCurrentWaypointSet()
            Object waypointSet = resolveCurrentWaypointSet(session, group);
            if (waypointSet == null) return false;

            Object wp = createWaypoint(pos, name, initials);
            if (wp == null) return false;

            // 通过 WaypointSet.add(Waypoint) 添加
            addWaypointToSet(waypointSet, wp);

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
                LOGGER.debug("getWorldManager() returned null");
                return resolveViaLegacyApi(session);
            }

            // worldManager.getCurrentWorld() → MinimapWorld
            Object world = getCurrentWorld.invoke(worldManager);
            if (world == null) {
                LOGGER.debug("getCurrentWorld() returned null");
                return null;
            }

            // 优先：按配置的 group 获取指定 WaypointSet
            if (group != null && !group.isEmpty() && getNamedWaypointSet != null) {
                try {
                    Object namedSet = getNamedWaypointSet.invoke(world, group);
                    if (namedSet != null) {
                        return namedSet;
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
                    LOGGER.debug("getCurrentWaypointSet() returned null");
                    return resolveFallbackWaypointSet(world);
                }
                return waypointSet;
            }

            return resolveFallbackWaypointSet(world);
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
     * 调用 WaypointSet.add(Waypoint) 添加路径点。
     * 新版 Xaero (24.x) 方法名为 add，旧版可能为 addWaypoint。
     */
    @SuppressWarnings("unchecked")
    private static void addWaypointToSet(Object waypointSet, Object waypoint) {
        try {
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");

            // 优先使用 add(Waypoint)（新版 Xaero 24.x）
            try {
                Method add = waypointSet.getClass().getMethod("add", waypointClass);
                add.invoke(waypointSet, waypoint);
                return;
            } catch (NoSuchMethodException ignored) {}

            // 回退：addWaypoint(Waypoint)（旧版 Xaero）
            try {
                Method addWaypoint = waypointSet.getClass().getMethod("addWaypoint", waypointClass);
                addWaypoint.invoke(waypointSet, waypoint);
                return;
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
            Object minimap = minimapField.get(null);
            getCurrentSession = minimap.getClass().getMethod("getCurrentSession");

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
    private static Object createWaypoint(LocatedPosition pos, String name, String initials) {
        try {
            if (waypointConstructor == null && !initMinimapReflection()) return null;
            if (waypointConstructor == null) return null;

            Class<?>[] paramTypes = waypointConstructor.getParameterTypes();
            int count = paramTypes.length;

            if (count == 9) {
                // 完整版：(x, y, z, name, initials, color, purpose, temp, yIncluded)
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(),
                        name, initials,
                        defaultWaypointColor, defaultWaypointPurpose,
                        false, true);
            } else if (count == 6) {
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
            } else {
                // 最简版：(x, y, z, name, initials)
                return waypointConstructor.newInstance(
                        pos.x(), pos.y(), pos.z(), name, initials);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to create Waypoint: {}", e.getMessage());
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
