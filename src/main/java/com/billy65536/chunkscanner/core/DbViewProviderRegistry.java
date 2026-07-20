package com.billy65536.chunkscanner.core;

import java.util.Map;
import java.util.Set;

import com.billy65536.chunkscanner.ChunkScannerMod;

import java.util.LinkedHashMap;
import java.util.Collections;

/** 全局视图类型注册表。 */
public final class DbViewProviderRegistry {
    private static final Map<String, Type> types = new LinkedHashMap<>();

    /** 注册一个视图类型。 */
    public static void register(Type type) {
        types.put(type.getId(), type);
        ChunkScannerMod.LOGGER.info("Registered DbViewProvider: {}", type.getId());
    }

    /** 获取所有已注册的类型（只读）。 */
    public static java.util.Collection<Type> getAll() {
        return Collections.unmodifiableCollection(types.values());
    }

    /** 通过 id 获取类型。 */
    public static Type get(String id) {
        return types.get(id);
    }

    /**
     * 数据库视图类型描述符。
     *
     * <p>
     * 注册到 {@link DbViewProviderRegistry} 中，用于在 DB 界面选择不同的视图提供者。
     * 每个类型具有 id、name、description（可本地化），以及适用的分析器 id 集合。
     * 实际的数据访问实例由类型描述符根据底层 ChunkDb 创建。
     * </p>
     */
    public interface Type {
        /** 唯一标识符，不可变。 */
        String getId();

        /** 显示名称（本地化）。 */
        String getName();

        /** 描述文本（本地化），用于悬停提示。 */
        String getDescription();

        /** 适用的分析器 id 集合。空集表示适用于所有。 */
        Set<String> applicableAnalyzers();

        /**
         * 根据底层 ChunkDb 创建此类型的 DbViewProvider 实例。
         * 如果此类型不适用于该数据库，返回 null。
         */
        DbViewProvider create(ChunkDb db);
    }

}