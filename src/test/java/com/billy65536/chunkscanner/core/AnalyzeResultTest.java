package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnalyzeResult 单元测试。
 * 覆盖所有工厂方法、查询方法、状态常量组合和 toString。
 */
@DisplayName("AnalyzeResult")
class AnalyzeResultTest {

    // ==================== 状态常量 ====================

    @Test
    @DisplayName("状态常量应为唯一 bitflag")
    void statusConstants_shouldBeUniqueBitflags() {
        assertEquals(0x01, AnalyzeResult.SUCCESS);
        assertEquals(0x02, AnalyzeResult.FOUND);
        assertEquals(0x04, AnalyzeResult.ERROR);
        assertEquals(0x08, AnalyzeResult.SKIPPED);

        // 验证互斥性
        assertEquals(0, AnalyzeResult.SUCCESS & AnalyzeResult.FOUND);
        assertEquals(0, AnalyzeResult.SUCCESS & AnalyzeResult.ERROR);
        assertEquals(0, AnalyzeResult.SUCCESS & AnalyzeResult.SKIPPED);
        assertEquals(0, AnalyzeResult.FOUND & AnalyzeResult.ERROR);
        assertEquals(0, AnalyzeResult.FOUND & AnalyzeResult.SKIPPED);
        assertEquals(0, AnalyzeResult.ERROR & AnalyzeResult.SKIPPED);
    }

    // ==================== found() ====================

    @Nested
    @DisplayName("found() 工厂方法")
    class FoundTests {
        @Test
        @DisplayName("found() 无参数 → status 为 SUCCESS|FOUND，info 为 null")
        void foundWithoutInfo_shouldHaveSuccessAndFoundStatus() {
            AnalyzeResult result = AnalyzeResult.found();
            assertEquals(AnalyzeResult.SUCCESS | AnalyzeResult.FOUND, result.getStatus());
            assertNull(result.getInfo());
            assertTrue(result.isSuccess());
            assertTrue(result.isFound());
            assertFalse(result.isError());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("found(info) → status 为 SUCCESS|FOUND，info 正确传递")
        void foundWithInfo_shouldStoreInfo() {
            String info = "找到 3 个告示牌";
            AnalyzeResult result = AnalyzeResult.found(info);
            assertEquals(AnalyzeResult.SUCCESS | AnalyzeResult.FOUND, result.getStatus());
            assertEquals(info, result.getInfo());
            assertTrue(result.isSuccess());
            assertTrue(result.isFound());
            assertFalse(result.isError());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("found(null) → info 为 null")
        void foundWithNullInfo_shouldHaveNullInfo() {
            AnalyzeResult result = AnalyzeResult.found(null);
            assertNull(result.getInfo());
        }
    }

    // ==================== skipped() ====================

    @Nested
    @DisplayName("skipped() 工厂方法")
    class SkippedTests {
        @Test
        @DisplayName("skipped() 无参数 → status 为 SUCCESS|SKIPPED，info 为 null")
        void skippedWithoutInfo_shouldHaveSuccessAndSkippedStatus() {
            AnalyzeResult result = AnalyzeResult.skipped();
            assertEquals(AnalyzeResult.SUCCESS | AnalyzeResult.SKIPPED, result.getStatus());
            assertNull(result.getInfo());
            assertTrue(result.isSuccess());
            assertFalse(result.isFound());
            assertFalse(result.isError());
            assertTrue(result.isSkipped());
        }

        @Test
        @DisplayName("skipped(info) → info 正确传递")
        void skippedWithInfo_shouldStoreInfo() {
            String info = "该区块无告示牌";
            AnalyzeResult result = AnalyzeResult.skipped(info);
            assertEquals(AnalyzeResult.SUCCESS | AnalyzeResult.SKIPPED, result.getStatus());
            assertEquals(info, result.getInfo());
        }

        @Test
        @DisplayName("skipped(null) → info 为 null")
        void skippedWithNullInfo_shouldHaveNullInfo() {
            AnalyzeResult result = AnalyzeResult.skipped(null);
            assertNull(result.getInfo());
        }
    }

    // ==================== error() ====================

    @Nested
    @DisplayName("error() 工厂方法")
    class ErrorTests {
        @Test
        @DisplayName("error(info) → status 仅为 ERROR，不含 SUCCESS")
        void error_shouldOnlyHaveErrorStatus() {
            String info = "区块加载失败";
            AnalyzeResult result = AnalyzeResult.error(info);
            assertEquals(AnalyzeResult.ERROR, result.getStatus());
            assertEquals(info, result.getInfo());
            assertFalse(result.isSuccess());
            assertFalse(result.isFound());
            assertTrue(result.isError());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("error(null) → info 为 null")
        void errorWithNullInfo_shouldHaveNullInfo() {
            AnalyzeResult result = AnalyzeResult.error(null);
            assertNull(result.getInfo());
        }

        @Test
        @DisplayName("error(\"\") → info 为空字符串")
        void errorWithEmptyInfo_shouldHaveEmptyInfo() {
            AnalyzeResult result = AnalyzeResult.error("");
            assertEquals("", result.getInfo());
        }
    }

    // ==================== partial() ====================

    @Nested
    @DisplayName("partial() 工厂方法")
    class PartialTests {
        @Test
        @DisplayName("partial(info) → status 为 SUCCESS|FOUND|ERROR")
        void partial_shouldHaveSuccessFoundAndError() {
            String info = "部分区块读取失败";
            AnalyzeResult result = AnalyzeResult.partial(info);
            assertEquals(AnalyzeResult.SUCCESS | AnalyzeResult.FOUND | AnalyzeResult.ERROR, result.getStatus());
            assertEquals(info, result.getInfo());
            assertTrue(result.isSuccess());
            assertTrue(result.isFound());
            assertTrue(result.isError());
            assertFalse(result.isSkipped());
        }

        @Test
        @DisplayName("partial(null) → info 为 null")
        void partialWithNullInfo_shouldHaveNullInfo() {
            AnalyzeResult result = AnalyzeResult.partial(null);
            assertNull(result.getInfo());
        }
    }

    // ==================== toString() ====================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {
        @Test
        @DisplayName("含 info → 包含 status 十六进制和 info")
        void toString_withInfo_shouldContainStatusAndInfo() {
            AnalyzeResult result = AnalyzeResult.found("test info");
            String str = result.toString();
            assertTrue(str.contains("AnalyzeResult"));
            assertTrue(str.contains("status=0x3")); // SUCCESS|FOUND = 0x03
            assertTrue(str.contains("info='test info'"));
        }

        @Test
        @DisplayName("不含 info → 不含 info 字段")
        void toString_withoutInfo_shouldNotContainInfo() {
            AnalyzeResult result = AnalyzeResult.found();
            String str = result.toString();
            assertTrue(str.contains("AnalyzeResult"));
            assertTrue(str.contains("status=0x3"));
            assertFalse(str.contains("info="));
        }

        @Test
        @DisplayName("skipped → status=0x9")
        void toString_skipped_shouldShowStatus9() {
            AnalyzeResult result = AnalyzeResult.skipped();
            assertTrue(result.toString().contains("status=0x9"));
        }

        @Test
        @DisplayName("error → status=0x4")
        void toString_error_shouldShowStatus4() {
            AnalyzeResult result = AnalyzeResult.error("err");
            assertTrue(result.toString().contains("status=0x4"));
        }
    }

    // ==================== getStatus / getInfo ====================

    @Test
    @DisplayName("getStatus 和 getInfo 应返回工厂方法设定的值")
    void getStatusAndGetInfo_shouldReturnFactoryValues() {
        AnalyzeResult result = AnalyzeResult.found("物品展示框 x5");
        assertEquals(0x03, result.getStatus());
        assertEquals("物品展示框 x5", result.getInfo());
    }
}
