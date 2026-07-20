package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    private static DbViewProviderRegistry.Type createType(String id, String name) {
        return new DbViewProviderRegistry.Type() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "desc: " + name; }
            @Override public Set<String> applicableAnalyzers() { return Set.of(); }
            @Override public DbViewProvider create(ChunkDb db) { return null; }
        };
    }

    // ==================== register / get ====================

    @Nested
    @DisplayName("register / get")
    class RegisterGet {

        @Test
        @DisplayName("注册后可通过 id 获取")
        void registered_shouldBeRetrievableById() {
            DbViewProviderRegistry.Type type = createType("test.getId1", "Name 1");
            DbViewProviderRegistry.register(type);

            DbViewProviderRegistry.Type retrieved = DbViewProviderRegistry.get("test.getId1");
            assertNotNull(retrieved);
            assertEquals("test.getId1", retrieved.getId());
            assertEquals("Name 1", retrieved.getName());
        }

        @Test
        @DisplayName("未注册的 id 返回 null")
        void unregisteredId_shouldReturnNull() {
            assertNull(DbViewProviderRegistry.get("nonexistent.id.xyz"));
        }

        @Test
        @DisplayName("同 id 重复注册会覆盖")
        void duplicateRegister_shouldOverwrite() {
            DbViewProviderRegistry.Type first = createType("test.dup.id", "First");
            DbViewProviderRegistry.Type second = createType("test.dup.id", "Second");

            DbViewProviderRegistry.register(first);
            DbViewProviderRegistry.register(second);

            DbViewProviderRegistry.Type retrieved = DbViewProviderRegistry.get("test.dup.id");
            assertEquals("Second", retrieved.getName());
        }
    }

    // ==================== getAll ====================

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("getAll 返回注册的所有类型")
        void getAll_shouldReturnAllRegistered() {
            DbViewProviderRegistry.Type t1 = createType("test.getAll.1", "A");
            DbViewProviderRegistry.Type t2 = createType("test.getAll.2", "B");
            DbViewProviderRegistry.register(t1);
            DbViewProviderRegistry.register(t2);

            Collection<DbViewProviderRegistry.Type> all = DbViewProviderRegistry.getAll();
            assertTrue(all.size() >= 2);
            assertTrue(all.contains(t1));
            assertTrue(all.contains(t2));
        }

        @Test
        @DisplayName("getAll 返回的集合不可修改")
        void getAll_shouldReturnUnmodifiableCollection() {
            DbViewProviderRegistry.Type type = createType("test.unmod", "Test");
            DbViewProviderRegistry.register(type);

            Collection<DbViewProviderRegistry.Type> all = DbViewProviderRegistry.getAll();
            assertThrows(UnsupportedOperationException.class, () -> all.add(
                    createType("should.fail", "Fail")));
        }
    }
}
