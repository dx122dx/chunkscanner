package com.billy65536.chunkscanner.core.db;

import com.billy65536.chunkscanner.ChunkScannerMod;
import com.billy65536.chunkscanner.core.AnalyzerRegistry;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.IDbAdaptor;
import com.billy65536.infrastructure.util.archive.ArchiveImage;
import com.billy65536.infrastructure.util.archive.ArchiveMetadata;
import com.billy65536.infrastructure.util.archive.ValidationResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * 已打包的数据库镜像（.zip），内容不可变，用于导入 / 导出交换。
 *
 * <p>本类继承基础设施的 {@link ArchiveImage}，复用其「元数据与负载完全分离」的
 * 通用归档能力（ZIP 注释定位随机命名的框架元数据、SHA-256 完整性校验、穿越防护解包），
 * 只补充 chunkscanner 自身的业务校验与字段解析。</p>
 *
 * <h2>两种包格式（双模探测）</h2>
 * <ul>
 *   <li><b>新格式</b>：ZIP 注释为随机 hex，框架元数据独立存于
 *       {@code archive.<hex>.metadata.json}，业务字段（scanId / analyzerId 等）
 *       与归档字段（exportTime / files）分别来自负载 {@code metadata.json} 与框架元数据；</li>
 *   <li><b>旧格式（历史导出包）</b>：无 ZIP 注释，{@code metadata.json} 内嵌
 *       {@code export} 段（导出时间 + 各文件摘要），{@link #open(Path)} 会回落构造
 *       等价的框架元数据视图，保证历史包仍可读。</li>
 * </ul>
 * 写出一律使用新格式（见 {@link DbExportUtil}）。
 */
public final class DbImage extends ArchiveImage {

    private static final Gson GSON = new Gson();

    /** 归档业务类型标识（写入归档元数据 business.type，见 {@link DbExportUtil}）。 */
    public static final String ARCHIVE_TYPE = "chunkscanner:db";

    private final Meta meta;

    private DbImage(Path zipPath, ArchiveMetadata archiveMeta, Meta meta) {
        super(zipPath, archiveMeta);
        this.meta = meta;
    }

    /** 获取解析出的元数据（open 后可用）。 */
    public Meta meta() {
        return meta;
    }

    // ==================== 打开 / 解析 ====================

    /**
     * 打开镜像并解析元数据（不校验、不解压）。
     *
     * <p>采用双模探测：ZIP 注释存在走新格式（业务字段读自负载 metadata.json，
     * 归档字段读自框架元数据）；注释缺失回落旧格式（从 metadata.json 的 export 段
     * 构造等价框架元数据视图）。</p>
     *
     * @param zipPath 镜像 ZIP 路径
     * @return 镜像句柄（含解析后的元数据）
     * @throws IOException 如果文件无法读取、metadata 缺失/非法，或两种探测均失败
     */
    public static DbImage open(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IOException("Database image not found: " + zipPath);
        }

        // 读负载 metadata.json（业务字段基底）
        JsonObject businessJson;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var entry = zf.getEntry(DbPackage.METADATA_FILE);
            if (entry == null) {
                throw new IOException(DbPackage.METADATA_FILE + " missing in image: " + zipPath);
            }
            try (InputStream in = zf.getInputStream(entry)) {
                businessJson = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
        }
        if (businessJson == null) {
            throw new IOException(DbPackage.METADATA_FILE + " is empty or not valid JSON in image: " + zipPath);
        }

        // 双模探测框架元数据
        ArchiveMetadata archiveMeta;
        try {
            archiveMeta = readMetadata(zipPath); // 新格式：经 ZIP 注释定位
        } catch (IOException e) {
            // 旧格式回落：从 metadata.json 的 export 段构造等价视图
            archiveMeta = legacyMetadataView(businessJson);
        }

        Meta meta = Meta.fromBusiness(businessJson, archiveMeta.time(), archiveMeta.files());
        return new DbImage(zipPath, archiveMeta, meta);
    }

    /** 读取可选字符串字段，缺失或为 JSON null 时返回 null。 */
    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** 从旧格式 metadata.json 的 export 段构造等价框架元数据视图。 */
    private static ArchiveMetadata legacyMetadataView(JsonObject businessJson) {
        List<ArchiveMetadata.FileEntry> files = new ArrayList<>();
        String time = null;
        if (businessJson.has("export") && businessJson.get("export").isJsonObject()) {
            JsonObject export = businessJson.getAsJsonObject("export");
            time = optString(export, "time");
            if (export.has("files") && export.get("files").isJsonArray()) {
                JsonArray arr = export.getAsJsonArray("files");
                for (int i = 0; i < arr.size(); i++) {
                    if (!arr.get(i).isJsonObject()) continue;
                    JsonObject fo = arr.get(i).getAsJsonObject();
                    files.add(new ArchiveMetadata.FileEntry(optString(fo, "name"), optString(fo, "sha256")));
                }
            }
        }
        // 旧包无框架 business 段，回落视图补写归档类型，使框架级 type 校验通过
        JsonObject legacyBusiness = new JsonObject();
        legacyBusiness.addProperty(ArchiveMetadata.BUSINESS_TYPE_KEY, ARCHIVE_TYPE);
        return new ArchiveMetadata(ArchiveMetadata.FORMAT_VERSION, time, files, legacyBusiness);
    }

    // ==================== 校验钩子 ====================

    @Override
    protected String expectedArchiveType() {
        return ARCHIVE_TYPE;
    }

    @Override
    protected void validateBusinessFields(List<String> errors, List<String> warnings) {
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

        if (meta.mainFile() == null || meta.mainFile().isEmpty()) {
            errors.add("Field 'database.file' is missing or empty");
        } else if (meta.files().stream().noneMatch(f -> f.name().equals(meta.mainFile()))) {
            errors.add("Declared main file is not present in image file list: " + meta.mainFile());
        }
    }

    @Override
    protected Set<String> requiredEntries() {
        // mainFile 缺失本身已由业务字段校验报错，此处不再重复且避免 Set.of 的 NPE
        return (meta.mainFile() == null || meta.mainFile().isEmpty())
                ? Set.of(DbPackage.METADATA_FILE)
                : Set.of(DbPackage.METADATA_FILE, meta.mainFile());
    }

    // ==================== 还原 ====================

    /**
     * 校验通过后，把镜像还原成 {@code parentDir} 下的一个数据库包。
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
     * @param validateFirst 是否先执行 {@link #validate()}；false 时跳过 SHA-256 校验
     * @return 还原后的数据库包（调用方负责关闭）
     * @throws IOException           如果校验失败（当 validateFirst=true）或解压失败
     * @throws IllegalStateException 如果校验未通过（当 validateFirst=true）
     */
    public DbPackage load(Path parentDir, boolean validateFirst) throws IOException {
        if (validateFirst) {
            ValidationResult result = validate();
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

    // ==================== 元数据模型 ====================

    /**
     * 镜像元数据。
     *
     * <p>业务字段（scanId / analyzerId / adaptorId / databaseType / mainFile）来自负载
     * {@code metadata.json}；归档字段（exportTime / files）来自框架元数据（新格式）或
     * 负载 {@code metadata.json} 的 export 段（旧格式）。</p>
     *
     * @param exportTime   导出时间（ISO-8601，来自框架元数据 time 或旧式 export.time）
     * @param scanId       扫描 ID
     * @param analyzerId   分析器 ID
     * @param adaptorId    适配器 ID（可为 null）
     * @param databaseType 主库工厂 ID（可为 null）
     * @param mainFile     主库负载文件名（可为 null）
     * @param files        各文件摘要声明（name + sha256），来自框架元数据或旧式 export.files
     */
    public record Meta(String exportTime, String scanId, Identifier analyzerId, Identifier adaptorId,
                       Identifier databaseType, String mainFile, List<ArchiveMetadata.FileEntry> files) {

        /** 从负载 metadata.json 解析业务字段，并附加归档字段（新格式入口）。 */
        public static Meta fromBusiness(JsonObject obj, String exportTime,
                                        List<ArchiveMetadata.FileEntry> files) {
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
            return new Meta(exportTime, scanId, analyzerId, adaptorId, databaseType, mainFile,
                    files != null ? files : List.of());
        }

        /** 从输入流解析 metadata.json（旧格式，含内嵌 export 段）。 */
        public static Meta parse(InputStream in) throws IOException {
            JsonObject obj = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (obj == null) {
                throw new IOException(DbPackage.METADATA_FILE + " is empty or not valid JSON");
            }
            ArchiveMetadata legacy = legacyMetadataView(obj);
            return fromBusiness(obj, legacy.time(), legacy.files());
        }

        /** 解析标识符，兼容无命名空间的写法。空字符串视作未定义哨兵。 */
        public static Identifier parseIdentifier(String raw) {
            if (raw == null || raw.isEmpty()) return ChunkScannerMod.ID_UNKNOWN;
            Identifier parsed = (raw.indexOf(':') >= 0) ? Identifier.tryParse(raw) : ChunkScannerMod.id(raw);
            return (parsed != null) ? parsed : ChunkScannerMod.ID_UNKNOWN;
        }
    }
}
