package com.billy65536.chunkscanner.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.util.Identifier;

import com.billy65536.chunkscanner.ChunkScannerMod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbPackage 单元测试。
 *
 * <p>重点锁定 {@code Meta.parse} 的 analyzerId 解析契约（兼容旧导出包）：</p>
 * <ul>
 *   <li>裸字符串（无冒号）走 {@code ChunkScannerMod.id}，
 *       <b>不能用 {@code Identifier.tryParse}</b> —— 后者会返回 {@code minecraft:} 前缀</li>
 *   <li>空字符串归一为 {@link ChunkScannerMod#ID_UNKNOWN}，
 *       <b>不能用 {@code id("")}</b> —— 那会产生非空的 {@code chunkscanner:} 绕过判空守卫</li>
 * </ul>
 */
@DisplayName("DbPackage")
class DbPackageTest {

    private static Reflected parseMeta(String json) throws IOException {
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return new Reflected(DbPackage.Meta.parse(in));
        }
    }

    /** 薄包装，只为让断言读起来更直白。 */
    private record Reflected(DbPackage.Meta meta) {}

    /** 写一个只含 metadata.json 的最小 ZIP。 */
    private static Path writeZip(Path dir, String name, String metadataJson) throws IOException {
        Path zip = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            if (metadataJson != null) {
                zos.putNextEntry(new ZipEntry("metadata.json"));
                zos.write(metadataJson.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            } else {
                zos.putNextEntry(new ZipEntry("placeholder.txt"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    // ==================== analyzerId 解析契约 ====================

    @Nested
    @DisplayName("Meta.parse — Identifier 解析")
    class IdentifierParsing {

        @Test
        @DisplayName("带命名空间的 id 原样解析")
        void namespacedId_shouldParseAsIs() throws IOException {
            var m = parseMeta("{\"scannerId\":\"chunkscanner:qshop\"}").meta();

            assertEquals(new Identifier("chunkscanner", "qshop"), m.analyzerId());
        }

        @Test
        @DisplayName("外部命名空间的 id 保留原命名空间")
        void foreignNamespace_shouldBePreserved() throws IOException {
            var m = parseMeta("{\"scannerId\":\"qab:custom\"}").meta();

            assertEquals(new Identifier("qab", "custom"), m.analyzerId());
        }

        @Test
        @DisplayName("裸字符串（无冒号）回退为 chunkscanner 命名空间，而非 minecraft")
        void bareString_shouldFallBackToModNamespace() throws IOException {
            var m = parseMeta("{\"scannerId\":\"qshop\"}").meta();

            assertEquals(new Identifier(ChunkScannerMod.MOD_ID, "qshop"), m.analyzerId());
            assertNotEquals(new Identifier("minecraft", "qshop"), m.analyzerId(),
                    "Identifier.tryParse(\"qshop\") 会返回 minecraft:qshop，必须先做冒号检测");
        }

        @Test
        @DisplayName("空字符串归一为 ID_UNKNOWN 哨兵")
        void emptyString_shouldBecomeUnknownSentinel() throws IOException {
            var m = parseMeta("{\"scannerId\":\"\"}").meta();

            assertEquals(ChunkScannerMod.ID_UNKNOWN, m.analyzerId());
            assertFalse(m.analyzerId().getPath().isEmpty(),
                    "不能退化为 id(\"\") 产生的 chunkscanner:，那会绕过所有判空守卫");
        }

        @Test
        @DisplayName("非法字符的 id 归一为 ID_UNKNOWN 而非抛异常")
        void illegalId_shouldBecomeUnknownSentinel() throws IOException {
            var m = parseMeta("{\"scannerId\":\"BadUpperCase:X\"}").meta();

            assertEquals(ChunkScannerMod.ID_UNKNOWN, m.analyzerId(),
                    "大写字母是非法 path，tryParse 返回 null，应回退哨兵");
        }

        @Test
        @DisplayName("缺失 scannerId 时 analyzerId 为 null（与空串区分）")
        void missingScannerId_shouldBeNull() throws IOException {
            var m = parseMeta("{\"databaseName\":\"scan-1\"}").meta();

            assertNull(m.analyzerId(), "字段缺失与字段为空串是两种不同情况");
        }

        @Test
        @DisplayName("JSON null 的 scannerId 视为缺失")
        void jsonNullScannerId_shouldBeNull() throws IOException {
            var m = parseMeta("{\"scannerId\":null}").meta();

            assertNull(m.analyzerId());
        }

        @Test
        @DisplayName("databaseType 走同一套解析规则")
        void databaseType_shouldUseSameRules() throws IOException {
            assertEquals(new Identifier(ChunkScannerMod.MOD_ID, "binary"),
                    parseMeta("{\"databaseType\":\"binary\"}").meta().databaseType());
            assertEquals(new Identifier("chunkscanner", "binary"),
                    parseMeta("{\"databaseType\":\"chunkscanner:binary\"}").meta().databaseType());
            assertEquals(ChunkScannerMod.ID_UNKNOWN,
                    parseMeta("{\"databaseType\":\"\"}").meta().databaseType());
            assertNull(parseMeta("{}").meta().databaseType());
        }
    }

    // ==================== Meta 其余字段 ====================

    @Nested
    @DisplayName("Meta.parse — 字段与容错")
    class MetaFields {

        @Test
        @DisplayName("完整 metadata 全字段解析")
        void fullMetadata_shouldParseAllFields() throws IOException {
            var m = parseMeta("""
                    {
                      "exportTime": "2026-08-05T12:00:00+08:00",
                      "databaseName": "scan-42",
                      "scannerId": "chunkscanner:qshop",
                      "databaseType": "chunkscanner:binary",
                      "mainFile": "chunkscanner_abc.dat",
                      "files": [
                        {"name": "chunkscanner_abc.dat", "sha256": "aa"},
                        {"name": "chunkscanner_abc.idx", "sha256": "bb"}
                      ]
                    }
                    """).meta();

            assertEquals("2026-08-05T12:00:00+08:00", m.exportTime());
            assertEquals("scan-42", m.databaseName());
            assertEquals("chunkscanner_abc.dat", m.mainFile());
            assertEquals(2, m.files().size());
            assertEquals("chunkscanner_abc.dat", m.files().get(0).name());
            assertEquals("bb", m.files().get(1).sha256());
        }

        @Test
        @DisplayName("缺失 files 时返回空列表而非 null")
        void missingFiles_shouldYieldEmptyList() throws IOException {
            var m = parseMeta("{\"databaseName\":\"s\"}").meta();

            assertNotNull(m.files());
            assertTrue(m.files().isEmpty());
        }

        @Test
        @DisplayName("files 非数组时返回空列表")
        void nonArrayFiles_shouldYieldEmptyList() throws IOException {
            var m = parseMeta("{\"files\":\"not-an-array\"}").meta();

            assertTrue(m.files().isEmpty());
        }

        @Test
        @DisplayName("空 JSON 对象可解析，字段全为 null")
        void emptyObject_shouldParseWithNulls() throws IOException {
            var m = parseMeta("{}").meta();

            assertNull(m.databaseName());
            assertNull(m.analyzerId());
            assertNull(m.mainFile());
            assertTrue(m.files().isEmpty());
        }

        @Test
        @DisplayName("空内容的 metadata 抛 IOException")
        void emptyContent_shouldThrow() {
            assertThrows(IOException.class, () -> parseMeta(""));
        }
    }

    // ==================== open ====================

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("文件不存在抛 IOException")
        void missingFile_shouldThrow(@TempDir Path dir) {
            IOException e = assertThrows(IOException.class,
                    () -> DbPackage.open(dir.resolve("nope.zip")));
            assertTrue(e.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("ZIP 中缺少 metadata.json 抛 IOException")
        void missingMetadataEntry_shouldThrow(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "no-meta.zip", null);

            IOException e = assertThrows(IOException.class, () -> DbPackage.open(zip));
            assertTrue(e.getMessage().contains("metadata.json"));
        }

        @Test
        @DisplayName("非 ZIP 文件抛 IOException")
        void notAZip_shouldThrow(@TempDir Path dir) throws IOException {
            Path fake = dir.resolve("fake.zip");
            Files.writeString(fake, "this is definitely not a zip archive");

            assertThrows(IOException.class, () -> DbPackage.open(fake));
        }

        @Test
        @DisplayName("合法包可打开并读到 meta")
        void validPackage_shouldExposeMeta(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "ok.zip",
                    "{\"databaseName\":\"scan-1\",\"scannerId\":\"chunkscanner:qshop\"}");

            DbPackage pkg = DbPackage.open(zip);

            assertNotNull(pkg.meta());
            assertEquals("scan-1", pkg.meta().databaseName());
            assertEquals(new Identifier("chunkscanner", "qshop"), pkg.meta().analyzerId());
        }
    }

    // ==================== validate ====================

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("analyzerId 缺失记为错误")
        void missingAnalyzerId_shouldBeError(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "no-analyzer.zip", "{\"databaseName\":\"s\"}");

            DbValidationResult r = DbPackage.open(zip).validate();

            assertFalse(r.valid());
            assertTrue(r.errors().stream().anyMatch(e -> e.contains("scannerId")));
        }

        @Test
        @DisplayName("mainFile 缺失记为错误")
        void missingMainFile_shouldBeError(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "no-main.zip",
                    "{\"databaseName\":\"s\",\"scannerId\":\"chunkscanner:qshop\"}");

            DbValidationResult r = DbPackage.open(zip).validate();

            assertFalse(r.valid());
            assertTrue(r.errors().stream().anyMatch(e -> e.contains("mainFile")));
        }

        @Test
        @DisplayName("mainFile 未出现在文件清单中记为错误")
        void mainFileNotInFileList_shouldBeError(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "main-missing.zip", """
                    {"databaseName":"s","scannerId":"chunkscanner:qshop",
                     "mainFile":"absent.dat","files":[]}
                    """);

            DbValidationResult r = DbPackage.open(zip).validate();

            assertFalse(r.valid());
            assertTrue(r.errors().stream().anyMatch(e -> e.contains("absent.dat")));
        }

        @Test
        @DisplayName("清单声明的文件在包内缺失记为错误")
        void declaredFileMissingInZip_shouldBeError(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "file-missing.zip", """
                    {"databaseName":"s","scannerId":"chunkscanner:qshop",
                     "mainFile":"a.dat","files":[{"name":"a.dat","sha256":"00"}]}
                    """);

            DbValidationResult r = DbPackage.open(zip).validate();

            assertFalse(r.valid());
            assertTrue(r.errors().stream().anyMatch(e -> e.contains("Package file missing")));
        }

        @Test
        @DisplayName("databaseType 缺失记为警告而非错误")
        void missingDatabaseType_shouldBeWarning(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "no-type.zip",
                    "{\"databaseName\":\"s\",\"scannerId\":\"chunkscanner:qshop\"}");

            DbValidationResult r = DbPackage.open(zip).validate();

            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("databaseType")),
                    "databaseType 缺失应可回退默认工厂，只记警告");
        }

        @Test
        @DisplayName("databaseName 缺失记为警告")
        void missingDatabaseName_shouldBeWarning(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "no-name.zip", "{\"scannerId\":\"chunkscanner:qshop\"}");

            DbValidationResult r = DbPackage.open(zip).validate();

            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("databaseName")));
        }

        @Test
        @DisplayName("包内存在未声明文件记为警告")
        void undeclaredFile_shouldBeWarning(@TempDir Path dir) throws IOException {
            Path zip = dir.resolve("extra.zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry("metadata.json"));
                zos.write("{\"scannerId\":\"chunkscanner:qshop\"}".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("stowaway.txt"));
                zos.write("surprise".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            DbValidationResult r = DbPackage.open(zip).validate();

            assertTrue(r.warnings().stream().anyMatch(w -> w.contains("stowaway.txt")));
        }

        @Test
        @DisplayName("SHA-256 不匹配记为错误")
        void sha256Mismatch_shouldBeError(@TempDir Path dir) throws IOException {
            Path zip = dir.resolve("bad-hash.zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry("metadata.json"));
                zos.write("""
                        {"databaseName":"s","scannerId":"chunkscanner:qshop",
                         "mainFile":"a.dat",
                         "files":[{"name":"a.dat","sha256":"deadbeef"}]}
                        """.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("a.dat"));
                zos.write("real content".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            DbValidationResult r = DbPackage.open(zip).validate();

            assertFalse(r.valid());
            assertTrue(r.errors().stream().anyMatch(e -> e.contains("SHA-256 mismatch")),
                    "完整性校验必须能发现内容被篡改");
        }

        @Test
        @DisplayName("校验失败时 load 抛异常，不产生半成品数据库")
        void invalidPackage_load_shouldThrow(@TempDir Path dir) throws IOException {
            Path zip = writeZip(dir, "invalid.zip", "{\"databaseName\":\"s\"}");
            DbPackage pkg = DbPackage.open(zip);

            assertThrows(IllegalStateException.class, () -> pkg.load(dir.resolve("out")));
        }
    }
}
