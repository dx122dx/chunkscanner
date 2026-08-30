package com.billy65536.chunkscanner.screen;

import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.gui.GuiUtil;
import com.billy65536.infrastructure.core.gui.filelist.FileMeta;
import com.billy65536.infrastructure.core.gui.filelist.IFileMetaParser;

/**
 * 将 DB 包摘要 {@link DbPackage.Info} 解析为文件列表行元数据。
 *
 * <p>{@link FileMeta#source()} 保留原始 {@link DbPackage.Info}，
 * 供文件列表的 [↺]/[✕] 操作列与悬停 tooltip 取回业务数据。</p>
 */
public final class DbFileMetaParser implements IFileMetaParser<DbPackage.Info> {

    public static final DbFileMetaParser INSTANCE = new DbFileMetaParser();

    private DbFileMetaParser() {
    }

    @Override
    public FileMeta parse(DbPackage.Info info) {
        return new FileMeta(info.scanId(), info.size(), GuiUtil.formatSize(info.size()), info);
    }
}
