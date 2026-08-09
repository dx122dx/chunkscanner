package com.billy65536.chunkscanner.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

/**
 * 全局分析器注册表。
 *
 * <p>所有 {@link IChunkAnalyzer} 实现通过此注册表注册，供扫描引擎和命令系统查询。
 * 注册表为静态单例，在模组初始化时完成注册。</p>
 *
 * <p>分析器与数据库适配器（{@link IDbAdaptor}）、视图提供者
 * （{@link DbViewProviderRegistry.ITypeDescriptor}）相互独立注册；
 * 分析器通过 {@link IChunkAnalyzer#getAdaptorId()} 声明自己使用的适配器 ID。</p>
 *
 * <p>注册顺序决定命令补全和列表展示的排列顺序。</p>
 */
public final class AnalyzerRegistry {

    private static final Map<Identifier, IChunkAnalyzer> analyzers = new LinkedHashMap<>();

    private AnalyzerRegistry() {}

    /**
     * 注册一个分析器。
     * 重复注册同 ID 会覆盖之前的分析器。
     *
     * @param analyzer 分析器实例
     */
    public static void register(IChunkAnalyzer analyzer) {
        if (analyzer == null || analyzer.getId() == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register null analyzer or analyzer with null ID, ignored");
            return;
        }
        analyzers.put(analyzer.getId(), analyzer);
        ChunkScannerMod.LOGGER.info("Registered analyzer: {}", analyzer.getId());
    }

    /** 通过 ID 获取分析器，不存在返回 null。 */
    public static IChunkAnalyzer get(Identifier id) {
        return analyzers.get(id);
    }

    /** 获取指定分析器声明的适配器 ID，未注册返回 {@code chunkscanner:raw}。 */
    public static Identifier getAdaptorId(Identifier analyzerId) {
        IChunkAnalyzer a = analyzers.get(analyzerId);
        if (a == null) return ChunkScannerMod.id("raw");
        Identifier ad = a.getAdaptorId();
        return ad != null ? ad : ChunkScannerMod.id("raw");
    }

    /** 获取所有已注册的分析器（只读）。 */
    public static Collection<IChunkAnalyzer> getAll() {
        return Collections.unmodifiableCollection(analyzers.values());
    }
}
