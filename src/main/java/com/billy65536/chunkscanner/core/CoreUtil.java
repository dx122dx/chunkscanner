package com.billy65536.chunkscanner.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Core 包内共用工具方法。
 */
public class CoreUtil {

    private CoreUtil() {}

    // ==================== 工具 ====================

    public static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    public static long packChunkPos(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
