package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.core.IChunkDb;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbImage 单元测试。
 *
 * <p>DbImage 是不可变镜像：从 ZIP 包内读取 metadata.json 解析为 {@link DbImage.Meta}，
 * 并提供 {@code validate()} / {@code load()}。本测试：</p>
 * <ul>
 *   <li>{@code Meta.parseIdentifier} 兼容命名空间解析（契约点）；</li>
 *   <li>{@code Meta.parse} 解析新 metadata schema（scanId/analyzerId/database.type/file/export.files）；</li>
 *   <li>{@code open} 缺失 metadata 的 ZIP 应抛 IOException；</li>
 *   <li>{@code validate} 对合法导出包应通过（依赖已注册的 sign 分析器）。</li>
 * </ul>
 */
@DisplayName("DbImage")
class DbImageTest {

    @BeforeAll
    static void registerDefaultFactory() {
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());
    }

    // ==================== parseIdentifier ====================

    @Nested
    @DisplayName("parseIdentifier")
    class ParseIdentifier {

        @Test
        @DisplayName("空字符串解析为未知哨兵（ID_UNKNOWN）")
        void empty_shouldBeUnknown() {
            assertEquals(ChunkScannerMod.ID_UNKNOWN, DbImage.Meta.parseIdentifier(""));
        }

        @Test
        @DisplayName("null 解析为未知哨兵")
        void nullValue_shouldBeUnknown() {
            assertEquals(ChunkScannerMod.ID_UNKNOWN, DbImage.Meta.parseIdentifier(null));
        }

        @Test
        @DisplayName("裸字符串补默认命名空间")
        void bare_shouldPrefixModNamespace() {
            Identifier id = DbImage.Meta.parseIdentifier("sign");
            assertEquals(ChunkScannerMod.id("sign"), id);
            assertEquals("chunkscanner", id.getNamespace());
        }

        @Test
        @DisplayName("带命名空间的字符串原样解析")
        void namespaced_shouldBeKept() {
            assertEquals(new Identifier("mod", "custom"), DbImage.Meta.parseIdentifier("mod:custom"));
        }

        @Test
        @DisplayName("非法字符串回落到未知哨兵")
        void illegal_shouldBeUnknown() {
            assertEquals(ChunkScannerMod.ID_UNKNOWN, DbImage.Meta.parseIdentifier(":::"));
        }
    }

    // ==================== Meta.parse ====================

    @Nested
    @DisplayName("Meta.parse")
    class MetaParse {

        @Test
        @DisplayName("解析 scanId / analyzerId / adaptorId / database.type / database.file / export.files")
        void shouldParseNewSchema() throws IOException {
            String json = "{"
                    + "\"scanId\":\"scan-1\","
                    + "\"analyzerId\":\"chunkscanner:sign\","
                    + "\"adaptorId\":\"chunkscanner:sign\","
                    + "\"database\":{\"type\":\"chunkscanner:binary\",\"file\":\"main.dat\"},"
                    + "\"export\":{\"time\":\"2026-08-09T12:00:00Z\","
                    + "\"files\":[{\"name\":\"main.dat\",\"sha256\":\"abc123\"}]}"
                    + "}";

            DbImage.Meta meta = DbImage.Meta.parse(stringInputStream(json));

            assertEquals("scan-1", meta.scanId());
            assertEquals(new Identifier("chunkscanner", "sign"), meta.analyzerId());
            assertEquals(new Identifier("chunkscanner", "sign"), meta.adaptorId());
            assertEquals(new Identifier("chunkscanner", "binary"), meta.databaseType());
            assertEquals("main.dat", meta.mainFile());
            assertEquals("2026-08-09T12:00:00Z", meta.exportTime());
            assertEquals(1, meta.files().size());
            assertEquals("main.dat", meta.files().get(0).name());
            assertEquals("abc123", meta.files().get(0).sha256());
        }

        @Test
        @DisplayName("缺少 database 段时 type/file 为 null 但解析不报错")
        void missingDatabase_shouldYieldNulls() throws IOException {
            String json = "{\"scanId\":\"s\",\"analyzerId\":\"chunkscanner:sign\"}";
            DbImage.Meta meta = DbImage.Meta.parse(stringInputStream(json));
            assertNull(meta.databaseType());
            assertNull(meta.mainFile());
        }

        @Test
        @DisplayName("旧版导出包无 adaptorId 字段时解析为 null（还原时由分析器推导）")
        void missingAdaptorId_shouldBeNull() throws IOException {
            String json = "{\"scanId\":\"s\",\"analyzerId\":\"chunkscanner:sign\"}";
            DbImage.Meta meta = DbImage.Meta.parse(stringInputStream(json));
            assertNull(meta.adaptorId());
        }

        @Test
        @DisplayName("空字符串 analyzerId 解析为未知哨兵而非抛异常")
        void emptyAnalyzer_shouldBeUnknown() throws IOException {
            String json = "{\"scanId\":\"s\",\"analyzerId\":\"\"}";
            DbImage.Meta meta = DbImage.Meta.parse(stringInputStream(json));
            assertEquals(ChunkScannerMod.ID_UNKNOWN, meta.analyzerId());
        }

        @Test
        @DisplayName("空 JSON 对象视为非法")
        void emptyJson_shouldThrow() {
            assertThrows(IOException.class, () -> DbImage.Meta.parse(stringInputStream("")));
        }
    }

    // ==================== open / validate ====================

    @Nested
    @DisplayName("open / validate")
    class OpenValidate {

        @Test
        @DisplayName("缺失 metadata.json 的 ZIP 打开时抛 IOException")
        void missingMeta_shouldThrow(@TempDir Path dir) throws IOException {
            Path zip = dir.resolve("no-meta.zip");
            // 写一个不含 metadata.json 的空壳 ZIP
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                zos.putNextEntry(new java.util.zip.ZipEntry("stray.txt"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            assertThrows(IOException.class, () -> DbImage.open(zip));
        }

        @Test
        @DisplayName("合法导出包 open 能解析 metadata 且 load 还原 DbPackage")
        void openValidImage_shouldParseMeta(@TempDir Path dir) throws IOException {
            // 手工构造一个最小化的导出 ZIP（含 metadata.json + 主负载文件）
            Path pkgDir = dir.resolve("pkg-" + DbFileUtil.safeFilenameStem("scan-1"));
            Files.createDirectories(pkgDir);
            Files.write(pkgDir.resolve("main.bin"), "payload".getBytes(StandardCharsets.UTF_8));
            String meta = "{"
                    + "\"scanId\":\"scan-1\","
                    + "\"analyzerId\":\"chunkscanner:sign\","
                    + "\"database\":{\"type\":\"chunkscanner:binary\",\"file\":\"main.bin\"},"
                    + "\"export\":{\"time\":\"2026-08-09T12:00:00Z\","
                    + "\"files\":[{\"name\":\"main.bin\",\"sha256\":\"abc123\"}]}"
                    + "}";
            Files.write(pkgDir.resolve("metadata.json"), meta.getBytes(StandardCharsets.UTF_8));

            Path zip = dir.resolve("out.zip");
            DbExportUtil.exportRawZip(DbPackage.open(pkgDir), zip);

            DbImage image = DbImage.open(zip);
            assertEquals("scan-1", image.meta().scanId());
            assertEquals(new Identifier("chunkscanner", "sign"), image.meta().analyzerId());

            // load 应还原一个可读取的 DbPackage
            DbPackage loaded = image.load(dir.resolve("loaded"), false);
            assertNotNull(loaded);
            assertEquals("scan-1", loaded.getScanId());
            loaded.close();
        }
    }

    // ==================== 工具 ====================

    private static InputStream stringInputStream(String s) {
        return new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
