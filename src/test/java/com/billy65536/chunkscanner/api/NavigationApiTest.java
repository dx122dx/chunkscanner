package com.billy65536.chunkscanner.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationCondition;
import com.billy65536.chunkscanner.core.navigation.NavigationConditionRegistry;
import com.billy65536.chunkscanner.core.navigation.NavigationTickDispatcher;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NavigationApi 公共契约测试。
 *
 * <p>覆盖不依赖 {@code MinecraftClient} 的部分：实例获取、tick 托管、条件注册。
 * {@code start/stop/tick} 等需要玩家与世界状态的行为交由手动回归清单覆盖。</p>
 */
@DisplayName("NavigationApi")
class NavigationApiTest {

    private final List<ChunkScannerNavigation> managed = new ArrayList<>();
    private final List<Identifier> conditions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (ChunkScannerNavigation nav : managed) {
            NavigationApi.unmanageTick(nav);
        }
        managed.clear();
        for (Identifier id : conditions) {
            NavigationApi.unregisterCondition(id);
        }
        conditions.clear();
        assertEquals(0, NavigationTickDispatcher.size(), "托管列表未清理干净");
    }

    // ==================== 实例获取 ====================

    @Nested
    @DisplayName("global / createNavigation")
    class Instances {

        @Test
        @DisplayName("global() 幂等返回同一实例")
        void global_shouldBeIdempotent() {
            assertSame(NavigationApi.global(), NavigationApi.global());
            assertSame(ChunkScannerNavigation.get(), NavigationApi.global());
        }

        @Test
        @DisplayName("createNavigation 每次返回新的独立实例")
        void createNavigation_shouldReturnDistinctInstances() {
            ChunkScannerNavigation a = NavigationApi.createNavigation("api-nav-a");
            ChunkScannerNavigation b = NavigationApi.createNavigation("api-nav-b");

            assertNotNull(a);
            assertNotNull(b);
            assertNotSame(a, b);
            assertNotSame(NavigationApi.global(), a);
        }

        @Test
        @DisplayName("同名 createNavigation 仍返回不同实例（不做缓存）")
        void createNavigation_sameName_shouldStillBeDistinct() {
            assertNotSame(NavigationApi.createNavigation("api-nav-same"),
                    NavigationApi.createNavigation("api-nav-same"));
        }

        @Test
        @DisplayName("独立实例队列互不干扰")
        void independentInstances_shouldHaveIsolatedQueues() {
            ChunkScannerNavigation a = NavigationApi.createNavigation("api-iso-a");
            ChunkScannerNavigation b = NavigationApi.createNavigation("api-iso-b");

            a.enqueue(1, 64, 1, "minecraft:overworld");
            a.enqueue(2, 64, 2, "minecraft:overworld");

            assertEquals(2, a.size());
            assertEquals(0, b.size(), "独立实例之间队列必须完全隔离");

            a.clear();
        }
    }

    // ==================== tick 托管 ====================

    @Nested
    @DisplayName("manageTick / unmanageTick")
    class TickManagement {

        @Test
        @DisplayName("托管独立实例成功")
        void manageTick_independent_shouldSucceed() {
            ChunkScannerNavigation nav = NavigationApi.createNavigation("api-manage");
            managed.add(nav);

            assertTrue(NavigationApi.manageTick(nav));
            assertEquals(1, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("托管全局实例被拒绝（否则每 tick 推进两次）")
        void manageTick_global_shouldBeRejected() {
            assertFalse(NavigationApi.manageTick(NavigationApi.global()));
            assertEquals(0, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("重复托管同一实例不产生重复项")
        void manageTick_duplicate_shouldNotDuplicate() {
            ChunkScannerNavigation nav = NavigationApi.createNavigation("api-manage-dup");
            managed.add(nav);

            assertTrue(NavigationApi.manageTick(nav));
            assertFalse(NavigationApi.manageTick(nav));
            assertEquals(1, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("unmanageTick 取消托管")
        void unmanageTick_shouldRemove() {
            ChunkScannerNavigation nav = NavigationApi.createNavigation("api-unmanage");
            NavigationApi.manageTick(nav);

            assertTrue(NavigationApi.unmanageTick(nav));
            assertEquals(0, NavigationTickDispatcher.size());
        }

        @Test
        @DisplayName("unmanageTick 未托管实例返回 false")
        void unmanageTick_unmanaged_shouldReturnFalse() {
            assertFalse(NavigationApi.unmanageTick(NavigationApi.createNavigation("api-never-managed")));
        }

        @Test
        @DisplayName("manageTick(null) / unmanageTick(null) 返回 false 而非抛异常")
        void tickManagement_null_shouldReturnFalse() {
            assertFalse(NavigationApi.manageTick(null));
            assertFalse(NavigationApi.unmanageTick(null));
        }
    }

    // ==================== 到达条件注册 ====================

    @Nested
    @DisplayName("条件注册")
    class Conditions {

        @Test
        @DisplayName("playerNearConditionId 返回内置 id")
        void playerNearConditionId_shouldMatchRegistryConstant() {
            assertEquals(NavigationConditionRegistry.PLAYER_NEAR, NavigationApi.playerNearConditionId());
            assertTrue(NavigationApi.hasCondition(NavigationApi.playerNearConditionId()));
        }

        @Test
        @DisplayName("内置条件不可注销")
        void builtInCondition_shouldNotBeUnregisterable() {
            assertFalse(NavigationApi.unregisterCondition(NavigationApi.playerNearConditionId()));
            assertTrue(NavigationApi.hasCondition(NavigationApi.playerNearConditionId()));
        }

        @Test
        @DisplayName("注册后 hasCondition 与 conditionIds 均可见")
        void registerCondition_shouldBeVisible() {
            Identifier id = new Identifier("qab", "api_test_cond");
            conditions.add(id);

            assertTrue(NavigationApi.registerCondition(id, (x, y, z, dim) -> client -> true));
            assertTrue(NavigationApi.hasCondition(id));
            assertTrue(NavigationApi.conditionIds().contains(id));
        }

        @Test
        @DisplayName("conditionIds 返回不可变集合")
        void conditionIds_shouldBeUnmodifiable() {
            java.util.Collection<Identifier> ids = NavigationApi.conditionIds();
            assertThrows(UnsupportedOperationException.class,
                    () -> ids.add(new Identifier("qab", "illegal")));
        }

        @Test
        @DisplayName("createCondition 未注册 id 回退内置而非抛异常")
        void createCondition_unknownId_shouldFallback() {
            NavigationCondition condition = assertDoesNotThrow(() -> NavigationApi.createCondition(
                    new Identifier("qab", "api_unknown_cond"), 0, 64, 0, "minecraft:overworld"));
            assertNotNull(condition);
        }

        @Test
        @DisplayName("createCondition 参数透传给注册的工厂")
        void createCondition_shouldPassArgs() {
            Identifier id = new Identifier("qab", "api_arg_cond");
            conditions.add(id);
            int[] captured = new int[3];
            String[] dim = new String[1];

            NavigationApi.registerCondition(id, (x, y, z, d) -> {
                captured[0] = x;
                captured[1] = y;
                captured[2] = z;
                dim[0] = d;
                return client -> false;
            });

            NavigationApi.createCondition(id, 7, 8, 9, "minecraft:the_end");

            assertArrayEquals(new int[]{7, 8, 9}, captured);
            assertEquals("minecraft:the_end", dim[0]);
        }

        @Test
        @DisplayName("注册 null 参数返回 false")
        void registerCondition_null_shouldReturnFalse() {
            assertFalse(NavigationApi.registerCondition(null, (x, y, z, d) -> client -> true));
            assertFalse(NavigationApi.registerCondition(new Identifier("qab", "api_null_factory"), null));
        }
    }
}
