package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据库导出工具（core 层）。
 *
 * <p>支持两种导出模式：</p>
 * <ul>
 *   <li><b>raw</b> — ZIP 归档，包含所有相关数据库文件和 metadata.json</li>
 *   <li><b>tsv</b> — TSV 文本文件，每行为十六进制 key + tab + 十六进制 value</li>
 * </ul>
 *
 * <p>导出的文件默认存储在 {@code chunkscanner/export/} 目录下，
 * 文件名格式为 {@code chunkscanner-{analyzerId}-{scanId}-{YYMMDDhhmmss}.{ext}}。</p>
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
    public static String buildDefaultFileName(String analyzerId, String scanId, String ext) {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        return "chunkscanner-" + analyzerId + "-" + scanId + "-" + time + "." + ext;
    }

    // ==================== 导出入口 ====================

    /**
     * 导出数据库原始文件为 ZIP 归档（包含 metadata.json）。
     *
     * @param db      已打开的数据库实例
     * @param outFile 输出文件路径，为 {@code null} 时自动生成到 export 目录
     * @return 实际写入的文件路径
     * @throws IOException 如果导出失败
     */
    public static Path exportRawZip(IChunkDb db, Path outFile) throws IOException {
        String scanId = db.getScanId();
        String analyzerId = db.getAnalyzerId();
        Path mainFile = db.getFilePath();
        if (mainFile == null || !Files.exists(mainFile)) {
            throw new IOException("Database file not found for database " + scanId);
        }

        // 计算文件 basename 以查找相关文件
        String fileName = mainFile.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String prefix = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
        Path parent = mainFile.getParent();
        if (parent == null) {
            parent = ChunkScannerMod.getDbDir();
        }

        List<Path> relatedFiles;
        try (Stream<Path> s = Files.list(parent)) {
            relatedFiles = s.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix);
            }).sorted().collect(Collectors.toList());
        }

        if (relatedFiles.isEmpty()) {
            throw new IOException("No files found for database " + scanId);
        }

        Path outPath = (outFile != null)
                ? outFile
                : ensureExportDir().resolve(buildDefaultFileName(analyzerId, scanId, "zip"));
        String databaseType = db.getFactoryId();
        String mainFileName = parent.relativize(mainFile).toString();
        zipFiles(relatedFiles, parent, outPath, scanId, analyzerId, databaseType, mainFileName);
        return outPath;
    }

    /**
     * 导出数据库全部条目为 TSV 文件（hex key + tab + hex value）。
     *
     * @param db      已打开的数据库实例
     * @param outFile 输出文件路径，为 {@code null} 时自动生成到 export 目录
     * @return 实际写入的文件路径
     * @throws IOException 如果导出失败
     */
    public static Path exportTsv(IChunkDb db, Path outFile) throws IOException {
        String scanId = db.getScanId();
        String analyzerId = db.getAnalyzerId();
        Path outPath = (outFile != null)
                ? outFile
                : ensureExportDir().resolve(buildDefaultFileName(analyzerId, scanId, "tsv"));

        List<IChunkDb.Entry> entries = db.getAllEntries();
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

    /** 将文件列表打包为 ZIP，保留相对路径，并添加 metadata.json。 */
    private static void zipFiles(List<Path> files, Path baseDir, Path zipPath,
                                  String scanId, String analyzerId,
                                  String databaseType, String mainFileName) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            byte[] buffer = new byte[8192];
            MessageDigest sha256;
            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 not available", e);
            }
            JsonArray fileArray = new JsonArray();

            for (Path file : files) {
                String entryName = baseDir.relativize(file).toString();
                zos.putNextEntry(new ZipEntry(entryName));
                sha256.reset();
                try (FileInputStream fis = new FileInputStream(file.toFile())) {
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                        sha256.update(buffer, 0, len);
                    }
                }
                zos.closeEntry();

                JsonObject fileObj = new JsonObject();
                fileObj.addProperty("name", entryName);
                fileObj.addProperty("sha256", bytesToHex(sha256.digest()));
                fileArray.add(fileObj);
            }

            JsonObject meta = new JsonObject();
            meta.addProperty("exportTime", ZonedDateTime.now()
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            meta.addProperty("databaseName", scanId);
            meta.addProperty("scannerId", analyzerId);
            if (databaseType != null) {
                meta.addProperty("databaseType", databaseType);
            }
            if (mainFileName != null) {
                meta.addProperty("mainFile", mainFileName);
            }
            meta.add("files", fileArray);

            byte[] metaBytes = new GsonBuilder().setPrettyPrinting().create()
                    .toJson(meta).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry("metadata.json"));
            zos.write(metaBytes);
            zos.closeEntry();
        }
    }

    /** 字节数组转十六进制字符串（完整）。 */
    static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
