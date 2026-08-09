package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.components.db.BinaryChunkDb;
import com.billy65536.chunkscanner.core.IChunkDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbExportUtil 单元测试。
 *
 * <p>覆盖纯逻辑与轻量 IO：</p>
 * <ul>
 *   <li>{@code buildDefaultFileName} 命名格式（不依赖 MC 运行时）</li>
 *   <li>{@code bytesToHex} 十六进制编解码</li>
 *   <li>{@code exportTsv} 输出 hex key + tab + hex value</li>
 *   <li>{@code exportRawZip} 归档内容、metadata.json 字段与 SHA-256 一致性</li>
 * </ul>
 *
 * <p>所有导出均显式传入 outFile，避开 {@code ChunkScannerMod.getDbRoot()}（游戏运行时依赖）。</p>
 */
@DisplayName("DbExportUtil")
class DbExportUtilTest {

    @BeforeAll
    static void registerDefaultFactory() {
        // 单元测试不触发 ChunkScannerMod 装配，需手动注册默认数据库工厂
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());
    }

    /** 最小化 IChunkDb 存根，只关心导出用到的方法。 */
    private static final class StubDb implements IChunkDb {
        private final String scanId;
        private final Identifier analyzerId;
        private final Path filePath;
        private final List<Entry> entries;

        StubDb(String scanId, Identifier analyzerId, Path filePath, List<Entry> entries) {
            this.scanId = scanId;
            this.analyzerId = analyzerId;
            this.filePath = filePath;
            this.entries = entries;
        }

        @Override public String getScanId() { return scanId; }
        @Override public Identifier getAnalyzerId() { return analyzerId; }
        @Override public Identifier getFactoryId() {
            return new Identifier("chunkscanner", "binary");
        }

        @Override public List<Entry> getAllEntries() { return entries; }

        // 导出路径未用到的方法，仅满足接口
        @Override public int intern(String s) { return 0; }
        @Override public String lookup(int id) { return null; }
        @Override public void put(byte[] key, byte[] value) {}
        @Override public void putAll(Iterable<Entry> entries) {}
        @Override public byte[] get(byte[] key) { return null; }
        @Override public void remove(byte[] key) {}
        @Override public boolean containsKey(byte[] key) { return false; }
        @Override public int size() { return 0; }
        @Override public long getChunkScanTime(String dimensionId, int cx, int cz) { return 0; }
        @Override public void updateChunkScanTime(String dimensionId, int cx, int cz, long timestamp) {}
        @Override public void open() {}
        @Override public boolean isOpen() { return false; }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public void readFrom(java.nio.channels.FileChannel channel) throws java.io.IOException {}
        @Override public void writeTo(java.nio.channels.FileChannel channel) throws java.io.IOException {}
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    // ==================== 文件命名 ====================

    @Nested
    @DisplayName("buildDefaultFileName")
    class FileName {

        @Test
        @DisplayName("格式为 chunkscanner-{analyzerId}-{scanId}-{yyMMddHHmmss}.{ext}")
        void format_shouldMatchContract() {
            String name = DbExportUtil.buildDefaultFileName(
                    new Identifier("chunkscanner", "qshop"), "scan-42", "zip");

            String expectedPrefix = "chunkscanner-chunkscanner:qshop-scan-42-";
            assertTrue(name.startsWith(expectedPrefix),
                    "实际: " + name);
            assertTrue(name.endsWith(".zip"));

            String timePart = name.substring(expectedPrefix.length(), name.length() - ".zip".length());
            assertTrue(Pattern.matches("\\d{12}", timePart),
                    "时间戳应为 12 位 yyMMddHHmmss，实际: " + timePart);

            String expectedNow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
            assertTrue(Math.abs(expectedNow.compareTo(timePart)) <= 1,
                    "时间戳应接近当前时刻");
        }

        @Test
        @DisplayName("不同扩展名可复用")
        void ext_shouldBeSuffix() {
            String zip = DbExportUtil.buildDefaultFileName(
                    new Identifier("chunkscanner", "qshop"), "s", "zip");
            String tsv = DbExportUtil.buildDefaultFileName(
                    new Identifier("chunkscanner", "qshop"), "s", "tsv");

            assertNotEquals(zip, tsv);
            assertTrue(zip.endsWith(".zip"));
            assertTrue(tsv.endsWith(".tsv"));
        }
    }

    // ==================== 十六进制编解码 ====================

    @Nested
    @DisplayName("bytesToHex")
    class BytesToHex {

        @Test
        @DisplayName("空数组为空串")
        void empty_shouldYieldEmpty() {
            assertEquals("", DbExportUtil.bytesToHex(new byte[0]));
        }

        @Test
        @DisplayName("全字节往返一致")
        void roundTrip_shouldMatch() {
            byte[] data = {0x00, 0x0f, 0x10, 0x7f, (byte) 0x80, (byte) 0xff, 0x4a};
            assertEquals("000f107f80ff4a", DbExportUtil.bytesToHex(data));
        }
    }

    // ==================== TSV 导出 ====================

    @Nested
    @DisplayName("exportTsv")
    class ExportTsv {

        @Test
        @DisplayName("每行输出 hex key + tab + hex value")
        void lines_shouldBeHexTabHex(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("out.tsv");
            IChunkDb db = new StubDb("scan-1", new Identifier("chunkscanner", "qshop"), null,
                    List.of(IChunkDb.Entry.of(hex("aabb"), hex("11")),
                            IChunkDb.Entry.of(hex("0000ff"), new byte[0])));

            DbExportUtil.exportTsv(db, out);

            List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            assertEquals("aabb\t11", lines.get(0));
            assertEquals("0000ff\t", lines.get(1), "空 value 也要输出 tab 后的空串");
        }

        @Test
        @DisplayName("空数据库生成空文件")
        void emptyDb_shouldProduceEmptyFile(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("empty.tsv");
            IChunkDb db = new StubDb("scan-1", new Identifier("chunkscanner", "qshop"), null,
                    List.of());

            DbExportUtil.exportTsv(db, out);

            assertEquals(0, Files.size(out));
        }

        @Test
        @DisplayName("返回实际写入的路径")
        void shouldReturnOutPath(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("ret.tsv");
            IChunkDb db = new StubDb("s", new Identifier("chunkscanner", "qshop"), null, List.of());

            assertEquals(out, DbExportUtil.exportTsv(db, out));
        }
    }

    // ==================== ZIP 导出 ====================

    @Nested
    @DisplayName("exportRawZip")
    class ExportRawZip {

        /** 构造一个含 metadata.json 与主负载文件的最小化包目录。 */
        private DbPackage newPackage(@TempDir Path dir, byte[] content) throws IOException {
            Path pkgDir = dir.resolve("pkg-" + DbFileUtil.safeFilenameStem("scan-1"));
            Files.createDirectories(pkgDir);
            Files.write(pkgDir.resolve("main.bin"), content);

            String meta = "{"
                    + "\"scanId\":\"scan-1\","
                    + "\"analyzerId\":\"chunkscanner:sign\","
                    + "\"database\":{\"type\":\"chunkscanner:binary\",\"file\":\"main.bin\"},"
                    + "\"export\":{\"time\":\"2026-08-09T12:00:00Z\","
                    + "\"files\":[{\"name\":\"main.bin\",\"sha256\":\"deadbeef\"}]}"
                    + "}";
            Files.write(pkgDir.resolve("metadata.json"), meta.getBytes(StandardCharsets.UTF_8));
            return DbPackage.open(pkgDir);
        }

        @Test
        @DisplayName("归档包含主数据库文件与 metadata.json")
        void zip_shouldContainFilesAndMeta(@TempDir Path dir) throws IOException {
            DbPackage pkg = newPackage(dir, "hello dat".getBytes(StandardCharsets.UTF_8));
            Path out = dir.resolve("out.zip");

            Path written = DbExportUtil.exportRawZip(pkg, out);
            assertEquals(out, written);

            try (ZipFile zip = new ZipFile(out.toFile())) {
                assertNotNull(zip.getEntry("main.bin"), "主数据库文件应被归档");
                assertNotNull(zip.getEntry("metadata.json"));

                String meta = new String(zip.getInputStream(zip.getEntry("metadata.json"))
                        .readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(meta.contains("\"scanId\": \"scan-1\""));
                assertTrue(meta.contains("\"analyzerId\": \"chunkscanner:sign\""));
                assertTrue(meta.contains("\"type\": \"chunkscanner:binary\""),
                        "metadata.database.type 应记录数据库类型");
                assertTrue(meta.contains("\"file\": \"main.bin\""),
                        "metadata.database.file 应记录主文件名");
                assertTrue(meta.contains("\"sha256\""), "metadata.json 应记录每个文件的 SHA-256");
            }
        }

        @Test
        @DisplayName("归档内文件内容与 SHA-256 元数据一致")
        void sha256_shouldMatchArchiveContent(@TempDir Path dir) throws IOException, java.security.NoSuchAlgorithmException {
            byte[] content = "payload for hash check".getBytes(StandardCharsets.UTF_8);
            DbPackage pkg = newPackage(dir, content);

            Path out = dir.resolve("out.zip");
            DbExportUtil.exportRawZip(pkg, out);

            try (ZipFile zip = new ZipFile(out.toFile())) {
                ZipEntry entry = zip.getEntry("main.bin");
                byte[] actual = zip.getInputStream(entry).readAllBytes();
                assertArrayEquals(content, actual, "归档内文件内容不得被改写");

                String meta = new String(zip.getInputStream(zip.getEntry("metadata.json"))
                        .readAllBytes(), StandardCharsets.UTF_8);

                String expectedSha = DbExportUtil.bytesToHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
                assertTrue(meta.contains("\"sha256\": \"" + expectedSha + "\""),
                        "metadata 的 sha256 必须等于原文件摘要");
            }
        }

        @Test
        @DisplayName("exportTime 使用 ISO_OFFSET_DATE_TIME 格式")
        void exportTime_shouldBeIsoOffset(@TempDir Path dir) throws IOException {
            DbPackage pkg = newPackage(dir, "x".getBytes(StandardCharsets.UTF_8));
            Path out = dir.resolve("out.zip");
            DbExportUtil.exportRawZip(pkg, out);

            try (ZipFile zip = new ZipFile(out.toFile())) {
                String meta = new String(zip.getInputStream(zip.getEntry("metadata.json"))
                        .readAllBytes(), StandardCharsets.UTF_8);
                String time = meta.replaceAll("(?s).*\"time\":\\s*\"([^\"]+)\".*", "$1");
                assertTrue(Pattern.matches(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})", time),
                        "应带时区（UTC 时输出 Z 后缀，其他时区为 ±hh:mm，均允许纳秒小数位），实际: " + time);
            }
        }
    }
}
