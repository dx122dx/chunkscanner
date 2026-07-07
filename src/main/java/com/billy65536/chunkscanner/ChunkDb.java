package com.billy65536.chunkscanner;

import java.util.Collections;
import java.util.List;

/**
 * 通用区块数据库接口。
 *
 * 设计目标：为分析器提供泛用的键值存储，不预设数据结构。
 * 每个 scanId 创建一个独立数据库实例，键为 byte[]，值为 byte[]。
 * 存储层自行负责序列化/反序列化，分析器无需关心。
 *
 * 内置字符串池（intern）用于高效压缩重复字符串。
 */
public interface ChunkDb {

    /** 获取此数据库实例的扫描 ID。 */
    String getScanId();

    // ==================== 字符串池 ====================

    /** 将字符串注册到全局池，返回唯一整数 ID。空串始终为 0。 */
    int intern(String s);

    /** 通过 ID 获取字符串。 */
    String lookup(int id);

    // ==================== 通用键值操作 ====================

    /** 存储键值对。如果键已存在则覆盖。 */
    void put(byte[] key, byte[] value);

    /** 批量存储键值对。 */
    void putAll(Iterable<Entry> entries);

    /** 获取键对应的值，不存在返回 null。 */
    byte[] get(byte[] key);

    /** 删除键。 */
    void remove(byte[] key);

    /**
     * 删除所有键以指定前缀开头的条目，返回删除数量。
     * 实现可选；默认抛出 UnsupportedOperationException。
     */
    default int removeAllWithPrefix(byte[] prefix) {
        throw new UnsupportedOperationException("removeAllWithPrefix not supported");
    }

    /** 键是否存在。 */
    boolean containsKey(byte[] key);

    /** 数据库中键值对总数。 */
    int size();

    /** 返回所有 KV 条目的快照，用于 GUI 浏览等只读场景。 */
    default List<Entry> getAllEntries() {
        return Collections.emptyList();
    }

    /** 返回所有 chunk 扫描记录的快照。 */
    default List<ChunkMeta> getAllChunkMetas() {
        return Collections.emptyList();
    }

    // ==================== Chunk 元数据 ====================

    /** 获取 chunk 上次扫描时间戳（毫秒），0 表示从未扫描。 */
    long getChunkScanTime(String dimensionId, int cx, int cz);

    /** 更新 chunk 扫描时间戳。 */
    void updateChunkScanTime(String dimensionId, int cx, int cz, long timestamp);

    // ==================== 生命周期 ====================

    /** 将内存数据刷写到磁盘。 */
    void flush();

    /** 关闭数据库，释放资源。 */
    void close();

    // ==================== 辅助类型 ====================

    /** 键值对条目。 */
    record Entry(byte[] key, byte[] value) {
        public static Entry of(byte[] key, byte[] value) {
            return new Entry(key, value);
        }
    }

    /** Chunk 扫描记录。 */
    record ChunkMeta(String dimensionId, int cx, int cz, long scanTime) {}
}
