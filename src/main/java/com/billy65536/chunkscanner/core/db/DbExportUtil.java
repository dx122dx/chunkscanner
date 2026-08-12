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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
 * 命名为 {@code chunkscanner-{analyzerPath}-{scanId}-{yyMMddHHmmss}.{ext}}。
 * 其中 {@code analyzerPath} 为分析器 ID 的 path 段（不含命名空间冒号），
 * {@code scanId} 为白名单清洗后的扫描 ID，二者均保证跨平台文件名合法；
 * 用户自定义文件名统一经 {@link #sanitizeExportFileName(String, String)} 净化。</p>
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

    /**
     * 确保导出目录存在。
     *
     * <p>GUI 保存对话框在设置当前目录前需要先调用本方法，避免首次使用时目录
     * 尚不存在导致 {@code JFileChooser} 回落到系统主目录。</p>
     *
     * @return 导出目录路径（已确保存在）
     */
    public static Path ensureExportDir() {
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
     * <p>{@code analyzerId} 只取 path 部分参与文件名：命名空间分隔符 {@code ':'} 在
     * Windows 等文件系统上非法，直接拼接会导致落盘时抛
     * {@code Illegal char <:>}。{@code scanId} 同为用户输入，一并清洗。</p>
     *
     * @param analyzerId 分析器 ID，可为 {@code null}
     * @param scanId     扫描 ID，可为 {@code null}
     * @param ext        文件扩展名（不含点号）
     * @return chunkscanner-{analyzerPath}-{scanId}-{yyMMddHHmmss}.{ext}
     */
    public static String buildDefaultFileName(Identifier analyzerId, String scanId, String ext) {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String safeAnalyzer = sanitizeSegment(analyzerId == null ? null : analyzerId.getPath());
        String safeScanId = sanitizeSegment(scanId);
        return "chunkscanner-" + safeAnalyzer + "-" + safeScanId + "-" + time + "." + ext;
    }

    /**
     * 将文件名片段规范化为安全字符集。
     *
     * <p>白名单为 {@code [a-z0-9_-]}，其余字符（含冒号、空格、路径分隔符与中文）
     * 一律替换为下划线。小写化必须指定 {@link Locale#ROOT}：土耳其语 locale 下
     * {@code 'I'.toLowerCase()} 会变成无点 {@code 'ı'}，导致同一输入在不同系统
     * 语言下生成不同文件名。</p>
     *
     * @param raw 原始片段，可为 {@code null}
     * @return 清洗后的片段；输入为空或清洗后为空时返回 {@code "unknown"}
     */
    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return s.isEmpty() ? "unknown" : s;
    }

    /**
     * 规范化用户自定义导出文件名。
     *
     * <p>处理步骤：① 用 {@link Path#getFileName()} 剥离任何目录成分，彻底阻断
     * {@code ../} 与绝对路径穿越；② 过滤文件系统非法字符与控制字符；
     * ③ 去除 Windows 不允许的结尾点与空格；④ 拒绝 {@code "."} / {@code ".."} 与空串；
     * ⑤ 扩展名缺失或不符时补 {@code requiredExt}（大小写不敏感比较，不重复追加）。</p>
     *
     * @param raw         用户输入，可为 {@code null}/空白
     * @param requiredExt 期望扩展名，不含点号（{@code "zip"} / {@code "tsv"}）
     * @return 规范化后的纯文件名；不可用时返回 {@code null}，由调用方回退默认名
     */
    public static String sanitizeExportFileName(String raw, String requiredExt) {
        if (raw == null || raw.isBlank()) return null;

        // 剥离目录成分：无论 ../x、/abs/x 还是 a\b\x，只保留最后一段
        String candidate = raw.trim().replace('\\', '/');
        try {
            Path fileName = Path.of(candidate).getFileName();
            if (fileName != null) candidate = fileName.toString();
        } catch (InvalidPathException e) {
            // 平台无法解析的路径：退化为按分隔符手工截取最后一段
            int slash = candidate.lastIndexOf('/');
            if (slash >= 0) candidate = candidate.substring(slash + 1);
        }

        // 过滤文件系统非法字符与控制字符，语义与 ChunkScannerMod.sanitizePath 一致
        candidate = candidate.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_");
        // Windows 不允许文件名以点或空格结尾
        candidate = candidate.replaceAll("[. ]+$", "");

        if (candidate.isEmpty() || ".".equals(candidate) || "..".equals(candidate)) {
            return null;
        }

        if (requiredExt == null || requiredExt.isBlank()) {
            return candidate;
        }
        String suffix = "." + requiredExt.toLowerCase(Locale.ROOT);
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            candidate = candidate + suffix;
        }
        return candidate;
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

    /**
     * 用归档框架打包负载，并写入独立元数据（含归档时间与各文件 SHA-256）。
     *
     * <p>business 段统一使用小写驼峰字段（{@code scanId}/{@code analyzerId}/
     * {@code adaptorId}/{@code databaseType}/{@code databaseFile}），
     * 归档类型 {@code chunkscanner:db} 由 {@link ArchiveWriter#finish(String, JsonObject)}
     * 强制写入。</p>
     */
    private static void zipPackage(DbPackage pkg, List<Path> payloads, Path zipPath) throws IOException {
        JsonObject business = new JsonObject();
        business.addProperty("scanId", pkg.getScanId());
        business.addProperty("analyzerId", pkg.getAnalyzerId().toString());
        business.addProperty("adaptorId", pkg.getAdaptorId().toString());
        if (pkg.getDbType() != null) {
            business.addProperty("databaseType", pkg.getDbType().toString());
        }
        business.addProperty("databaseFile", DbPackage.MAIN_ID + ".bin");

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
            writer.finish(DbImage.ARCHIVE_TYPE, business);
        }
    }

    /** 字节数组转十六进制字符串（完整）。 */
    static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
