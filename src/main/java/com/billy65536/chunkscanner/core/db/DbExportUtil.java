package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.infrastructure.util.archive.ArchiveWriter;
import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * 数据库导出工具（core 层）。
 *
 * <p>支持两种导出模式：</p>
 * <ul>
 *   <li><b>raw</b> —— 把整个 {@link DbPackage} 目录原样打成 ZIP。
 *       包内 {@code metadata.json} 作为普通负载参与打包（与磁盘完全一致），
 *       归档时间与各文件 SHA-256 由基础设施的归档框架统一写入独立元数据，
 *       可由 {@link DbImage} 完整还原；</li>
 *   <li><b>tsv</b> —— 单个数据库的 KV 文本转储，每行为十六进制 key + tab + 十六进制 value。</li>
 * </ul>
 *
 * <p>导出文件默认存放于 {@code chunkscanner/export/}，
 * 命名为 {@code chunkscanner-{analyzerId}-{scanId}-{yyMMddHHmmss}.{ext}}。</p>
 */
public final class DbExportUtil {

    private static final String EXPORT_DIR_NAME = "export";

    private DbExportUtil() {
    }

    // ==================== 目录与文件名 ====================

    /** 获取导出目录路径。 */
    public static Path getExportDir() {
        return ChunkScannerMod.getDbRoot().resolve(EXPORT_DIR_NAME);
    }

    /** 确保导出目录存在。 */
    private static Path ensureExportDir() {
        Path dir = getExportDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            ChunkScannerMod.LOGGER.warn("Failed to create export directory: {}", dir);
        }
        return dir;
    }

    /**
     * 生成默认导出文件名。
     *
     * @param analyzerId 分析器 ID
     * @param scanId     扫描 ID
     * @param ext        文件扩展名（不含点号）
     * @return chunkscanner-{analyzerId}-{scanId}-{yyMMddHHmmss}.{ext}
     */
    public static String buildDefaultFileName(Identifier analyzerId, String scanId, String ext) {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        return "chunkscanner-" + analyzerId + "-" + scanId + "-" + time + "." + ext;
    }

    // ==================== 导出入口 ====================

    /**
     * 将整个数据库包导出为 ZIP 归档。
     *
     * @param pkg     待导出的包（导出前会先刷写）
     * @param outFile 输出文件路径，为 {@code null} 时自动生成到 export 目录
     * @return 实际写入的文件路径
     * @throws IOException 如果包内无负载或写入失败
     */
    public static Path exportRawZip(DbPackage pkg, Path outFile) throws IOException {
        pkg.flush();

        List<Path> payloads = listPayloads(pkg.getDir());
        if (payloads.isEmpty()) {
            throw new IOException("No database payload to export for " + pkg.getScanId());
        }

        Path outPath = (outFile != null)
                ? outFile
                : ensureExportDir().resolve(buildDefaultFileName(pkg.getAnalyzerId(), pkg.getScanId(), "zip"));
        Path parent = outPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        zipPackage(pkg, payloads, outPath);
        return outPath;
    }

    /**
     * 导出数据库包主库的全部条目为 TSV 文件（hex key + tab + hex value）。
     *
     * <p>本工具属于底层设施，直接与 {@link DbPackage} 对接，不经过适配器。</p>
     *
     * @param pkg     已打开的数据库包
     * @param outFile 输出文件路径，为 {@code null} 时自动生成到 export 目录
     * @return 实际写入的文件路径
     * @throws IOException 如果导出失败
     */
    public static Path exportTsv(DbPackage pkg, Path outFile) throws IOException {
        Path outPath = (outFile != null)
                ? outFile
                : ensureExportDir().resolve(buildDefaultFileName(pkg.getAnalyzerId(), pkg.getScanId(), "tsv"));
        return writeTsv(pkg.main().getAllEntries(), outPath);
    }

    /** 把 KV 条目逐行写成「hex key + tab + hex value」。 */
    static Path writeTsv(List<IChunkDb.Entry> entries, Path outPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            for (IChunkDb.Entry entry : entries) {
                writer.write(bytesToHex(entry.key()));
                writer.write('\t');
                writer.write(bytesToHex(entry.value()));
                writer.newLine();
            }
        }
        return outPath;
    }

    // ==================== ZIP 实现 ====================

    /** 列出包内待打包的负载文件（含 metadata.json，排除临时文件）。 */
    private static List<Path> listPayloads(Path packageDir) throws IOException {
        try (Stream<Path> stream = Files.list(packageDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.endsWith(".tmp");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** 用归档框架打包负载，并写入独立元数据（含归档时间与各文件 SHA-256）。 */
    private static void zipPackage(DbPackage pkg, List<Path> payloads, Path zipPath) throws IOException {
        JsonObject business = new JsonObject();
        business.addProperty("type", "chunkscanner:db-image");
        business.addProperty("scanId", pkg.getScanId());
        business.addProperty("analyzerId", pkg.getAnalyzerId().toString());

        try (ArchiveWriter writer = new ArchiveWriter(zipPath)) {
            for (Path file : payloads) {
                String entryName = file.getFileName().toString();
                if (DbPackage.METADATA_FILE.equals(entryName)) {
                    // metadata.json 作为普通负载原样嵌入，框架元数据另存独立 entry
                    writer.addBytes(entryName, Files.readAllBytes(file));
                } else {
                    writer.addFile(entryName, file);
                }
            }
            writer.finish(business);
        }
    }

    /** 字节数组转十六进制字符串（完整）。 */
    static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
