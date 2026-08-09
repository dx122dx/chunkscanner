package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.core.db.DbPackage;

import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库适配器接口。
 *
 * <p>适配器是<b>分析器与消费端访问数据库的唯一正规渠道</b>：它把某个分析器专属的
 * 二进制格式封装成强类型的数据访问能力（如 {@code getAllRecords()}），对外隐藏
 * 底层 {@link IChunkDb} 的键值细节。分析器负责写入，视图提供者 / 命令 / GUI 负责读取，
 * 二者都只与本接口打交道，从不直接接触 {@link IChunkDb}。</p>
 *
 * <p>适配器实例由 {@link DbPackage} 通过本接口的 {@link FactoryRegistry 工厂注册表}创建，
 * 其身份（{@link IFactory#getId() adaptorId}）记录在包的 metadata 中。当包声明的 adaptorId
 * 未注册时，{@link DbPackage} 回退到内置的 {@code chunkscanner:raw} 适配器。</p>
 */
public interface IDbAdaptor {

    /** 返回所属的数据库包。 */
    DbPackage pkg();

    /** 适配器工厂：按 adaptorId 创建对应的适配器实例。 */
    interface IFactory {
        /** 适配器唯一标识符（即 adaptorId），如 {@code chunkscanner:qshop}。 */
        Identifier getId();

        /** 基于数据库包创建适配器实例。 */
        IDbAdaptor create(DbPackage pkg);
    }

    /** 适配器工厂全局注册表（独立于 AnalyzerRegistry / DbViewProviderRegistry）。 */
    final class FactoryRegistry {
        private static final Map<Identifier, IFactory> factories = new LinkedHashMap<>();

        private FactoryRegistry() {}

        /** 注册一个适配器工厂；第一个注册的成为 {@link #getDefault() 默认}。 */
        public static void register(IFactory factory) {
            factories.put(factory.getId(), factory);
        }

        /** 通过 adaptorId 获取工厂。 */
        public static IFactory get(Identifier id) {
            return factories.get(id);
        }

        /** 获取默认工厂（注册表中的第一个，约定为 {@code chunkscanner:raw}）。 */
        public static IFactory getDefault() {
            return factories.isEmpty() ? null : factories.values().iterator().next();
        }

        /** 所有已注册的工厂（只读，保持注册顺序）。 */
        public static java.util.Collection<IFactory> getAll() {
            return java.util.Collections.unmodifiableCollection(factories.values());
        }
    }
}
