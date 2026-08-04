package com.billy65536.chunkscanner.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置项的反射访问器，为 {@code /cs get|set|reset} 命令提供支撑。
 *
 * <p>类初始化时递归遍历 {@link ChunkScannerConfig} 的对象图，构建
 * 「点分路径 → 从根到叶的 {@link Field} 链」索引。路径与对象层级一一对应，
 * 例如 {@code scanner.maxTasksPerTick}、{@code integration.xaero.name}、
 * {@code components.qshop.sellKeyword}。
 *
 * <p>默认值来自 {@link #PRISTINE} —— 一个全新的 {@link ChunkScannerConfig} 实例。
 * 字段初始化器即是默认值的唯一事实来源，新增字段时无需在别处重复声明，
 * 杜绝默认值漂移。
 *
 * <p>索引在静态初始化时构建一次（27 项、深度 3 层，成本可忽略），之后所有查询为 O(1)，
 * 补全遍历为 O(n)。使用 {@link LinkedHashMap} 保证补全顺序与字段声明顺序一致。
 */
public final class ConfigReflectionAccessor {

    private ConfigReflectionAccessor() {}

    /** 默认值快照。字段初始化器即默认值的唯一来源。 */
    private static final ChunkScannerConfig PRISTINE = new ChunkScannerConfig();

    /** 点分路径 → Field 链（从根到叶）。按声明顺序排列，不可变。 */
    private static final Map<String, Field[]> PATHS;

    static {
        Map<String, Field[]> map = new LinkedHashMap<>();
        collect(ChunkScannerConfig.class, "", new ArrayList<>(), map);
        PATHS = Collections.unmodifiableMap(map);
    }

    /** 配置访问异常：路径不存在、值格式非法或反射失败。 */
    public static class ConfigAccessException extends Exception {
        public ConfigAccessException(String message) {
            super(message);
        }
    }

    // ==================== 索引构建 ====================

    /** 递归收集叶子字段路径。 */
    private static void collect(Class<?> type, String prefix, List<Field> chain, Map<String, Field[]> out) {
        for (Field f : type.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;
            if (!Modifier.isPublic(mod)) continue;

            String path = prefix.isEmpty() ? f.getName() : prefix + "." + f.getName();
            chain.add(f);
            if (isLeaf(f.getType())) {
                out.put(path, chain.toArray(new Field[0]));
            } else {
                collect(f.getType(), path, chain, out);
            }
            chain.remove(chain.size() - 1);
        }
    }

    /** 叶子类型判定：基本类型、其包装类、String、枚举为叶子，其余 POJO 继续递归。 */
    private static boolean isLeaf(Class<?> t) {
        return t.isPrimitive()
                || t.isEnum()
                || t == String.class
                || t == Integer.class || t == Long.class || t == Double.class
                || t == Float.class || t == Boolean.class || t == Short.class
                || t == Byte.class || t == Character.class;
    }

    // ==================== 查询 ====================

    /** 全部配置路径，按字段声明顺序。 */
    public static Collection<String> listPaths() {
        return PATHS.keySet();
    }

    /** 路径是否存在。 */
    public static boolean hasPath(String path) {
        return PATHS.containsKey(path);
    }

    /** 该路径字段的类型简名，用于错误提示与 get 展示。路径不存在返回 null。 */
    public static String getTypeName(String path) {
        Field[] chain = PATHS.get(path);
        return chain == null ? null : chain[chain.length - 1].getType().getSimpleName();
    }

    /** 读取活动配置中该路径的值。路径不存在或反射失败返回 null。 */
    public static Object getValue(ChunkScannerConfig config, String path) {
        return read(config, path);
    }

    /** 该路径的默认值，供 get 展示与 reset 使用。 */
    public static Object getDefaultValue(String path) {
        return read(PRISTINE, path);
    }

    private static Object read(ChunkScannerConfig root, String path) {
        Field[] chain = PATHS.get(path);
        if (chain == null || root == null) return null;
        try {
            Object cur = root;
            for (Field f : chain) {
                if (cur == null) return null;
                cur = f.get(cur);
            }
            return cur;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    // ==================== 写入 ====================

    /**
     * 解析字符串并写入活动配置。
     *
     * @throws ConfigAccessException 路径不存在、值格式非法或反射失败
     */
    public static void setValue(ChunkScannerConfig config, String path, String rawValue)
            throws ConfigAccessException {
        Field[] chain = requireChain(path);
        Field leaf = chain[chain.length - 1];
        write(config, chain, parseValue(leaf.getType(), rawValue, path));
    }

    /** 从默认值快照恢复该路径的值。 */
    public static void resetValue(ChunkScannerConfig config, String path) throws ConfigAccessException {
        Field[] chain = requireChain(path);
        write(config, chain, read(PRISTINE, path));
    }

    private static Field[] requireChain(String path) throws ConfigAccessException {
        Field[] chain = PATHS.get(path);
        if (chain == null) {
            throw new ConfigAccessException("Unknown config path: " + path);
        }
        return chain;
    }

    /** 沿 Field 链导航到叶子的宿主对象并写入。 */
    private static void write(ChunkScannerConfig root, Field[] chain, Object value)
            throws ConfigAccessException {
        if (root == null) {
            throw new ConfigAccessException("Config instance is null");
        }
        try {
            Object holder = root;
            for (int i = 0; i < chain.length - 1; i++) {
                holder = chain[i].get(holder);
                if (holder == null) {
                    throw new ConfigAccessException("Config sub-object is null at: " + chain[i].getName());
                }
            }
            chain[chain.length - 1].set(holder, value);
        } catch (IllegalAccessException e) {
            throw new ConfigAccessException("Failed to write config: " + e.getMessage());
        }
    }

    // ==================== 类型转换 ====================

    /** 按目标类型解析字符串。失败时抛出携带期望类型描述的异常。 */
    private static Object parseValue(Class<?> type, String raw, String path) throws ConfigAccessException {
        String s = raw == null ? "" : raw.trim();
        try {
            if (type == int.class || type == Integer.class) return Integer.parseInt(s);
            if (type == long.class || type == Long.class) return Long.parseLong(s);
            if (type == double.class || type == Double.class) return Double.parseDouble(s);
            if (type == float.class || type == Float.class) return Float.parseFloat(s);
            if (type == short.class || type == Short.class) return Short.parseShort(s);
            if (type == byte.class || type == Byte.class) return Byte.parseByte(s);
        } catch (NumberFormatException e) {
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path + ", expected " + type.getSimpleName());
        }

        if (type == boolean.class || type == Boolean.class) {
            // Boolean.parseBoolean 对任何非 "true" 都返回 false，会静默吞错，故显式校验
            if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path + ", expected true or false");
        }

        if (type.isEnum()) {
            for (Object c : type.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
            }
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path
                            + ", expected one of: " + String.join(", ", enumNames(type)));
        }

        // String：原样保留（不 trim，正则与路径点名可能含有意义的前后空格）
        if (type == String.class) return raw == null ? "" : raw;

        throw new ConfigAccessException("Unsupported config type: " + type.getSimpleName());
    }

    // ==================== 补全 ====================

    /**
     * value 参数的补全候选。
     * <ul>
     *   <li>boolean → {@code true} / {@code false}</li>
     *   <li>enum → 全部常量名</li>
     *   <li>其余类型 → 当前值（作为可编辑起点）</li>
     * </ul>
     * 路径不存在时返回空列表（补全需安全降级，不抛异常）。
     */
    public static List<String> suggestValues(ChunkScannerConfig config, String path) {
        Field[] chain = PATHS.get(path);
        if (chain == null) return List.of();
        Class<?> type = chain[chain.length - 1].getType();

        if (type == boolean.class || type == Boolean.class) {
            return List.of("true", "false");
        }
        if (type.isEnum()) {
            return enumNames(type);
        }
        Object cur = read(config, path);
        return cur == null ? List.of() : List.of(String.valueOf(cur));
    }

    private static List<String> enumNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Object c : type.getEnumConstants()) {
            names.add(((Enum<?>) c).name());
        }
        return names;
    }
}
