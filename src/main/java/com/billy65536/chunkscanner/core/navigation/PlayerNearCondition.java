package com.billy65536.chunkscanner.core.navigation;

import net.minecraft.client.MinecraftClient;

/**
 * 玩家距离目标位置在指定范围内即判定到达。
 */
public final class PlayerNearCondition implements NavigationCondition {

    private final int x, y, z;
    private final double reachDistSq;

    public PlayerNearCondition(int x, int y, int z, double reachDist) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.reachDistSq = reachDist * reachDist;
    }

    @Override
    public boolean isSatisfied(MinecraftClient client) {
        if (client.player == null) return false;
        double dx = client.player.getX() - x;
        double dy = client.player.getY() - y;
        double dz = client.player.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= reachDistSq;
    }
}
