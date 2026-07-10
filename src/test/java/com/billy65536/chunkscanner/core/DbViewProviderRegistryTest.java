package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbViewProvider.Registry 单元测试。
 */
@DisplayName("DbViewProvider.Registry")
class DbViewProviderRegistryTest {

    // ==================== 辅助 ====================

    /** 创建一个简单 Type 实现用于测试。 */
    private static DbViewProvider.Type createType(String id, String name) {
        return new DbViewProvider.Type() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "desc: " + name; }
            @Override public Set<String> applicableAnalyzers() { return Set.of(); }
            @Override public DbViewProvider create(BinaryChunkDb db) { return null; }
        };
    }

    // ==================== register / get ====================

    @Nested
    @DisplayName("register / get")
    class RegisterGet {

        @Test
        @DisplayName("注册后可通过 id 获取")
        void registered_shouldBeRetrievableById() {
            DbViewProvider.Type type = createType("test.getId1", "Name 1");
            DbViewProvider.Registry.register(type);

            DbViewProvider.Type retrieved = DbViewProvider.Registry.get("test.getId1");
            assertNotNull(retrieved);
            assertEquals("test.getId1", retrieved.getId());
            assertEquals("Name 1", retrieved.getName());
        }

        @Test
        @DisplayName("未注册的 id 返回 null")
        void unregisteredId_shouldReturnNull() {
            assertNull(DbViewProvider.Registry.get("nonexistent.id.xyz"));
        }

        @Test
        @DisplayName("同 id 重复注册会覆盖")
        void duplicateRegister_shouldOverwrite() {
            DbViewProvider.Type first = createType("test.dup.id", "First");
            DbViewProvider.Type second = createType("test.dup.id", "Second");

            DbViewProvider.Registry.register(first);
            DbViewProvider.Registry.register(second);

            DbViewProvider.Type retrieved = DbViewProvider.Registry.get("test.dup.id");
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
            DbViewProvider.Type t1 = createType("test.getAll.1", "A");
            DbViewProvider.Type t2 = createType("test.getAll.2", "B");
            DbViewProvider.Registry.register(t1);
            DbViewProvider.Registry.register(t2);

            Collection<DbViewProvider.Type> all = DbViewProvider.Registry.getAll();
            assertTrue(all.size() >= 2);
            assertTrue(all.contains(t1));
            assertTrue(all.contains(t2));
        }

        @Test
        @DisplayName("getAll 返回的集合不可修改")
        void getAll_shouldReturnUnmodifiableCollection() {
            DbViewProvider.Type type = createType("test.unmod", "Test");
            DbViewProvider.Registry.register(type);

            Collection<DbViewProvider.Type> all = DbViewProvider.Registry.getAll();
            assertThrows(UnsupportedOperationException.class, () -> all.add(
                    createType("should.fail", "Fail")));
        }
    }
}
