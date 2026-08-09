package com.billy65536.chunkscanner.components.analyzer;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.billy65536.infrastructure.core.render.Box;
import com.billy65536.infrastructure.core.render.BoxRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QShop 告示牌高亮边框渲染器。
 * <p>
 * 保留本地缓存与红→绿→黄渐变逻辑；线框盒的矩阵管理与绘制已下沉到
 * {@link BoxRenderer}（infrastructure 通用渲染器），本类只负责把缓存条目转换为
 * {@link Box} 列表并委托渲染，行为与原实现一致。
 */
public final class QShopHighlightRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkscanner.components.qshop.highlight");

    private static final long CACHE_TTL_MS = 2000;

    private static List<HighlightEntry> cachedEntries = Collections.emptyList();
    private static long lastCacheTime = 0;
    private static int lastEntryCount = -1;

    private record HighlightEntry(int x, int y, int z, long enhancementTimestamp) {}

    private QShopHighlightRenderer() {}

    public static void initialize() {
        LOGGER.info("QShop highlight renderer initialized, enabled={}",
                ChunkScannerMod.getConfig().components.qshop.highlightEnabled);
        WorldRenderEvents.LAST.register(QShopHighlightRenderer::doRender);
    }

    private static void doRender(WorldRenderContext context) {
        if (!ChunkScannerMod.getConfig().components.qshop.highlightEnabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        if (now - lastCacheTime > CACHE_TTL_MS) {
            cachedEntries = buildHighlightEntries(client);
            lastCacheTime = now;
        }

        if (cachedEntries.isEmpty()) return;

        // 把缓存条目转换为线框盒列表（单方块 1×1×1 + 渐变颜色），委托通用渲染器绘制
        List<Box> boxes = new ArrayList<>(cachedEntries.size());
        for (HighlightEntry entry : cachedEntries) {
            int color = computeColor(entry.enhancementTimestamp(), now);
            boxes.add(Box.ofBlocks(entry.x(), entry.y(), entry.z(),
                    entry.x(), entry.y(), entry.z(), color));
        }
        BoxRenderer.render(context, boxes);
    }

    // ==================== 数据构建 ====================

    private static List<HighlightEntry> buildHighlightEntries(MinecraftClient client) {
        ChunkScanner scanner = ChunkScannerMod.getScanner();
        if (scanner == null) return Collections.emptyList();

        int playerCX = client.player.getBlockPos().getX() >> 4;
        int playerCZ = client.player.getBlockPos().getZ() >> 4;
        String playerDim = client.world.getRegistryKey().getValue().toString();

        int highlightRadius = ChunkScannerMod.getConfig().components.qshop.highlightRadius;
        List<HighlightEntry> entries = new ArrayList<>();
        List<ScanSession> sessions = new ArrayList<>(scanner.getActiveSessions());

        for (ScanSession session : sessions) {
            if (!ChunkScannerMod.id("qshop").equals(session.analyzer.getId())) continue;

            QShopDbAdapter adapter = session.pkg.getAdaptor(QShopDbAdapter.class);
            List<QShopDbAdapter.Record> records;
            try {
                records = adapter.getAllRecords();
            } catch (Exception e) {
                LOGGER.error("Failed to read records from DB: {}", e.getMessage(), e);
                continue;
            }

            for (QShopDbAdapter.Record rec : records) {
                if (!playerDim.equals(rec.dimId())) continue;
                int rcCX = rec.x() >> 4;
                int rcCZ = rec.z() >> 4;
                if (Math.abs(rcCX - playerCX) > highlightRadius
                        || Math.abs(rcCZ - playerCZ) > highlightRadius) continue;

                entries.add(new HighlightEntry(rec.x(), rec.y(), rec.z(), rec.enhancementTimestamp()));
            }
        }

        if (entries.size() != lastEntryCount) {
            LOGGER.debug("Highlight entries refreshed: count={}, playerChunk=({},{})",
                    entries.size(), playerCX, playerCZ);
            lastEntryCount = entries.size();
        }

        return entries;
    }

    /**
     * 根据增强时间戳计算高亮颜色：未增强为红，增强后随时间由红渐变到绿，
     * 超过渐变周期后转为黄（表示已稳定）。
     */
    private static int computeColor(long enhancementTimestamp, long now) {
        if (enhancementTimestamp <= 0) return 0xFFFF0000;
        long gradientMs = ChunkScannerMod.getConfig().components.qshop.highlightGradientMs;
        if (gradientMs <= 0) return 0xFF00FF00; // 无渐变，始终绿色
        long ageMs = now - enhancementTimestamp;
        if (ageMs >= gradientMs) return 0xFFFFFF00;
        float t = Math.min(1.0f, (float) ageMs / gradientMs);
        int r = (int) (255 * t);
        int g = 255;
        return 0xFF000000 | (r << 16) | (g << 8);
    }
}
