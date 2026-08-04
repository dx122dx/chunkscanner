package com.billy65536.chunkscanner.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkScannerConfig} 嵌套结构与深拷贝测试。
 *
 * <p>深拷贝隔离性是最关键的一点：{@link TaskConfig#applyTo} 依赖 {@code copy()}
 * 返回完全独立的对象图，否则任务级配置的修改会污染全局配置。
 */
class ChunkScannerConfigTest {

    @Test
    void defaultsMatchDocumentedValues() {
        ChunkScannerConfig c = new ChunkScannerConfig();

        assertEquals(60, c.scanner.minRevisitIntervalSec);
        assertEquals(16, c.scanner.maxTasksPerTick);
        assertEquals(2, c.scanner.initialTasksPerTick);
        assertEquals(5_000_000L, c.scanner.targetTickNs);
        assertEquals(100, c.scanner.flushIntervalTicks);
        assertEquals(2, c.scanner.workerThreads);
        assertEquals(1.0, c.scanner.scanRadiusMultiplier);

        assertEquals("选中的坐标点", c.integration.xaero.name);
        assertEquals("目标", c.integration.xaero.initials);
        assertEquals("chunkscanner", c.integration.xaero.group);

        assertFalse(c.integration.baritone.autoEnabled);
        assertEquals(3.0, c.integration.baritone.reachDist);
        assertEquals(128, c.integration.baritone.compositeLimit);
        assertEquals(ChunkScannerConfig.BaritoneRiskWarning.SHOWN,
                c.integration.baritone.riskWarning);

        assertEquals("出售", c.components.qshop.sellKeyword);
        assertEquals("收购", c.components.qshop.buyKeyword);
        assertFalse(c.components.qshop.highlightEnabled);
        assertEquals(1, c.components.qshop.highlightRadius);
        assertEquals(86400_000L, c.components.qshop.highlightGradientMs);
        assertEquals(30_000L, c.components.qshop.manualEnhanceItemExpireMs);
        assertEquals(ChunkScannerConfig.EnhanceMatchMode.StrictAutomatic,
                c.components.qshop.enhanceMatchMode);
        assertEquals(ChunkScannerConfig.ChatInterceptionMethod.BOTH,
                c.components.qshop.chatInterceptionMethod);
    }

    @Test
    void copyPreservesAllValues() {
        ChunkScannerConfig src = new ChunkScannerConfig();
        src.scanner.maxTasksPerTick = 31;
        src.scanner.scanRadiusMultiplier = 3.5;
        src.integration.xaero.name = "shop";
        src.integration.baritone.riskWarning = ChunkScannerConfig.BaritoneRiskWarning.HIDDEN;
        src.components.qshop.sellKeyword = "SELL";
        src.components.qshop.highlightEnabled = true;

        ChunkScannerConfig dst = src.copy();

        assertEquals(31, dst.scanner.maxTasksPerTick);
        assertEquals(3.5, dst.scanner.scanRadiusMultiplier);
        assertEquals("shop", dst.integration.xaero.name);
        assertEquals(ChunkScannerConfig.BaritoneRiskWarning.HIDDEN,
                dst.integration.baritone.riskWarning);
        assertEquals("SELL", dst.components.qshop.sellKeyword);
        assertTrue(dst.components.qshop.highlightEnabled);
    }

    @Test
    void copyIsDeepSoSubObjectsAreNotShared() {
        ChunkScannerConfig src = new ChunkScannerConfig();
        ChunkScannerConfig dst = src.copy();

        assertNotSame(src.scanner, dst.scanner);
        assertNotSame(src.integration, dst.integration);
        assertNotSame(src.integration.xaero, dst.integration.xaero);
        assertNotSame(src.integration.baritone, dst.integration.baritone);
        assertNotSame(src.components, dst.components);
        assertNotSame(src.components.qshop, dst.components.qshop);
    }

    @Test
    void mutatingCopyDoesNotPolluteSource() {
        ChunkScannerConfig global = new ChunkScannerConfig();
        ChunkScannerConfig task = global.copy();

        task.scanner.maxTasksPerTick = 32;
        task.integration.xaero.name = "task-local";
        task.components.qshop.highlightEnabled = true;

        assertEquals(16, global.scanner.maxTasksPerTick);
        assertEquals("选中的坐标点", global.integration.xaero.name);
        assertFalse(global.components.qshop.highlightEnabled);
    }
}
