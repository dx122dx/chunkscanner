package com.billy65536.chunkscanner.core.navigation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NavigationConditionRegistry 单元测试。
 *
 * <p>锁定三条对外契约：</p>
 * <ol>
 *   <li>内置 {@code chunkscanner:player_near} 不可注销</li>
 *   <li>未注册 id 调用 {@code create} 回退内置条件，<b>不抛异常</b>
 *       —— 外部模组传错 id 不应崩游戏</li>
 *   <li>{@code ids()} 返回只读集合</li>
 * </ol>
 *
 * <p><b>Identifier 铁律</b>：一律用 {@code new Identifier(ns, path)} 构造，
 * path 必须全小写，禁用 2 参静态 {@code Identifier.of}。</p>
 */
@DisplayName("NavigationConditionRegistry")
class NavigationConditionRegistryTest {

    /** 测试期间注册过的自定义 id，用例结束统一注销避免污染静态注册表。 */
    private final List<Identifier> registered = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Identifier id : registered) {
            NavigationConditionRegistry.unregister(id);
        }
        registered.clear();
    }

    private Identifier track(Identifier id) {
        registered.add(id);
        return id;
    }

    /** 无副作用的桩条件工厂，记录透传下来的参数。 */
    private static final class RecordingFactory implements NavigationConditionRegistry.Factory {
        int x, y, z;
        String dimensionId;
        int callCount;

        @Override
        public NavigationCondition create(int x, int y, int z, String dimensionId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimensionId = dimensionId;
            this.callCount++;
            return client -> false;
        }
    }

    // ==================== 内置条件 ====================

    @Nested
    @DisplayName("内置 player_near")
    class BuiltIn {

        @Test
        @DisplayName("PLAYER_NEAR 命名空间为 chunkscanner")
        void playerNear_shouldUseModNamespace() {
            assertEquals(new Identifier(ChunkScannerMod.MOD_ID, "player_near"),
                    NavigationConditionRegistry.PLAYER_NEAR);
        }

        @Test
        @DisplayName("PLAYER_NEAR 默认已注册")
        void playerNear_shouldBeRegisteredByDefault() {
            assertTrue(NavigationConditionRegistry.contains(NavigationConditionRegistry.PLAYER_NEAR));
            assertNotNull(NavigationConditionRegistry.get(NavigationConditionRegistry.PLAYER_NEAR));
        }

        @Test
        @DisplayName("PLAYER_NEAR 不可注销")
        void playerNear_shouldNotBeUnregisterable() {
            assertFalse(NavigationConditionRegistry.unregister(NavigationConditionRegistry.PLAYER_NEAR));
            assertTrue(NavigationConditionRegistry.contains(NavigationConditionRegistry.PLAYER_NEAR),
                    "内置条件被注销后所有默认入队都会失去到达判定");
        }
    }

    // ==================== 注册与注销 ====================

    @Nested
    @DisplayName("register / unregister")
    class RegisterUnregister {

        @Test
        @DisplayName("注册自定义条件后可被查询到")
        void register_shouldMakeIdDiscoverable() {
            Identifier id = track(new Identifier("qab", "chest_opened"));

            assertTrue(NavigationConditionRegistry.register(id, (x, y, z, dim) -> client -> true));
            assertTrue(NavigationConditionRegistry.contains(id));
            assertTrue(NavigationConditionRegistry.ids().contains(id));
        }

        @Test
        @DisplayName("重复注册同 id 覆盖旧工厂")
        void register_duplicateId_shouldOverride() {
            Identifier id = track(new Identifier("qab", "override_me"));
            RecordingFactory first = new RecordingFactory();
            RecordingFactory second = new RecordingFactory();

            NavigationConditionRegistry.register(id, first);
            NavigationConditionRegistry.register(id, second);

            assertSame(second, NavigationConditionRegistry.get(id));
        }

        @Test
        @DisplayName("注册 null id 或 null 工厂返回 false")
        void register_nullArgs_shouldReturnFalse() {
            assertFalse(NavigationConditionRegistry.register(null, (x, y, z, dim) -> client -> true));
            assertFalse(NavigationConditionRegistry.register(new Identifier("qab", "null_factory"), null));
        }

        @Test
        @DisplayName("注销自定义条件后不再可查")
        void unregister_shouldRemove() {
            Identifier id = new Identifier("qab", "temporary");
            NavigationConditionRegistry.register(id, (x, y, z, dim) -> client -> true);

            assertTrue(NavigationConditionRegistry.unregister(id));
            assertFalse(NavigationConditionRegistry.contains(id));
        }

        @Test
        @DisplayName("注销未注册 id 返回 false")
        void unregister_unknownId_shouldReturnFalse() {
            assertFalse(NavigationConditionRegistry.unregister(new Identifier("qab", "never_registered")));
        }

        @Test
        @DisplayName("注销 null 返回 false")
        void unregister_null_shouldReturnFalse() {
            assertFalse(NavigationConditionRegistry.unregister(null));
        }

        @Test
        @DisplayName("contains(null) 返回 false 而非抛异常")
        void contains_null_shouldReturnFalse() {
            assertFalse(NavigationConditionRegistry.contains(null));
        }

        @Test
        @DisplayName("get(null) 返回 null 而非抛异常")
        void get_null_shouldReturnNull() {
            assertNull(NavigationConditionRegistry.get(null));
        }
    }

    // ==================== ids() 只读 ====================

    @Nested
    @DisplayName("ids()")
    class Ids {

        @Test
        @DisplayName("返回不可变集合")
        void ids_shouldBeUnmodifiable() {
            Collection<Identifier> ids = NavigationConditionRegistry.ids();
            assertThrows(UnsupportedOperationException.class,
                    () -> ids.add(new Identifier("qab", "illegal")));
        }

        @Test
        @DisplayName("至少包含内置条件")
        void ids_shouldContainBuiltIn() {
            assertTrue(NavigationConditionRegistry.ids()
                    .contains(NavigationConditionRegistry.PLAYER_NEAR));
        }
    }

    // ==================== create 参数透传与回退 ====================

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("参数原样透传给工厂")
        void create_shouldPassThroughArgs() {
            Identifier id = track(new Identifier("qab", "record_args"));
            RecordingFactory factory = new RecordingFactory();
            NavigationConditionRegistry.register(id, factory);

            NavigationCondition condition =
                    NavigationConditionRegistry.create(id, 100, 64, -200, "minecraft:the_nether");

            assertNotNull(condition);
            assertEquals(1, factory.callCount);
            assertEquals(100, factory.x);
            assertEquals(64, factory.y);
            assertEquals(-200, factory.z);
            assertEquals("minecraft:the_nether", factory.dimensionId);
        }

        @Test
        @DisplayName("未注册 id 回退内置条件而非抛异常")
        void create_unknownId_shouldFallbackNotThrow() {
            Identifier unknown = new Identifier("qab", "not_registered_at_all");
            assertFalse(NavigationConditionRegistry.contains(unknown));

            NavigationCondition condition = assertDoesNotThrow(
                    () -> NavigationConditionRegistry.create(unknown, 0, 64, 0, "minecraft:overworld"),
                    "外部模组传入未注册 id 不应导致崩溃");
            assertNotNull(condition, "回退后必须返回可用条件");
        }

        @Test
        @DisplayName("create(null) 回退内置条件而非抛异常")
        void create_nullId_shouldFallbackNotThrow() {
            NavigationCondition condition = assertDoesNotThrow(
                    () -> NavigationConditionRegistry.create(null, 0, 64, 0, "minecraft:overworld"));
            assertNotNull(condition);
        }

        @Test
        @DisplayName("内置条件可正常构建")
        void create_builtIn_shouldProduceCondition() {
            NavigationCondition condition = NavigationConditionRegistry.create(
                    NavigationConditionRegistry.PLAYER_NEAR, 10, 64, 20, "minecraft:overworld");
            assertNotNull(condition);
        }
    }
}
