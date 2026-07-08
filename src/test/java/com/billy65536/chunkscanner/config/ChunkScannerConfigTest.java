package com.billy65536.chunkscanner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkScannerConfig 单元测试。
 * 覆盖默认值和 copy() 方法。
 */
@DisplayName("ChunkScannerConfig")
class ChunkScannerConfigTest {

    // ==================== 默认值 ====================

    @Nested
    @DisplayName("默认值")
    class DefaultValuesTests {
        @Test
        @DisplayName("新实例应具有正确的扫描默认值")
        void newInstance_shouldHaveScanDefaults() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            assertEquals(60, config.minRevisitIntervalSec);
            assertEquals(16, config.maxTasksPerTick);
            assertEquals(2, config.initialTasksPerTick);
            assertEquals(5_000_000L, config.targetTickNs);
            assertEquals(100, config.flushIntervalTicks);
            assertEquals(2, config.workerThreads);
            assertEquals(1.0, config.scanRadiusMultiplier, 0.0001);
        }

        @Test
        @DisplayName("新实例应具有正确的路径点默认值")
        void newInstance_shouldHaveWaypointDefaults() {
            ChunkScannerConfig config = new ChunkScannerConfig();
            assertEquals("选中的坐标点", config.waypointName);
            assertEquals("目标", config.waypointInitials);
            assertEquals("chunkscanner", config.waypointGroup);
        }
    }

    // ==================== copy() ====================

    @Nested
    @DisplayName("copy()")
    class CopyTests {
        @Test
        @DisplayName("copy() → 新对象与原始对象相等（值相等）")
        void copy_shouldHaveSameValuesAsOriginal() {
            ChunkScannerConfig original = new ChunkScannerConfig();
            original.minRevisitIntervalSec = 120;
            original.maxTasksPerTick = 32;
            original.waypointName = "自定义名称";

            ChunkScannerConfig copy = original.copy();

            assertEquals(original.minRevisitIntervalSec, copy.minRevisitIntervalSec);
            assertEquals(original.maxTasksPerTick, copy.maxTasksPerTick);
            assertEquals(original.initialTasksPerTick, copy.initialTasksPerTick);
            assertEquals(original.targetTickNs, copy.targetTickNs);
            assertEquals(original.flushIntervalTicks, copy.flushIntervalTicks);
            assertEquals(original.workerThreads, copy.workerThreads);
            assertEquals(original.scanRadiusMultiplier, copy.scanRadiusMultiplier, 0.0001);
            assertEquals(original.waypointName, copy.waypointName);
            assertEquals(original.waypointInitials, copy.waypointInitials);
            assertEquals(original.waypointGroup, copy.waypointGroup);
        }

        @Test
        @DisplayName("copy() → 修改副本不影响原对象（独立副本）")
        void copyModification_shouldNotAffectOriginal() {
            ChunkScannerConfig original = new ChunkScannerConfig();
            ChunkScannerConfig copy = original.copy();

            copy.minRevisitIntervalSec = 999;
            copy.maxTasksPerTick = 999;
            copy.waypointName = "被修改的名称";
            copy.waypointInitials = "XX";

            // 原始对象保持不变
            assertEquals(60, original.minRevisitIntervalSec);
            assertEquals(16, original.maxTasksPerTick);
            assertEquals("选中的坐标点", original.waypointName);
            assertEquals("目标", original.waypointInitials);

            // 副本已修改
            assertEquals(999, copy.minRevisitIntervalSec);
            assertEquals(999, copy.maxTasksPerTick);
            assertEquals("被修改的名称", copy.waypointName);
            assertEquals("XX", copy.waypointInitials);
        }

        @Test
        @DisplayName("copy() → 返回不同引用")
        void copy_shouldReturnDifferentReference() {
            ChunkScannerConfig original = new ChunkScannerConfig();
            ChunkScannerConfig copy = original.copy();

            assertNotSame(original, copy);
        }

        @Test
        @DisplayName("copy() → 整数字段使用值类型，互不影响")
        void copyPrimitiveFields_shouldBeIndependent() {
            ChunkScannerConfig original = new ChunkScannerConfig();
            original.targetTickNs = 10_000_000L;
            original.flushIntervalTicks = 200;
            original.scanRadiusMultiplier = 2.5;

            ChunkScannerConfig copy = original.copy();

            copy.targetTickNs = 1L;
            copy.flushIntervalTicks = 1;
            copy.scanRadiusMultiplier = 0.1;

            assertEquals(10_000_000L, original.targetTickNs);
            assertEquals(200, original.flushIntervalTicks);
            assertEquals(2.5, original.scanRadiusMultiplier, 0.0001);
        }
    }
}
