package com.billy65536.chunkscanner.core;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.db.DbPackage;

import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 内置的原始（raw）数据库适配器，对应 adaptorId {@code chunkscanner:raw}。
 *
 * <p>直接透出底层 {@link IChunkDb} 的全部原始条目与 chunk 元数据，不做任何结构化解析。
 * 它是所有数据库包的<b>兜底适配器</b>：当包声明的 adaptorId 未注册时，
 * {@link DbPackage} 会回退到本适配器。</p>
 */
public final class RawDbAdaptor implements IDbAdaptor {

    /** raw 适配器的唯一标识符。 */
    public static final Identifier ID = ChunkScannerMod.id("raw");

    private final DbPackage pkg;
    private final IChunkDb db;

    public RawDbAdaptor(DbPackage pkg) {
        this.pkg = pkg;
        this.db = pkg.main();
    }

    @Override
    public DbPackage pkg() {
        return pkg;
    }

    /** 返回底层数据库的全部原始 KV 条目。 */
    public List<IChunkDb.Entry> getAllEntries() {
        return db.getAllEntries();
    }

    /** 返回底层数据库的全部 chunk 扫描元数据。 */
    public List<IChunkDb.ChunkMeta> getAllChunkMetas() {
        return db.getAllChunkMetas();
    }

    /** raw 适配器工厂，注册为默认适配器。 */
    public static final class Factory implements IDbAdaptor.IFactory {
        @Override
        public Identifier getId() {
            return ID;
        }

        @Override
        public IDbAdaptor create(DbPackage pkg) {
            return new RawDbAdaptor(pkg);
        }
    }
}
