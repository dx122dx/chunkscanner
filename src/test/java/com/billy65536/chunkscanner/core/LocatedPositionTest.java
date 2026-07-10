package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocatedPosition 单元测试。
 */
@DisplayName("LocatedPosition")
class LocatedPositionTest {

    // ==================== record 基础属性 ====================

    @Test
    @DisplayName("构造后可访问各字段")
    void constructor_shouldSetAllFields() {
        LocatedPosition pos = new LocatedPosition("minecraft:overworld", 100, 64, -200);
        assertEquals("minecraft:overworld", pos.dimensionId());
        assertEquals(100, pos.x());
        assertEquals(64, pos.y());
        assertEquals(-200, pos.z());
    }

    @Test
    @DisplayName("相同值应相等")
    void equals_sameValues_shouldBeEqual() {
        LocatedPosition a = new LocatedPosition("minecraft:overworld", 10, 20, 30);
        LocatedPosition b = new LocatedPosition("minecraft:overworld", 10, 20, 30);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("不同 dimensionId 应不相等")
    void equals_differentDimension_shouldNotBeEqual() {
        LocatedPosition overworld = new LocatedPosition("minecraft:overworld", 10, 20, 30);
        LocatedPosition nether = new LocatedPosition("minecraft:the_nether", 10, 20, 30);
        assertNotEquals(overworld, nether);
    }

    @Test
    @DisplayName("不同坐标应不相等")
    void equals_differentCoordinates_shouldNotBeEqual() {
        LocatedPosition a = new LocatedPosition("minecraft:overworld", 1, 2, 3);
        LocatedPosition b = new LocatedPosition("minecraft:overworld", 3, 2, 1);
        assertNotEquals(a, b);
    }

    // ==================== shortenDimension() ====================

    @Nested
    @DisplayName("shortenDimension")
    class ShortenDimension {

        @Test
        @DisplayName("去掉 minecraft: 前缀")
        void minecraftPrefix_shouldBeStripped() {
            assertEquals("overworld", LocatedPosition.shortenDimension("minecraft:overworld"));
            assertEquals("the_nether", LocatedPosition.shortenDimension("minecraft:the_nether"));
            assertEquals("the_end", LocatedPosition.shortenDimension("minecraft:the_end"));
        }

        @Test
        @DisplayName("null 返回 \"?\"")
        void nullInput_shouldReturnQuestionMark() {
            assertEquals("?", LocatedPosition.shortenDimension(null));
        }

        @Test
        @DisplayName("无前缀保持不变")
        void noPrefix_shouldRemainUnchanged() {
            assertEquals("overworld", LocatedPosition.shortenDimension("overworld"));
            assertEquals("custom:dim", LocatedPosition.shortenDimension("custom:dim"));
        }

        @Test
        @DisplayName("空字符串保持不变")
        void emptyString_shouldRemainEmpty() {
            assertEquals("", LocatedPosition.shortenDimension(""));
        }

        @Test
        @DisplayName("仅 minecraft: 的字符串返回空")
        void onlyMinecraftColon_shouldReturnEmpty() {
            assertEquals("", LocatedPosition.shortenDimension("minecraft:"));
        }
    }

    // ==================== toString() ====================

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("正坐标格式化正确")
        void positiveCoordinates_shouldFormatCorrectly() {
            LocatedPosition pos = new LocatedPosition("minecraft:overworld", 123, 64, 456);
            assertEquals("overworld (123, 64, 456)", pos.toString());
        }

        @Test
        @DisplayName("负坐标格式化正确")
        void negativeCoordinates_shouldFormatCorrectly() {
            LocatedPosition pos = new LocatedPosition("minecraft:the_nether", -10, 32, -500);
            assertEquals("the_nether (-10, 32, -500)", pos.toString());
        }

        @Test
        @DisplayName("混合正负坐标")
        void mixedSignCoordinates_shouldFormatCorrectly() {
            LocatedPosition pos = new LocatedPosition("minecraft:overworld", 0, -64, 100);
            assertEquals("overworld (0, -64, 100)", pos.toString());
        }

        @Test
        @DisplayName("自定义维度无前缀保持不变")
        void customDimensionWithoutMinecraftPrefix() {
            LocatedPosition pos = new LocatedPosition("somemod:custom_dim", 5, 10, 15);
            assertEquals("somemod:custom_dim (5, 10, 15)", pos.toString());
        }

        @Test
        @DisplayName("null dimensionId 显示为 \"?\"")
        void nullDimensionId_shouldShowQuestionMark() {
            LocatedPosition pos = new LocatedPosition(null, 1, 2, 3);
            assertEquals("? (1, 2, 3)", pos.toString());
        }

        @Test
        @DisplayName("极大坐标值格式化")
        void extremeCoordinates_shouldFormatCorrectly() {
            LocatedPosition pos = new LocatedPosition("minecraft:overworld",
                    Integer.MAX_VALUE, Integer.MIN_VALUE, 0);
            String expected = "overworld (" + Integer.MAX_VALUE + ", " + Integer.MIN_VALUE + ", 0)";
            assertEquals(expected, pos.toString());
        }
    }
}
