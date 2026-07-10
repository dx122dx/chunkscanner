package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkStatusBreakdown 单元测试。
 */
@DisplayName("ChunkStatusBreakdown")
class ChunkStatusBreakdownTest {

    // ==================== 构造与字段 ====================

    @Test
    @DisplayName("构造后可访问所有字段")
    void constructor_shouldSetAllFields() {
        ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 7);
        assertEquals(1, bd.pending());
        assertEquals(2, bd.scannedNoFind());
        assertEquals(3, bd.scannedFound());
        assertEquals(4, bd.pastRevisitNoFind());
        assertEquals(5, bd.pastRevisitFound());
        assertEquals(6, bd.error());
        assertEquals(7, bd.foundError());
    }

    @Test
    @DisplayName("相同值应相等")
    void equals_sameValues_shouldBeEqual() {
        ChunkScanner.ChunkStatusBreakdown a = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 7);
        ChunkScanner.ChunkStatusBreakdown b = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 7);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("不同值不应相等")
    void equals_differentValues_shouldNotBeEqual() {
        ChunkScanner.ChunkStatusBreakdown a = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 7);
        ChunkScanner.ChunkStatusBreakdown b = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 8);
        assertNotEquals(a, b);
    }

    // ==================== total() ====================

    @Nested
    @DisplayName("total()")
    class Total {

        @Test
        @DisplayName("全零时 total() 返回 0")
        void allZeros_shouldReturnZero() {
            ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(0, 0, 0, 0, 0, 0, 0);
            assertEquals(0, bd.total());
        }

        @Test
        @DisplayName("全部非零 → 正确求和")
        void allNonZero_shouldSumCorrectly() {
            ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(1, 2, 3, 4, 5, 6, 7);
            assertEquals(1 + 2 + 3 + 4 + 5 + 6 + 7, bd.total());
        }

        @Test
        @DisplayName("部分为零，部分非零 → 正确求和")
        void partialZeros_shouldSumCorrectly() {
            ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(0, 10, 0, 20, 0, 5, 0);
            assertEquals(35, bd.total());
        }

        @Test
        @DisplayName("负数也能被求和")
        void negativeValues_shouldBeSummed() {
            ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(-1, -2, -3, -4, -5, -6, -7);
            assertEquals(-28, bd.total());
        }

        @Test
        @DisplayName("单位置有值 → total 等于该值")
        void singleField_shouldReturnThatValue() {
            assertEquals(100, new ChunkScanner.ChunkStatusBreakdown(100, 0, 0, 0, 0, 0, 0).total());
            assertEquals(200, new ChunkScanner.ChunkStatusBreakdown(0, 200, 0, 0, 0, 0, 0).total());
            assertEquals(300, new ChunkScanner.ChunkStatusBreakdown(0, 0, 300, 0, 0, 0, 0).total());
            assertEquals(400, new ChunkScanner.ChunkStatusBreakdown(0, 0, 0, 400, 0, 0, 0).total());
            assertEquals(500, new ChunkScanner.ChunkStatusBreakdown(0, 0, 0, 0, 500, 0, 0).total());
            assertEquals(600, new ChunkScanner.ChunkStatusBreakdown(0, 0, 0, 0, 0, 600, 0).total());
            assertEquals(700, new ChunkScanner.ChunkStatusBreakdown(0, 0, 0, 0, 0, 0, 700).total());
        }

        @Test
        @DisplayName("极大值求和不会溢出（int 范围内）")
        void largeValues_shouldNotOverflow() {
            // 总和需在 int 范围内（Java record 字段类型为 int，求和用 int）
            ChunkScanner.ChunkStatusBreakdown bd = new ChunkScanner.ChunkStatusBreakdown(
                    100_000_000, 100_000_000, 0, 0, 0, 0, 0);
            assertEquals(200_000_000, bd.total());
        }
    }
}
