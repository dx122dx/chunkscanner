package com.billy65536.chunkscanner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskConfig 单元测试。
 *
 * <p><b>持久化契约</b>：{@code parse} 的短键名（revisit / tasks / initTasks / targetNs /
 * flush / threads / radius / wpName / wpInit / wpGroup）与 {@code toJson} 的长字段名
 * 都会被写入数据库文件并在 {@code rebootScanFromDb} 时读回，
 * <b>变更任一键名都会导致旧数据库的任务配置丢失</b>，因此必须被测试锁定。</p>
 */
@DisplayName("TaskConfig")
class TaskConfigTest {

    // ==================== parse ====================

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("null / 空白输入返回 null")
        void blankInput_shouldReturnNull() {
            assertNull(TaskConfig.parse(null));
            assertNull(TaskConfig.parse(""));
            assertNull(TaskConfig.parse("   "));
            assertNull(TaskConfig.parse("\t\n"));
        }

        @Test
        @DisplayName("全部短键名均可解析（持久化契约）")
        void allShortKeys_shouldParse() {
            TaskConfig c = TaskConfig.parse(
                    "revisit=60 tasks=16 initTasks=4 targetNs=500000 flush=20 "
                            + "threads=2 radius=1.5 wpName=shop wpInit=SP wpGroup=grp");

            assertNotNull(c);
            assertEquals(60, c.minRevisitIntervalSec);
            assertEquals(16, c.maxTasksPerTick);
            assertEquals(4, c.initialTasksPerTick);
            assertEquals(500000L, c.targetTickNs);
            assertEquals(20, c.flushIntervalTicks);
            assertEquals(2, c.workerThreads);
            assertEquals(1.5, c.scanRadiusMultiplier);
            assertEquals("shop", c.waypointName);
            assertEquals("SP", c.waypointInitials);
            assertEquals("grp", c.waypointGroup);
        }

        @Test
        @DisplayName("键名大小写不敏感")
        void keys_shouldBeCaseInsensitive() {
            TaskConfig c = TaskConfig.parse("REVISIT=30 WpName=abc TARGETNS=100");

            assertNotNull(c);
            assertEquals(30, c.minRevisitIntervalSec);
            assertEquals("abc", c.waypointName);
            assertEquals(100L, c.targetTickNs);
        }

        @Test
        @DisplayName("未指定的字段保持 null（表示走全局默认值）")
        void unspecifiedFields_shouldStayNull() {
            TaskConfig c = TaskConfig.parse("revisit=60");

            assertNotNull(c);
            assertEquals(60, c.minRevisitIntervalSec);
            assertNull(c.maxTasksPerTick);
            assertNull(c.waypointName);
        }

        @Test
        @DisplayName("多余空白被容忍")
        void extraWhitespace_shouldBeTolerated() {
            TaskConfig c = TaskConfig.parse("  revisit=60    tasks=8  ");

            assertNotNull(c);
            assertEquals(60, c.minRevisitIntervalSec);
            assertEquals(8, c.maxTasksPerTick);
        }

        @Test
        @DisplayName("值中含等号时只按第一个等号切分")
        void valueWithEquals_shouldSplitOnFirstOnly() {
            TaskConfig c = TaskConfig.parse("wpName=a=b");

            assertNotNull(c);
            assertEquals("a=b", c.waypointName);
        }

        @Test
        @DisplayName("非法数值被跳过，不影响其他字段")
        void invalidNumber_shouldBeSkipped() {
            TaskConfig c = TaskConfig.parse("revisit=abc tasks=8");

            assertNotNull(c);
            assertNull(c.minRevisitIntervalSec, "非法数值应被丢弃而非崩溃");
            assertEquals(8, c.maxTasksPerTick);
        }

        @Test
        @DisplayName("未知键被忽略，不影响其他字段")
        void unknownKey_shouldBeIgnored() {
            TaskConfig c = TaskConfig.parse("bogusKey=1 tasks=8");

            assertNotNull(c);
            assertEquals(8, c.maxTasksPerTick);
        }

        @Test
        @DisplayName("无等号的片段被忽略")
        void tokenWithoutEquals_shouldBeIgnored() {
            TaskConfig c = TaskConfig.parse("garbage tasks=8");

            assertNotNull(c);
            assertEquals(8, c.maxTasksPerTick);
        }

        @Test
        @DisplayName("全部字段解析失败时返回 null（等价于无配置）")
        void allFieldsInvalid_shouldReturnNull() {
            assertNull(TaskConfig.parse("bogus=1 alsoBogus=2"));
            assertNull(TaskConfig.parse("revisit=notanumber"));
        }

        @Test
        @DisplayName("负数与零可被解析（校验交由上层）")
        void negativeAndZero_shouldParse() {
            TaskConfig c = TaskConfig.parse("revisit=0 tasks=-1");

            assertNotNull(c);
            assertEquals(0, c.minRevisitIntervalSec);
            assertEquals(-1, c.maxTasksPerTick);
        }
    }

    // ==================== isAllNull ====================

    @Nested
    @DisplayName("isAllNull")
    class AllNull {

        @Test
        @DisplayName("新建配置所有字段为 null")
        void freshConfig_shouldBeAllNull() {
            assertTrue(new TaskConfig().isAllNull());
        }

        @Test
        @DisplayName("任一字段被设置后不再全 null")
        void anyFieldSet_shouldNotBeAllNull() {
            TaskConfig c = new TaskConfig();
            c.waypointGroup = "g";
            assertFalse(c.isAllNull());
        }
    }

    // ==================== JSON 往返 ====================

    @Nested
    @DisplayName("toJson / fromJson")
    class JsonRoundTrip {

        @Test
        @DisplayName("全字段 JSON 往返保真")
        void fullRoundTrip_shouldPreserveAllFields() {
            TaskConfig src = TaskConfig.parse(
                    "revisit=60 tasks=16 initTasks=4 targetNs=500000 flush=20 "
                            + "threads=2 radius=1.5 wpName=shop wpInit=SP wpGroup=grp");
            assertNotNull(src);

            TaskConfig back = TaskConfig.fromJson(src.toJson());

            assertNotNull(back);
            assertEquals(src.minRevisitIntervalSec, back.minRevisitIntervalSec);
            assertEquals(src.maxTasksPerTick, back.maxTasksPerTick);
            assertEquals(src.initialTasksPerTick, back.initialTasksPerTick);
            assertEquals(src.targetTickNs, back.targetTickNs);
            assertEquals(src.flushIntervalTicks, back.flushIntervalTicks);
            assertEquals(src.workerThreads, back.workerThreads);
            assertEquals(src.scanRadiusMultiplier, back.scanRadiusMultiplier);
            assertEquals(src.waypointName, back.waypointName);
            assertEquals(src.waypointInitials, back.waypointInitials);
            assertEquals(src.waypointGroup, back.waypointGroup);
        }

        @Test
        @DisplayName("null 字段不写入 JSON")
        void nullFields_shouldBeOmitted() {
            TaskConfig c = new TaskConfig();
            c.maxTasksPerTick = 8;

            String json = c.toJson();

            assertTrue(json.contains("maxTasksPerTick"));
            assertFalse(json.contains("waypointName"), "null 字段不应出现在 JSON 中");
        }

        @Test
        @DisplayName("JSON 长字段名是持久化契约，不得变更")
        void jsonFieldNames_shouldMatchContract() {
            TaskConfig c = TaskConfig.parse(
                    "revisit=1 tasks=2 initTasks=3 targetNs=4 flush=5 "
                            + "threads=6 radius=7.0 wpName=a wpInit=b wpGroup=c");
            assertNotNull(c);
            String json = c.toJson();

            for (String key : new String[]{
                    "minRevisitIntervalSec", "maxTasksPerTick", "initialTasksPerTick",
                    "targetTickNs", "flushIntervalTicks", "workerThreads",
                    "scanRadiusMultiplier", "waypointName", "waypointInitials", "waypointGroup"}) {
                assertTrue(json.contains("\"" + key + "\""),
                        "JSON 字段名 '" + key + "' 缺失，会导致旧数据库任务配置读不回来");
            }
        }

        @Test
        @DisplayName("空 / null / \"null\" 字符串反序列化为 null")
        void emptyOrNullJson_shouldReturnNull() {
            assertNull(TaskConfig.fromJson(null));
            assertNull(TaskConfig.fromJson(""));
            assertNull(TaskConfig.fromJson("null"));
        }

        @Test
        @DisplayName("空 JSON 对象反序列化为 null")
        void emptyJsonObject_shouldReturnNull() {
            assertNull(TaskConfig.fromJson("{}"));
        }

        @Test
        @DisplayName("非法 JSON 返回 null 而非抛异常")
        void malformedJson_shouldReturnNull() {
            assertNull(TaskConfig.fromJson("{not valid json"));
        }

        @Test
        @DisplayName("JSON 中 null 值字段被跳过")
        void jsonNullValues_shouldBeSkipped() {
            TaskConfig c = TaskConfig.fromJson(
                    "{\"maxTasksPerTick\":8,\"waypointName\":null}");

            assertNotNull(c);
            assertEquals(8, c.maxTasksPerTick);
            assertNull(c.waypointName);
        }

        @Test
        @DisplayName("仅含未知字段的 JSON 返回 null")
        void jsonWithOnlyUnknownFields_shouldReturnNull() {
            assertNull(TaskConfig.fromJson("{\"unknownField\":123}"));
        }
    }

    // ==================== applyTo ====================

    @Nested
    @DisplayName("applyTo")
    class ApplyTo {

        @Test
        @DisplayName("非 null 字段覆盖默认值")
        void nonNullFields_shouldOverrideDefaults() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            TaskConfig task = TaskConfig.parse("tasks=99 radius=3.5 wpName=custom");
            assertNotNull(task);

            ChunkScannerConfig result = task.applyTo(defaults);

            assertEquals(99, result.scanner.maxTasksPerTick);
            assertEquals(3.5, result.scanner.scanRadiusMultiplier);
            assertEquals("custom", result.integration.xaero.name);
        }

        @Test
        @DisplayName("null 字段沿用默认值")
        void nullFields_shouldFallBackToDefaults() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            int defaultThreads = defaults.scanner.workerThreads;

            ChunkScannerConfig result = TaskConfig.parse("tasks=99").applyTo(defaults);

            assertEquals(defaultThreads, result.scanner.workerThreads);
        }

        @Test
        @DisplayName("不污染传入的全局配置（深拷贝）")
        void applyTo_shouldNotMutateDefaults() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            int original = defaults.scanner.maxTasksPerTick;

            TaskConfig.parse("tasks=99").applyTo(defaults);

            assertEquals(original, defaults.scanner.maxTasksPerTick,
                    "applyTo 必须基于深拷贝，否则任务配置会污染全局配置");
        }

        @Test
        @DisplayName("空配置 applyTo 后与默认值一致")
        void emptyTaskConfig_shouldYieldDefaults() {
            ChunkScannerConfig defaults = new ChunkScannerConfig();
            ChunkScannerConfig result = new TaskConfig().applyTo(defaults);

            assertEquals(defaults.scanner.maxTasksPerTick, result.scanner.maxTasksPerTick);
            assertEquals(defaults.integration.xaero.name, result.integration.xaero.name);
        }
    }

    // ==================== copy / toDisplayString ====================

    @Nested
    @DisplayName("copy / toDisplayString")
    class CopyAndDisplay {

        @Test
        @DisplayName("copy 产出独立副本")
        void copy_shouldBeIndependent() {
            TaskConfig src = TaskConfig.parse("tasks=8 wpName=a");
            assertNotNull(src);

            TaskConfig dup = src.copy();
            dup.maxTasksPerTick = 99;

            assertEquals(8, src.maxTasksPerTick, "修改副本不应影响原对象");
            assertEquals(99, dup.maxTasksPerTick);
            assertEquals("a", dup.waypointName);
        }

        @Test
        @DisplayName("copy 保留 null 字段")
        void copy_shouldPreserveNulls() {
            TaskConfig dup = TaskConfig.parse("tasks=8").copy();
            assertNull(dup.waypointName);
        }

        @Test
        @DisplayName("空配置的 toDisplayString 为空串")
        void emptyConfig_displayString_shouldBeEmpty() {
            assertEquals("", new TaskConfig().toDisplayString());
        }

        @Test
        @DisplayName("toDisplayString 使用短键名且无首尾空白")
        void displayString_shouldUseShortKeys() {
            String s = TaskConfig.parse("revisit=60 tasks=8").toDisplayString();

            assertTrue(s.contains("revisit=60"));
            assertTrue(s.contains("tasks=8"));
            assertEquals(s.trim(), s, "不应有首尾空白");
        }

        @Test
        @DisplayName("toDisplayString 输出可被 parse 再次读回")
        void displayString_shouldBeReparsable() {
            TaskConfig src = TaskConfig.parse("revisit=60 tasks=8 radius=1.5");
            assertNotNull(src);

            TaskConfig back = TaskConfig.parse(src.toDisplayString());

            assertNotNull(back);
            assertEquals(src.minRevisitIntervalSec, back.minRevisitIntervalSec);
            assertEquals(src.maxTasksPerTick, back.maxTasksPerTick);
            assertEquals(src.scanRadiusMultiplier, back.scanRadiusMultiplier);
        }
    }
}
