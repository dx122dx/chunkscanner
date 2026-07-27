package com.billy65536.chunkscanner.components.analyzer;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.ChunkScanner;
import com.billy65536.chunkscanner.core.ScanSession;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QShop 告示牌高亮边框渲染器。
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
                ChunkScannerMod.CONFIG.qshopHighlightEnabled);
        WorldRenderEvents.LAST.register(QShopHighlightRenderer::doRender);
    }

    private static void doRender(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        if (now - lastCacheTime > CACHE_TTL_MS) {
            cachedEntries = buildHighlightEntries(client);
            lastCacheTime = now;
        }

        if (!ChunkScannerMod.CONFIG.qshopHighlightEnabled) {
            LOGGER.debug("Highlight disabled by config, skipping render");
            return;
        }
        if (cachedEntries.isEmpty()) return;

        renderHighlights(context, now);
    }

    // ==================== 矩阵管理 ====================

    /**
     * 使用 JOML 的 lookAt 构建标准视图矩阵，确保与 Minecraft 坐标系兼容。
     */
    private static void setupMatrices(WorldRenderContext context) {
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        float yaw = camera.getYaw();
        float pitch = camera.getPitch();

        // Minecraft 坐标系计算前方向量：
        // yaw=0 朝 +Z(南), yaw=90 朝 -X(西), pitch>0 朝下
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = pitch * MathHelper.RADIANS_PER_DEGREE;
        float fx = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        float fy = -MathHelper.sin(pitchRad);
        float fz = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);

        // 用 lookAt 构建视图矩阵
        float cx = (float) camPos.x;
        float cy = (float) camPos.y;
        float cz = (float) camPos.z;
        Matrix4f viewMatrix = new Matrix4f().lookAt(
                cx, cy, cz,
                cx + fx, cy + fy, cz + fz,
                0f, 1f, 0f
        );

        // 设置投影矩阵
        Matrix4f projectionMatrix = context.projectionMatrix();
        if (projectionMatrix != null) {
            RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorter.BY_DISTANCE);
        }

        // 写入 RenderSystem model-view 栈
        MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        modelViewStack.peek().getPositionMatrix().set(viewMatrix);
        RenderSystem.applyModelViewMatrix();
    }

    private static void restoreMatrices() {
        RenderSystem.getModelViewStack().pop();
        RenderSystem.applyModelViewMatrix();
    }

    // ==================== 高亮边框 ====================

    private static void renderHighlights(WorldRenderContext context, long now) {
        try {
            setupMatrices(context);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            RenderSystem.lineWidth(2.0f);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

            for (HighlightEntry entry : cachedEntries) {
                int color = computeColor(entry.enhancementTimestamp, now);
                drawWireframeBox(buffer, entry.x(), entry.y(), entry.z(), color);
            }

            tessellator.draw();
        } catch (Exception e) {
            LOGGER.error("RENDER ERROR: {}", e.getMessage(), e);
        } finally {
            RenderSystem.lineWidth(1.0f);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            restoreMatrices();
        }
    }

    // ==================== 工具 ====================

    private static void v(BufferBuilder buffer, double x, double y, double z, int r, int g, int b, int a) {
        buffer.vertex(x, y, z).color(r, g, b, a).next();
    }

    private static List<HighlightEntry> buildHighlightEntries(MinecraftClient client) {
        ChunkScanner scanner = ChunkScannerMod.getScanner();
        if (scanner == null) return Collections.emptyList();

        BlockPos playerPos = client.player.getBlockPos();
        int playerCX = playerPos.getX() >> 4;
        int playerCZ = playerPos.getZ() >> 4;
        String playerDim = client.world.getRegistryKey().getValue().toString();

        int highlightRadius = ChunkScannerMod.CONFIG.qshopHighlightRadius;
        List<HighlightEntry> entries = new ArrayList<>();
        List<ScanSession> sessions = new ArrayList<>(scanner.getActiveSessions());

        for (ScanSession session : sessions) {
            if (!"qshop".equals(session.analyzer.getId())) continue;

            QShopDbAdapter adapter = new QShopDbAdapter(session.db);
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

    private static int computeColor(long enhancementTimestamp, long now) {
        if (enhancementTimestamp <= 0) return 0xFFFF0000;
        long gradientMs = ChunkScannerMod.CONFIG.qshopHighlightGradientMs;
        if (gradientMs <= 0) return 0xFF00FF00; // 无渐变，始终绿色
        long ageMs = now - enhancementTimestamp;
        if (ageMs >= gradientMs) return 0xFFFFFF00;
        float t = Math.min(1.0f, (float) ageMs / gradientMs);
        int r = (int) (255 * t);
        int g = 255;
        return 0xFF000000 | (r << 16) | (g << 8);
    }

    private static void drawWireframeBox(BufferBuilder buffer, int bx, int by, int bz, int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;

        double margin = 0.005; // 微偏移避免 z-fighting
        double x1 = bx - margin, y1 = by - margin, z1 = bz - margin;
        double x2 = bx + 1.0 + margin, y2 = by + 1.0 + margin, z2 = bz + 1.0 + margin;

        v(buffer, x1, y1, z1, r, g, b, a); v(buffer, x2, y1, z1, r, g, b, a);
        v(buffer, x2, y1, z1, r, g, b, a); v(buffer, x2, y1, z2, r, g, b, a);
        v(buffer, x2, y1, z2, r, g, b, a); v(buffer, x1, y1, z2, r, g, b, a);
        v(buffer, x1, y1, z2, r, g, b, a); v(buffer, x1, y1, z1, r, g, b, a);

        v(buffer, x1, y2, z1, r, g, b, a); v(buffer, x2, y2, z1, r, g, b, a);
        v(buffer, x2, y2, z1, r, g, b, a); v(buffer, x2, y2, z2, r, g, b, a);
        v(buffer, x2, y2, z2, r, g, b, a); v(buffer, x1, y2, z2, r, g, b, a);
        v(buffer, x1, y2, z2, r, g, b, a); v(buffer, x1, y2, z1, r, g, b, a);

        v(buffer, x1, y1, z1, r, g, b, a); v(buffer, x1, y2, z1, r, g, b, a);
        v(buffer, x2, y1, z1, r, g, b, a); v(buffer, x2, y2, z1, r, g, b, a);
        v(buffer, x2, y1, z2, r, g, b, a); v(buffer, x2, y2, z2, r, g, b, a);
        v(buffer, x1, y1, z2, r, g, b, a); v(buffer, x1, y2, z2, r, g, b, a);
    }
}
