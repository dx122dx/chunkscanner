package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.db.DbStorage;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用区块数据库接口。
 *
 * <p>设计目标：为分析器提供泛用的键值存储，不预设数据结构。
 * 键为 byte[]，值为 byte[]，存储层自行负责序列化/反序列化。
 * 内置字符串池（intern）用于高效压缩重复字符串。</p>
 *
 * <p><b>职责边界</b>：本接口<b>不触及任何文件操作</b>。目录布局、文件命名、
 * 临时文件、原子改名、元数据（scanId / analyzerId / taskConfig / 子库清单）
 * 全部由 {@link DbPackage} 管理；实现只通过 {@link DbStorage} 拿到一个
 * {@link FileChannel} 来读写自己的负载。</p>
 */
public interface IChunkDb {

    // ==================== DB 元信息 ====================

    /** 获取此数据库实例的扫描 ID（由所属 {@link DbPackage} 注入）。 */
    String getScanId();

    /** 创建此数据库的分析器 ID（由所属 {@link DbPackage} 注入）。 */
    Identifier getAnalyzerId();

    /**
     * 返回创建此数据库的 {@link IFactory#getId() 工厂标识符}。
     * 默认返回 {@code null}，表示未指定/未知类型。
     */
    default Identifier getFactoryId() { return null; }

    /**
     * 当前负载的格式版本号，由 {@link DbPackage} 记录进 metadata。
     * 默认返回 0，表示实现未做版本管理。
     */
    default int getFormatVersion() { return 0; }

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

    /** 打开数据库，通过 {@link DbStorage} 加载负载到内存。已加载时应为空操作。 */
    void open();

    /** 是否已打开。 */
    boolean isOpen();

    /** 若有未保存修改，通过 {@link DbStorage} 写回负载。 */
    void flush();

    /** 关闭数据库，释放资源（实现应先 {@link #flush()}）。 */
    void close();

    // ==================== 负载读写（由 DbPackage 驱动） ====================

    /**
     * 从通道读取全部负载内容。
     *
     * <p>通道由 {@link DbPackage} 打开并负责关闭，实现不得假设其对应任何具体路径。</p>
     */
    void readFrom(FileChannel channel) throws IOException;

    /**
     * 将全部负载内容写入通道。
     *
     * <p>通道指向 {@link DbPackage} 准备好的临时文件，写入完成后由 DbPackage
     * 负责 {@code force} 与原子改名，实现不得自行做任何文件操作。</p>
     */
    void writeTo(FileChannel channel) throws IOException;

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
        Identifier getId();

        /**
         * 数据库负载文件的扩展名（不含点号），由实现自行指定，如 {@code "bin"}。
         * {@link DbPackage} 用它拼出包内文件名，如 {@code main.bin}。
         */
        String getExt();

        /**
         * 创建数据库实例（完整模式，构造时立即从 storage 加载负载）。
         *
         * @param scanId     扫描任务 ID
         * @param analyzerId 分析器 ID
         * @param storage    持久化通道，纯内存实例传 {@link DbStorage#NONE}
         */
        IChunkDb create(String scanId, Identifier analyzerId, DbStorage storage);

        /**
         * 创建数据库实例（元数据模式，延迟加载）。
         *
         * @return 未加载负载的实例，需调用 {@link IChunkDb#open()} 后才能读取内容
         */
        IChunkDb createMetadataOnly(String scanId, Identifier analyzerId, DbStorage storage);
    }

    /** 数据库工厂全局注册表。 */
    final class FactoryRegistry {
        private static final Map<Identifier, IFactory> factories = new LinkedHashMap<>();

        private FactoryRegistry() {}

        /** 注册一个数据库工厂。 */
        public static void register(IFactory factory) {
            factories.put(factory.getId(), factory);
        }

        /** 通过 ID 获取工厂。 */
        public static IFactory get(Identifier id) {
            return factories.get(id);
        }

        /** 获取默认工厂（注册表中的第一个）。 */
        public static IFactory getDefault() {
            return factories.isEmpty() ? null : factories.values().iterator().next();
        }
    }
}
