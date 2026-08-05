package com.billy65536.chunkscanner.core.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NavigationQueue 单元测试（纯逻辑，零 Minecraft 运行时依赖）。
 */
@DisplayName("NavigationQueue")
class NavigationQueueTest {

    private static NavigationEntry entry(int x, int y, int z) {
        return new NavigationEntry("minecraft:overworld", x, y, z);
    }

    /** 恒定结果的到达条件（不触碰 MinecraftClient，可传 null）。 */
    private static NavigationCondition always(boolean satisfied) {
        return client -> satisfied;
    }

    // ==================== 入队与查看 ====================

    @Nested
    @DisplayName("enqueue / peek")
    class EnqueuePeek {

        @Test
        @DisplayName("新建队列为空")
        void newQueue_shouldBeEmpty() {
            NavigationQueue q = new NavigationQueue();
            assertTrue(q.isEmpty());
            assertEquals(0, q.size());
            assertNull(q.peek());
            assertNull(q.peekCondition());
        }

        @Test
        @DisplayName("入队后 size 递增且 peek 返回队首")
        void enqueue_shouldAppendInOrder() {
            NavigationQueue q = new NavigationQueue();
            NavigationEntry first = entry(1, 64, 1);
            NavigationEntry second = entry(2, 64, 2);

            q.enqueue(first, always(false));
            q.enqueue(second, always(false));

            assertEquals(2, q.size());
            assertFalse(q.isEmpty());
            assertEquals(first, q.peek(), "peek 应返回最先入队的条目（FIFO）");
        }

        @Test
        @DisplayName("peekCondition 返回队首绑定的条件")
        void peekCondition_shouldReturnHeadCondition() {
            NavigationQueue q = new NavigationQueue();
            NavigationCondition cond = always(true);
            q.enqueue(entry(5, 64, 5), cond);

            assertSame(cond, q.peekCondition());
        }
    }

    // ==================== tick 推进 ====================

    @Nested
    @DisplayName("tick")
    class Tick {

        @Test
        @DisplayName("空队列 tick 返回 false")
        void emptyQueue_tickShouldReturnFalse() {
            assertFalse(new NavigationQueue().tick(null));
        }

        @Test
        @DisplayName("条件未满足时不弹出，返回 false")
        void conditionUnsatisfied_shouldNotPop() {
            NavigationQueue q = new NavigationQueue();
            NavigationEntry e = entry(1, 64, 1);
            q.enqueue(e, always(false));

            assertFalse(q.tick(null));
            assertEquals(1, q.size());
            assertEquals(e, q.peek());
        }

        @Test
        @DisplayName("条件满足时弹出队首并返回 true")
        void conditionSatisfied_shouldPopHead() {
            NavigationQueue q = new NavigationQueue();
            NavigationEntry first = entry(1, 64, 1);
            NavigationEntry second = entry(2, 64, 2);
            q.enqueue(first, always(true));
            q.enqueue(second, always(false));

            assertTrue(q.tick(null));
            assertEquals(1, q.size());
            assertEquals(second, q.peek(), "弹出队首后应轮到第二个条目");
        }

        @Test
        @DisplayName("条件为 null 时不弹出（避免无条件跳过目标）")
        void nullCondition_shouldNotPop() {
            NavigationQueue q = new NavigationQueue();
            q.enqueue(entry(1, 64, 1), null);

            assertFalse(q.tick(null));
            assertEquals(1, q.size());
        }

        @Test
        @DisplayName("连续 tick 可逐个弹出全部条目")
        void repeatedTick_shouldDrainQueue() {
            NavigationQueue q = new NavigationQueue();
            q.enqueue(entry(1, 64, 1), always(true));
            q.enqueue(entry(2, 64, 2), always(true));

            assertTrue(q.tick(null));
            assertTrue(q.tick(null));
            assertTrue(q.isEmpty());
            assertFalse(q.tick(null), "队列耗尽后 tick 应返回 false");
        }
    }

    // ==================== 快照与清空 ====================

    @Nested
    @DisplayName("getEntries / clear")
    class SnapshotClear {

        @Test
        @DisplayName("getEntries 按入队顺序返回快照")
        void getEntries_shouldPreserveOrder() {
            NavigationQueue q = new NavigationQueue();
            NavigationEntry a = entry(1, 64, 1);
            NavigationEntry b = entry(2, 64, 2);
            q.enqueue(a, always(false));
            q.enqueue(b, always(false));

            List<NavigationEntry> snapshot = q.getEntries();
            assertEquals(List.of(a, b), snapshot);
        }

        @Test
        @DisplayName("getEntries 返回的列表不可修改")
        void getEntries_shouldBeUnmodifiable() {
            NavigationQueue q = new NavigationQueue();
            q.enqueue(entry(1, 64, 1), always(false));

            List<NavigationEntry> snapshot = q.getEntries();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(entry(9, 9, 9)));
        }

        @Test
        @DisplayName("getEntries 是快照，后续入队不影响已取出的列表")
        void getEntries_shouldBeSnapshot() {
            NavigationQueue q = new NavigationQueue();
            q.enqueue(entry(1, 64, 1), always(false));
            List<NavigationEntry> snapshot = q.getEntries();

            q.enqueue(entry(2, 64, 2), always(false));

            assertEquals(1, snapshot.size(), "快照不应随队列变化");
            assertEquals(2, q.size());
        }

        @Test
        @DisplayName("clear 清空条目与条件")
        void clear_shouldEmptyQueue() {
            NavigationQueue q = new NavigationQueue();
            q.enqueue(entry(1, 64, 1), always(true));
            q.enqueue(entry(2, 64, 2), always(true));

            q.clear();

            assertTrue(q.isEmpty());
            assertEquals(0, q.size());
            assertNull(q.peek());
            assertNull(q.peekCondition());
            assertFalse(q.tick(null), "清空后 tick 不应弹出任何残留条件");
        }

        @Test
        @DisplayName("重复 clear 是幂等的")
        void clear_shouldBeIdempotent() {
            NavigationQueue q = new NavigationQueue();
            q.clear();
            assertDoesNotThrow(q::clear);
            assertTrue(q.isEmpty());
        }
    }
}
