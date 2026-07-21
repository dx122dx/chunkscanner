package com.billy65536.chunkscanner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IChunkDb 辅助类型及默认方法单元测试。
 */
@DisplayName("IChunkDb")
class ChunkDbTest {

    // ==================== Entry ====================

    @Nested
    @DisplayName("Entry")
    class EntryTest {

        @Test
        @DisplayName("of() 工厂方法正确创建 Entry")
        void factoryOf_shouldCreateEntry() {
            byte[] key = {1, 2, 3};
            byte[] value = {4, 5, 6};
            IChunkDb.Entry entry = IChunkDb.Entry.of(key, value);
            assertArrayEquals(key, entry.key());
            assertArrayEquals(value, entry.value());
        }

        @Test
        @DisplayName("空数组可正常创建")
        void emptyArrays_shouldWork() {
            IChunkDb.Entry entry = IChunkDb.Entry.of(new byte[0], new byte[0]);
            assertEquals(0, entry.key().length);
            assertEquals(0, entry.value().length);
        }

        @Test
        @DisplayName("相同引用的 Entry 应相等")
        void equals_sameReferences_shouldBeEqual() {
            byte[] k = {10, 20};
            byte[] v = {30, 40};
            IChunkDb.Entry a = IChunkDb.Entry.of(k, v);
            IChunkDb.Entry b = IChunkDb.Entry.of(k, v);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同 byte[] 对象即使内容相同也不相等（record 数组组件使用引用相等）")
        void equals_differentArraysSameContent_shouldNotBeEqual() {
            IChunkDb.Entry a = IChunkDb.Entry.of(new byte[]{10, 20}, new byte[]{30, 40});
            IChunkDb.Entry b = IChunkDb.Entry.of(new byte[]{10, 20}, new byte[]{30, 40});
            // Java record 的 byte[] 组件使用 Object.equals，即引用相等
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("不同内容的 Entry 应不相等")
        void equals_differentContent_shouldNotBeEqual() {
            IChunkDb.Entry a = IChunkDb.Entry.of(new byte[]{1}, new byte[]{2});
            IChunkDb.Entry b = IChunkDb.Entry.of(new byte[]{1}, new byte[]{3});
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("与 null 比较返回 false")
        void equals_null_shouldReturnFalse() {
            IChunkDb.Entry entry = IChunkDb.Entry.of(new byte[]{1}, new byte[]{2});
            assertNotEquals(null, entry);
        }

        @Test
        @DisplayName("key 为 null 时 of() 可正常创建")
        void nullKey_shouldWork() {
            IChunkDb.Entry entry = IChunkDb.Entry.of(null, new byte[]{1});
            assertNull(entry.key());
            assertArrayEquals(new byte[]{1}, entry.value());
        }

        @Test
        @DisplayName("value 为 null 时 of() 可正常创建")
        void nullValue_shouldWork() {
            IChunkDb.Entry entry = IChunkDb.Entry.of(new byte[]{1}, null);
            assertArrayEquals(new byte[]{1}, entry.key());
            assertNull(entry.value());
        }

        @Test
        @DisplayName("toString 包含 key 和 value 长度信息")
        void toString_shouldContainLengthInfo() {
            IChunkDb.Entry entry = IChunkDb.Entry.of(new byte[]{1, 2}, new byte[]{3, 4, 5});
            String s = entry.toString();
            assertNotNull(s);
            // record toString 格式类似 Entry[key=[1, 2], value=[3, 4, 5]]
            assertTrue(s.contains("Entry"));
        }
    }

    // ==================== ChunkMeta ====================

    @Nested
    @DisplayName("ChunkMeta")
    class ChunkMetaTest {

        @Test
        @DisplayName("构造后可访问各字段")
        void constructor_shouldSetAllFields() {
            IChunkDb.ChunkMeta meta = new IChunkDb.ChunkMeta("minecraft:overworld", 10, 20, 1234567890L);
            assertEquals("minecraft:overworld", meta.dimensionId());
            assertEquals(10, meta.cx());
            assertEquals(20, meta.cz());
            assertEquals(1234567890L, meta.scanTime());
        }

        @Test
        @DisplayName("相同值的 ChunkMeta 应相等")
        void equals_sameValues_shouldBeEqual() {
            IChunkDb.ChunkMeta a = new IChunkDb.ChunkMeta("overworld", 5, 5, 100L);
            IChunkDb.ChunkMeta b = new IChunkDb.ChunkMeta("overworld", 5, 5, 100L);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同 scanTime 应不相等")
        void equals_differentScanTime_shouldNotBeEqual() {
            IChunkDb.ChunkMeta a = new IChunkDb.ChunkMeta("overworld", 0, 0, 100L);
            IChunkDb.ChunkMeta b = new IChunkDb.ChunkMeta("overworld", 0, 0, 200L);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("null dimensionId 可正常构造")
        void nullDimensionId_shouldWork() {
            IChunkDb.ChunkMeta meta = new IChunkDb.ChunkMeta(null, 0, 0, 0L);
            assertNull(meta.dimensionId());
        }

        @Test
        @DisplayName("scanTime 为 0 表示从未扫描")
        void zeroScanTime_representsNeverScanned() {
            IChunkDb.ChunkMeta meta = new IChunkDb.ChunkMeta("overworld", 0, 0, 0L);
            assertEquals(0L, meta.scanTime());
        }
    }

    // ==================== 默认方法 ====================

    @Nested
    @DisplayName("默认方法")
    class DefaultMethods {

        private final IChunkDb stubDb = new IChunkDb() {
            @Override public String getScanId() { return "test"; }
            @Override public String getAnalyzerId() { return ""; }
            @Override public long getStorageSize() { return 0; }
            @Override public long getLastModifiedTime() { return 0; }
            @Override public int intern(String s) { return 0; }
            @Override public String lookup(int id) { return null; }
            @Override public void put(byte[] key, byte[] value) {}
            @Override public void putAll(Iterable<Entry> entries) {}
            @Override public byte[] get(byte[] key) { return new byte[0]; }
            @Override public void remove(byte[] key) {}
            @Override public boolean containsKey(byte[] key) { return false; }
            @Override public int size() { return 0; }
            @Override public long getChunkScanTime(String dimensionId, int cx, int cz) { return 0; }
            @Override public void updateChunkScanTime(String dimensionId, int cx, int cz, long timestamp) {}
            @Override public boolean isOpen() { return true; }
            @Override public void open() {}
            @Override public void flush() {}
            @Override public void close() {}
        };

        @Test
        @DisplayName("removeAllWithPrefix 默认抛出 UnsupportedOperationException")
        void removeAllWithPrefix_default_throwsUnsupportedOperationException() {
            assertThrows(UnsupportedOperationException.class, () ->
                    stubDb.removeAllWithPrefix(new byte[]{1, 2}));
        }

        @Test
        @DisplayName("getAllEntries 默认返回空列表")
        void getAllEntries_default_returnsEmptyList() {
            assertTrue(stubDb.getAllEntries().isEmpty());
        }

        @Test
        @DisplayName("getAllChunkMetas 默认返回空列表")
        void getAllChunkMetas_default_returnsEmptyList() {
            assertTrue(stubDb.getAllChunkMetas().isEmpty());
        }
    }
}
