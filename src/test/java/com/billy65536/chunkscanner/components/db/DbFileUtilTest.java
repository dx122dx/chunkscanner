package com.billy65536.chunkscanner.components.db;

import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.db.DbFileUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbFileUtil 单元测试。
 *
 * <p>重构后 DbFileUtil 收窄为纯文件名/路径工具 + 旧格式（BIL1）头部读取，
 * 不再承担包管理职责。本测试覆盖：</p>
 * <ul>
 *   <li>{@code safeFilenameStem} 路径收口（去目录、保留文件名）；</li>
 *   <li>{@code readLegacyHeader} 对真实 BinaryChunkDb 文件能解析出版本与 scanId；</li>
 *   <li>{@code readLegacyHeader} 对非二进制文件返回 null。</li>
 * </ul>
 */
@DisplayName("DbFileUtil")
class DbFileUtilTest {

    @BeforeAll
    static void registerDefaultFactory() {
        IChunkDb.FactoryRegistry.register(new BinaryChunkDb.Factory());
    }

    // ==================== safeFilenameStem ====================

    @Nested
    @DisplayName("safeFilenameStem")
    class SafeFilenameStem {

        @Test
        @DisplayName("输出以 chunkscanner_ 前缀")
        void hasPrefix() {
            assertTrue(DbFileUtil.safeFilenameStem("scan-1").startsWith("chunkscanner_"));
        }

        @Test
        @DisplayName("相同 scanId 产生稳定结果")
        void stableForSameInput() {
            String a = DbFileUtil.safeFilenameStem("scan-1");
            String b = DbFileUtil.safeFilenameStem("scan-1");
            assertEquals(a, b);
        }

        @Test
        @DisplayName("不同 scanId 通常产生不同结果")
        void differsForDifferentInput() {
            String a = DbFileUtil.safeFilenameStem("scan-1");
            String b = DbFileUtil.safeFilenameStem("scan-2");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("含路径分隔符的输入被收敛为无分隔符的安全名")
        void stripsDangerousChars() {
            String s = DbFileUtil.safeFilenameStem("a/b/c/../evil");
            assertFalse(s.contains("/"), "结果不得包含路径分隔符");
            assertFalse(s.contains("\\"), "结果不得包含反斜杠");
            assertTrue(s.startsWith("chunkscanner_"));
        }

        @Test
        @DisplayName("null 安全返回前缀 + 退化哈希")
        void nullInput_safe() {
            String s = DbFileUtil.safeFilenameStem(null);
            assertTrue(s.startsWith("chunkscanner_"));
        }
    }

    // ==================== readLegacyHeader ====================

    @Nested
    @DisplayName("readLegacyHeader")
    class ReadLegacyHeader {

        @Test
        @DisplayName("非二进制文件返回 EMPTY 哨兵（scanId 为空、版本 0）")
        void nonBinary_returnsEmpty(@TempDir Path dir) throws IOException {
            Path plain = dir.resolve("plain.txt");
            Files.writeString(plain, "this is not a database file");
            DbFileUtil.LegacyHeader header = DbFileUtil.readLegacyHeader(plain);
            assertNotNull(header, "readLegacyHeader 永不返回 null，无法识别时返回 EMPTY 哨兵");
            assertTrue(header.scanId().isEmpty(), "无 magic 头的普通文件 scanId 应为空");
            assertEquals(0, header.version(), "EMPTY 哨兵版本应为 0");
        }

        @Test
        @DisplayName("不存在的文件返回 EMPTY 哨兵")
        void missingFile_returnsEmpty(@TempDir Path dir) {
            DbFileUtil.LegacyHeader header = DbFileUtil.readLegacyHeader(dir.resolve("nope.dat"));
            assertNotNull(header);
            assertTrue(header.scanId().isEmpty());
        }
    }
}
