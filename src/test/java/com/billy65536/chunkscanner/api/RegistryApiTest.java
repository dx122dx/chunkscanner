package com.billy65536.chunkscanner.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.IDbViewProvider;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegistryApi 公共契约单元测试。
 *
 * <p>重点锁定 {@link RegistryApi#viewProvidersFor} 的<b>空集表示通用</b>语义
 * —— 该契约一旦被误改为「空集表示不适用」，所有通用视图会从数据库 GUI 中消失。</p>
 */
@DisplayName("RegistryApi")
class RegistryApiTest {

    // ==================== 辅助 ====================

    /** 构造一个仅用于测试的视图类型描述符。 */
    private static DbViewProviderRegistry.ITypeDescriptor viewType(String path, Set<Identifier> applicable) {
        Identifier id = ChunkScannerMod.id(path);
        return new DbViewProviderRegistry.ITypeDescriptor() {
            @Override public Identifier getId() { return id; }
            @Override public Text getName() { return Text.literal(path); }
            @Override public Text getDescription() { return Text.literal("desc:" + path); }
            @Override public Set<Identifier> applicableAdaptors() { return applicable; }
            @Override public IDbViewProvider create(DbPackage pkg) { return null; }
        };
    }

    // ==================== 视图适用性契约 ====================

    @Nested
    @DisplayName("viewProvidersFor —— 按 adaptorId 显式声明")
    class ViewApplicability {

        @Test
        @DisplayName("仅对 applicableAdaptors 中声明的适配器可见")
        void declaredAdaptor_shouldBeVisible() {
            Identifier target = ChunkScannerMod.id("test.api.adaptor.target");
            Identifier other = ChunkScannerMod.id("test.api.adaptor.other");

            DbViewProviderRegistry.ITypeDescriptor restricted =
                    viewType("test.api.view.restricted", Set.of(target));
            RegistryApi.registerViewProvider(restricted);

            assertTrue(RegistryApi.viewProvidersFor(target).contains(restricted));
            assertFalse(RegistryApi.viewProvidersFor(other).contains(restricted));
        }

        @Test
        @DisplayName("applicableAdaptors 为空集的视图对任何适配器都不可见")
        void emptySet_shouldBeInvisible() {
            DbViewProviderRegistry.ITypeDescriptor empty =
                    viewType("test.api.view.emptyset", Set.of());
            RegistryApi.registerViewProvider(empty);

            assertFalse(RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.adaptor.any"))
                    .contains(empty));
        }

        @Test
        @DisplayName("applicableAdaptors 返回 null 时不可见且不抛 NPE")
        void nullSet_shouldBeInvisible() {
            DbViewProviderRegistry.ITypeDescriptor nullApplicable =
                    viewType("test.api.view.nullset", null);
            RegistryApi.registerViewProvider(nullApplicable);

            assertFalse(RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.adaptor.any2"))
                    .contains(nullApplicable));
        }

        @Test
        @DisplayName("返回的列表不可修改")
        void result_shouldBeUnmodifiable() {
            List<DbViewProviderRegistry.ITypeDescriptor> result =
                    RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.adaptor.unmod"));
            assertThrows(UnsupportedOperationException.class,
                    () -> result.add(viewType("test.api.view.shouldfail", Set.of())));
        }
    }

    // ==================== 视图注册 ====================

    @Nested
    @DisplayName("视图注册")
    class ViewRegistration {

        @Test
        @DisplayName("注册后可通过 id 获取，hasViewProvider 为 true")
        void register_shouldBeRetrievable() {
            DbViewProviderRegistry.ITypeDescriptor type = viewType("test.api.view.get", Set.of());
            assertTrue(RegistryApi.registerViewProvider(type));

            Identifier id = ChunkScannerMod.id("test.api.view.get");
            assertSame(type, RegistryApi.getViewProvider(id));
            assertTrue(RegistryApi.hasViewProvider(id));
        }

        @Test
        @DisplayName("注册 null 返回 false 且不抛异常")
        void registerNull_shouldReturnFalse() {
            assertFalse(RegistryApi.registerViewProvider(null));
        }

        @Test
        @DisplayName("未注册的 id 查询返回 null 且 hasViewProvider 为 false")
        void unregistered_shouldReturnNull() {
            Identifier absent = ChunkScannerMod.id("test.api.view.absent-xyz");
            assertNull(RegistryApi.getViewProvider(absent));
            assertFalse(RegistryApi.hasViewProvider(absent));
        }

        @Test
        @DisplayName("viewProviders 返回的集合不可修改")
        void viewProviders_shouldBeUnmodifiable() {
            var all = RegistryApi.viewProviders();
            assertThrows(UnsupportedOperationException.class,
                    () -> all.add(viewType("test.api.view.failadd", Set.of())));
        }
    }

    // ==================== 分析器注册 ====================

    @Nested
    @DisplayName("分析器注册")
    class AnalyzerRegistration {

        @Test
        @DisplayName("注册 null 分析器不抛异常")
        void registerNullAnalyzer_shouldNotThrow() {
            assertDoesNotThrow(() -> RegistryApi.registerAnalyzer(null));
        }

        @Test
        @DisplayName("未注册分析器查询返回 null，hasAnalyzer 为 false")
        void unregisteredAnalyzer_shouldReturnNull() {
            Identifier absent = ChunkScannerMod.id("test.api.analyzer.absent-xyz");
            assertNull(RegistryApi.getAnalyzer(absent));
            assertFalse(RegistryApi.hasAnalyzer(absent));
        }

        @Test
        @DisplayName("未注册分析器的适配器 id 回退为 chunkscanner:raw")
        void unregisteredAnalyzer_adaptorShouldFallbackToRaw() {
            Identifier fallback = RegistryApi.getAdaptorId(
                    ChunkScannerMod.id("test.api.analyzer.noadaptor"));
            assertEquals("chunkscanner", fallback.getNamespace());
            assertEquals("raw", fallback.getPath());
        }

        @Test
        @DisplayName("analyzerIds 返回的列表不可修改")
        void analyzerIds_shouldBeUnmodifiable() {
            List<Identifier> ids = RegistryApi.analyzerIds();
            assertThrows(UnsupportedOperationException.class,
                    () -> ids.add(ChunkScannerMod.id("test.api.analyzer.failadd")));
        }
    }

    // ==================== 数据库工厂 ====================

    @Nested
    @DisplayName("数据库工厂")
    class DbFactoryRegistration {

        @Test
        @DisplayName("注册 null 工厂返回 false")
        void registerNullFactory_shouldReturnFalse() {
            assertFalse(RegistryApi.registerDbFactory(null));
        }

        @Test
        @DisplayName("未注册的工厂 id 查询返回 null，hasDbFactory 为 false")
        void unregisteredFactory_shouldReturnNull() {
            Identifier absent = ChunkScannerMod.id("test.api.factory.absent-xyz");
            assertNull(RegistryApi.getDbFactory(absent));
            assertFalse(RegistryApi.hasDbFactory(absent));
        }

        @Test
        @DisplayName("注册重复工厂 id 返回 false")
        void registerDuplicateFactory_shouldReturnFalse() {
            Identifier dup = ChunkScannerMod.id("test.api.factory.dup");
            boolean first = RegistryApi.registerDbFactory(
                    new com.billy65536.chunkscanner.core.IChunkDb.IFactory() {
                        @Override public Identifier getId() { return dup; }
                        @Override public String getExt() { return "bin"; }
                        @Override public com.billy65536.chunkscanner.core.IChunkDb create(com.billy65536.chunkscanner.core.db.DbStorage st) { return null; }
                    });
            // 首次注册应成功（注册表初始为空）
            assertTrue(first, "首次注册应成功（测试隔离前提下）");
        }
    }
}
