package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已打包的数据库镜像（.zip），内容不可变，用于导入 / 导出交换。
 *
 * <p>ZIP 内是一个 {@link DbPackage} 目录的扁平快照：根部一份
 * {@code metadata.json}（在包元数据基础上追加 {@code export} 段），
 * 其余为各数据库负载文件。</p>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>{@link #open(Path)} 解析包内 metadata，获取扫描信息（无需解压）；</li>
 *   <li>{@link #validate()} 验证包合法性（字段合法性 + SHA-256 完整性）；</li>
 *   <li>{@link #load(Path)} / {@link #load(Path, boolean)} 还原为 {@link DbPackage}。</li>
 * </ul>
 */
public final class DbImage {

    private static final Gson GSON = new Gson();

    private final Path zipPath;
    private final Meta meta;

    private DbImage(Path zipPath, Meta meta) {
        this.zipPath = zipPath;
        this.meta = meta;
    }

    /** 获取解析出的元数据（open 后可用）。 */
    public Meta meta() {
        return meta;
    }

    // ==================== 打开 / 解析 ====================

    /**
     * 打开镜像并解析 metadata（不校验、不解压）。
     *
     * @param zipPath 镜像 ZIP 路径
     * @return 镜像句柄（含解析后的元数据）
     * @throws IOException 如果文件无法读取或 metadata 缺失/非法
     */
    public static DbImage open(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IOException("Database image not found: " + zipPath);
        }
        Meta meta;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zf.getEntry(DbPackage.METADATA_FILE);
            if (entry == null) {
                throw new IOException(DbPackage.METADATA_FILE + " missing in image: " + zipPath);
            }
            try (InputStream in = zf.getInputStream(entry)) {
                meta = Meta.parse(in);
            }
        }
        return new DbImage(zipPath, meta);
    }

    // ==================== 校验 ====================

    /**
     * 校验镜像：字段合法性（analyzerId / databaseType 是否注册）
     * 与数据完整性（主文件是否存在、各文件 SHA-256 是否匹配）。
     *
     * @return 校验结果，{@link DbValidationResult#valid()} 为 true 表示可安全加载
     */
    public DbValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- 字段合法性 ----
        if (meta.scanId() == null || meta.scanId().isEmpty()) {
            errors.add("Field 'scanId' is missing or empty");
        }

        if (meta.analyzerId() == null) {
            errors.add("Field 'analyzerId' is missing or empty");
        } else if (AnalyzerRegistry.get(meta.analyzerId()) == null) {
            errors.add("Field 'analyzerId' is not a registered analyzer: " + meta.analyzerId());
        }

        // adaptorId 缺失（旧版导出包）时由 DbPackage 按分析器推导，不算错误
        if (meta.adaptorId() != null && IDbAdaptor.FactoryRegistry.get(meta.adaptorId()) == null) {
            warnings.add("Field 'adaptorId' is not a registered adaptor: " + meta.adaptorId()
                    + "; data will only be readable through the raw adaptor");
        }

        if (meta.databaseType() == null) {
            warnings.add("Field 'database.type' is missing; will fall back to default factory");
        } else if (IChunkDb.FactoryRegistry.get(meta.databaseType()) == null) {
            errors.add("Field 'database.type' refers to unknown factory: " + meta.databaseType());
        }

        // ---- 数据完整性 ----
        if (meta.mainFile() == null || meta.mainFile().isEmpty()) {
            errors.add("Field 'database.file' is missing or empty");
        } else if (meta.files().stream().noneMatch(f -> f.name().equals(meta.mainFile()))) {
            errors.add("Declared main file is not present in image file list: " + meta.mainFile());
        }

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            for (FileEntry fe : meta.files()) {
                ZipEntry ze = zf.getEntry(fe.name());
                if (ze == null) {
                    errors.add("Image file missing: " + fe.name());
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
            List<String> names = meta.files().stream().map(FileEntry::name).toList();
            zf.stream().forEach(ze -> {
                if (!ze.getName().equals(DbPackage.METADATA_FILE) && !names.contains(ze.getName())) {
                    warnings.add("Undeclared file present in image: " + ze.getName());
                }
            });
        } catch (IOException | NoSuchAlgorithmException e) {
            errors.add("Failed to read image for integrity check: " + e.getMessage());
        }

        return new DbValidationResult(errors.isEmpty(), errors, warnings);
    }

    // ==================== 加载 ====================

    /**
     * 校验通过后，把镜像还原成 {@code parentDir} 下的一个数据库包。
     *
     * <p>等价于 {@code load(parentDir, true)}。</p>
     *
     * @param parentDir 还原目标的父目录，包会落在 {@code parentDir/chunkscanner_<hash>/}
     * @return 还原后的数据库包（调用方负责关闭）
     * @throws IOException           如果校验失败或解压失败
     * @throws IllegalStateException 如果校验未通过
     */
    public DbPackage load(Path parentDir) throws IOException {
        return load(parentDir, true);
    }

    /**
     * 把镜像还原成 {@code parentDir} 下的一个数据库包，可选跳过校验。
     *
     * @param parentDir     还原目标的父目录
     * @param validateFirst 是否先执行 {@link #validate()}；false 时跳过 SHA-256 校验（效率优先，
     *                      例如调用方已自行确保镜像可信）
     * @return 还原后的数据库包（调用方负责关闭）
     * @throws IOException           如果校验失败（当 validateFirst=true）或解压失败
     * @throws IllegalStateException 如果校验未通过（当 validateFirst=true）
     */
    public DbPackage load(Path parentDir, boolean validateFirst) throws IOException {
        if (validateFirst) {
            DbValidationResult result = validate();
            if (!result.valid()) {
                throw new IllegalStateException("Database image validation failed: " + result.errors());
            }
        }
        if (meta.scanId() == null || meta.scanId().isEmpty()) {
            throw new IOException("Cannot restore image without a scanId");
        }
        Path target = DbPackage.dirFor(parentDir, meta.scanId());
        extractTo(target);
        return DbPackage.open(target);
    }

    // ==================== 内部工具 ====================

    /** 将包内所有 entry 解压到目标目录（覆盖已存在文件）。 */
    private void extractTo(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.normalize();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            zf.stream().forEach(ze -> {
                try {
                    Path out = targetDir.resolve(ze.getName()).normalize();
                    if (!out.startsWith(normalizedTarget)) {
                        throw new IOException("Illegal entry path escapes target dir: " + ze.getName());
                    }
                    if (ze.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zf.getInputStream(ze)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
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
     * 镜像元数据（对应包内 metadata.json）。
     *
     * @param exportTime   导出时间（ISO-8601，来自 {@code export.time}）
     * @param scanId       扫描 ID
     * @param analyzerId   分析器 ID
     * @param adaptorId    适配器 ID，决定还原后用哪个 {@link com.billy65536.chunkscanner.core.IDbAdaptor}
     *                     解读数据；1.x 与早期 2.0 导出包无此字段，为 null（还原时由分析器推导）
     * @param databaseType 主库工厂 ID（可为 null）
     * @param mainFile     主库负载文件名（可为 null）
     * @param files        各文件声明（name + sha256），来自 {@code export.files}
     */
    public record Meta(String exportTime, String scanId, Identifier analyzerId, Identifier adaptorId,
                       Identifier databaseType, String mainFile, List<FileEntry> files) {

        /** 从输入流解析 metadata.json。 */
        public static Meta parse(InputStream in) throws IOException {
            JsonObject obj = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (obj == null) {
                throw new IOException(DbPackage.METADATA_FILE + " is empty or not valid JSON");
            }

            String scanId = optString(obj, "scanId");
            String analyzerRaw = optString(obj, "analyzerId");
            Identifier analyzerId = (analyzerRaw != null) ? parseIdentifier(analyzerRaw) : null;
            String adaptorRaw = optString(obj, "adaptorId");
            Identifier adaptorId = (adaptorRaw != null) ? parseIdentifier(adaptorRaw) : null;

            Identifier databaseType = null;
            String mainFile = null;
            if (obj.has("database") && obj.get("database").isJsonObject()) {
                JsonObject db = obj.getAsJsonObject("database");
                String typeRaw = optString(db, "type");
                if (typeRaw != null) databaseType = parseIdentifier(typeRaw);
                mainFile = optString(db, "file");
            }

            String exportTime = null;
            List<FileEntry> files = new ArrayList<>();
            if (obj.has("export") && obj.get("export").isJsonObject()) {
                JsonObject export = obj.getAsJsonObject("export");
                exportTime = optString(export, "time");
                if (export.has("files") && export.get("files").isJsonArray()) {
                    JsonArray arr = export.getAsJsonArray("files");
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) continue;
                        JsonObject fo = arr.get(i).getAsJsonObject();
                        files.add(new FileEntry(optString(fo, "name"), optString(fo, "sha256")));
                    }
                }
            }
            return new Meta(exportTime, scanId, analyzerId, adaptorId, databaseType, mainFile, files);
        }

        /** 解析标识符，兼容无命名空间的写法。空字符串视作未定义哨兵。 */
        public static Identifier parseIdentifier(String raw) {
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
