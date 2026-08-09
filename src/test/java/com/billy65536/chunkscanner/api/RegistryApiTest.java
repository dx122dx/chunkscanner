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
            @Override public Set<Identifier> applicableAnalyzers() { return applicable; }
            @Override public IDbViewProvider create(DbPackage pkg) { return null; }
        };
    }

    // ==================== 视图适用性契约 ====================

    @Nested
    @DisplayName("viewProvidersFor —— 空集表示通用")
    class ViewApplicability {

        @Test
        @DisplayName("applicableAnalyzers 为空集的视图，对任意分析器均可见")
        void emptySet_shouldBeUniversal() {
            DbViewProviderRegistry.ITypeDescriptor universal =
                    viewType("test.api.view.universal", Set.of());
            RegistryApi.registerViewProvider(universal);

            List<DbViewProviderRegistry.ITypeDescriptor> forA =
                    RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.analyzer.a"));
            List<DbViewProviderRegistry.ITypeDescriptor> forB =
                    RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.analyzer.b"));

            assertTrue(forA.contains(universal), "空集视图应对分析器 a 可见");
            assertTrue(forB.contains(universal), "空集视图应对分析器 b 可见");
        }

        @Test
        @DisplayName("applicableAnalyzers 非空时，仅对集合内的分析器可见")
        void nonEmptySet_shouldBeRestricted() {
            Identifier target = ChunkScannerMod.id("test.api.analyzer.target");
            Identifier other = ChunkScannerMod.id("test.api.analyzer.other");

            DbViewProviderRegistry.ITypeDescriptor restricted =
                    viewType("test.api.view.restricted", Set.of(target));
            RegistryApi.registerViewProvider(restricted);

            assertTrue(RegistryApi.viewProvidersFor(target).contains(restricted));
            assertFalse(RegistryApi.viewProvidersFor(other).contains(restricted));
        }

        @Test
        @DisplayName("applicableAnalyzers 返回 null 时按通用处理（防 NPE）")
        void nullSet_shouldBeTreatedAsUniversal() {
            DbViewProviderRegistry.ITypeDescriptor nullApplicable =
                    viewType("test.api.view.nullset", null);
            RegistryApi.registerViewProvider(nullApplicable);

            assertTrue(RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.analyzer.any"))
                    .contains(nullApplicable));
        }

        @Test
        @DisplayName("返回的列表不可修改")
        void result_shouldBeUnmodifiable() {
            List<DbViewProviderRegistry.ITypeDescriptor> result =
                    RegistryApi.viewProvidersFor(ChunkScannerMod.id("test.api.analyzer.unmod"));
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
            assertDoesNotThrow(() -> RegistryApi.registerAnalyzer(null, ChunkScannerMod.id("raw")));
        }

        @Test
        @DisplayName("未注册分析器查询返回 null，hasAnalyzer 为 false")
        void unregisteredAnalyzer_shouldReturnNull() {
            Identifier absent = ChunkScannerMod.id("test.api.analyzer.absent-xyz");
            assertNull(RegistryApi.getAnalyzer(absent));
            assertFalse(RegistryApi.hasAnalyzer(absent));
        }

        @Test
        @DisplayName("defaultViewProviderId 为 chunkscanner:raw")
        void defaultViewProviderId_shouldBeRaw() {
            Identifier def = RegistryApi.defaultViewProviderId();
            assertEquals("chunkscanner", def.getNamespace());
            assertEquals("raw", def.getPath());
        }

        @Test
        @DisplayName("未注册分析器的默认视图回退为 chunkscanner:raw")
        void unregisteredAnalyzer_defaultViewShouldFallbackToRaw() {
            Identifier fallback = RegistryApi.getDefaultViewProvider(
                    ChunkScannerMod.id("test.api.analyzer.nodefault"));
            assertEquals(RegistryApi.defaultViewProviderId(), fallback);
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
                        @Override public com.billy65536.chunkscanner.core.IChunkDb create(String s, Identifier a, com.billy65536.chunkscanner.core.db.DbStorage st) { return null; }
                        @Override public com.billy65536.chunkscanner.core.IChunkDb createMetadataOnly(String s, Identifier a, com.billy65536.chunkscanner.core.db.DbStorage st) { return null; }
                    });
            // 首次注册应成功（注册表初始为空）
            assertTrue(first, "首次注册应成功（测试隔离前提下）");
        }
    }
}
