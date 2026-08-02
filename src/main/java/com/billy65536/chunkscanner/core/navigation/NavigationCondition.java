package com.billy65536.chunkscanner.core.navigation;

import net.minecraft.client.MinecraftClient;

/**
 * 导航到达判定条件。
 *
 * <p>由调用者实现，Baritone 只管"走"，条件达成的判定是调用者的责任。</p>
 */
@FunctionalInterface
public interface NavigationCondition {
    boolean isSatisfied(MinecraftClient client);
}
