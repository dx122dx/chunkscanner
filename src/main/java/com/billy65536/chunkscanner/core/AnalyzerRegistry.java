package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.ChunkScannerMod;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局分析器注册表。
 *
 * <p>所有 {@link ChunkAnalyzer} 实现通过此注册表注册，供扫描引擎和命令系统查询。
 * 注册表为静态单例，在模组初始化时完成注册。</p>
 *
 * <p>注册顺序决定命令补全和列表展示的排列顺序。</p>
 */
public final class AnalyzerRegistry {

    private static final Map<String, ChunkAnalyzer> analyzers = new LinkedHashMap<>();

    private AnalyzerRegistry() {}

    /** 注册一个分析器。重复注册会覆盖之前同 ID 的分析器。 */
    public static void register(ChunkAnalyzer analyzer) {
        analyzers.put(analyzer.getId(), analyzer);
        ChunkScannerMod.LOGGER.info("Registered analyzer: {}", analyzer.getId());
    }

    /** 通过 ID 获取分析器，不存在返回 null。 */
    public static ChunkAnalyzer get(String id) {
        return analyzers.get(id);
    }

    /** 获取所有已注册的分析器（只读）。 */
    public static Collection<ChunkAnalyzer> getAll() {
        return Collections.unmodifiableCollection(analyzers.values());
    }
}
