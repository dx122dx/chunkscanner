package com.billy65536.chunkscanner.components.view_provider;

import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.core.ChunkDb;
import com.billy65536.chunkscanner.core.DbViewProvider;
import com.billy65536.chunkscanner.core.DbViewProviderRegistry;

/**
 * 原始（Raw）数据库视图提供者。
 *
 * <p>封装 BinaryChunkDb，将其作为原始的 DbViewProvider 暴露给 DatabaseScreen。
 * 直接显示原始字节的键值对，不进行结构化解析。</p>
 *
 * <p>适用于所有分析器（applicableAnalyzers 为空集）。</p>
 */
public class RawDbProvider implements DbViewProvider {

    private final BinaryChunkDb delegate;

    public RawDbProvider(BinaryChunkDb delegate) {
        this.delegate = delegate;
    }

    // ==================== 委托方法 ====================

    @Override
    public Path filePath() {
        return delegate.filePath();
    }

    @Override
    public String analyzerName() {
        return delegate.analyzerName();
    }

    @Override
    public String scanId() {
        return delegate.getScanId();
    }

    @Override
    public long fileSize() {
        return delegate.fileSize();
    }

    @Override
    public long lastModified() {
        return delegate.lastModified();
    }

    @Override
    public void open() {
        delegate.open();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public int kvCount() {
        return delegate.kvCount();
    }

    @Override
    public int chunkMetaCount() {
        return delegate.chunkMetaCount();
    }

    @Override
    public List<ChunkDb.Entry> getAllEntries() {
        return delegate.getAllEntries();
    }

    @Override
    public List<ChunkDb.ChunkMeta> getAllChunkMetas() {
        return delegate.getAllChunkMetas();
    }

    // ==================== 类型描述符 ====================

    /** Raw 视图类型描述符：直接显示原始字节。适用于所有分析器。 */
    public static class Type implements DbViewProviderRegistry.Type {
        @Override
        public String getId() { return "raw"; }

        @Override
        public String getName() {
            return Text.translatable("chunkscanner.dbview.raw.name").getString();
        }

        @Override
        public String getDescription() {
            return Text.translatable("chunkscanner.dbview.raw.desc").getString();
        }

        @Override
        public Set<String> applicableAnalyzers() {
            return Collections.emptySet(); // 适用于所有
        }

        @Override
        public DbViewProvider create(ChunkDb db) {
            if (!(db instanceof BinaryChunkDb bdb)) return null;
            return new RawDbProvider(bdb);
        }
    }
}
