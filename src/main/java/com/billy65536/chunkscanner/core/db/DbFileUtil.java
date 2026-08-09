package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 数据库文件名与旧格式嗅探工具。
 *
 * <p>目录布局、元数据与生命周期由 {@link DbPackage} 负责；本类只保留两件与
 * 具体存储实现无关的纯函数职责：</p>
 * <ul>
 *   <li>由 scanId 推导安全的包目录名（{@link #safeFilenameStem(String)}）；</li>
 *   <li>从 1.x 扁平文件的二进制头里嗅探元信息，供 {@link DbPackage} 自动迁移使用
 *       （{@link #readLegacyHeader(Path)}）。</li>
 * </ul>
 */
public final class DbFileUtil {

    /** 数据库文件魔数 "CHNKSCAN"（小端）。 */
    public static final long MAGIC = 0x4E4143534B4E4843L;

    /** 合法头部的最小字节数：magic(8) + version(4) + scanIdLen(2)。 */
    private static final int MIN_HEADER_SIZE = 14;

    /** 包目录名前缀。 */
    public static final String STEM_PREFIX = "chunkscanner_";

    private DbFileUtil() {}

    // ==================== 文件名推导 ====================

    /**
     * 由 scanId 推导安全的包目录名：{@code chunkscanner_{hash}}。
     *
     * <p>scanId 可能包含任意字符（含路径分隔符），因此统一取 SHA-256 前 8 字节
     * 转 36 进制，保证跨平台文件名安全且对同一 scanId 稳定。</p>
     */
    public static String safeFilenameStem(String scanId) {
        String raw = scanId == null ? "" : scanId;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFFL);
            }
            return STEM_PREFIX + Long.toUnsignedString(hash & 0x7FFFFFFFFFFFFFFFL, 36);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，不可能缺失；退化为 hashCode 仅为编译期完备性
            return STEM_PREFIX + Integer.toUnsignedString(raw.hashCode(), 36);
        }
    }

    // ==================== 旧格式嗅探 ====================

    /**
     * 1.x 扁平数据库文件的二进制头信息。
     *
     * @param scanId         扫描 ID
     * @param analyzerId     分析器 ID
     * @param taskConfigJson 任务配置 JSON（v4+ 才有，可能为 null）
     * @param version        负载格式版本
     */
    public record LegacyHeader(String scanId, Identifier analyzerId, String taskConfigJson, int version) {

        /** 无法识别时的空值。 */
        public static final LegacyHeader EMPTY =
                new LegacyHeader("", ChunkScannerMod.ID_UNKNOWN, null, 0);

        /** 是否为无法识别的文件。 */
        public boolean isEmpty() {
            return scanId.isEmpty();
        }
    }

    /**
     * 从旧版扁平数据库文件的头部嗅探元信息。
     *
     * <p>仅供 {@link DbPackage} 迁移旧数据使用；新格式的元信息一律来自
     * 包内的 {@code metadata.json}。无法识别时返回 {@link LegacyHeader#EMPTY}。</p>
     */
    public static LegacyHeader readLegacyHeader(Path file) {
        if (file == null || !Files.isRegularFile(file)) return LegacyHeader.EMPTY;

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long total = raf.length();
            if (total < MIN_HEADER_SIZE) return LegacyHeader.EMPTY;

            if (readLongLE(raf) != MAGIC) return LegacyHeader.EMPTY;

            int version = readIntLE(raf);
            String scanId = readBlock(raf, total);
            if (scanId == null || scanId.isEmpty()) return LegacyHeader.EMPTY;

            Identifier analyzerId = ChunkScannerMod.ID_UNKNOWN;
            if (version >= 2) {
                String raw = readBlock(raf, total);
                if (raw != null && !raw.isEmpty()) {
                    Identifier parsed = raw.indexOf(':') >= 0
                            ? Identifier.tryParse(raw)
                            : ChunkScannerMod.id(raw);
                    if (parsed != null) analyzerId = parsed;
                }
            }

            String taskConfigJson = null;
            if (version >= 4) {
                String raw = readBlock(raf, total);
                if (raw != null && !raw.isEmpty()) taskConfigJson = raw;
            }

            return new LegacyHeader(scanId, analyzerId, taskConfigJson, version);
        } catch (IOException | RuntimeException e) {
            return LegacyHeader.EMPTY;
        }
    }

    /** 读取一段「u16 长度 + UTF-8 内容」；越界返回 null，长度为 0 返回空串。 */
    private static String readBlock(RandomAccessFile raf, long total) throws IOException {
        if (raf.getFilePointer() + 2 > total) return null;
        int len = readShortLE(raf);
        if (len < 0 || raf.getFilePointer() + len > total) return null;
        if (len == 0) return "";
        byte[] data = new byte[len];
        raf.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static long readLongLE(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[8];
        raf.readFully(b);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    private static int readIntLE(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static int readShortLE(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[2];
        raf.readFully(b);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }
}
