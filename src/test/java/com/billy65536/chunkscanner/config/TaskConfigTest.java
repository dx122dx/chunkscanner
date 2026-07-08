package com.billy65536.chunkscanner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskConfig 单元测试。
 * 覆盖 parse、isAllNull、applyTo、toDisplayString、toJson、fromJson、copy。
 */
@DisplayName("TaskConfig")
class TaskConfigTest {

    // ==================== 构造 ====================

    @Test
    @DisplayName("new TaskConfig() → 所有字段为 null")
    void newTaskConfig_shouldHaveAllFieldsNull() {
        TaskConfig config = new TaskConfig();
        assertNull(config.minRevisitIntervalSec);
        assertNull(config.maxTasksPerTick);
        assertNull(config.initialTasksPerTick);
        assertNull(config.targetTickNs);
        assertNull(config.flushIntervalTicks);
        assertNull(config.workerThreads);
        assertNull(config.scanRadiusMultiplier);
        assertNull(config.waypointName);
        assertNull(config.waypointInitials);
        assertNull(config.waypointGroup);
    }

    // ==================== isAllNull() ====================

    @Nested
    @DisplayName("isAllNull()")
    class IsAllNullTests {
        @Test
        @DisplayName("全 null → true")
        void allNull_shouldReturnTrue() {
            assertTrue(new TaskConfig().isAllNull());
        }

        @Test
        @DisplayName("任一字段非 null → false")
        void anyNonNull_shouldReturnFalse() {
            TaskConfig config = new TaskConfig();
            config.minRevisitIntervalSec = 60;
            assertFalse(config.isAllNull());
        }

        @Test
        @DisplayName("所有字段逐个设为非 null → 均返回 false")
        void eachFieldNonNull_shouldReturnFalse() {
            TaskConfig config;

            config = new TaskConfig(); config.maxTasksPerTick = 16;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.initialTasksPerTick = 4;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.targetTickNs = 1000L;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.flushIntervalTicks = 50;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.workerThreads = 4;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.scanRadiusMultiplier = 2.0;
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.waypointName = "test";
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.waypointInitials = "T";
            assertFalse(config.isAllNull());

            config = new TaskConfig(); config.waypointGroup = "g";
            assertFalse(config.isAllNull());
        }
    }

    // ==================== parse() ====================

    @Nested
    @DisplayName("parse(String)")
    class ParseTests {
        @Test
        @DisplayName("null → 返回 null")
        void parseNull_shouldReturnNull() {
            assertNull(TaskConfig.parse(null));
        }

        @Test
        @DisplayName("空字符串 → 返回 null")
        void parseEmpty_shouldReturnNull() {
            assertNull(TaskConfig.parse(""));
        }

        @Test
        @DisplayName("空白字符串 → 返回 null")
        void parseBlank_shouldReturnNull() {
            assertNull(TaskConfig.parse("   "));
        }

        @Test
        @DisplayName("值为 0 并非 null → 返回非 null 的 TaskConfig")
        void parseWithZero_shouldReturnNonNullConfig() {
            // revisit=0 将 minRevisitIntervalSec 设为 0，0 != null，所以返回 TaskConfig
            assertNotNull(TaskConfig.parse("revisit=0"));
        }

        @Test
        @DisplayName("单个 revisit 值 → 正确解析")
        void parseRevisit_shouldSetValue() {
            TaskConfig config = TaskConfig.parse("revisit=30");
            assertNotNull(config);
            assertEquals(30, config.minRevisitIntervalSec);
            assertNull(config.maxTasksPerTick);
        }

        @Test
        @DisplayName("单个 tasks 值 → 正确解析")
        void parseTasks_shouldSetValue() {
            TaskConfig config = TaskConfig.parse("tasks=32");
            assertNotNull(config);
            assertEquals(32, config.maxTasksPerTick);
        }

        @Test
        @DisplayName("所有支持的键 → 全部正确解析")
        void parseAllKeys_shouldSetAllValues() {
            String input = "revisit=120 tasks=8 initTasks=4 targetNs=10000000 flush=50 threads=4 radius=2.0 wpName=商店 wpInit=S wpGroup=test";
            TaskConfig config = TaskConfig.parse(input);
            assertNotNull(config);

            assertEquals(120, config.minRevisitIntervalSec);
            assertEquals(8, config.maxTasksPerTick);
            assertEquals(4, config.initialTasksPerTick);
            assertEquals(10000000L, config.targetTickNs);
            assertEquals(50, config.flushIntervalTicks);
            assertEquals(4, config.workerThreads);
            assertEquals(2.0, config.scanRadiusMultiplier, 0.0001);
            assertEquals("商店", config.waypointName);
            assertEquals("S", config.waypointInitials);
            assertEquals("test", config.waypointGroup);
        }

        @Test
        @DisplayName("键名大小写不敏感")
        void parseCaseInsensitive_shouldWork() {
            TaskConfig config = TaskConfig.parse("REVISIT=60 TASKS=16");
            assertNotNull(config);
            assertEquals(60, config.minRevisitIntervalSec);
            assertEquals(16, config.maxTasksPerTick);
        }

        @Test
        @DisplayName("键值对含多余空格 → 仍正确解析")
        void parseWithExtraSpaces_shouldWork() {
            TaskConfig config = TaskConfig.parse("  revisit=30   tasks=8  ");
            assertNotNull(config);
            assertEquals(30, config.minRevisitIntervalSec);
            assertEquals(8, config.maxTasksPerTick);
        }

        @Test
        @DisplayName("WP 字段含中文 → 正确解析")
        void parseWpFieldsWithChinese_shouldWork() {
            TaskConfig config = TaskConfig.parse("wpName=我的商店 wpInit=商 wpGroup=自定义组");
            assertNotNull(config);
            assertEquals("我的商店", config.waypointName);
            assertEquals("商", config.waypointInitials);
            assertEquals("自定义组", config.waypointGroup);
        }

        @Test
        @DisplayName("targetNs 长整型边界值 → 正确")
        void parseTargetNsLarge_shouldWork() {
            TaskConfig config = TaskConfig.parse("targetNs=50000000");
            assertNotNull(config);
            assertEquals(50_000_000L, config.targetTickNs);
        }
    }

    // ==================== applyTo() ====================

    @Nested
    @DisplayName("applyTo(ChunkScannerConfig)")
    class ApplyToTests {
        @Test
        @DisplayName("全 null TaskConfig → 返回 defaults 的副本")
        void applyAllNull_shouldReturnDefaultsCopy() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            defaults.minRevisitIntervalSec = 99;

            TaskConfig taskConfig = new TaskConfig();
            ChunkScannerConfig result = taskConfig.applyTo(defaults);

            assertEquals(99, result.minRevisitIntervalSec);
            assertEquals(defaults.maxTasksPerTick, result.maxTasksPerTick);
            assertNotSame(defaults, result);
        }

        @Test
        @DisplayName("部分字段非 null → 覆盖对应字段")
        void applyPartialOverride_shouldOverrideNonNullFields() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();

            TaskConfig taskConfig = new TaskConfig();
            taskConfig.minRevisitIntervalSec = 30;
            taskConfig.workerThreads = 8;
            taskConfig.waypointName = "自定义";

            ChunkScannerConfig result = taskConfig.applyTo(defaults);

            // 覆盖的字段
            assertEquals(30, result.minRevisitIntervalSec);
            assertEquals(8, result.workerThreads);
            assertEquals("自定义", result.waypointName);

            // 未覆盖的字段保持默认值
            assertEquals(defaults.maxTasksPerTick, result.maxTasksPerTick);
            assertEquals(defaults.initialTasksPerTick, result.initialTasksPerTick);
            assertEquals(defaults.targetTickNs, result.targetTickNs);
            assertEquals(defaults.flushIntervalTicks, result.flushIntervalTicks);
            assertEquals(defaults.scanRadiusMultiplier, result.scanRadiusMultiplier, 0.0001);
            assertEquals(defaults.waypointInitials, result.waypointInitials);
            assertEquals(defaults.waypointGroup, result.waypointGroup);
        }

        @Test
        @DisplayName("全字段非 null → 全部覆盖")
        void applyAllOverride_shouldOverrideAllFields() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();

            TaskConfig taskConfig = new TaskConfig();
            taskConfig.minRevisitIntervalSec = 10;
            taskConfig.maxTasksPerTick = 20;
            taskConfig.initialTasksPerTick = 5;
            taskConfig.targetTickNs = 1_000_000L;
            taskConfig.flushIntervalTicks = 25;
            taskConfig.workerThreads = 6;
            taskConfig.scanRadiusMultiplier = 3.0;
            taskConfig.waypointName = "A";
            taskConfig.waypointInitials = "B";
            taskConfig.waypointGroup = "C";

            ChunkScannerConfig result = taskConfig.applyTo(defaults);

            assertEquals(10, result.minRevisitIntervalSec);
            assertEquals(20, result.maxTasksPerTick);
            assertEquals(5, result.initialTasksPerTick);
            assertEquals(1_000_000L, result.targetTickNs);
            assertEquals(25, result.flushIntervalTicks);
            assertEquals(6, result.workerThreads);
            assertEquals(3.0, result.scanRadiusMultiplier, 0.0001);
            assertEquals("A", result.waypointName);
            assertEquals("B", result.waypointInitials);
            assertEquals("C", result.waypointGroup);
        }

        @Test
        @DisplayName("applyTo 不修改 defaults 原对象")
        void applyTo_shouldNotModifyDefaults() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            int originalRevisit = defaults.minRevisitIntervalSec;

            TaskConfig taskConfig = new TaskConfig();
            taskConfig.minRevisitIntervalSec = 999;

            taskConfig.applyTo(defaults);

            // defaults 原对象未被修改
            assertEquals(originalRevisit, defaults.minRevisitIntervalSec);
        }
    }

    // ==================== toDisplayString() ====================

    @Nested
    @DisplayName("toDisplayString()")
    class ToDisplayStringTests {
        @Test
        @DisplayName("全 null → 空字符串")
        void allNull_shouldReturnEmptyString() {
            assertEquals("", new TaskConfig().toDisplayString());
        }

        @Test
        @DisplayName("单个字段非 null → 正确格式")
        void singleField_shouldContainKeyValue() {
            TaskConfig config = new TaskConfig();
            config.minRevisitIntervalSec = 60;
            assertEquals("revisit=60", config.toDisplayString());
        }

        @Test
        @DisplayName("多字段 → 空格分隔")
        void multipleFields_shouldBeSpaceSeparated() {
            TaskConfig config = new TaskConfig();
            config.minRevisitIntervalSec = 30;
            config.maxTasksPerTick = 8;
            config.waypointName = "商店";

            String display = config.toDisplayString();
            assertTrue(display.contains("revisit=30"));
            assertTrue(display.contains("tasks=8"));
            assertTrue(display.contains("wpName=商店"));
            // 字段间有空格
            assertTrue(display.contains(" "));
        }

        @Test
        @DisplayName("radius 浮点数 → 正确显示")
        void doubleField_shouldDisplayCorrectly() {
            TaskConfig config = new TaskConfig();
            config.scanRadiusMultiplier = 1.5;
            assertEquals("radius=1.5", config.toDisplayString());
        }

        @Test
        @DisplayName("targetNs 长整型 → 正确显示")
        void longField_shouldDisplayCorrectly() {
            TaskConfig config = new TaskConfig();
            config.targetTickNs = 5000000L;
            assertEquals("targetNs=5000000", config.toDisplayString());
        }
    }

    // ==================== toJson / fromJson round-trip ====================

    @Nested
    @DisplayName("toJson() / fromJson()")
    class JsonTests {
        @Test
        @DisplayName("全 null → toJson 返回 {}")
        void toJsonAllNull_shouldReturnEmptyBraces() {
            assertEquals("{}", new TaskConfig().toJson());
        }

        @Test
        @DisplayName("部分字段非 null → toJson 包含对应键")
        void toJsonPartial_shouldContainCorrectKeys() {
            TaskConfig config = new TaskConfig();
            config.minRevisitIntervalSec = 45;
            config.waypointName = "测试";

            String json = config.toJson();
            assertTrue(json.contains("\"minRevisitIntervalSec\":45"));
            assertTrue(json.contains("\"waypointName\":\"测试\""));
        }

        @Test
        @DisplayName("fromJson(null) → null")
        void fromJsonNull_shouldReturnNull() {
            assertNull(TaskConfig.fromJson(null));
        }

        @Test
        @DisplayName("fromJson(\"\") → null")
        void fromJsonEmpty_shouldReturnNull() {
            assertNull(TaskConfig.fromJson(""));
        }

        @Test
        @DisplayName("fromJson(\"null\") → null")
        void fromJsonNullString_shouldReturnNull() {
            assertNull(TaskConfig.fromJson("null"));
        }

        @Test
        @DisplayName("fromJson(\"{}\") → null")
        void fromJsonEmptyObject_shouldReturnNull() {
            assertNull(TaskConfig.fromJson("{}"));
        }

        @Test
        @DisplayName("toJson → fromJson 往返一致")
        void roundTrip_shouldPreserveValues() {
            TaskConfig original = new TaskConfig();
            original.minRevisitIntervalSec = 90;
            original.maxTasksPerTick = 24;
            original.initialTasksPerTick = 8;
            original.targetTickNs = 10_000_000L;
            original.flushIntervalTicks = 150;
            original.workerThreads = 3;
            original.scanRadiusMultiplier = 1.8;
            original.waypointName = "往返测试";
            original.waypointInitials = "往";
            original.waypointGroup = "roundtrip";

            String json = original.toJson();
            TaskConfig restored = TaskConfig.fromJson(json);

            assertNotNull(restored);
            assertEquals(original.minRevisitIntervalSec, restored.minRevisitIntervalSec);
            assertEquals(original.maxTasksPerTick, restored.maxTasksPerTick);
            assertEquals(original.initialTasksPerTick, restored.initialTasksPerTick);
            assertEquals(original.targetTickNs, restored.targetTickNs);
            assertEquals(original.flushIntervalTicks, restored.flushIntervalTicks);
            assertEquals(original.workerThreads, restored.workerThreads);
            assertEquals(original.scanRadiusMultiplier, restored.scanRadiusMultiplier, 0.0001);
            assertEquals(original.waypointName, restored.waypointName);
            assertEquals(original.waypointInitials, restored.waypointInitials);
            assertEquals(original.waypointGroup, restored.waypointGroup);
        }

        @Test
        @DisplayName("JSON 中包含 null 字段 → 跳过")
        void fromJsonWithNullFields_shouldSkip() {
            String json = "{\"minRevisitIntervalSec\":null,\"maxTasksPerTick\":16}";
            TaskConfig config = TaskConfig.fromJson(json);
            assertNotNull(config);
            assertNull(config.minRevisitIntervalSec);
            assertEquals(16, config.maxTasksPerTick);
        }
    }

    // ==================== copy() ====================

    @Nested
    @DisplayName("copy()")
    class CopyTests {
        @Test
        @DisplayName("全 null copy → 值相等")
        void copyAllNull_shouldBeEqual() {
            TaskConfig original = new TaskConfig();
            TaskConfig copy = original.copy();

            assertTrue(copy.isAllNull());
            assertNotSame(original, copy);
        }

        @Test
        @DisplayName("部分字段 copy → 值相等，引用不同")
        void copyPartial_shouldPreserveValues() {
            TaskConfig original = new TaskConfig();
            original.minRevisitIntervalSec = 45;
            original.waypointName = "原件";

            TaskConfig copy = original.copy();

            assertEquals(45, copy.minRevisitIntervalSec);
            assertEquals("原件", copy.waypointName);
            assertNull(copy.maxTasksPerTick);
            assertNotSame(original, copy);
        }

        @Test
        @DisplayName("修改副本不影响原对象")
        void copyModification_shouldNotAffectOriginal() {
            TaskConfig original = new TaskConfig();
            original.minRevisitIntervalSec = 30;

            TaskConfig copy = original.copy();
            copy.minRevisitIntervalSec = 999;
            copy.waypointName = "修改过的";

            assertEquals(30, original.minRevisitIntervalSec);
            assertNull(original.waypointName);
        }
    }
}
