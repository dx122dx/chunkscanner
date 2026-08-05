package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.google.gson.Gson;

import net.minecraft.util.Identifier;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 导出数据库包（.zip） API —— 供其他模组调用。
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>{@link #open(Path)} 解析包内 metadata，获取扫描信息（无需解压）；</li>
 *   <li>{@link #validate()} 验证包合法性（字段合法性 + 数据完整性 SHA256）；</li>
 *   <li>{@link #load(Path)} / {@link #load(Path, boolean)} 将包还原为 {@link IChunkDb}；</li>
 *   <li>{@link #openUnchecked(Path, Path)} 跳过校验直接加载（效率优先）。</li>
 * </ul>
 */
public final class DbPackage {

    private static final String METADATA_ENTRY = "metadata.json";
    private static final Gson GSON = new Gson();

    private final Path zipPath;
    private final Meta meta;

    private DbPackage(Path zipPath, Meta meta) {
        this.zipPath = zipPath;
        this.meta = meta;
    }

    /** 获取解析出的元数据（open 后可用）。 */
    public Meta meta() {
        return meta;
    }

    // ==================== 打开 / 解析 ====================

    /**
     * 打开导出包并解析 metadata（不校验、不解压）。
     *
     * @param zipPath 导出 ZIP 路径
     * @return DbPackage 实例（含解析后的元数据）
     * @throws IOException            如果文件无法读取或 metadata 缺失/非法
     * @throws IllegalArgumentException 如果 metadata 结构不合法
     */
    public static DbPackage open(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IOException("Export package not found: " + zipPath);
        }
        Meta meta;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zf.getEntry(METADATA_ENTRY);
            if (entry == null) {
                throw new IOException("metadata.json missing in package: " + zipPath);
            }
            try (InputStream in = zf.getInputStream(entry)) {
                meta = Meta.parse(in);
            }
        }
        return new DbPackage(zipPath, meta);
    }

    // ==================== 校验 ====================

    /**
     * 校验包的合法性：字段合法性（analyzerId / databaseType 是否注册）+ 数据完整性
     * （mainFile 是否存在、各文件 SHA256 是否匹配）。
     *
     * @return 校验结果，{@link DbValidationResult#valid()} 为 true 表示可安全加载
     */
    public DbValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- 字段合法性 ----
        if (meta.analyzerId() == null) {
            errors.add("Field 'scannerId' (analyzerId) is missing or empty");
        } else if (AnalyzerRegistry.get(meta.analyzerId()) == null) {
            errors.add("Field 'scannerId' (analyzerId) is not a registered analyzer: " + meta.analyzerId());
        }

        if (meta.databaseType() == null) {
            warnings.add("Field 'databaseType' is missing; will fall back to default factory");
        } else if (IChunkDb.FactoryRegistry.get(meta.databaseType()) == null) {
            errors.add("Field 'databaseType' refers to unknown factory: " + meta.databaseType());
        }

        if (meta.databaseName() == null || meta.databaseName().isEmpty()) {
            warnings.add("Field 'databaseName' is missing or empty");
        }

        // ---- 数据完整性 ----
        if (meta.mainFile() == null || meta.mainFile().isEmpty()) {
            errors.add("Field 'mainFile' is missing or empty");
        } else if (meta.files().stream().noneMatch(f -> f.name().equals(meta.mainFile()))) {
            errors.add("Declared 'mainFile' is not present in package file list: " + meta.mainFile());
        }

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            for (FileEntry fe : meta.files()) {
                ZipEntry ze = zf.getEntry(fe.name());
                if (ze == null) {
                    errors.add("Package file missing: " + fe.name());
                    continue;
                }
                try (InputStream in = zf.getInputStream(ze)) {
                    String actual = sha256Hex(in);
                    if (!actual.equalsIgnoreCase(fe.sha256())) {
                        errors.add("SHA-256 mismatch for file '" + fe.name()
                                + "': expected " + fe.sha256() + " but got " + actual);
                    }
                }
            }
            // 列出 ZIP 中存在但 metadata 未声明的文件（仅警告）
            var names = meta.files().stream().map(FileEntry::name).toList();
            zf.stream().forEach(ze -> {
                if (!ze.getName().equals(METADATA_ENTRY) && !names.contains(ze.getName())) {
                    warnings.add("Undeclared file present in package: " + ze.getName());
                }
            });
        } catch (IOException | NoSuchAlgorithmException e) {
            errors.add("Failed to read package for integrity check: " + e.getMessage());
        }

        return new DbValidationResult(errors.isEmpty(), errors, warnings);
    }

    // ==================== 加载 ====================

    /**
     * 校验通过后，将包解压到目标目录并还原为 {@link IChunkDb}。
     *
     * <p>等价于 {@code load(targetDir, true)}。</p>
     *
     * @param targetDir 解压目标目录（会被创建）
     * @return 还原后的数据库实例（已 open）
     * @throws IOException            如果校验失败或解压/加载失败
     * @throws IllegalStateException 如果校验未通过
     */
    public IChunkDb load(Path targetDir) throws IOException {
        return load(targetDir, true);
    }

    /**
     * 将包解压到目标目录并还原为 {@link IChunkDb}。
     *
     * @param targetDir  解压目标目录（会被创建）
     * @param validateFirst 是否先执行 {@link #validate()}；false 时跳过校验直接加载（效率优先，
     *                      例如调用方已自行确保包可信）
     * @return 还原后的数据库实例（已 open）
     * @throws IOException            如果校验失败（当 validateFirst=true）或解压/加载失败
     * @throws IllegalStateException 如果校验未通过（当 validateFirst=true）
     */
    public IChunkDb load(Path targetDir, boolean validateFirst) throws IOException {
        if (validateFirst) {
            DbValidationResult result = validate();
            if (!result.valid()) {
                throw new IllegalStateException("Export package validation failed: " + result.errors());
            }
        }
        extractTo(targetDir);
        IChunkDb.IFactory factory =
                (meta.databaseType() != null) ? IChunkDb.FactoryRegistry.get(meta.databaseType())
                                              : IChunkDb.FactoryRegistry.getDefault();
        if (factory == null) {
            throw new IOException("No suitable database factory available for package");
        }
        IChunkDb db = factory.createMetadataOnly(meta.databaseName(), meta.analyzerId(), targetDir);
        db.open();
        return db;
    }

    // ==================== 内部工具 ====================

    /** 将包内所有 entry 解压到目标目录（覆盖已存在文件）。 */
    private void extractTo(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            zf.stream().forEach(ze -> {
                try {
                    Path out = targetDir.resolve(ze.getName()).normalize();
                    if (!out.startsWith(targetDir.normalize())) {
                        throw new IOException("Illegal entry path escapes target dir: " + ze.getName());
                    }
                    if (ze.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zf.getInputStream(ze)) {
                            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /** 计算输入流的 SHA-256 十六进制串。 */
    private static String sha256Hex(InputStream in) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            md.update(buf, 0, len);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    // ==================== 元数据模型 ====================

    /**
     * 导出包元数据（对应 metadata.json）。
     *
     * @param exportTime   导出时间（ISO-8601）
     * @param databaseName 扫描 ID
     * @param analyzerId   分析器 ID（metadata 中的 {@code scannerId}）
     * @param databaseType 数据库工厂 ID（可为 null）
     * @param mainFile     主数据文件相对名（可为 null）
     * @param files        各文件声明（name + sha256）
     */
    public record Meta(String exportTime, String databaseName, Identifier analyzerId,
                       Identifier databaseType, String mainFile, List<FileEntry> files) {

        /** 从输入流解析 metadata.json。 */
        public static Meta parse(InputStream in) throws IOException {
            JsonObject obj = GSON.fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                    JsonObject.class);
            if (obj == null) {
                throw new IOException("metadata.json is empty or not valid JSON");
            }
            String exportTime = optString(obj, "exportTime");
            String databaseName = optString(obj, "databaseName");
            String analyzerRaw = optString(obj, "scannerId");
            String databaseRaw = optString(obj, "databaseType");
            String mainFile = optString(obj, "mainFile");

            // 兼容旧包：无命名空间时回退为 chunkscanner:<原值>
            Identifier analyzerId = (analyzerRaw != null) ? parseIdentifier(analyzerRaw) : null;
            Identifier databaseType = (databaseRaw != null) ? parseIdentifier(databaseRaw) : null;

            List<FileEntry> files = new ArrayList<>();
            if (obj.has("files") && obj.get("files").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("files");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject fo = arr.get(i).getAsJsonObject();
                    files.add(new FileEntry(optString(fo, "name"), optString(fo, "sha256")));
                }
            }
            return new Meta(exportTime, databaseName, analyzerId, databaseType, mainFile, files);
        }

        /** 解析标识符，兼容旧格式（无命名空间）。空字符串视作未定义哨兵。 */
        private static Identifier parseIdentifier(String raw) {
            if (raw == null || raw.isEmpty()) return ChunkScannerMod.ID_UNKNOWN;
            Identifier parsed = (raw.indexOf(':') >= 0) ? Identifier.tryParse(raw) : ChunkScannerMod.id(raw);
            return (parsed != null) ? parsed : ChunkScannerMod.ID_UNKNOWN;
        }

        private static String optString(JsonObject obj, String key) {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
        }
    }

    /** 包内单文件声明。 */
    public record FileEntry(String name, String sha256) {
    }
}
