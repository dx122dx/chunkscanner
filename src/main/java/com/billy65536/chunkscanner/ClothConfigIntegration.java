package com.billy65536.chunkscanner;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config API 集成。
 *
 * 提供图形化配置界面，当 Cloth Config 模组被加载时，
 * 通过 ModMenu 入口可打开此配置界面。
 *
 * 配置界面分为三个分类：
 * - 扫描：基本扫描参数（重访间隔、速率、视距倍率等）
 * - 路径点：Xaero 路径点联动参数（名称、缩写、组）
 * - 高级：底层性能参数（线程数、目标 tick 耗时）
 */
public class ClothConfigIntegration {

    /**
     * 创建 Cloth Config 配置界面。如果 Cloth Config 未加载则返回 null。
     * @param parent 返回时的父界面
     */
    public static Screen createConfigScreen(Screen parent) {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) return null;

        var builder = me.shedaniel.clothconfig2.api.ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("ChunkScanner 设置"));

        // 保存时将配置写回 JSON 文件（Cloth Config 内部已管理内存中的值）
        builder.setSavingRunnable(() -> ConfigLoader.save(ChunkScannerMod.CONFIG));

        // === 扫描分类 ===
        var general = builder.getOrCreateCategory(Text.literal("扫描"));

        general.addEntry(builder.entryBuilder()
                .startIntSlider(Text.literal("最小重访间隔（秒）"),
                        ChunkScannerMod.CONFIG.minRevisitIntervalSec, 0, 3600)
                .setDefaultValue(60)
                .setTooltip(Text.literal("同一区块在此时间内不会被重新扫描。0 = 不禁用"))
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.minRevisitIntervalSec = v)
                .build());

        general.addEntry(builder.entryBuilder()
                .startIntSlider(Text.literal("最大扫描速率（chunk/tick）"),
                        ChunkScannerMod.CONFIG.maxTasksPerTick, 1, 32)
                .setDefaultValue(16)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.maxTasksPerTick = v)
                .build());

        general.addEntry(builder.entryBuilder()
                .startIntSlider(Text.literal("初始扫描速率（chunk/tick）"),
                        ChunkScannerMod.CONFIG.initialTasksPerTick, 1, 16)
                .setDefaultValue(2)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.initialTasksPerTick = v)
                .build());

        general.addEntry(builder.entryBuilder()
                .startIntSlider(Text.literal("刷写间隔（tick）"),
                        ChunkScannerMod.CONFIG.flushIntervalTicks, 10, 1000)
                .setDefaultValue(100)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.flushIntervalTicks = v)
                .build());

        general.addEntry(builder.entryBuilder()
                .startDoubleField(Text.literal("扫描视距倍率"),
                        ChunkScannerMod.CONFIG.scanRadiusMultiplier)
                .setDefaultValue(1.0).setMin(0.1).setMax(4.0)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.scanRadiusMultiplier = v)
                .build());

        // === 路径点分类 ===
        var waypoint = builder.getOrCreateCategory(Text.literal("路径点"));

        waypoint.addEntry(builder.entryBuilder()
                .startStrField(Text.literal("路径点名称"),
                        ChunkScannerMod.CONFIG.waypointName)
                .setDefaultValue("选中的坐标点")
                .setTooltip(Text.literal("Xaero 路径点的显示名称"))
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.waypointName = v)
                .build());

        waypoint.addEntry(builder.entryBuilder()
                .startStrField(Text.literal("路径点缩写"),
                        ChunkScannerMod.CONFIG.waypointInitials)
                .setDefaultValue("目标")
                .setTooltip(Text.literal("Xaero 路径点的缩写/符号"))
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.waypointInitials = v)
                .build());

        waypoint.addEntry(builder.entryBuilder()
                .startStrField(Text.literal("路径点组名"),
                        ChunkScannerMod.CONFIG.waypointGroup)
                .setDefaultValue("chunkscanner")
                .setTooltip(Text.literal("Xaero 路径点所在组（WaypointSet 名称）。留空则添加到当前组"))
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.waypointGroup = v)
                .build());

        // === 高级分类 ===
        var advanced = builder.getOrCreateCategory(Text.literal("高级"));

        advanced.addEntry(builder.entryBuilder()
                .startIntSlider(Text.literal("工作线程数"),
                        ChunkScannerMod.CONFIG.workerThreads, 1, 8)
                .setDefaultValue(2)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.workerThreads = v)
                .build());

        advanced.addEntry(builder.entryBuilder()
                .startLongField(Text.literal("目标 tick 耗时（纳秒）"),
                        ChunkScannerMod.CONFIG.targetTickNs)
                .setDefaultValue(5_000_000L).setMin(1_000_000L).setMax(50_000_000L)
                .setSaveConsumer(v -> ChunkScannerMod.CONFIG.targetTickNs = v)
                .build());

        return builder.build();
    }
}
