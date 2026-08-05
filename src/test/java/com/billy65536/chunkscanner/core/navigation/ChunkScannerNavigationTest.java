package com.billy65536.chunkscanner.core.navigation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.billy65536.chunkscanner.core.LocatedPosition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkScannerNavigation 多实例语义单元测试。
 *
 * <p>v1.1.0 将导航由单例重构为可实例化。本类锁定的核心是
 * <b>多实例之间的队列隔离</b> —— 一旦退化为共享静态队列，
 * 外部模组（qab）的购物路线会与玩家的 {@code /cs nav} 队列互相清空。</p>
 *
 * <p>注意：涉及 Baritone 路径执行的 {@code start()/stop()/tick()} 需要
 * Minecraft 运行时，本类不覆盖，交由手动回归清单验证。</p>
 */
@DisplayName("ChunkScannerNavigation")
class ChunkScannerNavigationTest {

    /** 每个用例结束后清空全局实例队列，避免污染其他测试。 */
    @AfterEach
    void cleanUpGlobal() {
        ChunkScannerNavigation.get().clear();
    }

    private static NavigationCondition never() {
        return client -> false;
    }

    // ==================== 实例身份 ====================

    @Nested
    @DisplayName("实例身份")
    class Identity {

        @Test
        @DisplayName("get() 多次调用返回同一全局实例")
        void get_shouldBeSingleton() {
            assertSame(ChunkScannerNavigation.get(), ChunkScannerNavigation.get());
        }

        @Test
        @DisplayName("全局实例的 isGlobal 为 true，名称为 global")
        void globalInstance_shouldReportGlobal() {
            ChunkScannerNavigation global = ChunkScannerNavigation.get();
            assertTrue(global.isGlobal());
            assertEquals("global", global.getName());
        }

        @Test
        @DisplayName("create(name) 每次返回全新实例")
        void create_shouldReturnDistinctInstances() {
            ChunkScannerNavigation a = ChunkScannerNavigation.create("test-a");
            ChunkScannerNavigation b = ChunkScannerNavigation.create("test-a");

            assertNotSame(a, b, "同名创建也应是两个独立实例");
        }

        @Test
        @DisplayName("create(name) 创建的实例 isGlobal 为 false 且保留名称")
        void create_shouldNotBeGlobal() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("qab");
            assertFalse(nav.isGlobal());
            assertEquals("qab", nav.getName());
        }

        @Test
        @DisplayName("create 出的实例与全局实例不是同一个")
        void create_shouldDifferFromGlobal() {
            assertNotSame(ChunkScannerNavigation.get(), ChunkScannerNavigation.create("other"));
        }
    }

    // ==================== 队列隔离（核心契约） ====================

    @Nested
    @DisplayName("队列隔离")
    class QueueIsolation {

        @Test
        @DisplayName("两个独立实例各自持有队列，入队互不影响")
        void independentInstances_shouldNotShareQueue() {
            ChunkScannerNavigation a = ChunkScannerNavigation.create("iso-a");
            ChunkScannerNavigation b = ChunkScannerNavigation.create("iso-b");

            a.enqueue(1, 64, 1, "minecraft:overworld", never());
            a.enqueue(2, 64, 2, "minecraft:overworld", never());

            assertEquals(2, a.size());
            assertEquals(0, b.size(), "实例 b 的队列不应被实例 a 的入队影响");
        }

        @Test
        @DisplayName("独立实例入队不影响全局实例队列")
        void independentInstance_shouldNotAffectGlobal() {
            ChunkScannerNavigation global = ChunkScannerNavigation.get();
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("iso-global");

            int before = global.size();
            nav.enqueue(10, 64, 10, "minecraft:overworld", never());

            assertEquals(before, global.size(), "全局队列不应被独立实例污染");
            assertEquals(1, nav.size());
        }

        @Test
        @DisplayName("clear 只清空自身队列")
        void clear_shouldOnlyAffectSelf() {
            ChunkScannerNavigation a = ChunkScannerNavigation.create("clear-a");
            ChunkScannerNavigation b = ChunkScannerNavigation.create("clear-b");

            a.enqueue(1, 64, 1, "minecraft:overworld", never());
            b.enqueue(2, 64, 2, "minecraft:overworld", never());

            a.clear();

            assertEquals(0, a.size());
            assertEquals(1, b.size(), "clear 不应清空其他实例的队列");
        }

        @Test
        @DisplayName("getQueue 返回的是实例自身的队列对象")
        void getQueue_shouldBeInstanceScoped() {
            ChunkScannerNavigation a = ChunkScannerNavigation.create("q-a");
            ChunkScannerNavigation b = ChunkScannerNavigation.create("q-b");

            assertNotSame(a.list(), b.list());
        }
    }

    // ==================== 入队重载 ====================

    @Nested
    @DisplayName("enqueue 重载")
    class EnqueueOverloads {

        @Test
        @DisplayName("enqueue(x,y,z,dim) 使用默认到达条件")
        void enqueueCoords_shouldUseDefaultCondition() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("enq-coords");
            nav.enqueue(3, 64, 3, "minecraft:overworld");

            assertEquals(1, nav.size());
            assertNotNull(nav.getQueue().peekCondition(), "默认应绑定内置到达条件");
        }

        @Test
        @DisplayName("enqueue(LocatedPosition) 正确映射坐标与维度")
        void enqueueLocatedPosition_shouldMapFields() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("enq-pos");
            nav.enqueue(new LocatedPosition("minecraft:the_nether", 7, 30, -9));

            NavigationEntry head = nav.getQueue().peek();
            assertNotNull(head);
            assertEquals("minecraft:the_nether", head.dimensionId());
            assertEquals(7, head.x());
            assertEquals(30, head.y());
            assertEquals(-9, head.z());
        }

        @Test
        @DisplayName("enqueue(NavigationEntry, cond) 保留传入的条件实例")
        void enqueueEntry_shouldKeepCondition() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("enq-entry");
            NavigationCondition cond = never();
            nav.enqueue(new NavigationEntry("minecraft:overworld", 1, 2, 3), cond);

            assertSame(cond, nav.getQueue().peekCondition());
        }

        @Test
        @DisplayName("enqueueWithCondition 未注册 id 时回退内置条件而非抛异常")
        void enqueueWithUnknownConditionId_shouldFallback() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("enq-unknown-cond");

            assertDoesNotThrow(() -> nav.enqueueWithCondition(
                    1, 64, 1, "minecraft:overworld",
                    new net.minecraft.util.Identifier("test", "absent-condition-xyz")));
            assertEquals(1, nav.size());
            assertNotNull(nav.getQueue().peekCondition());
        }

        @Test
        @DisplayName("多次入队按 FIFO 顺序保存")
        void multipleEnqueue_shouldPreserveOrder() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("enq-order");
            nav.enqueue(1, 64, 1, "minecraft:overworld", never());
            nav.enqueue(2, 64, 2, "minecraft:overworld", never());

            assertEquals(1, nav.getQueue().peek().x());
            assertEquals(2, nav.list().get(1).x());
        }
    }

    // ==================== 状态查询 ====================

    @Nested
    @DisplayName("状态查询")
    class StateQuery {

        @Test
        @DisplayName("新实例 size 为 0 且 list 为空")
        void newInstance_shouldBeEmpty() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("state-empty");
            assertEquals(0, nav.size());
            assertTrue(nav.list().isEmpty());
        }

        @Test
        @DisplayName("list 返回的列表不可修改")
        void list_shouldBeUnmodifiable() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("state-unmod");
            nav.enqueue(1, 64, 1, "minecraft:overworld", never());

            var list = nav.list();
            assertThrows(UnsupportedOperationException.class,
                    () -> list.add(new NavigationEntry("minecraft:overworld", 9, 9, 9)));
        }

        @Test
        @DisplayName("setAutoEnabled / isAutoEnabled 为实例级状态")
        void autoEnabled_shouldBeInstanceScoped() {
            ChunkScannerNavigation a = ChunkScannerNavigation.create("auto-a");
            ChunkScannerNavigation b = ChunkScannerNavigation.create("auto-b");

            a.setAutoEnabled(true);
            b.setAutoEnabled(false);

            assertTrue(a.isAutoEnabled());
            assertFalse(b.isAutoEnabled());
        }

        @Test
        @DisplayName("isBaritoneAvailable 在无 Baritone 环境下返回 false 且不抛异常")
        void isBaritoneAvailable_shouldNotThrow() {
            assertDoesNotThrow(ChunkScannerNavigation::isBaritoneAvailable);
        }
    }
}
