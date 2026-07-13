package com.billy65536.chunkscanner.components.db;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

/**
 * DB 文件工具类 —— 统一所有二进制文件元数据读取和文件操作。
 *
 * 避免了 ChunkScannerMod 和 DatabaseScreen 中的重复代码。
 */
public final class DbFileUtil {

    /** 默认 Magic（引用 BinaryChunkDb 的单一定义）。 */
    static final long MAGIC = BinaryChunkDb.MAGIC;

    /** 最小头大小（magic(8) + version(4) + scanIdLen(2) = 14）。 */
    private static final int MIN_HEADER_SIZE = 14;

    private DbFileUtil() {}

    // ==================== 元数据读取 ====================

    /**
     * 从二进制文件中读取 scanId 和 analyzerName。
     * 使用 FileChannel 只读取头部数据，避免大文件全量加载到内存。
     */
    public static FileMeta readFileMeta(Path file) {
        try {
            long fileLen = Files.size(file);
            if (fileLen < MIN_HEADER_SIZE) return FileMeta.EMPTY;
            long lastModified = Files.getLastModifiedTime(file).toMillis();

            // 只读取头部静态部分（最多 4096 字节，应覆盖所有合理的 scanId 和 analyzerName）
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

            String analyzerName = "";
            if (version >= 2) {
                if (buf.remaining() < 2) return new FileMeta(scanId, "", fileLen, lastModified, file);
                int analyzerLen = buf.getShort() & 0xFFFF;
                if (analyzerLen > 0 && analyzerLen <= 1024 && buf.remaining() >= analyzerLen) {
                    byte[] analyzerBytes = new byte[analyzerLen];
                    buf.get(analyzerBytes);
                    analyzerName = new String(analyzerBytes, StandardCharsets.UTF_8);
                }
            }

            return new FileMeta(scanId, analyzerName, fileLen, lastModified, file);
        } catch (IOException e) {
            return FileMeta.EMPTY;
        }
    }

    // ==================== 文件列表 ====================

    /**
     * 列出所有数据库文件的文件元数据（跨所有上下文递归搜索）。
     * 不再局限于当前服务器/世界，确保断开重连后仍能看到之前的 DB 文件。
     */
    public static List<FileMeta> listAllDbFiles() {
        List<FileMeta> result = new ArrayList<>();
        Path root = ChunkScannerMod.getDbRoot();
        if (!Files.exists(root)) return result;

        try (Stream<Path> files = Files.walk(root, 4)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("chunkscanner_") && name.endsWith(".dat");
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
        // fallback：使用当前上下文构造路径
        return ChunkScannerMod.getDbDir().resolve(BinaryChunkDb.safeFileName(scanId));
    }

    // ==================== 文件操作 ====================

    /**
     * 通过 scanId 删除数据库文件。
     * @return true 表示删除成功，false 表示文件不存在
     */
    public static boolean deleteDbFile(String scanId) throws IOException {
        Path file = resolveFilePath(scanId);
        return Files.deleteIfExists(file);
    }

    // ==================== 聊天消息 ====================

    /**
     * 在聊天中列出所有 DB 文件及其大小、分析器。
     */
    public static void chatListDbFiles(net.minecraft.client.MinecraftClient client) {
        List<FileMeta> files = listAllDbFiles();
        if (files.isEmpty()) {
            sendMsg(client, Text.translatable("chunkscanner.gui.database.no_files").formatted(Formatting.GRAY));
            return;
        }
        sendMsg(client, Text.translatable("chunkscanner.gui.database.title")
                .formatted(Formatting.GOLD, Formatting.BOLD));
        for (FileMeta meta : files) {
            String sizeStr = meta.fileSize() < 1024
                    ? meta.fileSize() + " B"
                    : meta.fileSize() < 1024 * 1024
                        ? String.format("%.1f KB", meta.fileSize() / 1024.0)
                        : String.format("%.1f MB", meta.fileSize() / (1024.0 * 1024.0));
            String aName = meta.analyzerName() != null && !meta.analyzerName().isEmpty()
                    ? meta.analyzerName() : "?";
            sendMsg(client, Text.literal("  ")
                    .append(Text.literal(meta.scanId()).formatted(Formatting.YELLOW))
                    .append(Text.literal(" [" + aName + "]").formatted(Formatting.GRAY))
                    .append(Text.literal(" " + sizeStr).formatted(Formatting.WHITE)));
        }
    }

    private static void sendMsg(net.minecraft.client.MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    // ==================== 辅助类型 ====================

    /**
     * 数据库文件的轻量元数据（scanId、analyzerName、大小、修改时间、文件路径）。
     * 不加载 KV 数据，仅用于文件列表展示。
     */
    public record FileMeta(String scanId, String analyzerName, long fileSize, long lastModified, Path filePath) {
        public static final FileMeta EMPTY = new FileMeta("", "", 0, 0, null);

        public boolean isEmpty() {
            return scanId.isEmpty();
        }
    }
}
