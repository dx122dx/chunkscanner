package com.billy65536.chunkscanner.components.db;

import com.billy65536.chunkscanner.core.db.DbFileUtil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbFileUtil 及 FileMeta 单元测试。
 * 覆盖 FileMeta record、readFileMeta 二进制解析及其边界情况。
 */
@DisplayName("DbFileUtil")
class DbFileUtilTest {

    // 复刻 BinaryChunkDb.MAGIC 用于测试
    private static final long MAGIC = 0x4E4143534B4E4843L; // "CHNKSCAN" (little-endian)

    // ==================== FileMeta ====================

    @Nested
    @DisplayName("FileMeta")
    class FileMetaTest {

        @Test
        @DisplayName("EMPTY 常量的 scanId 为空字符串")
        void empty_shouldHaveEmptyScanId() {
            assertTrue(DbFileUtil.FileMeta.EMPTY.isEmpty());
            assertEquals("", DbFileUtil.FileMeta.EMPTY.scanId());
        }

        @Test
        @DisplayName("isEmpty()：scanId 非空 → false")
        void nonEmptyScanId_shouldNotBeEmpty() {
            DbFileUtil.FileMeta meta = new DbFileUtil.FileMeta("test-scan", "analyzer", 100, 200, null);
            assertFalse(meta.isEmpty());
        }

        @Test
        @DisplayName("构造后各字段可正确访问")
        void constructor_shouldSetAllFields() {
            Path fakePath = Path.of("/tmp/test.db");
            DbFileUtil.FileMeta meta = new DbFileUtil.FileMeta("scan-1", "qshop", 4096, 1234567890000L, fakePath);

            assertEquals("scan-1", meta.scanId());
            assertEquals("qshop", meta.analyzerId());
            assertEquals(4096, meta.fileSize());
            assertEquals(1234567890000L, meta.lastModified());
            assertEquals(fakePath, meta.filePath());
        }

        @Test
        @DisplayName("相同值的 FileMeta 应相等")
        void equals_sameValues_shouldBeEqual() {
            DbFileUtil.FileMeta a = new DbFileUtil.FileMeta("s", "a", 100, 200, null);
            DbFileUtil.FileMeta b = new DbFileUtil.FileMeta("s", "a", 100, 200, null);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("不同 scanId 应不相等")
        void equals_differentScanId_shouldNotBeEqual() {
            DbFileUtil.FileMeta a = new DbFileUtil.FileMeta("s1", "", 0, 0, null);
            DbFileUtil.FileMeta b = new DbFileUtil.FileMeta("s2", "", 0, 0, null);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("null filePath 可正常处理")
        void nullFilePath_shouldWork() {
            DbFileUtil.FileMeta meta = new DbFileUtil.FileMeta("scan", "a", 0, 0, null);
            assertNull(meta.filePath());
            assertFalse(meta.isEmpty());
        }

        @Test
        @DisplayName("toString 包含 scanId 和 analyzerId")
        void toString_containsKeyInfo() {
            DbFileUtil.FileMeta meta = new DbFileUtil.FileMeta("scanX", "analyzerY", 1024, 500, null);
            String s = meta.toString();
            assertTrue(s.contains("scanX"));
            assertTrue(s.contains("analyzerY"));
        }
    }

    // ==================== readFileMeta ====================

    @Nested
    @DisplayName("readFileMeta(Path)")
    class ReadFileMetaTest {

        @TempDir
        Path tempDir;

        // --- 辅助：写入二进制头部 ---

        private Path writeFile(String scanId, int version, String analyzerId) throws Exception {
            Path file = tempDir.resolve("test_" + System.nanoTime() + ".db");
            byte[] scanIdBytes = scanId.getBytes(StandardCharsets.UTF_8);
            byte[] analyzerBytes = analyzerId.getBytes(StandardCharsets.UTF_8);

            // 计算头部总大小
            int headerSize = 8 + 4 + 2 + scanIdBytes.length; // magic + version + scanIdLen + scanId
            if (version >= 2) {
                headerSize += 2 + analyzerBytes.length; // analyzerIdLen + analyzerId
            }

            ByteBuffer buf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(version);
            buf.putShort((short) scanIdBytes.length);
            buf.put(scanIdBytes);
            if (version >= 2) {
                buf.putShort((short) analyzerBytes.length);
                buf.put(analyzerBytes);
            }
            buf.flip();

            Files.write(file, buf.array());
            return file;
        }

        // --- 正常情况 ---

        @Test
        @DisplayName("version=1 文件 → 正确解析 scanId，analyzerId 为空")
        void readFileMeta_v1_shouldParseScanIdOnly() throws Exception {
            Path file = writeFile("my-scan-v1", 1, "");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);

            assertFalse(meta.isEmpty());
            assertEquals("my-scan-v1", meta.scanId());
            assertEquals("", meta.analyzerId());
            assertTrue(meta.fileSize() > 0);
            assertEquals(file, meta.filePath());
        }

        @Test
        @DisplayName("version=2 文件 → 正确解析 scanId 和 analyzerId")
        void readFileMeta_v2_shouldParseBoth() throws Exception {
            Path file = writeFile("scan-v2", 2, "qshop");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);

            assertFalse(meta.isEmpty());
            assertEquals("scan-v2", meta.scanId());
            assertEquals("qshop", meta.analyzerId());
        }

        @Test
        @DisplayName("version=3 文件 → 正常解析")
        void readFileMeta_v3_shouldWork() throws Exception {
            Path file = writeFile("scan-v3", 3, "sign");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);

            assertFalse(meta.isEmpty());
            assertEquals("scan-v3", meta.scanId());
            assertEquals("sign", meta.analyzerId());
        }

        @Test
        @DisplayName("scanId 含中文字符 → 正确解析 UTF-8")
        void readFileMeta_chineseScanId_shouldParseUtf8() throws Exception {
            Path file = writeFile("扫描任务-测试", 2, "分析器");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);

            assertFalse(meta.isEmpty());
            assertEquals("扫描任务-测试", meta.scanId());
            assertEquals("分析器", meta.analyzerId());
        }

        @Test
        @DisplayName("scanId 最大长度 1024 → 可以解析")
        void readFileMeta_maxScanIdLength_shouldWork() throws Exception {
            String longScanId = "a".repeat(1024);
            Path file = writeFile(longScanId, 1, "");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);

            assertFalse(meta.isEmpty());
            assertEquals(1024, meta.scanId().length());
        }

        // --- 异常/边界情况 ---

        @Test
        @DisplayName("文件过小（< 14 字节）→ 返回 EMPTY")
        void readFileMeta_tooSmall_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("tiny.db");
            Files.write(file, new byte[]{1, 2, 3});

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("magic 不匹配 → 返回 EMPTY")
        void readFileMeta_badMagic_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("badmagic.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(0xDEADBEEFCAFEBABEL); // 错误 magic
            buf.putInt(1);
            buf.putShort((short) 0);
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("version < 1 → 返回 EMPTY")
        void readFileMeta_versionZero_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("ver0.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(0); // 非法版本
            buf.putShort((short) 0);
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("version 为负数 → 返回 EMPTY")
        void readFileMeta_negativeVersion_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("negver.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(-1);
            buf.putShort((short) 0);
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("scanIdLen = 0 → 返回 EMPTY")
        void readFileMeta_zeroScanIdLen_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("zerolen.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(1);
            buf.putShort((short) 0); // zero scanIdLen
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("scanIdLen 超过 1024 → 返回 EMPTY")
        void readFileMeta_scanIdLenTooLarge_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("toolarge.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(1);
            buf.putShort((short) 2048); // 超过限制
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("scanIdLen 超过文件剩余数据 → 返回 EMPTY")
        void readFileMeta_scanIdLenExceedsRemaining_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("short.db");
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(1);
            buf.putShort((short) 100); // 声称 100 字节，但文件只有 16 字节
            Files.write(file, buf.array());

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("version=2 但 analyzerIdLen 数据被截断 → 不 crash")
        void readFileMeta_v2_truncatedAnalyzerLen_shouldNotCrash() throws Exception {
            Path file = tempDir.resolve("trunc.db");
            String scanId = "test";
            byte[] scanIdBytes = scanId.getBytes(StandardCharsets.UTF_8);

            // version=2 头部：magic(8) + version(4) + scanIdLen(2) + scanId(n) + analyzerLen(2) ← 截断
            int headerSize = 8 + 4 + 2 + scanIdBytes.length;
            ByteBuffer buf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(MAGIC);
            buf.putInt(2);
            buf.putShort((short) scanIdBytes.length);
            buf.put(scanIdBytes);
            // analyzerIdLen 被截断（故意不写，让 buf.remaining() < 2）
            Files.write(file, buf.array());

            // 不应抛出异常
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertNotNull(meta);
            assertFalse(meta.isEmpty());
            assertEquals("test", meta.scanId());
            assertEquals("", meta.analyzerId()); // analyzer 解析失败，回退为空
        }

        @Test
        @DisplayName("不存在的文件 → 返回 EMPTY")
        void readFileMeta_missingFile_shouldReturnEmpty() {
            Path missingFile = tempDir.resolve("does_not_exist.db");
            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(missingFile);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("空文件 → 返回 EMPTY")
        void readFileMeta_emptyFile_shouldReturnEmpty() throws Exception {
            Path file = tempDir.resolve("empty.db");
            Files.write(file, new byte[0]);

            DbFileUtil.FileMeta meta = DbFileUtil.readFileMeta(file);
            assertTrue(meta.isEmpty());
        }
    }
}
