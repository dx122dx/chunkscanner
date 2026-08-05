package com.billy65536.chunkscanner.core.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.billy65536.chunkscanner.ChunkScannerMod;

import net.minecraft.util.Identifier;

/**
 * DB 文件工具类 —— 统一所有二进制文件元数据读取和文件操作。
 */
public final class DbFileUtil {

    /** 文件魔数："CHNKSCAN"（little-endian uint64）。 */
    public static final long MAGIC = 0x4E4143534B4E4843L;

    /** 最小头大小（magic(8) + version(4) + scanIdLen(2) = 14）。 */
    private static final int MIN_HEADER_SIZE = 14;

    private DbFileUtil() {}

    // ==================== 元数据读取 ====================

    /**
     * 从二进制文件中读取 scanId 和 analyzerId。
     * 使用 RandomAccessFile 只读取头部数据，避免大文件全量加载到内存。
     */
    public static FileMeta readFileMeta(Path file) {
        try {
            long fileLen = Files.size(file);
            if (fileLen < MIN_HEADER_SIZE) return FileMeta.EMPTY;
            long lastModified = Files.getLastModifiedTime(file).toMillis();

            // 只读取头部静态部分（最多 4096 字节，应覆盖所有合理的 scanId 和 analyzerId）
            int readLen = (int) Math.min(fileLen, 4096);
            byte[] headBytes = new byte[readLen];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.readFully(headBytes);
            }

            ByteBuffer buf = ByteBuffer.wrap(headBytes).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getLong() != MAGIC) return FileMeta.EMPTY;

            int version = buf.getInt();
            if (version < 1) return FileMeta.EMPTY;

            int scanIdLen = buf.getShort() & 0xFFFF;
            if (scanIdLen <= 0 || scanIdLen > 1024 || buf.remaining() < scanIdLen)
                return FileMeta.EMPTY;

            byte[] scanIdBytes = new byte[scanIdLen];
            buf.get(scanIdBytes);
            String scanId = new String(scanIdBytes, StandardCharsets.UTF_8);

            String analyzerRaw = "";
            if (version >= 2) {
                if (buf.remaining() < 2) return new FileMeta(scanId, ChunkScannerMod.ID_UNKNOWN, fileLen, lastModified, file);
                int analyzerLen = buf.getShort() & 0xFFFF;
                if (analyzerLen > 0 && analyzerLen <= 1024 && buf.remaining() >= analyzerLen) {
                    byte[] analyzerBytes = new byte[analyzerLen];
                    buf.get(analyzerBytes);
                    analyzerRaw = new String(analyzerBytes, StandardCharsets.UTF_8);
                }
            }
            // 兼容旧文件：无命名空间时回退为 chunkscanner:<原值>；含冒号则按完整标识符解析
            // 空字符串（analyzerLen==0 或解析无内容）一律视作未定义哨兵
            Identifier analyzerId;
            if (analyzerRaw.isEmpty()) {
                analyzerId = ChunkScannerMod.ID_UNKNOWN;
            } else if (analyzerRaw.indexOf(':') >= 0) {
                analyzerId = Identifier.tryParse(analyzerRaw);
                if (analyzerId == null) analyzerId = ChunkScannerMod.ID_UNKNOWN;
            } else {
                analyzerId = ChunkScannerMod.id(analyzerRaw);
            }

            return new FileMeta(scanId, analyzerId, fileLen, lastModified, file);
        } catch (IOException e) {
            return FileMeta.EMPTY;
        }
    }

    // ==================== 文件列表 ====================

    /**
     * 列出所有数据库文件的文件元数据（跨所有上下文递归搜索）。
     * 不再局限于当前服务器/世界，确保断开重连后仍能看到之前的 DB 文件。
     *
     * <p>文件命名：chunkscanner_{hash}.{analyzerId}.{dbExt}
     * 子数据库（含 .sub_ 的文件）会被过滤，不单独列出。</p>
     */
    public static List<FileMeta> listAllDbFiles() {
        List<FileMeta> result = new ArrayList<>();
        Path root = ChunkScannerMod.getDbRoot();
        if (!Files.exists(root)) return result;

        try (Stream<Path> files = Files.walk(root, 4)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                // 匹配所有 chunkscanner_ 文件，排除子数据库 (.sub_)
                return name.startsWith("chunkscanner_") && !name.contains(".sub_");
            }).forEach(p -> {
                FileMeta meta = readFileMeta(p);
                if (!meta.isEmpty()) result.add(meta);
            });
        } catch (IOException e) {
            ChunkScannerMod.LOGGER.warn("Failed to list DB files: {}", e.getMessage());
        }

        result.sort(Comparator.comparingLong(FileMeta::lastModified).reversed());
        return result;
    }

    /**
     * 列出所有数据库文件的 scanId（用于命令补全和聊天列表）。
     */
    public static List<String> listAllScanIds() {
        List<FileMeta> files = listAllDbFiles();
        List<String> ids = new ArrayList<>(files.size());
        for (FileMeta m : files) ids.add(m.scanId());
        return ids;
    }

    /**
     * 根据 scanId 查找对应的文件路径（跨所有上下文搜索）。
     * 用于删除、显示路径等操作。
     */
    public static Path resolveFilePath(String scanId) {
        // 先在已缓存的列表中查找
        for (FileMeta m : listAllDbFiles()) {
            if (m.scanId().equals(scanId) && m.filePath() != null) {
                return m.filePath();
            }
        }
        // fallback：按命名约定 chunkscanner_{hash}.{analyzerId}.{dbExt} 在默认目录中匹配
        String stem = safeFilenameStem(scanId);
        Path dir = ChunkScannerMod.getDbDir();
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, stem + ".*")) {
            for (Path p : stream) {
                if (!p.getFileName().toString().contains(".sub_")) {
                    return p;
                }
            }
        } catch (IOException ignored) {
            return dir.resolve("[ERROR - Failed to resolve file path]");
        }
        return dir.resolve(stem + ".bin");
    }

    /**
     * 生成安全的文件名主干：chunkscanner_{hash}（不含扩展名）。
     */
    public static String safeFilenameStem(String scanId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(scanId.getBytes(StandardCharsets.UTF_8));
            long hash = ((long) (digest[0] & 0xFF) << 56)
                      | ((long) (digest[1] & 0xFF) << 48)
                      | ((long) (digest[2] & 0xFF) << 40)
                      | ((long) (digest[3] & 0xFF) << 32)
                      | ((long) (digest[4] & 0xFF) << 24)
                      | ((long) (digest[5] & 0xFF) << 16)
                      | ((long) (digest[6] & 0xFF) << 8)
                      | (digest[7] & 0xFF);
            return "chunkscanner_" + Long.toUnsignedString(hash & 0x7FFFFFFFFFFFFFFFL, 36);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== 文件操作 ====================

    /**
     * 通过 scanId 删除数据库文件及其所有子数据库文件。
     * @return true 表示至少删除了一个文件，false 表示无文件可删
     */
    public static boolean deleteDbFile(String scanId) throws IOException {
        Path file = resolveFilePath(scanId);
        boolean deleted = Files.deleteIfExists(file);

        // 同时删除所有关联的子数据库文件
        // 主文件命名：chunkscanner_{hash}.{analyzerId}.{dbExt}
        // 子文件命名：chunkscanner_{hash}.{analyzerId}.sub_{subId}.{dbExt}
        String fileName = file.getFileName().toString();
        int extIdx = fileName.lastIndexOf('.');
        if (extIdx > 0) {
            String stem = fileName.substring(0, extIdx);
            String ext = fileName.substring(extIdx + 1);
            String glob = stem + ".sub_*." + ext;
            Path parent = file.getParent();
            if (parent != null) {
                try (java.nio.file.DirectoryStream<Path> stream =
                             Files.newDirectoryStream(parent, glob)) {
                    for (Path subFile : stream) {
                        if (Files.deleteIfExists(subFile)) {
                            deleted = true;
                            ChunkScannerMod.LOGGER.info("Deleted sub-db file: {}", subFile.getFileName());
                        }
                    }
                }
            }
        }

        return deleted;
    }

    // ==================== 辅助类型 ====================

    /**
     * 数据库文件的轻量元数据（scanId、analyzerId、大小、修改时间、文件路径）。
     * 不加载 KV 数据，仅用于文件列表展示。
     */
    public record FileMeta(String scanId, Identifier analyzerId, long fileSize, long lastModified, Path filePath) {
        public static final FileMeta EMPTY = new FileMeta("", ChunkScannerMod.ID_UNKNOWN, 0, 0, null);

        public boolean isEmpty() {
            return scanId.isEmpty();
        }
    }
}
