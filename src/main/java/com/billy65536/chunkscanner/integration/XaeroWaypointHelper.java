package com.billy65536.chunkscanner.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
    private static volatile Object defaultWaypointColor;  // WaypointColor 枚举实例
    private static volatile Object defaultWaypointPurpose; // WaypointPurpose.NORMAL
    private static volatile Method getCurrentSession;
    private static volatile Method getWorldManager;
    private static volatile Method getCurrentWorld;
    private static volatile Method getWorldByDim;         // getWorld(Identifier) / getMinimapWorld(Identifier)
    private static volatile Method getCurrentWaypointSet;
    private static volatile Method getNamedWaypointSet;    // getWaypointSet(String)
    private static volatile Method waypointSetSet;         // Waypoint.setSet(WaypointSet)
    private static volatile Constructor<?> waypointSetConstructor; // new WaypointSet(String)
    private static volatile Method addWaypointSetToWorld;  // MinimapWorld.addWaypointSet(WaypointSet) / createWaypointSet
    private static volatile Method getWaypointSets;         // MinimapWorld.getWaypointSets() 获取所有 set

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
                LOGGER.info("getCurrentSession() returned null");
                return false;
            }

            // session.getWorldManager().getWorldByDim(pos.dimensionId) → getWaypointSet(group) 等
            Object waypointSet = resolveCurrentWaypointSet(session, group, pos);
            if (waypointSet == null) return false;

            Object wp = createWaypoint(pos, name, initials);
            if (wp == null) return false;

            // 将 Waypoint 绑定到 WaypointSet（确保组别和维度正确）
            bindWaypointToSet(wp, waypointSet, group);

            // 直接设置 Waypoint 的维度信息（适配新版 Xaero 内部维度字段）
            setWaypointDimension(wp, pos);

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
     * 优先根据扫描位置的维度查找对应 MinimapWorld，再按 group 名获取指定组。
     * 回退到当前 WaypointSet。
     *
     * @param pos  扫描位置，包含维度信息
     */
    private static Object resolveCurrentWaypointSet(Object session, String group, LocatedPosition pos) {
        try {
            // session.getWorldManager() → WorldManager
            Object worldManager = getWorldManager.invoke(session);
            if (worldManager == null) {
                LOGGER.debug("getWorldManager() returned null");
                return resolveViaLegacyApi(session);
            }

            // 优先：根据扫描位置的维度查找正确的 MinimapWorld
            Object world = resolveWorldByDimension(worldManager, pos);
            if (world == null) {
                LOGGER.info("Could not resolve MinimapWorld for dimension '{}', falling back to current world", pos.dimensionId());
                world = getCurrentWorld.invoke(worldManager);
            }
            if (world == null) {
                LOGGER.debug("getCurrentWorld() returned null");
                return null;
            }

            // 优先：按配置的 group 获取指定 WaypointSet
            if (group != null && !group.isEmpty()) {
                try {
                    Object namedSet = getOrCreateWaypointSet(world, group);
                    if (namedSet != null) {
                        return namedSet;
                    }
                    LOGGER.debug("Could not get/create WaypointSet \"{}\", falling back to current set", group);
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

    /** 缓存的 XaeroPath 参数构造器（当 getWorld 接受 XaeroPath 而非 Identifier 时使用）。 */
    private static volatile Constructor<?> xaeroPathConstructor;

    /**
     * 根据维度 ID 查找对应的 MinimapWorld。
     * 尝试多种方法：getWorld(XaeroPath/Identifier/String) → 遍历所有世界 → 创建新世界
     */
    private static Object resolveWorldByDimension(Object worldManager, LocatedPosition pos) {
        String dimId = pos.dimensionId();
        if (dimId == null || dimId.isEmpty()) return null;

        // 方法 1：WorldManager.getWorld(?) — 按实际情况构造参数
        if (getWorldByDim != null) {
            Class<?> paramType = getWorldByDim.getParameterTypes()[0];
            Object dimArg = buildDimensionArgument(dimId, paramType);
            if (dimArg != null) {
                try {
                    Object world = getWorldByDim.invoke(worldManager, dimArg);
                    if (world != null) {
                        LOGGER.info("Found MinimapWorld via getWorld({}) for dimension: {}",
                                paramType.getSimpleName(), dimId);
                        return world;
                    }
                    LOGGER.info("getWorld({}) returned null for dimension: {}, world may not exist yet",
                            paramType.getSimpleName(), dimId);
                } catch (Exception e) {
                    LOGGER.info("getWorld({}) failed for {}: {}",
                            paramType.getSimpleName(), dimId, e.getMessage());
                }
            }
        } else {
            LOGGER.info("getWorldByDim is null - no dimension lookup available");
        }

        // 方法 2：遍历所有世界容器，匹配维度 ID（兼容 WaypointWorldRootContainer 等）
        try {
            java.util.Collection<?> worlds = getWorldCollection(worldManager);
            if (worlds != null) {
                LOGGER.info("Iterating {} worlds to find dimension: {}", worlds.size(), dimId);
                int idx = 0;
                for (Object container : worlds) {
                    idx++;
                    LOGGER.info("  World[{}]: type={}", idx, container.getClass().getSimpleName());
                    // 先尝试直接获取维度
                    String containerDim = getWorldDimension(container);
                    LOGGER.info("  World[{}]: extracted dimension={}", idx, containerDim);

                    // 尝试从容器中提取实际的 MinimapWorld
                    Object actualWorld = unwrapWorldContainer(container, dimId);
                    if (actualWorld != null) {
                        LOGGER.info("Found MinimapWorld by container unwrap for dimension: {}", dimId);
                        return actualWorld;
                    }
                }
            } else {
                LOGGER.info("getWorldCollection returned null, cannot iterate worlds");
            }
        } catch (Exception e) {
            LOGGER.info("Iterating world list failed: {}", e.getMessage());
        }

        // 方法 3：尝试通过 WorldManager 创建/获取目标维度的世界
        try {
            Object world = tryCreateOrGetWorld(worldManager, dimId);
            if (world != null) return world;
        } catch (Exception e) {
            LOGGER.info("tryCreateOrGetWorld failed: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 根据目标类型构造维度参数。
     * 支持 Identifier、String、XaeroPath 等。
     */
    private static Object buildDimensionArgument(String dimId, Class<?> paramType) {
        // Identifier
        if (paramType == Identifier.class) {
            try {
                return new Identifier(dimId);
            } catch (Exception e) {
                return null;
            }
        }
        // String
        if (paramType == String.class) {
            return dimId;
        }
        // XaeroPath（Xaero 24.x 类型的路径对象）
        if (paramType.getSimpleName().contains("XaeroPath") || paramType.getName().contains("xaero")) {
            try {
                return createXaeroPath(dimId, paramType);
            } catch (Exception e) {
                LOGGER.info("Failed to create XaeroPath: {}", e.getMessage());
            }
        }
        // 兜底：如果参数类型能直接用 String 构造
        try {
            Constructor<?> ctor = paramType.getConstructor(String.class);
            return ctor.newInstance(dimId);
        } catch (Exception ignored) {}
        return null;
    }

    /** 反射创建 XaeroPath 实例。核心思路：先尝试私有构造器（XaeroPath 无 public 构造器），再静态工厂。 */
    private static Object createXaeroPath(String dimId, Class<?> targetType) {
        // 优先使用已缓存的构造器/工厂
        if (xaeroPathConstructor != null) {
            try {
                return xaeroPathConstructor.newInstance(dimId);
            } catch (Exception e) {
                xaeroPathConstructor = null; // 缓存失效
            }
        }

        // 解析维度 ID 为 namespace + path 两部分
        String namespace = "minecraft";
        String path = dimId;
        int colonIdx = dimId.indexOf(':');
        if (colonIdx >= 0) {
            namespace = dimId.substring(0, colonIdx);
            path = dimId.substring(colonIdx + 1);
        }
        Identifier dimKey;
        try { dimKey = new Identifier(dimId); }
        catch (Exception e) { dimKey = null; }

        // —— 策略 0：输出所有构造器（含私有）供调试 ——
        LOGGER.info("XaeroPath constructors (public + declared):");
        for (Constructor<?> c : targetType.getConstructors()) {
            logConstructor(c, "  [public]");
        }
        for (Constructor<?> c : targetType.getDeclaredConstructors()) {
            if ((c.getModifiers() & java.lang.reflect.Modifier.PUBLIC) == 0) {
                logConstructor(c, "  [private/protected]");
            }
        }

        // —— 策略 1：tryDeclaredConstruct — 尝试所有参数模式（含私有构造器） ——
        Object result;

        // 1a. (String) — 公开或私有
        result = tryDeclaredConstruct(targetType, new Class<?>[]{String.class}, new Object[]{dimId});
        if (result != null) return result;

        // 1b. (Identifier)
        if (dimKey != null) {
            result = tryDeclaredConstruct(targetType, new Class<?>[]{Identifier.class}, new Object[]{dimKey});
            if (result != null) return result;
        }

        // 1c. (ResourceLocation)
        try {
            Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
            if (dimKey != null) {
                Object rl = rlClass.getConstructor(String.class, String.class).newInstance(namespace, path);
                result = tryDeclaredConstruct(targetType, new Class<?>[]{rlClass}, new Object[]{rl});
                if (result != null) return result;
            }
        } catch (Exception ignored) {}

        // 1d. (String, String)
        result = tryDeclaredConstruct(targetType, new Class<?>[]{String.class, String.class}, new Object[]{namespace, path});
        if (result != null) return result;

        // 1e. (String, String, ...) 变长参数版本
        try {
            for (Constructor<?> c : targetType.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length >= 2 && p[0] == String.class && p[1] == String.class
                        && java.util.Arrays.stream(p).skip(2).allMatch(pt -> pt == String.class)) {
                    Object[] args = new Object[p.length];
                    args[0] = namespace;
                    for (int i = 1; i < args.length; i++) args[i] = path;
                    c.setAccessible(true);
                    Object obj = c.newInstance(args);
                    xaeroPathConstructor = c;
                    LOGGER.info("Created XaeroPath via {}(String...): {}", targetType.getSimpleName(), dimId);
                    return obj;
                }
            }
        } catch (Exception ignored) {}

        // —— 策略 2：通用扫描所有构造器（含私有），智能匹配参数 ——
        for (Constructor<?> c : targetType.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 0) continue;
            Object[] args = new Object[params.length];
            boolean allMatch = true;
            for (int i = 0; i < params.length; i++) {
                if (params[i] == String.class) {
                    args[i] = (i == 0) ? (params.length == 1 ? dimId : namespace) : path;
                } else if (params[i] == Identifier.class && dimKey != null) {
                    args[i] = dimKey;
                } else if (params[i] == int.class || params[i] == Integer.class) {
                    args[i] = -1;
                } else if (params[i] == boolean.class || params[i] == Boolean.class) {
                    args[i] = false;
                } else if (params[i] == long.class || params[i] == Long.class) {
                    args[i] = 0L;
                } else {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                try {
                    c.setAccessible(true);
                    Object pathObj = c.newInstance(args);
                    xaeroPathConstructor = c;
                    LOGGER.info("Created XaeroPath via private ctor {}: {}",
                            targetType.getSimpleName(), dimId);
                    return pathObj;
                } catch (Exception ignored) {}
            }
        }

        // —— 策略 3：静态工厂方法 ——
        // 3a. 静态工厂 (String)
        for (String factoryName : new String[]{"of", "from", "fromString", "valueOf",
                "parse", "create", "get", "forDimension", "ofDimension", "fromId",
                "fromIdentifier", "fromDim", "fromDimId", "fromPath", "fromFullId"}) {
            result = tryStaticFactory(targetType, factoryName, String.class, dimId);
            if (result != null) return result;
        }

        // 3b. 静态工厂 (String, String)
        for (String factoryName : new String[]{"of", "from", "create", "get"}) {
            try {
                Method m = targetType.getMethod(factoryName, String.class, String.class);
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && targetType.isAssignableFrom(m.getReturnType())) {
                    Object pathObj = m.invoke(null, namespace, path);
                    LOGGER.info("Created XaeroPath via {}.{}(String, String): {}",
                            targetType.getSimpleName(), factoryName, dimId);
                    return pathObj;
                }
            } catch (Exception ignored) {}
        }

        // 3c. 静态工厂 (Identifier)
        if (dimKey != null) {
            for (String factoryName : new String[]{"of", "from", "fromIdentifier", "create", "get"}) {
                result = tryStaticFactory(targetType, factoryName, Identifier.class, dimKey);
                if (result != null) return result;
            }
        }

        // 3d. 扫描所有返回 XaeroPath 的静态方法
        for (Method m : targetType.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (!targetType.isAssignableFrom(m.getReturnType())) continue;
            if (m.getParameterCount() == 0 || m.getParameterCount() > 2) continue;
            Class<?>[] pTypes = m.getParameterTypes();
            try {
                if (pTypes.length == 1) {
                    Object arg;
                    if (pTypes[0] == String.class) arg = dimId;
                    else if (pTypes[0] == Identifier.class && dimKey != null) arg = dimKey;
                    else continue;
                    Object obj = m.invoke(null, arg);
                    LOGGER.info("Created XaeroPath via static {}.{}(): {}",
                            targetType.getSimpleName(), m.getName(), dimId);
                    return obj;
                }
            } catch (Exception ignored) {}
        }

        LOGGER.info("All XaeroPath creation strategies failed for: {}", dimId);
        return null;
    }

    private static void logConstructor(Constructor<?> c, String prefix) {
        Class<?>[] params = c.getParameterTypes();
        StringBuilder sb = new StringBuilder(prefix).append(' ').append(c.getName()).append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        sb.append(')');
        LOGGER.info(sb.toString());
    }

    /** 尝试用指定参数类型构造对象（含私有构造器），失败返回 null。 */
    private static Object tryDeclaredConstruct(Class<?> targetType, Class<?>[] paramTypes, Object[] args) {
        try {
            Constructor<?> ctor = targetType.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            Object obj = ctor.newInstance(args);
            xaeroPathConstructor = ctor;
            LOGGER.info("Created XaeroPath via {}({}): {}",
                    targetType.getSimpleName(),
                    java.util.Arrays.stream(paramTypes).map(Class::getSimpleName)
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    args[0]);
            return obj;
        } catch (Exception ignored) {}
        return null;
    }

    /** 尝试静态工厂方法，失败返回 null。 */
    private static Object tryStaticFactory(Class<?> targetType, String methodName, Class<?> paramType, Object arg) {
        try {
            Method m = targetType.getMethod(methodName, paramType);
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && targetType.isAssignableFrom(m.getReturnType())) {
                Object obj = m.invoke(null, arg);
                LOGGER.info("Created XaeroPath via {}.{}({}): {}",
                        targetType.getSimpleName(), methodName, paramType.getSimpleName(), arg);
                return obj;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 从 world 容器（如 WaypointWorldRootContainer）中解包出实际的 MinimapWorld。
     * 容器可能封装了单个 MinimapWorld 或持有按维度索引的 Map。
     */
    private static Object unwrapWorldContainer(Object container, String targetDimId) {
        Class<?> contClass = container.getClass();

        // 0. 如果本身就是 MinimapWorld，直接检查维度匹配
        if (contClass.getSimpleName().contains("MinimapWorld")) {
            String dim = getWorldDimension(container);
            if (targetDimId.equals(dim)) return container;
            return null;
        }

        // 1. 尝试 getName() / getDimId() 获取容器的维度名，进行快速匹配
        String containerDim = getWorldDimension(container);
        if (targetDimId.equals(containerDim)) {
            // 容器本身匹配，尝试提取 MinimapWorld
            Object mw = extractMinimapWorldFrom(container);
            return mw != null ? mw : container;
        }

        // 2. 尝试容器.getWorld(String / Identifier / XaeroPath)
        for (String name : new String[]{"getWorld", "getMinimapWorld", "get"}) {
            for (Method m : contClass.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Class<?> pType = m.getParameterTypes()[0];
                try {
                    Object dimArg = buildDimensionArgument(targetDimId, pType);
                    if (dimArg != null) {
                        Object world = m.invoke(container, dimArg);
                        if (world != null) {
                            LOGGER.info("Unwrapped container via {}({}) for: {}", name, pType.getSimpleName(), targetDimId);
                            return world;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 2.5. 遍历容器内部的 Map（WaypointWorldRootContainer 可能在内部持有 Map<XaeroPath, MinimapWorld>）
        try {
            for (Field f : getAllFields(contClass)) {
                f.setAccessible(true);
                Object fieldVal = f.get(container);
                if (fieldVal instanceof java.util.Map) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) fieldVal;
                    LOGGER.info("Container '{}' has Map field '{}' with {} entries",
                            contClass.getSimpleName(), f.getName(), map.size());
                    for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                        Object key = entry.getKey();
                        String keyStr = key != null ? key.toString() : "null";
                        if (keyStr.contains(":")) {
                            LOGGER.info("  Map key: {} -> value: {}",
                                    keyStr, entry.getValue() != null ? entry.getValue().getClass().getSimpleName() : "null");
                            if (targetDimId.equals(keyStr)) {
                                LOGGER.info("Found MinimapWorld via container Map key match: {}", targetDimId);
                                return entry.getValue();
                            }
                        }
                    }
                    for (Object v : map.values()) {
                        if (v != null) {
                            String dim = getWorldDimension(v);
                            if (dim != null) LOGGER.info("  Map value dimension: {}", dim);
                            if (targetDimId.equals(dim)) {
                                LOGGER.info("Found MinimapWorld via container Map value dimension match");
                                return v;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.info("Container Map iteration failed: {}", e.getMessage());
        }

        // 2.6. 遍历容器内部所有字段：找 MinimapWorld 或子容器
        try {
            for (Field f : getAllFields(contClass)) {
                f.setAccessible(true);
                Object fieldVal = f.get(container);
                if (fieldVal == null || fieldVal == container) continue;

                // 直接是 MinimapWorld
                if (fieldVal.getClass().getSimpleName().contains("MinimapWorld")) {
                    String dim = getWorldDimension(fieldVal);
                    if (targetDimId.equals(dim)) return fieldVal;
                }

                // Iterable/Collection
                if (fieldVal instanceof Iterable) {
                    for (Object item : (Iterable<?>) fieldVal) {
                        if (item == null) continue;
                        String dim = getWorldDimension(item);
                        if (targetDimId.equals(dim)) return item;
                        if (item.getClass().getSimpleName().contains("Container")
                                || item.getClass().getSimpleName().contains("Root")) {
                            Object inner = unwrapWorldContainer(item, targetDimId);
                            if (inner != null) return inner;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.info("Container field iteration failed: {}", e.getMessage());
        }

        // 3. 提取 MinimapWorld 并检查维度
        Object mw = extractMinimapWorldFrom(container);
        if (mw != null) {
            String mwDim = getWorldDimension(mw);
            if (targetDimId.equals(mwDim)) return mw;
        }

        return null;
    }

    /** 获取类及其所有父类的 declared fields。 */
    private static java.util.List<Field> getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                fields.add(f);
            }
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    /** 从容器中提取 MinimapWorld（不检查维度匹配）。 */
    private static Object extractMinimapWorldFrom(Object container) {
        Class<?> contClass = container.getClass();

        // 尝试无参 getter：getWorld() / getMinimapWorld() / getCurrentWorld()
        for (String name : new String[]{"getWorld", "getMinimapWorld", "getCurrentWorld", "getMinimapWorldInstance"}) {
            try {
                Method m = contClass.getMethod(name);
                Object result = m.invoke(container);
                if (result != null && result != container
                        && result.getClass().getSimpleName().contains("Minimap")) {
                    LOGGER.info("Extracted MinimapWorld from container via {}()", name);
                    return result;
                }
            } catch (Exception ignored) {}
        }

        // 尝试通过字段获取：world, minimapWorld, minimap_world, currentWorld
        for (Field f : contClass.getDeclaredFields()) {
            String fName = f.getName().toLowerCase();
            if (fName.contains("world") || fName.contains("minimap")) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(container);
                    if (value != null && value != container
                            && value.getClass().getSimpleName().contains("Minimap")) {
                        LOGGER.info("Extracted MinimapWorld from container field: {}", f.getName());
                        return value;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 尝试遍历所有字段找 MinimapWorld
        for (Field f : contClass.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object value = f.get(container);
                if (value != null && value.getClass().getSimpleName().contains("MinimapWorld")) {
                    LOGGER.info("Extracted MinimapWorld from container field: {}", f.getName());
                    return value;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    /** 尝试在给定 class 上按名称列表查找第一个可用的 Method。 */
    private static Method tryFindMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
        }
        return null;
    }

    /** 尝试在给定 class 上查找匹配参数类型的构造器。 */
    private static Constructor<?> tryFindConstructor(Class<?> clazz, Class<?>... paramTypes) {
        try {
            return clazz.getConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 获取 MinimapWorld 的集合（支持 List、Collection、Map 等）。
     * 优先返回 Collection，Map 则取其 values()。
     */
    private static java.util.Collection<?> getWorldCollection(Object worldManager) {
        for (String name : new String[]{"getWorlds", "getMinimapWorlds", "getAllWorlds", "getWorldMap"}) {
            try {
                Method m = worldManager.getClass().getMethod(name);
                Object result = m.invoke(worldManager);
                if (result == null) continue;

                if (result instanceof java.util.Collection) {
                    return (java.util.Collection<?>) result;
                }
                if (result instanceof java.util.Map) {
                    LOGGER.info("WorldManager.{}() returned Map, using values()", name);
                    return ((java.util.Map<?, ?>) result).values();
                }
                if (result instanceof Iterable) {
                    // 将 Iterable 转为 Collection
                    java.util.List<Object> list = new java.util.ArrayList<>();
                    for (Object o : (Iterable<?>) result) list.add(o);
                    return list;
                }
                LOGGER.info("WorldManager.{}() returned: {}", name, result.getClass().getName());
            } catch (Exception ignored) {
                LOGGER.debug("WorldManager.{}() not found or failed", name);
            }
        }

        // 遍历所有无参方法，查找返回 Collection 或 Map 的方法
        for (Method m : worldManager.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            try {
                Object result = m.invoke(worldManager);
                if (result instanceof java.util.Collection) {
                    LOGGER.info("Found world collection via: {}()", m.getName());
                    return (java.util.Collection<?>) result;
                }
                if (result instanceof java.util.Map) {
                    LOGGER.info("Found world map via: {}()", m.getName());
                    return ((java.util.Map<?, ?>) result).values();
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    /** 获取 world/container 的维度 ID 字符串。 */
    private static String getWorldDimension(Object world) {
        if (world == null) return null;
        Class<?> worldClass = world.getClass();

        // 0. 先通过 unwrap 拿到真正的 MinimapWorld 再取维度
        Object mw = extractMinimapWorldFrom(world);
        if (mw != null && mw != world && !mw.getClass().equals(world.getClass())) {
            String dim = getWorldDimension(mw);
            if (dim != null) return dim;
        }

        // 1. 按名称尝试常见 getter
        for (String name : new String[]{"getDimId", "getDimension", "getDimensionId",
                "getFullId", "getId", "getKey", "getDimKey", "getPath", "getXaeroPath",
                "getWorldKey", "getWorldId", "getIdentifier", "getResourceLocation"}) {
            try {
                Method m = worldClass.getMethod(name);
                Object result = m.invoke(world);
                if (result instanceof Identifier) return result.toString();
                if (result instanceof String) {
                    String s = (String) result;
                    if (s.contains(":")) return s;
                }
                if (result != null) {
                    String s = result.toString();
                    // XaeroPath 可能有 toString() 返回类似 "minecraft:overworld" 的格式
                    if (s.contains(":")) return s;
                }
            } catch (Exception ignored) {}
        }

        // 1.5 尝试 getPath() 返回的 XaeroPath 对象，再取 toString()
        try {
            Method m = worldClass.getMethod("getPath");
            Object pathObj = m.invoke(world);
            if (pathObj != null) {
                String s = pathObj.toString();
                if (s.contains(":")) return s;
                // 尝试 pathObj.getPath() / pathObj.toString()
                try {
                    Method pathStr = pathObj.getClass().getMethod("getPath");
                    Object ps = pathStr.invoke(pathObj);
                    if (ps instanceof String && ((String) ps).contains(":")) return (String) ps;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 2. 遍历所有无参 public 方法，查找返回 Identifier 或 String 且含 ":" 的
        for (Method m : worldClass.getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) continue;
            Class<?> retType = m.getReturnType();
            if (retType == void.class || retType == boolean.class || retType == int.class
                    || retType == long.class || retType == float.class || retType == double.class)
                continue;
            try {
                Object result = m.invoke(world);
                if (result == null) continue;
                if (result instanceof Identifier) return result.toString();
                if (result instanceof String) {
                    String s = (String) result;
                    if (s.contains(":")) return s;
                }
                // 任何返回 "namespace:path" 格式的对象
                String s = result.toString();
                if (s.contains(":") && !s.startsWith("xaero.") && !s.contains(" ")) return s;
            } catch (Exception ignored) {}
        }

        // 3. 遍历所有字段
        for (Field f : worldClass.getDeclaredFields()) {
            String fName = f.getName().toLowerCase();
            boolean relevant = fName.contains("dim") || fName.contains("world")
                    || fName.contains("path") || fName.contains("id") || fName.contains("key");
            if (relevant) {
                try {
                    f.setAccessible(true);
                    Object result = f.get(world);
                    if (result instanceof Identifier) return result.toString();
                    if (result instanceof String) {
                        String s = (String) result;
                        if (s.contains(":")) return s;
                    }
                    if (result != null) {
                        String s = result.toString();
                        if (s.contains(":")) return s;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. 尝试从容器内部 Map 的 key 中寻找（针对 WaypointWorldRootContainer）
        try {
            for (Field f : worldClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object fieldVal = f.get(world);
                if (fieldVal instanceof java.util.Map) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) fieldVal;
                    for (Object key : map.keySet()) {
                        if (key != null) {
                            String s = key.toString();
                            if (s.contains(":")) {
                                LOGGER.info("Extracted dimension from container Map key: {}", s);
                                return s;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        LOGGER.info("Could not extract dimension ID from: {} ({} fields, {} methods, class={})",
                worldClass.getSimpleName(),
                worldClass.getDeclaredFields().length,
                worldClass.getMethods().length,
                worldClass.getName());
        return null;
    }

    /**
     * 尝试在 WorldManager 上创建或获取目标维度的 MinimapWorld。
     * 支持多种可能的参数类型：Identifier / String / XaeroPath。
     */
    private static Object tryCreateOrGetWorld(Object worldManager, String dimId) {
        Class<?> wmClass = worldManager.getClass();

        for (String name : new String[]{"getOrCreateWorld", "getOrCreate", "addWorld",
                "createWorld", "registerWorld", "getMinimapWorld"}) {
            for (Method m : wmClass.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Class<?> pType = m.getParameterTypes()[0];
                Object dimArg = buildDimensionArgument(dimId, pType);
                if (dimArg == null) continue;
                try {
                    Object world = m.invoke(worldManager, dimArg);
                    if (world != null) {
                        LOGGER.info("Created/got MinimapWorld via {}({}) for: {}",
                                m.getName(), pType.getSimpleName(), dimId);
                        return world;
                    }
                } catch (Exception e) {
                    LOGGER.debug("{}({}) failed: {}", m.getName(), pType.getSimpleName(), e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * 获取或创建指定名称的 WaypointSet。
     * 优先 getWaypointSet(String)，失败时尝试创建新 WaypointSet 并注册到 world。
     */
    private static Object getOrCreateWaypointSet(Object world, String group) {
        // 优先：直接获取已存在的 WaypointSet
        if (getNamedWaypointSet != null) {
            try {
                Object set = getNamedWaypointSet.invoke(world, group);
                if (set != null) {
                    LOGGER.info("Found existing WaypointSet \"{}\"", group);
                    return set;
                }
                LOGGER.info("getWaypointSet(\"{}\") returned null, will try to create", group);
            } catch (Exception e) {
                LOGGER.info("getWaypointSet(\"{}\") failed: {}", group, e.getMessage());
            }
        }

        // 尝试通过遍历所有 waypoint sets 查找
        if (getWaypointSets != null) {
            try {
                Object sets = getWaypointSets.invoke(world);
                if (sets instanceof Iterable) {
                    for (Object s : (Iterable<?>) sets) {
                        String setName = getWaypointSetName(s);
                        if (group.equals(setName)) {
                            // 缓存这个方法以便下次使用
                            try {
                                Method m = world.getClass().getMethod("getWaypointSet", String.class);
                                getNamedWaypointSet = m;
                            } catch (Exception ignored) {}
                            return s;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("getWaypointSets iteration failed: {}", e.getMessage());
            }
        }

        // 尝试创建新的 WaypointSet
        if (waypointSetConstructor != null) {
            try {
                Object newSet = waypointSetConstructor.newInstance(group);
                // 注册到 world
                if (addWaypointSetToWorld != null) {
                    addWaypointSetToWorld.invoke(world, newSet);
                }
                LOGGER.info("Created WaypointSet \"{}\", re-fetching to ensure proper registration", group);
                // 注册后重新获取，确保拿到 world 内部正确追踪的 WaypointSet 实例
                if (getNamedWaypointSet != null) {
                    try {
                        Object registeredSet = getNamedWaypointSet.invoke(world, group);
                        if (registeredSet != null) {
                            LOGGER.info("Re-fetched registered WaypointSet \"{}\"", group);
                            return registeredSet;
                        }
                    } catch (Exception ignored) {}
                }
                // 如果找不到已注册的，尝试从 world 的 WaypointSets 列表中匹配
                if (getWaypointSets != null) {
                    try {
                        Object sets = getWaypointSets.invoke(world);
                        if (sets instanceof Iterable) {
                            for (Object s : (Iterable<?>) sets) {
                                String setName = getWaypointSetName(s);
                                if (group.equals(setName)) {
                                    LOGGER.info("Found registered WaypointSet \"{}\" by name match", group);
                                    return s;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                // 如果都不行，返回新创建的 set（可能已经注册好了）
                return newSet;
            } catch (Exception e) {
                LOGGER.info("Failed to create WaypointSet: {}", e.getMessage());
            }
        }

        // 尝试通过 world 的方法创建（如 createWaypointSet(String)）
        for (String name : new String[]{"createWaypointSet", "addWaypointSet", "registerWaypointSet"}) {
            try {
                Method m = world.getClass().getMethod(name, String.class);
                Object set = m.invoke(world, group);
                if (set != null) return set;
            } catch (Exception ignored) {}
        }

        return null;
    }

    /** 获取 WaypointSet 的名称（用于 group 匹配）。 */
    private static String getWaypointSetName(Object waypointSet) {
        for (String name : new String[]{"getName", "getSetName", "name"}) {
            try {
                Method m = waypointSet.getClass().getMethod(name);
                Object result = m.invoke(waypointSet);
                if (result instanceof String) return (String) result;
            } catch (Exception ignored) {}
            try {
                Field f = waypointSet.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object result = f.get(waypointSet);
                if (result instanceof String) return (String) result;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 将 Waypoint 绑定到 WaypointSet，确保组别正确。
     * 新版 Xaero 可能需要在 Waypoint 上调用 setSet() 来关联所属组。
     */
    private static void bindWaypointToSet(Object waypoint, Object waypointSet, String group) {
        // 方法 1：waypoint.setSet(WaypointSet)
        if (waypointSetSet != null) {
            try {
                waypointSetSet.invoke(waypoint, waypointSet);
                LOGGER.info("Bound waypoint to WaypointSet via setSet()");
                return;
            } catch (Exception e) {
                LOGGER.info("waypoint.setSet() failed: {}", e.getMessage());
            }
        }

        // 方法 2：直接设置字段
        try {
            for (Field f : waypoint.getClass().getDeclaredFields()) {
                if (f.getType().isAssignableFrom(waypointSet.getClass())
                        || waypointSet.getClass().isAssignableFrom(f.getType())) {
                    if (f.getName().toLowerCase().contains("set")) {
                        f.setAccessible(true);
                        f.set(waypoint, waypointSet);
                        LOGGER.info("Bound waypoint to WaypointSet via field: {}", f.getName());
                        return;
                    }
                }
                if (f.getType() == String.class
                        && (f.getName().equals("setName") || f.getName().equals("group"))) {
                    f.setAccessible(true);
                    if (f.get(waypoint) == null && group != null && !group.isEmpty()) {
                        f.set(waypoint, group);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.info("bindWaypointToSet reflection failed: {}", e.getMessage());
        }
    }

    /**
     * 直接在 Waypoint 对象上设置维度信息。
     * 适配新版 Xaero 中 Waypoint 可能内带 dimId / 维度字段的场景。
     */
    private static void setWaypointDimension(Object waypoint, LocatedPosition pos) {
        if (pos.dimensionId() == null || pos.dimensionId().isEmpty()) return;

        Class<?> wpClass = waypoint.getClass();
        Identifier dimKey = null;
        try {
            dimKey = new Identifier(pos.dimensionId());
        } catch (Exception ignored) {}

        // 方法 1：setDimId(Identifier) 或 setDimension(Identifier)
        if (dimKey != null) {
            for (String name : new String[]{"setDimId", "setDimension", "setDimensionId", "setWorld"}) {
                for (Method m : wpClass.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1) {
                        Class<?> pType = m.getParameterTypes()[0];
                        if (pType == Identifier.class || pType == String.class) {
                            try {
                                m.invoke(waypoint, pType == Identifier.class ? dimKey : pos.dimensionId());
                                LOGGER.info("Set waypoint dimension via {}({}): {}", name,
                                        pType.getSimpleName(), pos.dimensionId());
                                return;
                            } catch (Exception e) {
                                LOGGER.debug("Waypoint.{}({}) failed: {}", name, pType.getSimpleName(), e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        // 方法 2：直接设置字段
        for (String fieldName : new String[]{"dimId", "dimension", "dimensionId", "dim", "world"}) {
            try {
                Field f = wpClass.getDeclaredField(fieldName);
                f.setAccessible(true);
                Class<?> fType = f.getType();
                if (fType == Identifier.class && dimKey != null) {
                    f.set(waypoint, dimKey);
                    LOGGER.info("Set waypoint dimension field '{}' to Identifier: {}", fieldName, pos.dimensionId());
                    return;
                } else if (fType == String.class) {
                    f.set(waypoint, pos.dimensionId());
                    LOGGER.info("Set waypoint dimension field '{}' to String: {}", fieldName, pos.dimensionId());
                    return;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                LOGGER.debug("Failed to set waypoint field '{}': {}", fieldName, e.getMessage());
            }
        }

        LOGGER.debug("Could not set dimension on Waypoint object directly (may not be needed)");
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

            // 4a. WorldManager → getWorld(Identifier) / getMinimapWorld(Identifier) — 按维度查找世界
            try {
                // 尝试 net.minecraft.util.Identifier 参数版本
                getWorldByDim = tryFindMethod(worldManagerClass,
                        "getWorld", "getMinimapWorld");
                if (getWorldByDim != null && (getWorldByDim.getParameterCount() != 1
                        || getWorldByDim.getParameterTypes()[0] != Identifier.class)) {
                    // 再尝试找到参数类型为 Identifier 的重载
                    for (Method m : worldManagerClass.getMethods()) {
                        if ((m.getName().equals("getWorld") || m.getName().equals("getMinimapWorld"))
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == Identifier.class) {
                            getWorldByDim = m;
                            break;
                        }
                    }
                }
                if (getWorldByDim != null) {
                    LOGGER.info("WorldManager dimension lookup: {} (param: {})",
                            getWorldByDim.getName(),
                            getWorldByDim.getParameterTypes()[0].getSimpleName());
                } else {
                    LOGGER.info("WorldManager does NOT have getWorld/getMinimapWorld with dimension param");
                }
            } catch (Exception e) {
                getWorldByDim = null;
                LOGGER.debug("No per-dimension world lookup available");
            }

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

            // 5a. WaypointSet 构造函数 new WaypointSet(String) — 用于创建不存在的组
            try {
                Class<?> waypointSetClass = getNamedWaypointSet != null
                        ? getNamedWaypointSet.getReturnType()
                        : Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
                waypointSetConstructor = tryFindConstructor(waypointSetClass, String.class);
                if (waypointSetConstructor != null) {
                    LOGGER.info("WaypointSet(String) constructor found");
                }
            } catch (Exception e) {
                waypointSetConstructor = null;
            }

            // 5b. MinimapWorld → addWaypointSet(WaypointSet) / createWaypointSet(String) — 注册新组
            try {
                Class<?> waypointSetClass = (getNamedWaypointSet != null)
                        ? getNamedWaypointSet.getReturnType()
                        : Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
                for (String name : new String[]{"addWaypointSet", "registerWaypointSet", "createWaypointSet"}) {
                    try {
                        Method m = worldClass.getMethod(name, waypointSetClass);
                        addWaypointSetToWorld = m;
                        LOGGER.info("MinimapWorld.{}() found", name);
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (Exception e) {
                addWaypointSetToWorld = null;
            }

            // 5c. MinimapWorld → getWaypointSets() — 遍历所有组
            try {
                getWaypointSets = tryFindMethod(worldClass, "getWaypointSets", "getAllWaypointSets", "getSets");
                if (getWaypointSets != null) {
                    LOGGER.info("MinimapWorld.{}() found", getWaypointSets.getName());
                }
            } catch (Exception e) {
                getWaypointSets = null;
            }

            // 5d. Waypoint.setSet(WaypointSet) — 绑定 Waypoint 到其所属组
            try {
                Class<?> wpSetClass = (getNamedWaypointSet != null)
                        ? getNamedWaypointSet.getReturnType()
                        : Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
                try {
                    waypointSetSet = waypointClass.getMethod("setSet", wpSetClass);
                    LOGGER.info("Waypoint.setSet(WaypointSet) found");
                } catch (NoSuchMethodException e) {
                    // 尝试查找任意接受 WaypointSet 的方法
                    for (Method m : waypointClass.getMethods()) {
                        if (m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == wpSetClass
                                && m.getName().startsWith("set")) {
                            waypointSetSet = m;
                            LOGGER.info("Waypoint.{}() found as setSet replacement", m.getName());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                waypointSetSet = null;
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
