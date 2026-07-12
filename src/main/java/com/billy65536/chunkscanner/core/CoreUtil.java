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

    /**
     * 替换字符串中的 {key} 占位符。
     * 根据 headers 和 values 数组，将 {headerName} 替换为对应的 value。
     * 例如：headers = ["Owner", "Item"], values = ["南瓜", "钻石"]
     *       模板 "{Owner} - {Item}" → "南瓜 - 钻石"
     * 不存在的占位符保持原样，值为 null 的替换为空字符串。
     *
     * @param template 包含 {key} 占位符的模板字符串
     * @param headers  列标题数组
     * @param values   列值数组（与 headers 一一对应）
     * @return 替换后的字符串
     */
    public static String replacePlaceholders(String template, String[] headers, String[] values) {
        if (template == null || template.isEmpty()) return template;
        if (headers == null || values == null || headers.length != values.length) return template;

        // 预构建查找映射，避免 O(n) 次 String.replace 和双重替换问题
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put("{" + headers[i] + "}", values[i] != null ? values[i] : "");
        }

        StringBuilder sb = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            int brace = template.indexOf('{', i);
            if (brace == -1) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, brace);
            int close = template.indexOf('}', brace + 1);
            if (close == -1) {
                sb.append(template, brace, template.length());
                break;
            }
            String key = template.substring(brace, close + 1);
            String replacement = map.get(key);
            if (replacement != null) {
                sb.append(replacement);
            } else {
                sb.append(key); // 不认识的占位符保持原样
            }
            i = close + 1;
        }
        return sb.toString();
    }
}
