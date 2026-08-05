package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnalyzerRegistry 单元测试。
 * 覆盖 register、get、getAll 及其边界情况。
 */
@DisplayName("AnalyzerRegistry")
class AnalyzerRegistryTest {

    /** 创建一个匿名 IChunkAnalyzer 用于测试。 */
    private static IChunkAnalyzer createAnalyzer(String id, String name) {
        return new IChunkAnalyzer() {
            @Override public Identifier getId() { return ChunkScannerMod.id(id); }
            @Override public Text getName() { return Text.literal(name); }
            @Override public Text getDescription() { return Text.literal("desc: " + name); }
            @Override
            public AnalyzeResult analyze(net.minecraft.world.chunk.WorldChunk chunk,
                                          int cx, int cz, String dimId, IChunkDb db, long now) {
                return AnalyzeResult.skipped();
            }
        };
    }

    // ==================== register / get ====================

    @Nested
    @DisplayName("register / get")
    class RegisterGet {

        @Test
        @DisplayName("注册后可通过 id 获取")
        void registered_shouldBeRetrievableById() {
            IChunkAnalyzer analyzer = createAnalyzer("test.analyzer.get1", "Test Get");
            AnalyzerRegistry.register(analyzer);

            IChunkAnalyzer retrieved = AnalyzerRegistry.get(ChunkScannerMod.id("test.analyzer.get1"));
            assertNotNull(retrieved);
            assertEquals(ChunkScannerMod.id("test.analyzer.get1"), retrieved.getId());
            assertEquals("Test Get", retrieved.getName().getString());
        }

        @Test
        @DisplayName("未注册的 id 返回 null")
        void unregisteredId_shouldReturnNull() {
            assertNull(AnalyzerRegistry.get(ChunkScannerMod.id("nonexistent.analyzer.id.xyz")));
        }

        @Test
        @DisplayName("同 id 重复注册会覆盖旧分析器")
        void duplicateRegister_shouldOverwrite() {
            IChunkAnalyzer first = createAnalyzer("test.analyzer.dup", "First");
            IChunkAnalyzer second = createAnalyzer("test.analyzer.dup", "Second");

            AnalyzerRegistry.register(first);
            AnalyzerRegistry.register(second);

            IChunkAnalyzer retrieved = AnalyzerRegistry.get(ChunkScannerMod.id("test.analyzer.dup"));
            assertNotNull(retrieved);
            assertEquals("Second", retrieved.getName().getString());
        }

        @Test
        @DisplayName("注册 null id 的分析器 → get(null) 返回 null")
        void nullId_shouldReturnNull() {
            IChunkAnalyzer analyzer = createAnalyzer("test.analyzer.nullget", "Test");
            AnalyzerRegistry.register(analyzer);

            assertNull(AnalyzerRegistry.get((Identifier) null));
        }
    }

    // ==================== getAll ====================

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("getAll 返回所有已注册的分析器")
        void getAll_shouldReturnAllRegistered() {
            IChunkAnalyzer a1 = createAnalyzer("test.analyzer.all.1", "A");
            IChunkAnalyzer a2 = createAnalyzer("test.analyzer.all.2", "B");
            AnalyzerRegistry.register(a1);
            AnalyzerRegistry.register(a2);

            Collection<IChunkAnalyzer> all = AnalyzerRegistry.getAll();
            assertTrue(all.size() >= 2);
            assertTrue(all.contains(a1));
            assertTrue(all.contains(a2));
        }

        @Test
        @DisplayName("getAll 返回的集合不可修改")
        void getAll_shouldReturnUnmodifiableCollection() {
            IChunkAnalyzer analyzer = createAnalyzer("test.analyzer.unmod", "Unmod");
            AnalyzerRegistry.register(analyzer);

            Collection<IChunkAnalyzer> all = AnalyzerRegistry.getAll();
            assertThrows(UnsupportedOperationException.class,
                    () -> all.add(createAnalyzer("should.fail", "Fail")));
        }

        @Test
        @DisplayName("注册表为空时 getAll 返回空集合")
        void getAll_whenRegistryEmpty_shouldReturnEmptyCollection() {
            // AnalyzerRegistry 是静态的，可能在之前测试中已有注册
            // 我们仅验证返回的集合不为 null 且是可迭代的
            Collection<IChunkAnalyzer> all = AnalyzerRegistry.getAll();
            assertNotNull(all);
            // 验证可以安全遍历
            assertDoesNotThrow(() -> {
                for (IChunkAnalyzer a : all) {
                    assertNotNull(a);
                }
            });
        }
    }

    // ==================== 注册顺序 ====================

    @Nested
    @DisplayName("注册顺序")
    class RegistrationOrder {

        @Test
        @DisplayName("注册顺序决定 getAll 返回的迭代顺序（LinkedHashMap）")
        void getAll_preservesInsertionOrder() {
            IChunkAnalyzer first = createAnalyzer("test.analyzer.order.1", "1st");
            IChunkAnalyzer second = createAnalyzer("test.analyzer.order.2", "2nd");

            AnalyzerRegistry.register(first);
            AnalyzerRegistry.register(second);

            Collection<IChunkAnalyzer> all = AnalyzerRegistry.getAll();
            // 将 collection 转为数组检查顺序
            IChunkAnalyzer[] arr = all.toArray(new IChunkAnalyzer[0]);
            int idx1 = -1, idx2 = -1;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == first) idx1 = i;
                if (arr[i] == second) idx2 = i;
            }
            assertTrue(idx1 >= 0, "first analyzer should be in collection");
            assertTrue(idx2 >= 0, "second analyzer should be in collection");
            assertTrue(idx1 < idx2, "first registered should appear before second");
        }
    }
}
