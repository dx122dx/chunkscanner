package com.billy65536.chunkscanner.core.navigation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NavigationTickDispatcher 单元测试。
 *
 * <p><b>最高优先级契约</b>：托管器必须拒绝注册全局实例。
 * 全局实例已由 {@code ChunkScannerMod.onClientTick} 直接驱动，
 * 若再被托管器 tick，到达判定会在同一 tick 内执行两次，
 * 导致导航队列被跳过一个目标 —— 这是极隐蔽的回归点。</p>
 */
@DisplayName("NavigationTickDispatcher")
class NavigationTickDispatcherTest {

    /** 测试期间注册过的实例，确保用例间不互相污染静态托管列表。 */
    private final List<ChunkScannerNavigation> registered = new ArrayList<>();

    @BeforeEach
    void assertCleanStart() {
        // 前一个用例若泄漏，这里能尽早暴露
        assertEquals(0, NavigationTickDispatcher.size(),
                "托管列表应在用例开始前为空，说明上一个用例未清理");
    }

    @AfterEach
    void cleanUp() {
        for (ChunkScannerNavigation nav : registered) {
            NavigationTickDispatcher.unregister(nav);
        }
        registered.clear();
        assertEquals(0, NavigationTickDispatcher.size());
    }

    private ChunkScannerNavigation track(ChunkScannerNavigation nav) {
        registered.add(nav);
        return nav;
    }

    // ==================== 拒绝全局实例（核心契约） ====================

    @Nested
    @DisplayName("拒绝全局实例")
    class RejectGlobal {

        @Test
        @DisplayName("register 全局实例返回 false 且不入列")
        void registerGlobal_shouldBeRejected() {
            ChunkScannerNavigation global = ChunkScannerNavigation.get();

            assertFalse(NavigationTickDispatcher.register(global),
                    "全局实例已由模组内部驱动，不得被托管");
            assertEquals(0, NavigationTickDispatcher.size(),
                    "全局实例被托管会导致到达判定每 tick 执行两次");
        }

        @Test
        @DisplayName("多次尝试注册全局实例始终被拒绝")
        void registerGlobalRepeatedly_shouldStayRejected() {
            ChunkScannerNavigation global = ChunkScannerNavigation.get();

            assertFalse(NavigationTickDispatcher.register(global));
            assertFalse(NavigationTickDispatcher.register(global));
            assertEquals(0, NavigationTickDispatcher.size());
        }
    }

    // ==================== 注册与注销 ====================

    @Nested
    @DisplayName("register / unregister")
    class RegisterUnregister {

        @Test
        @DisplayName("注册独立实例成功且 size 递增")
        void registerIndependent_shouldSucceed() {
            ChunkScannerNavigation nav = track(ChunkScannerNavigation.create("disp-a"));

            assertTrue(NavigationTickDispatcher.register(nav));
            assertEquals(1, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("重复注册同一实例返回 false 且不产生重复项")
        void duplicateRegister_shouldNotDuplicate() {
            ChunkScannerNavigation nav = track(ChunkScannerNavigation.create("disp-dup"));

            assertTrue(NavigationTickDispatcher.register(nav));
            assertFalse(NavigationTickDispatcher.register(nav));
            assertEquals(1, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("注册 null 返回 false 且不影响 size")
        void registerNull_shouldReturnFalse() {
            assertFalse(NavigationTickDispatcher.register(null));
            assertEquals(0, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("注册多个不同实例各自独立入列")
        void registerMultiple_shouldAccumulate() {
            ChunkScannerNavigation a = track(ChunkScannerNavigation.create("disp-multi-a"));
            ChunkScannerNavigation b = track(ChunkScannerNavigation.create("disp-multi-b"));

            assertTrue(NavigationTickDispatcher.register(a));
            assertTrue(NavigationTickDispatcher.register(b));
            assertEquals(2, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("unregister 已托管实例返回 true 且 size 递减")
        void unregisterManaged_shouldSucceed() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("disp-unreg");
            NavigationTickDispatcher.register(nav);

            assertTrue(NavigationTickDispatcher.unregister(nav));
            assertEquals(0, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("unregister 未托管实例返回 false")
        void unregisterUnmanaged_shouldReturnFalse() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("disp-never");
            assertFalse(NavigationTickDispatcher.unregister(nav));
        }

        @Test
        @DisplayName("unregister null 返回 false")
        void unregisterNull_shouldReturnFalse() {
            assertFalse(NavigationTickDispatcher.unregister(null));
        }

        @Test
        @DisplayName("重复 unregister 第二次返回 false")
        void doubleUnregister_shouldReturnFalseSecondTime() {
            ChunkScannerNavigation nav = ChunkScannerNavigation.create("disp-double-unreg");
            NavigationTickDispatcher.register(nav);

            assertTrue(NavigationTickDispatcher.unregister(nav));
            assertFalse(NavigationTickDispatcher.unregister(nav));
        }
    }

    // ==================== tickAll 容错 ====================

    @Nested
    @DisplayName("tickAll")
    class TickAll {

        @Test
        @DisplayName("空托管列表时 tickAll 直接返回，不抛异常")
        void tickAllEmpty_shouldNotThrow() {
            assertDoesNotThrow(() -> NavigationTickDispatcher.tickAll(null));
        }

        @Test
        @DisplayName("托管实例队列为空时 tickAll 不抛异常")
        void tickAllWithEmptyQueue_shouldNotThrow() {
            ChunkScannerNavigation nav = track(ChunkScannerNavigation.create("disp-tick-empty"));
            NavigationTickDispatcher.register(nav);

            assertDoesNotThrow(() -> NavigationTickDispatcher.tickAll(null));
        }
    }
}
