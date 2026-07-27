package com.billy65536.chunkscanner.core;

/**
 * 单个区块在可见范围内的状态分类（用于状态条渲染）。
 *
 * <p>此 record 独立于 {@link ChunkScanner} 存在，以便单元测试无需加载
 * Minecraft 类即可引用。</p>
 */
public record ChunkStatusBreakdown(
    int pending, int scannedNoFind, int scannedFound,
    int pastRevisitNoFind, int pastRevisitFound, int error, int foundError
) {
    public int total() {
        return pending + scannedNoFind + scannedFound
                + pastRevisitNoFind + pastRevisitFound + error + foundError;
    }
}
