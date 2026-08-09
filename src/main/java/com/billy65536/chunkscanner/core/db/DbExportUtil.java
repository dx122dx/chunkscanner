package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据库导出工具（core 层）。
 *
 * <p>支持两种导出模式：</p>
 * <ul>
 *   <li><b>raw</b> —— 把整个 {@link DbPackage} 目录原样打成 ZIP，
 *       包内 {@code metadata.json} 在原元数据基础上追加 {@code export} 段
 *       （导出时间与各文件 SHA-256），可由 {@link DbImage} 完整还原；</li>
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

    /** 列出包内待打包的负载文件（排除 metadata.json 与临时文件）。 */
    private static List<Path> listPayloads(Path packageDir) throws IOException {
        try (Stream<Path> stream = Files.list(packageDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals(DbPackage.METADATA_FILE) && !name.endsWith(".tmp");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** 打包负载文件，并写入追加了 {@code export} 段的 metadata.json。 */
    private static void zipPackage(DbPackage pkg, List<Path> payloads, Path zipPath) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }

        JsonArray fileArray = new JsonArray();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            byte[] buffer = new byte[8192];
            for (Path file : payloads) {
                String entryName = file.getFileName().toString();
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

            JsonObject meta = readPackageMetadata(pkg);
            JsonObject export = new JsonObject();
            export.addProperty("time", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            export.add("files", fileArray);
            meta.add("export", export);

            byte[] metaBytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    .toJson(meta).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry(DbPackage.METADATA_FILE));
            zos.write(metaBytes);
            zos.closeEntry();
        }
    }

    /** 原样读出包内的 metadata.json，作为导出元数据的基底。 */
    private static JsonObject readPackageMetadata(DbPackage pkg) throws IOException {
        Path metaFile = pkg.getDir().resolve(DbPackage.METADATA_FILE);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IOException("Failed to read package metadata: " + metaFile, e);
        }
        throw new IOException("Malformed package metadata: " + metaFile);
    }

    /** 字节数组转十六进制字符串（完整）。 */
    static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
