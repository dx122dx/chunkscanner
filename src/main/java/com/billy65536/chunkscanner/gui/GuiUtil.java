package com.billy65536.chunkscanner.gui;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkAnalyzer;
import com.billy65536.chunkscanner.core.ChunkScanner;

import net.minecraft.text.Text;

/**
 * 跨 GUI 共用的工具方法。
 *
 * 避免在 ChunkScannerScreen / DatabaseScreen 中重复定义。
 */
public final class GuiUtil {

    private GuiUtil() {}

    /* ==================== 数学 ==================== */

    /** 三向 clamp。 */
    public static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /** 点是否落在矩形内。 */
    public static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /* ==================== 格式化 ==================== */

    /** 平台无关的文件大小格式化。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 分析器 ID → 显示名称。 */
    public static Text getAnalyzerDisplayName(String analyzerId) {
        if (analyzerId == null || analyzerId.isEmpty()) return Text.empty();
        ChunkScanner scanner = ChunkScannerMod.getScanner();
        if (scanner != null) {
            IChunkAnalyzer a = AnalyzerRegistry.get(analyzerId);
            if (a != null) return a.getName();
        }
        return Text.literal(analyzerId);
    }

    /* ==================== 字节 → 十六进制 ==================== */

    /** 截断式十六进制渲染。 */
    public static String bytesToHex(byte[] data, int maxLen) {
        if (data == null || data.length == 0) return "(empty)";
        int len = Math.min(data.length, maxLen);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (data.length > maxLen) sb.append("...");
        return sb.toString();
    }

    /** 完整十六进制渲染。 */
    public static String bytesToFullHex(byte[] data) {
        if (data == null || data.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
