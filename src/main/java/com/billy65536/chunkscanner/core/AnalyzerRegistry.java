package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.ChunkScannerMod;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.Identifier;

/**
 * 全局分析器注册表。
 *
 * <p>所有 {@link IChunkAnalyzer} 实现通过此注册表注册，供扫描引擎和命令系统查询。
 * 注册表为静态单例，在模组初始化时完成注册。</p>
 *
 * <p>注册顺序决定命令补全和列表展示的排列顺序。</p>
 */
public final class AnalyzerRegistry {

    /** 默认视图提供者标识符（命名空间 chunkscanner）。 */
    public static final Identifier DEFAULT_VIEW = ChunkScannerMod.id("raw");

    private static final Map<Identifier, IChunkAnalyzer> analyzers = new LinkedHashMap<>();
    private static final Map<Identifier, Identifier> defaultViewProviders = new LinkedHashMap<>();

    private AnalyzerRegistry() {}

    /**
     * 注册一个分析器并指定默认视图提供者为 "chunkscanner:raw"。
     * 重复注册会覆盖之前同 ID 的分析器及其默认视图提供者。
     *
     * @param analyzer            分析器实例
     */
    public static void register(IChunkAnalyzer analyzer) {
        register(analyzer, DEFAULT_VIEW);
    }

    /**
     * 注册一个分析器并指定默认视图提供者 id。
     * 重复注册会覆盖之前同 ID 的分析器及其默认视图提供者。
     *
     * @param analyzer            分析器实例
     * @param defaultViewProvider 默认视图提供者 id（对应 DbViewProviderRegistry 中注册的 id），留空或 null 视为 "chunkscanner:raw"
     */
    public static void register(IChunkAnalyzer analyzer, Identifier defaultViewProvider) {
        if (analyzer == null || analyzer.getId() == null) {
            ChunkScannerMod.LOGGER.warn("Attempted to register null analyzer or analyzer with null ID, ignored");
            return;
        }
        analyzers.put(analyzer.getId(), analyzer);
        Identifier dvp = (defaultViewProvider == null) ? DEFAULT_VIEW : defaultViewProvider;
        defaultViewProviders.put(analyzer.getId(), dvp);
        ChunkScannerMod.LOGGER.info("Registered analyzer: {} (default view: {})", analyzer.getId(), dvp);
    }

    /** 通过 ID 获取分析器，不存在返回 null。 */
    public static IChunkAnalyzer get(Identifier id) {
        return analyzers.get(id);
    }

    /**
     * 获取分析器对应的默认视图提供者 id，未注册返回 "chunkscanner:raw"。
     */
    public static Identifier getDefaultViewProvider(Identifier analyzerId) {
        return defaultViewProviders.getOrDefault(analyzerId, DEFAULT_VIEW);
    }

    /** 获取所有已注册的分析器（只读）。 */
    public static Collection<IChunkAnalyzer> getAll() {
        return Collections.unmodifiableCollection(analyzers.values());
    }
}
