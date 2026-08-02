package com.billy65536.chunkscanner.core.navigation;

/**
 * 导航队列中的一个目标位置。
 *
 * <p>与 {@link com.billy65536.chunkscanner.core.LocatedPosition} 结构相同，
 * 但作为导航包的独立类型，保持 core.navigation 包的自包含性。</p>
 */
public record NavigationEntry(String dimensionId, int x, int y, int z) {
}
