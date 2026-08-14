package com.billy65536.chunkscanner.components.analyzer;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.components.view_provider.QShopFilter;
import com.billy65536.chunkscanner.components.view_provider.QShopFilterConfig;
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.RawDbAdaptor;
import com.billy65536.chunkscanner.core.db.DbExportUtil;
import com.billy65536.chunkscanner.core.db.DbImage;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.infrastructure.util.reflect.FlatConfigs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归测试：/cs db filtercopy（带过滤）生成的目标库，经「导出 ZIP → qab 加载」后
 * 保留记录的增强 itemId 必须完整，否则 qab 无法 plan。
 */
@DisplayName("QShopDbAdapter.filterInPlace 全链路")
class QShopFilterCopyRegressionTest {

    @BeforeAll
    static void registerFactories() {
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());
        IDbAdaptor.FactoryRegistry.register(new RawDbAdaptor.Factory());
        IDbAdaptor.FactoryRegistry.register(new QShopDbAdapter.Factory());
    }

    @Test
    @DisplayName("mode=1 过滤后导出/加载：只剩 sell 且增强 itemId 完整")
    void filterCopyMode1_preservesItemIdThroughExportAndLoad(@TempDir Path tmp) throws Exception {
        Path parent = tmp.resolve("dbs");
        Files.createDirectories(parent);

        // 1) 源包 A：一条 sell + 一条 buy，均带增强数据
        DbPackage pkgA = DbPackage.create(parent, "A",
                ChunkScannerMod.id("qshop"), QShopDbAdapter.id());
        try (pkgA) {
            QShopDbAdapter a = pkgA.getAdaptor(QShopDbAdapter.class);
            a.addRecord("minecraft:overworld", 0, 0, 1, 64, 3, "billy",
                    QShopContract.MODE_SELL, 64, "钻石", 500, "minecraft:diamond", 1000L);
            a.enhanceRecord("minecraft:overworld", 0, 0, 1, 64, 3, "minecraft:diamond",
                    false, false, "{}");
            a.addRecord("minecraft:overworld", 0, 0, 10, 64, 3, "billy",
                    QShopContract.MODE_BUY, 32, "绿宝石", 300, "minecraft:emerald", 2000L);
            a.enhanceRecord("minecraft:overworld", 0, 0, 10, 64, 3, "minecraft:emerald",
                    false, false, "{}");
            pkgA.flush();
        }

        // 2) 复制 A -> B（filtercopy 的复制环节）
        try (DbPackage src = DbPackage.open(DbPackage.dirFor(parent, "A"))) {
            src.copyTo(parent, "B");
        }

        // 3) 打开 B 执行 mode=1（保留出售）过滤
        QShopFilterConfig cfg = FlatConfigs.createFrom("mode=1", QShopFilterConfig.class);
        QShopFilter filter = new QShopFilter(cfg);
        int removed;
        try (DbPackage pkgB = DbPackage.open(DbPackage.dirFor(parent, "B"))) {
            removed = pkgB.getAdaptor(QShopDbAdapter.class).filterInPlace(filter);
        }
        assertEquals(1, removed, "mode=1 应删除 1 条 buy 记录");

        // 4) 导出 B 为 ZIP
        Path zip = tmp.resolve("B.zip");
        try (DbPackage pkgB = DbPackage.open(DbPackage.dirFor(parent, "B"))) {
            DbExportUtil.exportRawZip(pkgB, zip);
        }

        // 5) 模拟 qab：加载 ZIP 还原并读取
        DbImage img = DbImage.open(zip);
        Path loadDir = tmp.resolve("load");
        Files.createDirectories(loadDir);
        try (DbPackage loaded = img.load(loadDir, false)) {
            List<QShopDbAdapter.Record> records = loaded.getAdaptor(QShopDbAdapter.class).getAllRecords();
            assertEquals(1, records.size(), "B 应只剩 1 条 sell 记录");
            QShopDbAdapter.Record r = records.get(0);
            assertEquals(QShopContract.MODE_SELL, r.mode(), "保留记录应为 sell");
            assertEquals("minecraft:diamond", r.itemId(),
                    "增强 itemId 必须在 filtercopy+导出+加载后保留");
        }
    }

    @Test
    @DisplayName("全 sell 非增强记录 + mode=1（无删除）：itemId 经 filtercopy 后必须保留")
    void filterCopyAllSellNonEnhanced_preservesItemId(@TempDir Path tmp) throws Exception {
        Path parent = tmp.resolve("dbs");
        Files.createDirectories(parent);

        // 1) 源包 A：两条 sell、无增强数据（analyzer 典型产物：itemId 走主库 pool + FLAG_ID_RECOVERED）
        DbPackage pkgA = DbPackage.create(parent, "A",
                ChunkScannerMod.id("qshop"), QShopDbAdapter.id());
        try (pkgA) {
            QShopDbAdapter a = pkgA.getAdaptor(QShopDbAdapter.class);
            a.addRecord("minecraft:overworld", 0, 0, 1, 64, 3, "billy",
                    QShopContract.MODE_SELL, 64, "钻石", 500, "minecraft:diamond", 1000L);
            a.addRecord("minecraft:overworld", 0, 0, 10, 64, 3, "billy",
                    QShopContract.MODE_SELL, 32, "绿宝石", 300, "minecraft:emerald", 2000L);
            pkgA.flush();
        }

        // 2) 复制 A -> B（filtercopy 的复制环节）
        try (DbPackage src = DbPackage.open(DbPackage.dirFor(parent, "A"))) {
            src.copyTo(parent, "B");
        }

        // 3) 打开 B 执行 mode=1（保留出售）——全部 sell，不应删除任何记录
        QShopFilterConfig cfg = FlatConfigs.createFrom("mode=1", QShopFilterConfig.class);
        QShopFilter filter = new QShopFilter(cfg);
        int removed;
        try (DbPackage pkgB = DbPackage.open(DbPackage.dirFor(parent, "B"))) {
            removed = pkgB.getAdaptor(QShopDbAdapter.class).filterInPlace(filter);
        }
        assertEquals(0, removed, "全 sell 记录 mode=1 不应删除任何记录");

        // 4) 导出 B 为 ZIP
        Path zip = tmp.resolve("B.zip");
        try (DbPackage pkgB = DbPackage.open(DbPackage.dirFor(parent, "B"))) {
            DbExportUtil.exportRawZip(pkgB, zip);
        }

        // 5) 模拟 qab：加载 ZIP 还原并读取
        DbImage img = DbImage.open(zip);
        Path loadDir = tmp.resolve("load");
        Files.createDirectories(loadDir);
        try (DbPackage loaded = img.load(loadDir, false)) {
            List<QShopDbAdapter.Record> records = loaded.getAdaptor(QShopDbAdapter.class).getAllRecords();
            assertEquals(2, records.size(), "B 应保留 2 条 sell 记录");
            for (QShopDbAdapter.Record r : records) {
                assertEquals(QShopContract.MODE_SELL, r.mode());
                if ("钻石".equals(r.itemName())) {
                    assertEquals("minecraft:diamond", r.itemId(),
                            "FLAG_ID_RECOVERED 恢复的 itemId 必须在 filtercopy 后保留");
                } else {
                    assertEquals("minecraft:emerald", r.itemId());
                }
            }
        }
    }

    @Test
    @DisplayName("普通 copy（对照）导出/加载：sell + buy 都保留且 itemId 完整")
    void plainCopy_preservesItemId(@TempDir Path tmp) throws Exception {
        Path parent = tmp.resolve("dbs");
        Files.createDirectories(parent);

        DbPackage pkgA = DbPackage.create(parent, "A",
                ChunkScannerMod.id("qshop"), QShopDbAdapter.id());
        try (pkgA) {
            QShopDbAdapter a = pkgA.getAdaptor(QShopDbAdapter.class);
            a.addRecord("minecraft:overworld", 0, 0, 1, 64, 3, "billy",
                    QShopContract.MODE_SELL, 64, "钻石", 500, "minecraft:diamond", 1000L);
            a.enhanceRecord("minecraft:overworld", 0, 0, 1, 64, 3, "minecraft:diamond",
                    false, false, "{}");
            a.addRecord("minecraft:overworld", 0, 0, 10, 64, 3, "billy",
                    QShopContract.MODE_BUY, 32, "绿宝石", 300, "minecraft:emerald", 2000L);
            a.enhanceRecord("minecraft:overworld", 0, 0, 10, 64, 3, "minecraft:emerald",
                    false, false, "{}");
            pkgA.flush();
        }

        try (DbPackage src = DbPackage.open(DbPackage.dirFor(parent, "A"))) {
            src.copyTo(parent, "C");
        }

        Path zip = tmp.resolve("C.zip");
        try (DbPackage pkgC = DbPackage.open(DbPackage.dirFor(parent, "C"))) {
            DbExportUtil.exportRawZip(pkgC, zip);
        }

        DbImage img = DbImage.open(zip);
        Path loadDir = tmp.resolve("load-c");
        Files.createDirectories(loadDir);
        try (DbPackage loaded = img.load(loadDir, false)) {
            List<QShopDbAdapter.Record> records = loaded.getAdaptor(QShopDbAdapter.class).getAllRecords();
            assertEquals(2, records.size(), "普通 copy 应保留 2 条记录");
            for (QShopDbAdapter.Record r : records) {
                if (r.mode() == QShopContract.MODE_SELL) {
                    assertEquals("minecraft:diamond", r.itemId());
                } else {
                    assertEquals("minecraft:emerald", r.itemId());
                }
            }
        }
    }
}
