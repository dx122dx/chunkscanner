package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.config.TaskConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用区块数据库接口。
 *
 * 设计目标：为分析器提供泛用的键值存储，不预设数据结构。
 * 每个 scanId 创建一个独立数据库实例，键为 byte[]，值为 byte[]。
 * 存储层自行负责序列化/反序列化，分析器无需关心。
 *
 * 内置字符串池（intern）用于高效压缩重复字符串。
 */
public interface IChunkDb {

    // ==================== DB 元信息 ====================

    /** 获取此数据库实例的扫描 ID。 */
    String getScanId();
    
    /** 创建此数据库的分析器 ID。 */
    String getAnalyzerId();

    /** 文件大小（字节）。 */
    long getStorageSize();

    /** 最后修改时间戳。 */
    long getLastModifiedTime();

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

    /** 打开数据库，加载数据到内存。已有实现可留空。 */
    void open();

    /** 是否已打开。 */
    boolean isOpen();

    /** 将内存数据刷写到磁盘。 */
    void flush();

    /** 关闭数据库，释放资源。 */
    void close();

    // ==================== 子数据库 ====================

    /**
     * 获取或创建子数据库。
     *
     * <p>子数据库用于存储与主数据分离的附加数据（如聊天增强数据），
     * 使得主数据库在重访区块时可以被安全清除而不丢失增强数据。</p>
     *
     * <p>id=0 返回自身，id>0 返回独立存储的子数据库。
     * 子数据库拥有独立的字符串池和 KV 存储，文件独立。
     * 注意：父子数据库之间字符串 ID 不通用，intern/lookup 必须在同一实例内配对使用。</p>
     *
     * @param id 子数据库标识（非负整数，0 = 自身）
     * @return 子数据库实例
     * @throws UnsupportedOperationException 如果实现不支持子数据库
     */
    default IChunkDb getSubDb(int id) {
        if (id == 0) return this;
        throw new UnsupportedOperationException("Sub-database not supported");
    }

    // ==================== 任务配置持久化 ====================

    /**
     * 获取存储在数据库中的任务配置。
     * @return 任务配置，如果未存储则返回 null
     */
    default TaskConfig getTaskConfig() { return null; }

    /**
     * 将任务配置序列化并存储到数据库。
     * @param config 任务配置，null 表示清除已有配置
     */
    default void setTaskConfig(TaskConfig config) {}

    // ==================== 辅助类型 ====================

    /** 键值对条目。 */
    record Entry(byte[] key, byte[] value) {
        public static Entry of(byte[] key, byte[] value) {
            return new Entry(key, value);
        }
    }

    /** Chunk 扫描记录。 */
    record ChunkMeta(String dimensionId, int cx, int cz, long scanTime) {}

    // ==================== 数据库工厂 ====================

    /**
     * 数据库工厂接口。
     *
     * <p>每个数据库实现通过 Factory 注册到 {@link FactoryRegistry}，
     * 允许模组扩展替换底层存储引擎。</p>
     */
    interface IFactory {
        /** 工厂唯一标识符。 */
        String getId();

        /**
         * 数据库文件扩展标识符，由实现自行指定。
         * 推荐使用 id + 版本号形式，如: “bin4”
         */
        String getExt();

        /**
         * 创建数据库实例（完整模式，构造时立即加载数据）。
         *
         * @param scanId        扫描任务 ID
         * @param analyzerId  分析器 ID
         * @param dbDir         数据库文件存储目录
         * @return 新的 IChunkDb 实例
         */
        IChunkDb create(String scanId, String analyzerId, Path dbDir);

        /**
         * 创建数据库实例（元数据模式，延迟加载）。
         *
         * @param scanId        扫描任务 ID
         * @param analyzerId  分析器 ID
         * @param dbDir         数据库文件存储目录
         * @return 新的 IChunkDb 实例（未加载数据，需调用 open()）
         */
        IChunkDb createMetadataOnly(String scanId, String analyzerId, Path dbDir);
    }

    /** 数据库工厂全局注册表。 */
    final class FactoryRegistry {
        private static final Map<String, IFactory> factories = new LinkedHashMap<>();

        private FactoryRegistry() {}

        /** 注册一个数据库工厂。 */
        public static void register(IFactory factory) {
            factories.put(factory.getId(), factory);
        }

        /** 通过 ID 获取工厂。 */
        public static IFactory get(String id) {
            return factories.get(id);
        }

        /** 获取默认工厂（注册表中的第一个）。 */
        public static IFactory getDefault() {
            return factories.isEmpty() ? null : factories.values().iterator().next();
        }
    }
}
