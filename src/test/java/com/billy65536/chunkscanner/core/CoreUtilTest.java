package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CoreUtil 单元测试。
 * 主要测试 packChunkPos 的位打包/解包正确性。
 */
@DisplayName("CoreUtil")
class CoreUtilTest {

    // ==================== packChunkPos() ====================

    @Test
    @DisplayName("原点 (0, 0) → 0L")
    void packOrigin_shouldReturnZero() {
        assertEquals(0L, CoreUtil.packChunkPos(0, 0));
    }

    @Test
    @DisplayName("正坐标 (1, 1) → 高位=1 低位=1")
    void packPositiveCoords_shouldPlaceCorrectly() {
        long packed = CoreUtil.packChunkPos(1, 1);
        assertEquals(1, (int) (packed >> 32));       // cx 在高 32 位
        assertEquals(1, (int) (packed & 0xFFFFFFFFL)); // cz 在低 32 位
    }

    @Test
    @DisplayName("大正坐标 → 正确打包")
    void packLargePositiveCoords() {
        int cx = 100000;
        int cz = 200000;
        long packed = CoreUtil.packChunkPos(cx, cz);
        assertEquals(cx, (int) (packed >> 32));
        assertEquals(cz, (int) (packed & 0xFFFFFFFFL));
    }

    @Test
    @DisplayName("负坐标 → 正确处理符号扩展")
    void packNegativeCoords_shouldHandleSignExtension() {
        int cx = -1;
        int cz = -2;
        long packed = CoreUtil.packChunkPos(cx, cz);
        assertEquals(-1, (int) (packed >> 32));
        // cz 在低 32 位，需要 int 截断
        assertEquals(-2, (int) (packed & 0xFFFFFFFFL));
    }

    @Test
    @DisplayName("混合符号坐标 → 正确处理")
    void packMixedSignCoords() {
        long packed = CoreUtil.packChunkPos(-50, 100);
        assertEquals(-50, (int) (packed >> 32));
        assertEquals(100, (int) (packed & 0xFFFFFFFFL));
    }

    @Test
    @DisplayName("最大值 Integer.MAX_VALUE → 正确")
    void packMaxInt() {
        int cx = Integer.MAX_VALUE;
        int cz = Integer.MAX_VALUE;
        long packed = CoreUtil.packChunkPos(cx, cz);
        assertEquals(cx, (int) (packed >> 32));
        assertEquals(cz, (int) (packed & 0xFFFFFFFFL));
    }

    @Test
    @DisplayName("最小值 Integer.MIN_VALUE → 正确")
    void packMinInt() {
        int cx = Integer.MIN_VALUE;
        int cz = Integer.MIN_VALUE;
        long packed = CoreUtil.packChunkPos(cx, cz);
        assertEquals(cx, (int) (packed >> 32));
        assertEquals(cz, (int) (packed & 0xFFFFFFFFL));
    }

    @Test
    @DisplayName("不同坐标打包结果不应相同（唯一性）")
    void packDifferentCoords_shouldProduceUniqueResults() {
        long p1 = CoreUtil.packChunkPos(1, 2);
        long p2 = CoreUtil.packChunkPos(2, 1);
        long p3 = CoreUtil.packChunkPos(1, 3);
        long p4 = CoreUtil.packChunkPos(3, 1);

        assertNotEquals(p1, p2);
        assertNotEquals(p1, p3);
        assertNotEquals(p1, p4);
        assertNotEquals(p2, p3);
        assertNotEquals(p2, p4);
        assertNotEquals(p3, p4);
    }

    @Test
    @DisplayName("对称性：多次打包/解包保持一致")
    void packUnpackRoundTrip_shouldBeConsistent() {
        int[][] testCoords = {
                {0, 0}, {1, 1}, {-1, -1}, {100, -50},
                {-300, 200}, {Integer.MAX_VALUE, 0}, {0, Integer.MIN_VALUE}
        };

        for (int[] coord : testCoords) {
            int cx = coord[0];
            int cz = coord[1];
            long packed = CoreUtil.packChunkPos(cx, cz);
            int unpackedCx = (int) (packed >> 32);
            int unpackedCz = (int) (packed & 0xFFFFFFFFL);
            assertEquals(cx, unpackedCx, "cx mismatch for (" + cx + "," + cz + ")");
            assertEquals(cz, unpackedCz, "cz mismatch for (" + cx + "," + cz + ")");
        }
    }
}
