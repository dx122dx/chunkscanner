package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.db.DbPackage;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbViewProviderRegistry 单元测试。
 */
@DisplayName("DbViewProviderRegistry")
class DbViewProviderRegistryTest {

    // ==================== 辅助 ====================

    /** 创建一个简单 ViewType 实现用于测试。 */
    private static DbViewProviderRegistry.ITypeDescriptor createType(String id, String name) {
        return new DbViewProviderRegistry.ITypeDescriptor() {
            @Override public Identifier getId() { return ChunkScannerMod.id(id); }
            @Override public Text getName() { return Text.literal(name); }
            @Override public Text getDescription() { return Text.literal("desc: " + name); }
            @Override public Set<Identifier> applicableAnalyzers() { return Set.of(); }
            @Override public IDbViewProvider create(DbPackage pkg) { return null; }
        };
    }

    // ==================== register / get ====================

    @Nested
    @DisplayName("register / get")
    class RegisterGet {

        @Test
        @DisplayName("注册后可通过 id 获取")
        void registered_shouldBeRetrievableById() {
            DbViewProviderRegistry.ITypeDescriptor type = createType("test.getid1", "Name 1");
            DbViewProviderRegistry.register(type);

            DbViewProviderRegistry.ITypeDescriptor retrieved = DbViewProviderRegistry.get(ChunkScannerMod.id("test.getid1"));
            assertNotNull(retrieved);
            assertEquals(ChunkScannerMod.id("test.getid1"), retrieved.getId());
            assertEquals("Name 1", retrieved.getName().getString());
        }

        @Test
        @DisplayName("未注册的 id 返回 null")
        void unregisteredId_shouldReturnNull() {
            assertNull(DbViewProviderRegistry.get(ChunkScannerMod.id("nonexistent.id.xyz")));
        }

        @Test
        @DisplayName("同 id 重复注册会覆盖")
        void duplicateRegister_shouldOverwrite() {
            DbViewProviderRegistry.ITypeDescriptor first = createType("test.dup.id", "First");
            DbViewProviderRegistry.ITypeDescriptor second = createType("test.dup.id", "Second");

            DbViewProviderRegistry.register(first);
            DbViewProviderRegistry.register(second);

            DbViewProviderRegistry.ITypeDescriptor retrieved = DbViewProviderRegistry.get(ChunkScannerMod.id("test.dup.id"));
            assertEquals("Second", retrieved.getName().getString());
        }
    }

    // ==================== getAll ====================

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("getAll 返回注册的所有类型")
        void getAll_shouldReturnAllRegistered() {
            DbViewProviderRegistry.ITypeDescriptor t1 = createType("test.getall.1", "A");
            DbViewProviderRegistry.ITypeDescriptor t2 = createType("test.getall.2", "B");
            DbViewProviderRegistry.register(t1);
            DbViewProviderRegistry.register(t2);

            Collection<DbViewProviderRegistry.ITypeDescriptor> all = DbViewProviderRegistry.getAll();
            assertTrue(all.size() >= 2);
            assertTrue(all.contains(t1));
            assertTrue(all.contains(t2));
        }

        @Test
        @DisplayName("getAll 返回的集合不可修改")
        void getAll_shouldReturnUnmodifiableCollection() {
            DbViewProviderRegistry.ITypeDescriptor type = createType("test.unmod", "Test");
            DbViewProviderRegistry.register(type);

            Collection<DbViewProviderRegistry.ITypeDescriptor> all = DbViewProviderRegistry.getAll();
            assertThrows(UnsupportedOperationException.class, () -> all.add(
                    createType("should.fail", "Fail")));
        }
    }
}
