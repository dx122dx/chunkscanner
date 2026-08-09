package com.billy65536.chunkscanner.core.db;

import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * 数据库负载的持久化通道。
 *
 * <p>这是 {@link com.billy65536.chunkscanner.core.IChunkDb} 与文件系统之间的唯一边界：
 * 数据库实现只拿到一个 {@link FileChannel}，不感知路径、临时文件、原子改名与并发加锁——
 * 这些全部由 {@link DbPackage} 收口。</p>
 *
 * <p><b>写时安全</b>：{@link #write} 的实现必须遵循「写临时文件 → {@code force} → 原子改名」
 * 协议，保证进程崩溃或磁盘写失败时原文件仍然完好。</p>
 */
public interface DbStorage {

    /** 通道任务：拿到通道后完成一次完整的读或写。 */
    @FunctionalInterface
    interface ChannelTask {
        void accept(FileChannel channel) throws IOException;
    }

    /**
     * 以只读方式打开负载并交给 {@code task}。
     *
     * @return {@code false} 表示尚无持久化内容，{@code task} 不会被调用
     */
    boolean read(ChannelTask task) throws IOException;

    /**
     * 全量重写负载。
     *
     * <p>{@code task} 拿到的是一个空的临时通道；写入完成后由实现负责
     * {@code force(true)} 并原子替换正式文件。</p>
     */
    void write(ChannelTask task) throws IOException;

    /** 负载字节数；不存在时返回 0。 */
    long size();

    /** 负载最后修改时间戳（毫秒）；不存在时返回 0。 */
    long lastModified();

    /** 纯内存存储：不持久化任何内容，供测试与临时数据库使用。 */
    DbStorage NONE = new DbStorage() {
        @Override
        public boolean read(ChannelTask task) {
            return false;
        }

        @Override
        public void write(ChannelTask task) {
            // 无持久化目标，静默丢弃
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public long lastModified() {
            return 0;
        }

        @Override
        public String toString() {
            return "DbStorage.NONE";
        }
    };
}
